package com.google.ai.edge.gallery.customtasks.baotranslate

import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioCache
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WAVEFORM_HISTORY
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.waveformAmplitude
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.EmptyTranscriptionException
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingCaptioner
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingSttPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadProcessor
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VoskStreamingPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.TtsEngine
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranscription
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
private const val AUDIO_READ_EMPTY_SLEEP_MS = 20L
private const val AUDIO_READ_STALL_TIMEOUT_MS = 3_000L
// Extra window, past the end of TTS playback, during which the mic stays gated so the speaker's
// acoustic decay isn't captured as the next conversational turn (echo/feedback guard).
// Increased from 150ms to 300ms to prevent cutoff of dual-speaker transitions and allow
// sufficient acoustic decay tail before the mic re-opens.
private const val PLAYBACK_TAIL_MUTE_MS = 300L
// Silence timeout for continuous conversation mode: if no speech is detected (no live segments
// queued) for this duration after the last translation, auto-stop recording. Enables buttonless
// conversation — user starts once, speaks, pauses, system translates, then auto-stops.
private const val SILENCE_AUTO_STOP_MS = 15_000L
// Mic frame amplitude above which a frame counts as speech activity (shared by the silence
// auto-stop timer and face-to-face turn endpointing). Silero VAD remains the authority on what
// is actually speech — this only gates WHEN a captured turn is handed to it.
private const val SPEECH_AMPLITUDE_THRESHOLD = 0.01f
// Face-to-face turn endpoint: after speech was heard, this much trailing quiet ends the turn and
// flushes the whole utterance as ONE segment. Turn-based capture (instead of the 8s/4s overlapped
// live windows) is what stops the same sentence being re-transcribed and re-spoken per window.
private const val F2F_TURN_END_SILENCE_MS = 700L

enum class SpeechTimbre {
  /** Use [speakerSe] when provided; otherwise fall back to the locally-enrolled timbre. */
  LocalEnrolled,
  /** Use [speakerSe] only — never fall back to the local enrolled timbre. */
  PeerOnly,
}

@SuppressLint("RestrictedApi")
internal class RecordingController(
  private val pipelines: PipelineLifecycleManager,
  private val audioRouter: AudioRouter,
  private val bleManager: BleConversationManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  private val getApp: () -> Application,
  private val voiceProfileManager: com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager? = null,
  private val onAutoAcceptDetectedLanguage: ((String) -> Unit)? = null,
) {
  private val recordingMutex = Mutex()
  private val segmentProcessingMutex = Mutex()
  // Guards against overlapping streaming partial-caption decodes (at most one in flight).
  private val partialCaptionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
  // Multilingual TRUE streaming ASR for live token-by-token captions: a per-language [StreamingCaptioner]
  // (sherpa zipformer transducer for English, Vosk for the other languages). Loaded for the active
  // caption language; null while warming or when no streaming model exists (-> chunked-Whisper).
  @Volatile private var captioner: StreamingCaptioner? = null
  @Volatile private var captionerLang: String? = null
  // True once a load attempt for [captionerLang] has resolved (so the read loop can tell "warming"
  // from "failed/absent" and fall back to chunked without blocking the audio thread).
  @Volatile private var captionerChecked = false

  // Test-only seam: when set, the real recording read loop sources its frames from this injected PCM
  // instead of the mic, so instrumentation exercises the PRODUCTION VAD -> turn-endpoint -> streaming
  // partial -> translation loop (not a parallel reimplementation). Null in production. Frames are
  // returned as fast as the loop reads (no real-time pacing); exhaustion returns 0 and the test calls
  // stopRecording() to trigger the final-segment flush.
  @Volatile private var injectedFrameSource: InjectedFrameSource? = null

  /**
   * Real-time-paced frame reader over a fixed PCM buffer, mimicking a live mic: it only releases
   * audio up to the wall-clock playback position, so VAD turn-endpointing (which keys off wall-clock
   * silence) and the streaming-partial cadence behave exactly as with a real microphone. Returns 0
   * while waiting for the next frame's real time (the read loop sleeps), and 0 once exhausted.
   */
  private class InjectedFrameSource(private val pcm: ShortArray, private val sampleRate: Int) {
    private var position = 0
    private var startMs = 0L

    fun read(buffer: ShortArray): Int {
      val now = android.os.SystemClock.elapsedRealtime()
      if (startMs == 0L) startMs = now
      if (position >= pcm.size) return 0
      val playbackSamples = ((now - startMs) * sampleRate / 1000L).toInt()
      val available = minOf(playbackSamples - position, pcm.size - position)
      if (available <= 0) return 0
      val count = minOf(buffer.size, available)
      System.arraycopy(pcm, position, buffer, 0, count)
      position += count
      return count
    }
  }
  @Volatile private var holdsRecordingLock = false
  @Volatile private var currentRecordingSessionId = 0L
  var recordingJob: Job? = null
  var audioRecord: AudioRecord? = null

  // Dedicated scope for the recording session and its live-window translation segments.
  // Cancelling this scope stops the mic loop AND any queued-but-unstarted (or mid-flight)
  // translation/TTS jobs, preventing stale transcripts from firing after the user stops.
  private var recordingScope: CoroutineScope? = null

  // Hands-free conversation guard: true while the app is playing a translation aloud. The capture
  // loop discards mic input during that window, so continuous (no-button) listening never re-hears
  // and re-translates its own spoken output — the feedback loop that otherwise breaks bidirectional
  // face-to-face mode. This is the portable guarantee; hardware AEC (below) handles residual leakage.
  @Volatile private var capturePaused = false

  @Volatile private var discardRecordingOnStop = false

  private val conversationManager = ConversationManager()

  // Single seam for phase changes: every transition is mirrored into UiState so the UI surfaces
  // the live Listening / Translating / Speaking turn state without a second source of truth.
  private fun conversationEvent(event: ConversationManager.() -> Unit) {
    val before = conversationManager.phase
    conversationManager.event()
    val phase = conversationManager.phase
    if (phase != before) {
      BaoLog.i(REC_TAG, "Conversation phase $before -> $phase")
    }
    uiState.update { if (it.conversationPhase == phase) it else it.copy(conversationPhase = phase) }
  }

  @SuppressLint("RestrictedApi")
  internal companion object {
    // Test-only injection point. When non-null, the next startRecording() sources audio from this
    // 16 kHz mono PCM instead of the microphone, then clears it. Set only by instrumentation tests
    // (the device echo canceller makes acoustic speaker->own-mic loopback unusable for STT).
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testPcmSource: ShortArray? = null

    /** Last target speaker embedding passed to OpenVoice convert, or null if no clone was attempted. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testLastCloneTargetSe: FloatArray? = null

    /** True when [OpenVoiceVoiceConverter.convert] was attempted with a non-null target embedding. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testLastWasCloned: Boolean = false
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
    discardRecordingOnStop = false
    conversationEvent { onRecordingStart() }

    uiState.update {
      it.copy(
        pipelineStatus = PipelineStatus.StartingRecording,
        errorMessage = null,
        liveTranslationPreview = null,
        liveSourcePreview = null,
        amplitudes = emptyList(),
        elapsedSeconds = 0f,
      )
    }

    // Test-only seam: drive the REAL read loop from injected PCM instead of the mic (the loop reads
    // frames from injectedFrameSource when set). A device's echo canceller cancels its own speaker
    // output captured by its own mic, so an automated speaker->mic self-loopback cannot feed STT;
    // this routes clean PCM through the exact production VAD -> turn-endpoint -> streaming-partial ->
    // translate -> UI loop. Never set outside instrumentation tests.
    val injectedPcm = testPcmSource
    if (injectedPcm != null) {
      testPcmSource = null
      injectedFrameSource = InjectedFrameSource(injectedPcm, PipelineConfig.STT_SAMPLE_RATE)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    recordingScope = scope
    // Multilingual caption provisioning, OFF the audio thread: if the streaming model for the
    // speaker's language is present, pre-warm it so its ~1s load never stalls first-turn capture; if
    // it's a supported language not yet downloaded, fetch it in the background (this session captions
    // via chunked-Whisper, the next streams). Languages with no streaming model just use chunked.
    captionLanguageCode()?.let { lang ->
      if (BaoTranslateModelManager.isCaptionModelReady(app, lang)) {
        scope.launch(Dispatchers.IO) { ensureCaptioner(lang) }
      } else {
        scope.launch(Dispatchers.IO) { BaoTranslateModelManager.downloadCaptionModel(app, lang) }
      }
    }
    recordingJob = scope.launch {
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
      val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
      if (minBufferSize <= 0) {
        BaoLog.e(REC_TAG, "AudioRecord returned invalid min buffer size: $minBufferSize")
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

      // Capture into a generous ring buffer — 0.5 s of PCM16, never below 4x the OS minimum — so a
      // transient stall in the VAD/STT/translation consumer never overflows AudioRecord and drops
      // samples (the cause of scratchy/glitchy capture). Mirrors the 4x sizing on the playback track.
      val captureBufferBytes = maxOf(minBufferSize * 4, sampleRate * Short.SIZE_BYTES / 2)
      val recorder = AudioRecord(
        audioSource,
        sampleRate,
        channelConfig,
        audioFormat,
        captureBufferBytes,
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
      val buffer = ShortArray((minBufferSize / 2).coerceAtLeast(sampleRate / 10))
      val startTime = System.currentTimeMillis()
      val liveWindowSamples = sampleRate * LIVE_TRANSLATION_WINDOW_SECONDS
      val liveStrideSamples = sampleRate * LIVE_TRANSLATION_STRIDE_SECONDS
      val minSegmentSamples = sampleRate * MIN_TRANSLATION_SEGMENT_SECONDS
      val pendingSamples = ShortArray(liveWindowSamples)
      val partialCaptionStepSamples = sampleRate * 3 / 2 // 1.5s streaming-partial cadence
      var sampleCount = 0
      var samplesSinceLastLiveWindow = 0
      var samplesSinceLastPartial = 0
      var streamingTurnPrimed = false
      // The language to caption this session (speaker's source language), fixed for the recording.
      val captionLang = captionLanguageCode()
      var queuedLiveSegment = false
      var recordingFailed = false
      var publishedRecordingState = false
      var lastPositiveReadAt = System.currentTimeMillis()
      // Silence auto-stop: tracks when speech activity was last detected (live segment queued or
      // non-silent mic frame). If SILENCE_AUTO_STOP_MS elapses with no activity, recording stops
      // automatically — enables buttonless continuous conversation.
      var lastSpeechActivityAt = System.currentTimeMillis()
      // Face-to-face turn endpointing: true once the current turn has heard speech, so a trailing
      // F2F_TURN_END_SILENCE_MS of quiet flushes the whole utterance as one segment.
      var turnSpeechHeard = false

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
        "AudioRecord started session=$recordingSessionId source=$audioSource captureBufferBytes=$captureBufferBytes minBufferBytes=$minBufferSize preferredInputId=${preferredInputInfo?.id}",
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
      // Hardware echo cancellation + noise suppression on this capture session. Best-effort: the
      // factory returns null on devices without the effect, in which case the capturePaused gate
      // (which discards mic input during playback) is the sole — and sufficient — feedback guard.
      val echoCanceler =
        if (AcousticEchoCanceler.isAvailable())
          AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
        else null
      val noiseSuppressor =
        if (NoiseSuppressor.isAvailable())
          NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
        else null
      try {
      readLoop@ while (isActive && uiState.value.isRecordingActive) {
        // Silence auto-stop: if no speech activity for SILENCE_AUTO_STOP_MS after at least one
        // translation was committed, stop recording. This enables buttonless conversation — the
        // user starts once, speaks, and the system auto-stops after a natural pause.
        // Face-to-face mode is fully hands-free: the session stays live across long pauses for as
        // long as the screen is open (leaving the screen stops it), so neither speaker ever has to
        // re-arm the mic mid-conversation.
        if (queuedLiveSegment && !capturePaused && !uiState.value.faceToFaceMode) {
          val silenceMs = System.currentTimeMillis() - lastSpeechActivityAt
          if (silenceMs >= SILENCE_AUTO_STOP_MS) {
            BaoLog.i(
              REC_TAG,
              "Silence auto-stop after ${silenceMs}ms session=$recordingSessionId f2f=${uiState.value.faceToFaceMode}",
            )
            break
          }
        }

        val readCount =
          injectedFrameSource?.read(buffer)
            ?: recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

        when {
          readCount > 0 -> {
            lastPositiveReadAt = System.currentTimeMillis()
            if (capturePaused) {
              // App is speaking its own translation: drop this audio and reset the live window so the
              // echo (and any half-captured pre-playback speech) never mixes into the next speaker's
              // turn. This is what makes continuous, no-button bidirectional conversation usable.
              sampleCount = 0
              samplesSinceLastLiveWindow = 0
              turnSpeechHeard = false
              continue@readLoop
            }

            // Track speech activity: if the mic frame has meaningful amplitude, update the
            // last-speech timestamp. This prevents silence auto-stop during quiet but active speech.
            val frameAmplitude = waveformAmplitude(buffer, readCount)
            if (frameAmplitude > SPEECH_AMPLITUDE_THRESHOLD) {
              lastSpeechActivityAt = System.currentTimeMillis()
              turnSpeechHeard = true
            }
            val faceToFace = uiState.value.faceToFaceMode

            var bufferOffset = 0
            while (bufferOffset < readCount) {
              val copyCount = minOf(readCount - bufferOffset, liveWindowSamples - sampleCount)
              System.arraycopy(buffer, bufferOffset, pendingSamples, sampleCount, copyCount)
              sampleCount += copyCount
              samplesSinceLastLiveWindow += copyCount
              bufferOffset += copyCount

              if (sampleCount == liveWindowSamples) {
                if (faceToFace) {
                  // Buffer full mid-monologue: flush the whole window as one turn, no overlap.
                  // Overlapping windows re-transcribe and re-speak the shared 4s — the duplicate
                  // spam this turn-based path exists to eliminate.
                  if (turnSpeechHeard) {
                    queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
                    queuedLiveSegment = true
                    lastSpeechActivityAt = System.currentTimeMillis()
                  }
                  sampleCount = 0
                  samplesSinceLastLiveWindow = 0
                  turnSpeechHeard = false
                } else {
                  queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
                  queuedLiveSegment = true
                  lastSpeechActivityAt = System.currentTimeMillis()
                  val overlapSamples = liveWindowSamples - liveStrideSamples
                  System.arraycopy(pendingSamples, liveStrideSamples, pendingSamples, 0, overlapSamples)
                  sampleCount = overlapSamples
                  samplesSinceLastLiveWindow = 0
                }
              }
            }

            // Streaming partial caption from the FIRST detected speech (not gated on the 1s
            // translation minimum), so the recognized caption appears word-by-word as you talk
            // instead of only at the turn endpoint below — industry-standard live captioning.
            if (faceToFace && turnSpeechHeard) {
              if (captionLang != null && isCaptionViable(captionLang)) {
                // TRUE streaming ASR for this language (sherpa for English, Vosk otherwise),
                // pre-warmed off the audio thread at recording start: prime with everything heard so
                // far this turn, then feed each newly-read frame so the hypothesis grows token-by-
                // token. While the recognizer is still warming, the feed is a no-op (skips a frame)
                // rather than stalling capture.
                if (captioner != null && captionerLang == captionLang) {
                  if (!streamingTurnPrimed) {
                    BaoLog.i(REC_TAG, "LIVE_PARTIAL_BRANCH=streaming:$captionLang session=$recordingSessionId")
                    feedCaptionPartial(recordingSessionId, captionLang, pendingSamples.copyOf(sampleCount))
                    streamingTurnPrimed = true
                  } else {
                    feedCaptionPartial(recordingSessionId, captionLang, buffer.copyOf(readCount))
                  }
                }
              } else {
                // No streaming model for this language (AUTO / unsupported / load failed): periodic
                // chunked-Whisper re-decode, which is multilingual via the offline Whisper model.
                if (!streamingTurnPrimed) {
                  BaoLog.i(REC_TAG, "LIVE_PARTIAL_BRANCH=chunked session=$recordingSessionId")
                  streamingTurnPrimed = true
                }
                samplesSinceLastPartial += readCount
                if (samplesSinceLastPartial >= partialCaptionStepSamples) {
                  samplesSinceLastPartial = 0
                  queuePartialCaption(recordingSessionId, pendingSamples.copyOf(sampleCount))
                }
              }
            }

            // Face-to-face turn endpoint: speech followed by a natural pause flushes the whole
            // utterance as ONE segment, which is then translated and spoken before the next turn.
            if (
              faceToFace &&
              turnSpeechHeard &&
              sampleCount >= minSegmentSamples &&
              System.currentTimeMillis() - lastSpeechActivityAt >= F2F_TURN_END_SILENCE_MS
            ) {
              BaoLog.i(REC_TAG, "F2F turn endpoint samples=$sampleCount session=$recordingSessionId")
              queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
              queuedLiveSegment = true
              sampleCount = 0
              samplesSinceLastLiveWindow = 0
              samplesSinceLastPartial = 0
              streamingTurnPrimed = false
              captioner?.reset()
              turnSpeechHeard = false
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            val newAmplitudes = (uiState.value.amplitudes + frameAmplitude).takeLast(WAVEFORM_HISTORY)
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
            if (capturePaused) {
              // The device audio HAL can starve the mic while the app's own TTS plays through the
              // speaker. Capture is deliberately gated during playback anyway, so an empty read
              // here is expected — keep the stall reference fresh or the watchdog kills the
              // hands-free session at the first spoken translation longer than the timeout.
              lastPositiveReadAt = now
              delay(AUDIO_READ_EMPTY_SLEEP_MS)
              continue@readLoop
            }
            if (now - lastPositiveReadAt >= AUDIO_READ_STALL_TIMEOUT_MS && injectedFrameSource == null) {
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
            delay(AUDIO_READ_EMPTY_SLEEP_MS)
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
        // The watchdog/read-error exits skip the normal end-of-loop stop event; without this the
        // turn control keeps showing a live phase for a session whose mic is already dead.
        conversationEvent { onRecordingStop() }
        return@launch
      }

      // Process the trailing tail regardless of whether live windows already fired. Gating the
      // final flush on a full stride (4s) silently dropped the last <4s of speech: that tail past
      // the last window boundary was never part of any emitted window, so use the 1s minimum.
      val finalSampleCount = if (queuedLiveSegment) samplesSinceLastLiveWindow else sampleCount
      if (!discardRecordingOnStop && finalSampleCount >= minSegmentSamples) {
        val finalStart = sampleCount - finalSampleCount
        segmentProcessingMutex.withLock {
          processAudioSegment(
            pendingSamples.copyOfRange(finalStart, sampleCount),
            recordingSessionId = recordingSessionId,
            preserveRecordingStatus = false,
            reportEmptySpeech = !queuedLiveSegment,
          )
        }
      } else if (!discardRecordingOnStop && sampleCount > 0 && !queuedLiveSegment) {
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_recording_short)) }
      }
      conversationEvent { onRecordingStop() }
      // The loop can exit on its own (silence auto-stop) while UiState still says Recording —
      // without this the mic icon would show a live session whose AudioRecord is already released.
      uiState.update {
        if (it.isRecordingActive) it.copy(pipelineStatus = PipelineStatus.Idle) else it
      }
      } finally {
        echoCanceler?.release()
        noiseSuppressor?.release()
      }
      } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          recorder.stop()
        }
        recorder.release()
        audioRecord = null
      }
      } finally {
        injectedFrameSource = null
        unlockRecordingIfHeld()
      }
    }
  }

  fun stopRecording() {
    if (!uiState.value.isRecordingActive) return
    discardRecordingOnStop = false
    uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    recordingScope?.cancel()
    recordingScope = null
    recordingJob = null
    captioner?.reset()
    // Scope cancellation kills the read loop before its own onRecordingStop line runs, so the
    // phase must be closed out here or the UI stays stuck on Listening/Speaking.
    conversationEvent { onRecordingStop() }
  }

  /** Cancel recording and discard in-flight audio — no final-segment flush. */
  fun cancelRecording() {
    if (!uiState.value.isRecordingActive) return
    discardRecordingOnStop = true
    uiState.update {
      it.copy(
        pipelineStatus = PipelineStatus.Idle,
        liveTranslationPreview = null,
        elapsedSeconds = 0f,
        amplitudes = emptyList(),
      )
    }
    recordingScope?.cancel()
    recordingScope = null
    recordingJob = null
    captioner?.reset()
    conversationEvent { onRecordingStop() }
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
    val scope = recordingScope ?: return
    scope.launch(Dispatchers.IO) {
      segmentProcessingMutex.withLock {
        processAudioSegment(
          audioSamples = audioSamples,
          recordingSessionId = recordingSessionId,
          preserveRecordingStatus = true,
          reportEmptySpeech = false,
        )
      }
    }
  }

  // Streaming partial caption: while a turn is still being spoken, decode the audio heard SO FAR and
  // surface it as the live recognized text — so the caption streams as you talk instead of only
  // appearing at end-of-turn. Best-effort and non-destructive: it never translates, commits a
  // transcript, or speaks. At most one runs at a time (compareAndSet), and it shares
  // segmentProcessingMutex with the final decode so the single Whisper context is never used
  // concurrently; the in-flight flag is cleared on completion (success, failure, or cancel).
  private fun queuePartialCaption(recordingSessionId: Long, audioSamples: ShortArray) {
    if (!partialCaptionInFlight.compareAndSet(false, true)) return
    val scope = recordingScope
    if (scope == null) {
      partialCaptionInFlight.set(false)
      return
    }
    val job =
      scope.launch(Dispatchers.IO) {
        if (isStaleRecordingSegment(recordingSessionId)) return@launch
        val whisper = pipelines.pipelineMutex.withLock { pipelines.whisperPipeline } ?: return@launch
        val text =
          segmentProcessingMutex.withLock {
            if (isStaleRecordingSegment(recordingSessionId)) null
            else whisper.transcribeBlocking(audioSamples).getOrNull()?.text
          }
        if (text != null && isValidTranscription(text) && !isStaleRecordingSegment(recordingSessionId)) {
          BaoLog.i(REC_TAG, "Partial caption chars=${text.length} session=$recordingSessionId")
          uiState.update { if (it.isRecordingActive) it.copy(liveSourcePreview = text) else it }
        }
      }
    job.invokeOnCompletion { partialCaptionInFlight.set(false) }
  }

  // The ISO code of the language to caption this session — the speaker's configured source language.
  // null when AUTO or a language with no streaming model (-> chunked-Whisper caption instead).
  private fun captionLanguageCode(): String? {
    val sourceKey = uiState.value.sourceLanguage
    if (sourceKey == SupportedLanguages.AUTO.key) return null
    val code = SupportedLanguages.CODE_MAP[sourceKey] ?: return null
    return if (BaoTranslateModelManager.captionEngineFor(code) != null) code else null
  }

  // True when a provisioned streaming model exists for [langCode] AND a prior load attempt for this
  // language has not failed. A present-but-unloadable model resolves to false so the read loop falls
  // back to chunked-Whisper instead of showing nothing.
  private fun isCaptionViable(langCode: String): Boolean =
    BaoTranslateModelManager.isCaptionModelReady(getApp(), langCode) &&
      !(captionerLang == langCode && captionerChecked && captioner == null)

  // Loads the streaming captioner for [langCode] (blocking native load, ~1s). MUST be called OFF the
  // audio read thread — pre-warmed at recording start so the first turn never stalls capture. Builds
  // the sherpa transducer for English and Vosk for the other languages; caches one captioner at a
  // time and rebuilds when the caption language changes.
  @Synchronized
  private fun ensureCaptioner(langCode: String): StreamingCaptioner? {
    if (captionerLang == langCode) {
      captioner?.let { return it }
      if (captionerChecked) return null
    } else {
      captioner?.release()
      captioner = null
      captionerLang = langCode
      captionerChecked = false
    }
    val dir = BaoTranslateModelManager.getCaptionModelDir(getApp(), langCode)
    val pipeline: StreamingCaptioner? =
      when (BaoTranslateModelManager.captionEngineFor(langCode)) {
        BaoTranslateModelManager.CaptionEngine.SHERPA ->
          dir?.let { StreamingSttPipeline(it.absolutePath) }?.takeIf { it.initialize() }
        BaoTranslateModelManager.CaptionEngine.VOSK ->
          dir?.let { VoskStreamingPipeline(it.absolutePath) }?.takeIf { it.initialize() }
        null -> null
      }
    // Mark checked only AFTER the load attempt resolves (warming vs failed distinction).
    captionerChecked = true
    if (pipeline == null) {
      BaoLog.w(REC_TAG, "Caption model for '$langCode' present but failed to load; using chunked")
    }
    captioner = pipeline
    return pipeline
  }

  /** Releases the native streaming captioner; call when the owning ViewModel is cleared. */
  fun releaseStreamingStt() {
    captioner?.release()
    captioner = null
    captionerLang = null
    captionerChecked = false
  }

  // Feeds an INCREMENTAL audio chunk to the (pre-warmed) captioner for [langCode] and surfaces its
  // growing hypothesis as the live caption. Non-blocking: skips the frame while warming rather than
  // loading on the audio thread. Returns false if no loaded captioner for this language.
  private fun feedCaptionPartial(recordingSessionId: Long, langCode: String, samples: ShortArray): Boolean {
    val cap = captioner?.takeIf { captionerLang == langCode && it.isReady } ?: return false
    if (isStaleRecordingSegment(recordingSessionId)) return true
    val text = cap.acceptAndDecode(samples).trim()
    if (text.isNotBlank() && !isStaleRecordingSegment(recordingSessionId)) {
      uiState.update { if (it.isRecordingActive) it.copy(liveSourcePreview = text) else it }
    }
    return true
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
    conversationEvent { onProcessingStart() }
    try {
      runSegmentPipeline(audioSamples, recordingSessionId, preserveRecordingStatus, reportEmptySpeech)
    } finally {
      // Segments that end without playback (VAD-empty, blank decode, errors) return the phase to
      // Listening while the mic loop is still live; after playback the tail-mute chain already
      // landed on Listening, and after a session stop the Idle phase makes this a no-op.
      conversationEvent { onSegmentComplete() }
    }
  }

  private suspend fun runSegmentPipeline(
    audioSamples: ShortArray,
    recordingSessionId: Long?,
    preserveRecordingStatus: Boolean,
    reportEmptySpeech: Boolean,
	  ) {
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
      // Re-arm per segment: a previous segment's playback chain lands the phase back on
      // Listening, and this transition (Listening -> Processing) marks the next STT pass.
      conversationEvent { onProcessingStart() }
      val transcriptionResult = whisper.transcribeBlocking(segment.toShortArray())
      BaoLog.i(REC_TAG, "STT done success=${transcriptionResult.isSuccess} chars=${transcriptionResult.getOrNull()?.text?.length ?: 0}")
      if (isStaleRecordingSegment(recordingSessionId)) return

      val transcription = transcriptionResult.fold(
        onSuccess = { it },
        onFailure = { error ->
          BaoLog.w(REC_TAG, "Transcription failed: ${error.message}")
          // A blank decode (VAD false-positive / noise window) is benign in continuous live mode;
          // suppress it there to match the VAD-empty and invalid-transcription branches. A real
          // native decode failure still surfaces so a corrupt model is never silent.
          val benignEmpty = error is EmptyTranscriptionException
          if ((reportEmptySpeech || !benignEmpty) && !isStaleRecordingSegment(recordingSessionId)) {
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

      // Surface the recognized SOURCE text immediately — the translation below (Qwen) is the
      // dominant latency (~1s+), so showing the live caption now makes the conversation feel live
      // instead of waiting for the full translated commit.
      uiState.update { it.copy(liveSourcePreview = transcription.text) }

      // Face-to-face (single-device, 2-speaker) auto-detects every turn (like AUTO source) so either
      // person's language is transcribed; STT is already in auto-detect for both.
      val twoWay = uiState.value.faceToFaceMode
      val autoDetected = twoWay || uiState.value.sourceLanguage == SupportedLanguages.AUTO.key
      val sourceLang = if (autoDetected) {
        SupportedLanguages.normalizeDetectedCode(transcription.language)
          ?: SupportedLanguages.CODE_MAP[SupportedLanguages.AUTO.key]
          ?: "auto"
      } else {
        SupportedLanguages.codeFor(uiState.value.sourceLanguage)
      }

      // In face-to-face the two configured languages are a bidirectional pair: a turn spoken in the
      // target language is translated BACK to the source language, so both speakers are understood on
      // one device. Everywhere else, translation always goes to the configured target.
      val langA = SupportedLanguages.codeFor(uiState.value.sourceLanguage)
      val langB = SupportedLanguages.codeFor(uiState.value.targetLanguage)
      val targetLang = if (twoWay && sourceLang == langB) langA else langB

      if (autoDetected) {
        if (isStaleRecordingSegment(recordingSessionId)) return
        val detectedKey = SupportedLanguages.keyForCode(sourceLang)
          ?.takeIf { key -> key != SupportedLanguages.AUTO.key }
        val shouldAutoAccept = !twoWay && uiState.value.autoAcceptDetectedLanguage
        uiState.update { it.copy(detectedLanguage = detectedKey) }
        if (detectedKey != null && shouldAutoAccept) {
          onAutoAcceptDetectedLanguage?.invoke(detectedKey)
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
              uiState.update { it.copy(liveTranslationPreview = translatedText, liveSourcePreview = null) }
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
              liveSourcePreview = null,
            )
          }

          // Live playback policy: one-shot mode always speaks on stop; headset routes speak live.
          // Face-to-face speaks live ON the phone speaker too — that is the product: the
          // capturePaused gate plus hardware AEC (both set up in the read loop) exist precisely so
          // speaker playback cannot feed back into continuous capture.
          val shouldPlayNow =
            !preserveRecordingStatus ||
              uiState.value.faceToFaceMode ||
              uiState.value.currentAudioDevice !is AudioDevice.Speaker
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
  // through the same engine-selection + playback logic.
  internal suspend fun synthesizeSpeech(
    text: String,
    language: String,
    recordingSessionId: Long? = null,
    preserveRecordingStatus: Boolean = false,
    // A connected peer's shared voice timbre (256-d OpenVoice speaker embedding). When non-null, the
    // output is cloned into THAT speaker's voice so a multi-speaker conversation is heard in each
    // person's own voice; null => the locally-enrolled user's timbre (the normal local-speech path).
    speakerSe: FloatArray? = null,
    timbre: SpeechTimbre = SpeechTimbre.LocalEnrolled,
  ): Boolean {
    testLastCloneTargetSe = null
    testLastWasCloned = false

    if (isStaleRecordingSegment(recordingSessionId)) return false

    if (!preserveRecordingStatus) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Speaking) }
    }

    val state = uiState.value
    val ovConverter = pipelines.openVoiceConverter

    // Cache key: voice profile enrollment state determines whether cloning is applied, so include
    // a fingerprint of the enrolled embedding so cache hits are correct across profile changes.
    val voiceId = when {
      timbre == SpeechTimbre.PeerOnly -> "peer"
      state.voiceProfileEnrolled -> state.activeVoiceProfileId
      else -> "default"
    }

    // Prosody speed adjustment: when a voice profile is enrolled, use the user's natural speaking
    // rate to adjust Kokoro's speed parameter. This makes the cloned output sound more natural by
    // matching the user's cadence rather than Kokoro's default rate.
    val prosodySpeed = if (state.voiceProfileEnrolled && timbre != SpeechTimbre.PeerOnly) {
      val profileId = state.activeVoiceProfileId
      voiceProfileManager?.loadProsody(profileId)?.speedMultiplier ?: 1.0f
    } else 1.0f

    // Check cache BEFORE synthesis so replay of previously translated messages is instant.
    val cached = AudioCache.get(text = text, language = language, voiceId = voiceId, speed = prosodySpeed)
    val audio = if (cached != null) {
      cached
    } else {
      val kokoroVoice = KokoroTtsPipeline.getVoiceForLanguage(language)
      val base = pipelines.ttsRouter().synthesize(
        text = text,
        language = language,
        kokoroVoiceId = kokoroVoice,
        speed = prosodySpeed,
      )

      // Voice clone: the OpenVoice tone-color converter re-times the base audio into a target timbre.
      // It is language- AND source-agnostic, so this clones EVERY supported language — including the
      // platform-TTS fallback languages (de/ko/ru/ar), not just Kokoro's. [speakerSe] (a peer's shared
      // timbre) takes precedence so a multi-speaker conversation is heard in each speaker's own voice;
      // otherwise the locally-enrolled user's timbre is used when enrolled. Falls back to the un-cloned
      // base when no timbre applies or a conversion fails.
      val targetSe = when (timbre) {
        SpeechTimbre.PeerOnly -> speakerSe
        SpeechTimbre.LocalEnrolled ->
          speakerSe ?: pipelines.openVoiceTargetSe?.takeIf { state.voiceProfileEnrolled }
      }
      if (targetSe != null) {
        testLastCloneTargetSe = targetSe.copyOf()
      }
      val synthesized = if (ovConverter != null && targetSe != null && base != null) {
        testLastWasCloned = true
        ovConverter.convert(base, targetSe) ?: base
      } else {
        base
      }

      // Store in cache for instant replay later.
      if (synthesized != null) {
        AudioCache.put(text = text, language = language, voiceId = voiceId, speed = prosodySpeed, audio = synthesized)
      }
      synthesized
    }

    var played = false
    if (audio != null) {
      if (isStaleRecordingSegment(recordingSessionId)) return false
      withContext(Dispatchers.Default) {
        // Gate the capture loop for the duration of playback (+ a short acoustic-decay tail) so the
        // live mic never hears and re-translates this output. finally guarantees the gate reopens even
        // if playback is interrupted, otherwise the mic would stay deaf for the rest of the session.
        capturePaused = true
        conversationEvent { onPlaybackStart() }
        try {
          audioRouter.play(audio.samples, sampleRate = audio.sampleRate)
          conversationEvent { onPlaybackEnd() }
          delay(PLAYBACK_TAIL_MUTE_MS)
        } finally {
          // Cancellation can skip onPlaybackEnd above; emit it here first so the
          // Speaking -> Cooldown -> Listening chain stays valid on every exit path.
          conversationEvent { onPlaybackEnd() }
          conversationEvent { onTailMuteComplete() }
          capturePaused = false
        }
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
