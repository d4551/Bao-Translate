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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

private const val REC_TAG = "BaoTranslateRec"

internal class RecordingController(
  private val pipelines: PipelineLifecycleManager,
  private val audioRouter: AudioRouter,
  private val bleManager: BleConversationManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  private val getApp: () -> Application,
) {
  private val recordingMutex = Mutex()
  @Volatile private var holdsRecordingLock = false
  var recordingJob: Job? = null
  var audioRecord: AudioRecord? = null

  fun startRecording() {
    if (uiState.value.isRecording) return
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

    uiState.update { it.copy(pipelineStatus = PipelineStatus.Recording, errorMessage = null) }

    recordingJob = viewModelScope.launch(Dispatchers.IO) {
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
      val maxSamples = sampleRate * 60
      val allSamples = ShortArray(maxSamples)
      var sampleCount = 0
      var recordingFailed = false

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

      // `isActive` lets the blocking read loop exit on coroutine cancellation (e.g. ViewModel
      // cleared mid-recording) so the AudioRecord below is released and the mic doesn't stay live.
      while (isActive && uiState.value.isRecording) {
        val readCount = recorder.read(buffer, 0, buffer.size)

        when {
          readCount > 0 -> {
            val copyCount = minOf(readCount, maxSamples - sampleCount)
            if (copyCount > 0) {
              System.arraycopy(buffer, 0, allSamples, sampleCount, copyCount)
              sampleCount += copyCount
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            var sumSquares = 0L
            for (i in 0 until readCount) {
              val sample = buffer[i].toLong()
              sumSquares += sample * sample
            }
            val amplitude = (sumSquares.toFloat() / readCount) / (32768f * 32768f)
            val newAmplitudes = (uiState.value.amplitudes + amplitude).takeLast(50)

            uiState.update { it.copy(
              elapsedSeconds = elapsed,
              amplitudes = newAmplitudes,
            ) }
          }
          readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_BAD_VALUE -> {
            BaoLog.e(REC_TAG, "AudioRecord read returned error: $readCount")
            unlockRecordingIfHeld()
            uiState.update { it.copy(
              pipelineStatus = PipelineStatus.Idle,
              errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
            ) }
            recordingFailed = true
            break
          }
        }
      }

      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
      }
      recorder.release()
      audioRecord = null

      if (recordingFailed) {
        unlockRecordingIfHeld()
        return@launch
      }

      if (sampleCount > sampleRate) {
        processAudioSegment(allSamples.copyOf(sampleCount))
      } else if (sampleCount > 0) {
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_recording_short)) }
      }
    }
  }

  fun stopRecording() {
    if (!uiState.value.isRecording) return
    uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    unlockRecordingIfHeld()
  }

  private fun unlockRecordingIfHeld() {
    if (holdsRecordingLock) {
      holdsRecordingLock = false
      recordingMutex.unlock()
    }
  }

  private suspend fun processAudioSegment(audioSamples: ShortArray) {
    uiState.update { it.copy(pipelineStatus = PipelineStatus.Processing) }

    val (whisper, translation, vad) = pipelines.pipelineMutex.withLock {
      Triple(pipelines.whisperPipeline, pipelines.translationPipeline, pipelines.vadProcessor)
    }

    if (whisper == null) {
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = getApp().getString(R.string.bao_translate_error_stt_not_init),
      ) }
      return
    }

    if (translation == null) {
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
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
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = getApp().getString(R.string.bao_translate_error_vad_init),
        ) }
        return
      }
    }

    val speechSegments = vadProcessor.processAudioSegment(audioSamples)
    if (speechSegments.isEmpty()) {
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = getApp().getString(R.string.bao_translate_error_no_speech_detected),
      ) }
      return
    }

    for (segment in speechSegments) {
      val transcriptionResult = whisper.transcribeBlocking(segment.toShortArray())

      val transcription = transcriptionResult.fold(
        onSuccess = { it },
        onFailure = { error ->
          BaoLog.w(REC_TAG, "Transcription failed: ${error.message}")
          uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_error_transcription_failed, error.message)) }
          null
        },
      ) ?: continue

      if (!isValidTranscription(transcription.text)) {
        BaoLog.d(REC_TAG, "Filtered invalid transcription: '${transcription.text}'")
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_no_clear_speech)) }
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

      when (translationOutcome) {
        is TranslationOutcome.Success -> {
          val translatedText = translationOutcome.result.translatedText
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

          uiState.update { it.copy(
            transcripts = it.transcripts + message,
          ) }

          val audioPlayed = synthesizeSpeech(translatedText, targetLang)
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

    uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
  }

  // `internal` so the conversation receive path (BaoTranslateViewModel) can speak peer messages
  // through the same engine-selection + playback logic, instead of duplicating it.
  internal suspend fun synthesizeSpeech(text: String, language: String): Boolean {
    uiState.update { it.copy(pipelineStatus = PipelineStatus.Speaking) }

    val state = uiState.value
    val engine: TtsEngine? = when {
      state.ttsEngine == "pocket_tts" && state.voiceProfileEnrolled && pipelines.voiceCloneTts != null -> pipelines.voiceCloneTts
      state.ttsEngine == "pocket_tts" && pipelines.kokoroTts != null -> pipelines.kokoroTts
      else -> pipelines.kokoroTts
    }

    var played = false
    if (engine != null) {
      val voiceId = if (engine == pipelines.kokoroTts) {
        KokoroTtsPipeline.getVoiceForLanguage(language)
      } else null
      val audioSamples = engine.synthesize(text, voiceId)
      if (audioSamples != null) {
        withContext(Dispatchers.Default) {
          audioRouter.play(audioSamples)
        }
        played = true
      } else {
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_tts_synthesis_failed)) }
      }
    } else {
      uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_tts_engine_not_ready)) }
    }

    uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    return played
  }
}
