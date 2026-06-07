package com.google.ai.edge.gallery.customtasks.baotranslate

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadProcessor
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.TtsEngine
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranscription
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.UUID
import kotlin.math.sqrt

private const val REC_TAG = "BaoTranslateRec"
private const val INPUT_ROUTE_TIMEOUT_MS = 2_000L
private const val LIVE_TRANSLATION_WINDOW_SECONDS = 8
private const val LIVE_TRANSLATION_STRIDE_SECONDS = 4
private const val MIN_TRANSLATION_SEGMENT_SECONDS = 1
private const val WAVEFORM_VISUAL_GAIN = 2.5f
private const val AUDIO_READ_EMPTY_SLEEP_MS = 20L
private const val AUDIO_READ_STALL_TIMEOUT_MS = 3_000L

internal class RecordingController(
  private val pipelines: PipelineLifecycleManager,
  private val audioRouter: AudioRouter,
  private val bleManager: BleConversationManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  private val getApp: () -> Application,
) {
  private val recordingMutex = Mutex()
  private val segmentProcessingMutex = Mutex()
  @Volatile private var holdsRecordingLock = false
  @Volatile private var currentRecordingSessionId = 0L
  var recordingJob: Job? = null
  var audioRecord: AudioRecord? = null

  // Live-window translation segments are launched as independent coroutines. Track them so
  // stopRecording() can cancel any that are queued-but-unstarted (or mid-flight at a suspension
  // point), preventing TTS/transcripts from firing after the user has stopped.
  private val liveSegmentJobs = mutableListOf<Job>()

  internal companion object {
    // Test-only injection point. When non-null, the next startRecording() sources audio from this
    // 16 kHz mono PCM instead of the microphone, then clears it. Set only by instrumentation tests
    // (the device echo canceller makes acoustic speaker->own-mic loopback unusable for STT).
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testPcmSource: ShortArray? = null
  }

  fun startRecording() {
    if (uiState.value.isRecordingActive) return
    val app = getApp()
    if (!pipelines.requiredPipelinesReady()) {
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.ModelsNotReady,
        errorMessage = app.getString(R.string.bao_translate_error_models_not_ready),
      ) }
      return
    }

    if (!recordingMutex.tryLock()) return
    holdsRecordingLock = true
    val recordingSessionId = currentRecordingSessionId + 1
    currentRecordingSessionId = recordingSessionId

    uiState.update {
      it.copy(
        pipelineStatus = PipelineStatus.StartingRecording,
        errorMessage = null,
        liveTranslationPreview = null,
        amplitudes = emptyList(),
        elapsedSeconds = 0f,
      )
    }

    // Test-only seam: drive the live-translation pipeline from injected PCM instead of the mic.
    // A device's echo canceller cancels its own speaker output captured by its own mic, so an
    // automated speaker->mic self-loopback cannot feed STT; this routes clean PCM through the SAME
    // VAD -> Whisper -> translate -> UI path. Never set outside instrumentation tests.
    val injectedPcm = testPcmSource
    if (injectedPcm != null) {
      testPcmSource = null
      recordingJob = viewModelScope.launch(Dispatchers.IO) {
        runInjectedLiveTranslationForTest(recordingSessionId, injectedPcm)
      }
      return
    }

    recordingJob = viewModelScope.launch(Dispatchers.IO) {
      try {
      if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_permission_mic_denied),
        ) }
        return@launch
      }

      val sampleRate = PipelineConfig.STT_SAMPLE_RATE
      val channelConfig = AudioFormat.CHANNEL_IN_MONO
      val audioFormat = AudioFormat.ENCODING_PCM_16BIT
      val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
      if (bufferSize <= 0) {
        BaoLog.e(REC_TAG, "AudioRecord returned invalid min buffer size: $bufferSize")
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
        ) }
        return@launch
      }

      val currentOutput = uiState.value.currentAudioDevice
      val preferredInput = uiState.value.preferredInputDevice
      val audioSource = when {
        currentOutput is AudioDevice.BluetoothHeadset &&
          (currentOutput.transport == BluetoothTransport.BLE_AUDIO ||
            currentOutput.transport == BluetoothTransport.SCO) ->
          MediaRecorder.AudioSource.VOICE_COMMUNICATION
        preferredInput != null -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else -> MediaRecorder.AudioSource.MIC
      }

      val preferredInputInfo = preferredInput?.let { audioRouter.getInputDeviceInfo(it) }
      if (preferredInput != null && preferredInputInfo == null) {
        BaoLog.e(REC_TAG, "Selected Bluetooth input is no longer available: $preferredInput")
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
        ) }
        return@launch
      }

      val recorder = AudioRecord(
        audioSource,
        sampleRate,
        channelConfig,
        audioFormat,
        bufferSize,
      )

      if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        BaoLog.e(REC_TAG, "AudioRecord failed to initialize")
        recorder.release()
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
        ) }
        return@launch
      }

      if (preferredInputInfo != null && !recorder.setPreferredDevice(preferredInputInfo)) {
        BaoLog.e(REC_TAG, "AudioRecord rejected preferred Bluetooth input: ${preferredInputInfo.productName}")
        recorder.release()
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
        ) }
        return@launch
      }

      audioRecord = recorder
      val buffer = ShortArray((bufferSize / 2).coerceAtLeast(sampleRate / 10))
      val startTime = System.currentTimeMillis()
      val liveWindowSamples = sampleRate * LIVE_TRANSLATION_WINDOW_SECONDS
      val liveStrideSamples = sampleRate * LIVE_TRANSLATION_STRIDE_SECONDS
      val minSegmentSamples = sampleRate * MIN_TRANSLATION_SEGMENT_SECONDS
      val pendingSamples = ShortArray(liveWindowSamples)
      var sampleCount = 0
      var samplesSinceLastLiveWindow = 0
      var queuedLiveSegment = false
      var recordingFailed = false
      var publishedRecordingState = false
      var lastPositiveReadAt = System.currentTimeMillis()

      recorder.startRecording()

      if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        BaoLog.e(REC_TAG, "AudioRecord did not enter recording state")
        recorder.release()
        audioRecord = null
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
        ) }
        return@launch
      }
      BaoLog.i(
        REC_TAG,
        "AudioRecord started session=$recordingSessionId source=$audioSource bufferBytes=$bufferSize preferredInputId=${preferredInputInfo?.id}",
      )

      if (preferredInputInfo != null && !recorder.waitForPreferredInputRoute(preferredInputInfo.id)) {
        BaoLog.e(
          REC_TAG,
          "AudioRecord routed to ${recorder.routedDevice?.productName} instead of selected Bluetooth input ${preferredInputInfo.productName}",
        )
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          recorder.stop()
        }
        recorder.release()
        audioRecord = null
        unlockRecordingIfHeld()
        uiState.update { it.copy(
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
        ) }
        return@launch
      }

      // `isActive` lets the read loop exit on coroutine cancellation (e.g. ViewModel cleared
      // mid-recording) so the AudioRecord below is released and the mic doesn't stay live.
      try {
      while (isActive && uiState.value.isRecordingActive) {
        val readCount = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

        when {
          readCount > 0 -> {
            lastPositiveReadAt = System.currentTimeMillis()
            var bufferOffset = 0
            while (bufferOffset < readCount) {
              val copyCount = minOf(readCount - bufferOffset, liveWindowSamples - sampleCount)
              System.arraycopy(buffer, bufferOffset, pendingSamples, sampleCount, copyCount)
              sampleCount += copyCount
              samplesSinceLastLiveWindow += copyCount
              bufferOffset += copyCount

              if (sampleCount == liveWindowSamples) {
                queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
                queuedLiveSegment = true
                val overlapSamples = liveWindowSamples - liveStrideSamples
                System.arraycopy(pendingSamples, liveStrideSamples, pendingSamples, 0, overlapSamples)
                sampleCount = overlapSamples
                samplesSinceLastLiveWindow = 0
              }
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            var sumSquares = 0L
            for (i in 0 until readCount) {
              val sample = buffer[i].toLong()
              sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares.toDouble() / readCount) / 32768.0
            val amplitude = (sqrt(rms).toFloat() * WAVEFORM_VISUAL_GAIN).coerceIn(0f, 1f)
            val newAmplitudes = (uiState.value.amplitudes + amplitude).takeLast(50)
            if (!publishedRecordingState) {
              val stats = buffer.copyOf(readCount).audioStats()
              BaoLog.i(
                REC_TAG,
                "First mic frame session=$recordingSessionId read=$readCount rms=${stats.rms} peak=${stats.peak}",
              )
              publishedRecordingState = true
            }

            uiState.update { state ->
              if (!state.isRecordingActive) {
                state
              } else {
                state.copy(
                  pipelineStatus = PipelineStatus.Recording,
                  elapsedSeconds = elapsed,
                  amplitudes = newAmplitudes,
                )
              }
            }
          }
          readCount == 0 -> {
            val now = System.currentTimeMillis()
            if (now - lastPositiveReadAt >= AUDIO_READ_STALL_TIMEOUT_MS) {
              BaoLog.e(
                REC_TAG,
                "AudioRecord produced no mic frames for ${now - lastPositiveReadAt}ms session=$recordingSessionId",
              )
              uiState.update { it.copy(
                pipelineStatus = PipelineStatus.Idle,
                errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
              ) }
              recordingFailed = true
              break
            }
            Thread.sleep(AUDIO_READ_EMPTY_SLEEP_MS)
          }
          readCount < 0 -> {
            BaoLog.e(REC_TAG, "AudioRecord read returned error: $readCount")
            uiState.update { it.copy(
              pipelineStatus = PipelineStatus.Idle,
              errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
            ) }
            recordingFailed = true
            break
          }
        }
      }

      if (recordingFailed) {
        return@launch
      }

      // Process the trailing tail regardless of whether live windows already fired. Gating the
      // final flush on a full stride (4s) silently dropped the last <4s of speech: that tail past
      // the last window boundary was never part of any emitted window, so use the 1s minimum.
      val finalSampleCount = if (queuedLiveSegment) samplesSinceLastLiveWindow else sampleCount
      if (finalSampleCount >= minSegmentSamples) {
        val finalStart = sampleCount - finalSampleCount
        segmentProcessingMutex.withLock {
          processAudioSegment(
            pendingSamples.copyOfRange(finalStart, sampleCount),
            recordingSessionId = recordingSessionId,
            preserveRecordingStatus = false,
            reportEmptySpeech = !queuedLiveSegment,
          )
        }
      } else if (sampleCount > 0 && !queuedLiveSegment) {
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_recording_short)) }
      }
      } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          recorder.stop()
        }
        recorder.release()
        audioRecord = null
      }
      } finally {
        unlockRecordingIfHeld()
      }
    }
  }

  // Mirrors the mic read-loop's lifecycle (publish Recording, window the audio into live segments,
  // flush the tail, stay "recording" until stop) but sources audio from injected PCM. Reuses the
  // real queueRealtimeTranslationSegment / processAudioSegment path so STT, translation, and UI
  // markers behave exactly as in production.
  private suspend fun runInjectedLiveTranslationForTest(recordingSessionId: Long, pcm: ShortArray) {
    try {
      val sampleRate = PipelineConfig.STT_SAMPLE_RATE
      val liveWindowSamples = sampleRate * LIVE_TRANSLATION_WINDOW_SECONDS
      val liveStrideSamples = sampleRate * LIVE_TRANSLATION_STRIDE_SECONDS
      val minSegmentSamples = sampleRate * MIN_TRANSLATION_SEGMENT_SECONDS
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Recording, errorMessage = null) }
      BaoLog.i(REC_TAG, "Injected live translation session=$recordingSessionId samples=${pcm.size} (TEST)")

      var queuedLiveSegment = false
      var offset = 0
      while (offset + liveWindowSamples <= pcm.size && coroutineContext.isActive) {
        queueRealtimeTranslationSegment(recordingSessionId, pcm.copyOfRange(offset, offset + liveWindowSamples))
        queuedLiveSegment = true
        offset += liveStrideSamples
      }
      val tailStart = if (queuedLiveSegment) offset else 0
      if (pcm.size - tailStart >= minSegmentSamples) {
        segmentProcessingMutex.withLock {
          processAudioSegment(
            pcm.copyOfRange(tailStart, pcm.size),
            recordingSessionId = recordingSessionId,
            preserveRecordingStatus = true,
            reportEmptySpeech = !queuedLiveSegment,
          )
        }
      }

      // Stay "recording" until the test presses stop, exactly like the mic loop.
      while (coroutineContext.isActive && uiState.value.isRecordingActive) {
        delay(100)
      }
    } finally {
      unlockRecordingIfHeld()
    }
  }

  fun stopRecording() {
    if (!uiState.value.isRecordingActive) return
    uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    // Cancel queued/in-flight live-window segments so their transcribe→translate→TTS does not fire
    // after the user has stopped. The recordingJob's final-segment flush is intentionally left
    // running (it is not in this list) so the tail of speech is still translated.
    synchronized(liveSegmentJobs) {
      liveSegmentJobs.forEach { it.cancel() }
      liveSegmentJobs.clear()
    }
  }

  private fun unlockRecordingIfHeld() {
    if (holdsRecordingLock) {
      holdsRecordingLock = false
      recordingMutex.unlock()
    }
  }

  private fun queueRealtimeTranslationSegment(recordingSessionId: Long, audioSamples: ShortArray) {
    BaoLog.i(
      REC_TAG,
      "Queue live segment session=$recordingSessionId ${audioSamples.audioStats()}",
    )
    val job = viewModelScope.launch(Dispatchers.IO) {
      segmentProcessingMutex.withLock {
        processAudioSegment(
          audioSamples = audioSamples,
          recordingSessionId = recordingSessionId,
          preserveRecordingStatus = true,
          reportEmptySpeech = false,
        )
      }
    }
    synchronized(liveSegmentJobs) {
      liveSegmentJobs.removeAll { it.isCompleted }
      liveSegmentJobs.add(job)
    }
    job.invokeOnCompletion {
      synchronized(liveSegmentJobs) { liveSegmentJobs.remove(job) }
    }
  }

  private fun statusAfterSegment(preserveRecordingStatus: Boolean): PipelineStatus =
    if (preserveRecordingStatus && uiState.value.isRecording) {
      PipelineStatus.Recording
    } else {
      PipelineStatus.Idle
    }

  private fun isStaleRecordingSegment(recordingSessionId: Long?): Boolean =
    recordingSessionId != null && recordingSessionId != currentRecordingSessionId

	  private suspend fun processAudioSegment(
    audioSamples: ShortArray,
    recordingSessionId: Long? = null,
    preserveRecordingStatus: Boolean = false,
    reportEmptySpeech: Boolean = true,
	  ) {
	    if (isStaleRecordingSegment(recordingSessionId)) return
	    val inputStats = audioSamples.audioStats()
	    BaoLog.i(
	      REC_TAG,
	      "Process audio segment preserve=$preserveRecordingStatus reportEmpty=$reportEmptySpeech $inputStats",
	    )

    if (!preserveRecordingStatus) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Processing) }
    }

    val (whisper, translation, vad) = pipelines.pipelineMutex.withLock {
      Triple(pipelines.whisperPipeline, pipelines.translationPipeline, pipelines.vadProcessor)
    }

    if (whisper == null) {
      uiState.update { it.copy(
        pipelineStatus = statusAfterSegment(preserveRecordingStatus),
        errorMessage = getApp().getString(R.string.bao_translate_error_stt_not_init),
      ) }
      return
    }

    if (translation == null) {
      uiState.update { it.copy(
        pipelineStatus = statusAfterSegment(preserveRecordingStatus),
        errorMessage = getApp().getString(R.string.bao_translate_error_translation_not_init),
      ) }
      return
    }

    val vadProcessor = vad ?: run {
      val newVad = VadProcessor(getApp())
      if (newVad.initialize()) {
        pipelines.pipelineMutex.withLock { pipelines.vadProcessor = newVad }
        newVad
      } else {
        uiState.update { it.copy(
          pipelineStatus = statusAfterSegment(preserveRecordingStatus),
          errorMessage = getApp().getString(R.string.bao_translate_error_vad_init),
        ) }
        return
      }
    }

	    val speechSegments = vadProcessor.processAudioSegment(audioSamples)
	    BaoLog.i(
	      REC_TAG,
	      "VAD returned segments=${speechSegments.size} preserve=$preserveRecordingStatus inputSamples=${audioSamples.size}",
	    )
	    if (isStaleRecordingSegment(recordingSessionId)) return

    if (speechSegments.isEmpty()) {
      if (reportEmptySpeech) {
        uiState.update { it.copy(
          pipelineStatus = statusAfterSegment(preserveRecordingStatus),
          errorMessage = getApp().getString(R.string.bao_translate_error_no_speech_detected),
        ) }
      }
      return
    }

    for (segment in speechSegments) {
      val transcriptionResult = whisper.transcribeBlocking(segment.toShortArray())
      BaoLog.i(REC_TAG, "STT done success=${transcriptionResult.isSuccess} chars=${transcriptionResult.getOrNull()?.text?.length ?: 0}")
      if (isStaleRecordingSegment(recordingSessionId)) return

      val transcription = transcriptionResult.fold(
        onSuccess = { it },
        onFailure = { error ->
          BaoLog.w(REC_TAG, "Transcription failed: ${error.message}")
          if (!isStaleRecordingSegment(recordingSessionId)) {
            uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_error_transcription_failed, error.message)) }
          }
          null
        },
      ) ?: continue

      if (!isValidTranscription(transcription.text)) {
        if (reportEmptySpeech) {
          uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_no_clear_speech)) }
        }
        continue
      }

      val sourceLang = if (uiState.value.sourceLanguage == SupportedLanguages.AUTO.key) {
        SupportedLanguages.normalizeDetectedCode(transcription.language)
          ?: SupportedLanguages.CODE_MAP[SupportedLanguages.AUTO.key]
          ?: "auto"
      } else {
        SupportedLanguages.codeFor(uiState.value.sourceLanguage)
      }

      val targetLang = SupportedLanguages.codeFor(uiState.value.targetLanguage)

      if (uiState.value.sourceLanguage == SupportedLanguages.AUTO.key) {
        if (isStaleRecordingSegment(recordingSessionId)) return
        uiState.update {
          it.copy(
            detectedLanguage = SupportedLanguages.keyForCode(sourceLang)
              ?.takeIf { key -> key != SupportedLanguages.AUTO.key },
          )
        }
      }

      val translationOutcome = translation.translateBlocking(
        sourceText = transcription.text,
        sourceLanguage = sourceLang,
        targetLanguage = targetLang,
      )
      if (isStaleRecordingSegment(recordingSessionId)) return

      when (translationOutcome) {
	        is TranslationOutcome.Success -> {
          if (isStaleRecordingSegment(recordingSessionId)) return
	          val translatedText = translationOutcome.result.translatedText
	          BaoLog.i(
	            REC_TAG,
	            "Translation success preserve=$preserveRecordingStatus source=$sourceLang target=$targetLang translatedChars=${translatedText.length}",
	          )
          // Overlapping live windows (8s window / 4s stride) re-translate the shared 4s overlap,
          // yielding a duplicate of the previous live commit. Skip exact consecutive duplicates so
          // the same phrase is neither appended nor spoken twice; still refresh the live preview.
          // Only unique content reaches here, so no speech is lost.
          if (preserveRecordingStatus) {
            val lastUserText = uiState.value.transcripts.lastOrNull { it.isUser }?.translatedText
            if (lastUserText != null && lastUserText.trim().equals(translatedText.trim(), ignoreCase = true)) {
              uiState.update { it.copy(liveTranslationPreview = translatedText) }
              continue
            }
          }

          val messageId = UUID.randomUUID().toString()
          val message = TranslationMessage(
            id = messageId,
            originalText = transcription.text,
            translatedText = translatedText,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            timestamp = System.currentTimeMillis(),
            isUser = true,
            audioPlayed = null,
            translationError = null,
          )

          uiState.update {
            it.copy(
              transcripts = it.transcripts + message,
              liveTranslationPreview = if (preserveRecordingStatus) translatedText else it.liveTranslationPreview,
            )
          }

          val shouldPlayNow =
            !preserveRecordingStatus || uiState.value.currentAudioDevice !is AudioDevice.Speaker
          val audioPlayed = if (shouldPlayNow) {
            synthesizeSpeech(
              text = translatedText,
              language = targetLang,
              recordingSessionId = recordingSessionId,
              preserveRecordingStatus = preserveRecordingStatus,
            )
          } else {
            false
          }
          if (isStaleRecordingSegment(recordingSessionId)) return
          uiState.update { state ->
            state.copy(
              transcripts = state.transcripts.map { existing ->
                if (existing.id == messageId) existing.copy(audioPlayed = audioPlayed) else existing
              },
            )
          }

          if (bleManager.getConnectedCount() > 0) {
            bleManager.sendTranscript(transcription.text, sourceLang, targetLang)
          }
        }
	        is TranslationOutcome.Failure -> {
	          BaoLog.w(
	            REC_TAG,
	            "Translation failed preserve=$preserveRecordingStatus source=$sourceLang target=$targetLang reasonChars=${translationOutcome.reason.length}",
	          )
          if (isStaleRecordingSegment(recordingSessionId)) return
          val message = TranslationMessage(
            id = UUID.randomUUID().toString(),
            originalText = transcription.text,
            translatedText = "",
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            timestamp = System.currentTimeMillis(),
            isUser = true,
            translationError = translationOutcome.reason,
          )

          uiState.update { it.copy(
            transcripts = it.transcripts + message,
            errorMessage = translationOutcome.reason,
          ) }
        }
      }
    }

    if (!preserveRecordingStatus && !isStaleRecordingSegment(recordingSessionId)) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    }
  }

  // `internal` so the conversation receive path (BaoTranslateViewModel) can speak peer messages
  // through the same engine-selection + playback logic, instead of duplicating it.
  internal suspend fun synthesizeSpeech(
    text: String,
    language: String,
    recordingSessionId: Long? = null,
    preserveRecordingStatus: Boolean = false,
  ): Boolean {
    if (isStaleRecordingSegment(recordingSessionId)) return false

    if (!preserveRecordingStatus) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Speaking) }
    }

    val state = uiState.value
    val kokoro = pipelines.kokoroTts
    val ovConverter = pipelines.openVoiceConverter
    val ovTargetSe = pipelines.openVoiceTargetSe

    // Languages Kokoro can't voice (de/ko/ru/ar/...) MUST use the platform-TTS fallback — otherwise
    // they'd be spoken with an English Kokoro voice (wrong-language gibberish). No clone for these
    // (the converter only re-times Kokoro output), but a correct generic voice beats wrong audio.
    val nativeToKokoro = KokoroTtsPipeline.supportsLanguage(language)

    // Preferred (Kokoro languages): OpenVoice cross-lingual clone — Kokoro supplies correct
    // pronunciation, then the converter re-times it into the enrolled user's timbre. Falls back to
    // generic Kokoro if conversion fails or the user hasn't enrolled a voice.
    val audio = when {
      !nativeToKokoro -> pipelines.platformTts?.synthesizeAudio(text, language)
      state.voiceProfileEnrolled && ovConverter != null && ovTargetSe != null && kokoro != null -> {
        val base = kokoro.synthesizeAudio(text, KokoroTtsPipeline.getVoiceForLanguage(language))
        base?.let { ovConverter.convert(it, ovTargetSe) } ?: base
      }
      kokoro != null ->
        kokoro.synthesizeAudio(text, KokoroTtsPipeline.getVoiceForLanguage(language))
      else -> null
    }

    var played = false
    if (audio != null) {
      if (isStaleRecordingSegment(recordingSessionId)) return false
      withContext(Dispatchers.Default) {
        audioRouter.play(audio.samples, sampleRate = audio.sampleRate)
      }
      played = true
    } else {
      if (!isStaleRecordingSegment(recordingSessionId)) {
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_tts_engine_not_ready)) }
      }
    }

    if (!preserveRecordingStatus && !isStaleRecordingSegment(recordingSessionId)) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    }
    return played
  }

  private fun AudioRecord.waitForPreferredInputRoute(deviceId: Int): Boolean {
    val deadline = System.currentTimeMillis() + INPUT_ROUTE_TIMEOUT_MS
    do {
      if (routedDevice?.id == deviceId) return true
      Thread.sleep(50)
    } while (System.currentTimeMillis() < deadline)
    return routedDevice?.id == deviceId
  }

}

private val BaoTranslateUiState.isRecordingActive: Boolean
  get() = isRecording || isStartingRecording

private data class AudioStats(
  val samples: Int,
  val rms: String,
  val peak: Int,
)

private fun ShortArray.audioStats(): AudioStats {
  if (isEmpty()) {
    return AudioStats(samples = 0, rms = "0.000000", peak = 0)
  }

  var peak = 0
  var sumSquares = 0.0
  forEach { sample ->
    val value = sample.toInt()
    peak = maxOf(peak, kotlin.math.abs(value))
    sumSquares += value.toDouble() * value.toDouble()
  }
  val rms = sqrt(sumSquares / size) / 32768.0
  return AudioStats(
    samples = size,
    rms = "%.6f".format(java.util.Locale.US, rms),
    peak = peak,
  )
}
