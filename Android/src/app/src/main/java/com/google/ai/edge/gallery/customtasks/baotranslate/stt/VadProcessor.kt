package com.google.ai.edge.gallery.customtasks.baotranslate.stt

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

private const val TAG = "VadProcessor"
private const val SAMPLE_RATE = PipelineConfig.STT_SAMPLE_RATE
private const val MIN_SPEECH_DURATION_SEC = 0.25f
private const val MIN_SILENCE_DURATION_SEC = 0.25f
private const val MAX_SPEECH_DURATION_SEC = 20.0f
private const val WINDOW_SIZE = 512
private const val MIN_STT_SEGMENT_SAMPLES = SAMPLE_RATE
private const val FALLBACK_RMS_THRESHOLD = 0.015f
private const val FALLBACK_PEAK_THRESHOLD = 1_000

/**
 * Outcome of [VadProcessor.initialize]. Explicit states replace a bare `Boolean` so callers can
 * distinguish "native VAD ready" from "no model, energy-fallback" from "native init failed" without
 * guessing — and so the energy-fallback path is a documented contract, not an accident.
 */
sealed interface VadInitResult {
  /** Native Silero VAD initialized and will segment audio. */
  data object Initialized : VadInitResult

  /** The VAD model file is absent; [VadProcessor.processAudioSegment] uses energy-based fallback. */
  data object ModelUnavailable : VadInitResult

  /** Native initialization threw; the same energy-based fallback applies. */
  data class Failed(val message: String) : VadInitResult
}

class VadProcessor(private val context: Context) {
  private var vad: Vad? = null
  private var isReady = false

  // Serializes native inference against release() so the sherpa-onnx handle can never be freed
  // (cleanup on a model switch / onCleared) while a decode is mid-flight on a captured reference.
  // Also serializes concurrent decode calls (Vad is not safe for concurrent native use).
  private val inferenceLock = Any()

  fun initialize(): VadInitResult {
    val modelPath = BaoTranslateModelManager.getVadModelPath(context)
    if (!File(modelPath).exists()) {
      BaoLog.w(TAG, "Silero VAD model not found: $modelPath")
      return VadInitResult.ModelUnavailable
    }

    // The native Vad(...) constructor is JNI; a load/init fault would otherwise escape the caller.
    // Funnel it into a typed Failed result so the pipeline degrades to energy-based segmentation.
    return runCatching {
      val sileroConfig = SileroVadModelConfig(
        model = modelPath,
        threshold = 0.5f,
        minSilenceDuration = MIN_SILENCE_DURATION_SEC,
        minSpeechDuration = MIN_SPEECH_DURATION_SEC,
        windowSize = WINDOW_SIZE,
        maxSpeechDuration = MAX_SPEECH_DURATION_SEC,
      )

      val tenConfig = TenVadModelConfig(
        model = "",
        threshold = 0.5f,
        minSilenceDuration = MIN_SILENCE_DURATION_SEC,
        minSpeechDuration = MIN_SPEECH_DURATION_SEC,
        windowSize = WINDOW_SIZE,
        maxSpeechDuration = MAX_SPEECH_DURATION_SEC,
      )

      val config = VadModelConfig(
        sileroVadModelConfig = sileroConfig,
        tenVadModelConfig = tenConfig,
        sampleRate = SAMPLE_RATE,
        numThreads = PipelineConfig.VAD_THREADS,
        provider = "cpu",
        debug = false,
      )

      vad = Vad(null, config)
      isReady = true
      BaoLog.i(TAG, "Silero VAD initialized via Sherpa-ONNX")
      VadInitResult.Initialized
    }.getOrElse { e ->
      isReady = false
      BaoLog.w(TAG, "Silero VAD native init failed; energy fallback will be used: ${e.message}")
      VadInitResult.Failed(e.message ?: "VAD native init failed")
    }
  }

  fun processAudioSegment(audioSamples: ShortArray): List<List<Short>> = synchronized(inferenceLock) {
    val v = vad
    if (!isReady || v == null) {
      return fallbackSegmentation(audioSamples)
    }

    if (audioSamples.size < WINDOW_SIZE) {
      return listOf(audioSamples.toList())
    }

    val floatSamples = FloatArray(audioSamples.size) { i ->
      audioSamples[i].toFloat() / 32768f
    }

    // The sherpa-onnx VAD calls below are native (JNI); a native fault would otherwise escape the
    // recording coroutine (no CoroutineExceptionHandler) and crash the app. Funnel any throw into
    // the energy-based [fallbackSegmentation] so recording degrades gracefully instead.
    return runCatching {
      v.reset()
      v.acceptWaveform(floatSamples)
      v.flush()

      val segments = mutableListOf<List<Short>>()
      while (!v.empty()) {
        val floatSeg = v.front().samples
        if (floatSeg.isNotEmpty()) {
          segments.add(
            ShortArray(floatSeg.size) { i ->
              (floatSeg[i] * 32768f).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            }.toList()
          )
        }
        v.pop()
      }
      v.reset()

      val usableSegments = segments.filter { it.size >= MIN_STT_SEGMENT_SAMPLES }
      when {
        usableSegments.isNotEmpty() -> usableSegments
        audioSamples.hasFallbackSpeechEnergy() -> listOf(audioSamples.toList())
        else -> emptyList()
      }
    }.getOrElse { e ->
      BaoLog.w(TAG, "VAD native inference failed; using energy fallback: ${e.message}")
      fallbackSegmentation(audioSamples)
    }
  }

  fun reset() {
    synchronized(inferenceLock) { vad?.reset() }
  }

  fun cleanup() {
    synchronized(inferenceLock) {
      vad?.release()
      vad = null
      isReady = false
    }
  }

  private fun fallbackSegmentation(audioSamples: ShortArray): List<List<Short>> {
    val minSegmentSamples = SAMPLE_RATE
    val silenceThreshold = 500
    if (!audioSamples.hasFallbackSpeechEnergy()) {
      return emptyList()
    }

    if (audioSamples.size < minSegmentSamples) {
      return listOf(audioSamples.toList())
    }

    val segments = mutableListOf<List<Short>>()
    var currentSegment = mutableListOf<Short>()
    var silenceCount = 0
    val silenceWindow = (SAMPLE_RATE * 0.15f).toInt()

    for (sample in audioSamples) {
      currentSegment.add(sample)

      if (kotlin.math.abs(sample.toInt()) < silenceThreshold) {
        silenceCount++
      } else {
        silenceCount = 0
      }

      if (silenceCount >= silenceWindow && currentSegment.size >= minSegmentSamples) {
        segments.add(currentSegment.toList())
        currentSegment = mutableListOf()
        silenceCount = 0
      }
    }

    if (currentSegment.size >= minSegmentSamples / 2) {
      segments.add(currentSegment.toList())
    }

    return segments
  }

  private fun ShortArray.hasFallbackSpeechEnergy(): Boolean {
    if (isEmpty()) return false
    var peak = 0
    var sumSquares = 0.0
    forEach { sample ->
      val value = sample.toInt()
      peak = maxOf(peak, kotlin.math.abs(value))
      sumSquares += value.toDouble() * value.toDouble()
    }
    val rms = kotlin.math.sqrt(sumSquares / size) / 32768.0
    return peak >= FALLBACK_PEAK_THRESHOLD && rms >= FALLBACK_RMS_THRESHOLD
  }
}
