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

class VadProcessor(private val context: Context) {
  private var vad: Vad? = null
  private var isReady = false

  fun initialize(): Boolean {
    val modelPath = BaoTranslateModelManager.getVadModelPath(context)
    val modelFile = File(modelPath)
    if (!modelFile.exists()) {
      BaoLog.w(TAG, "Silero VAD model not found: $modelPath")
      return false
    }

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
    return true
  }

  fun processAudioSegment(audioSamples: ShortArray): List<List<Short>> {
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

    v.reset()
    v.acceptWaveform(floatSamples)
    v.flush()

    val segments = mutableListOf<List<Short>>()

    while (!v.empty()) {
      val speechSegment = v.front()
      val floatSeg = speechSegment.samples

      if (floatSeg.isNotEmpty()) {
        val shortSeg = ShortArray(floatSeg.size) { i ->
          (floatSeg[i] * 32768f).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        }
        segments.add(shortSeg.toList())
      }
      v.pop()
    }

    val usableSegments = segments.filter { it.size >= MIN_STT_SEGMENT_SAMPLES }
    if (usableSegments.isNotEmpty()) {
      v.reset()
      return usableSegments
    }

    if (audioSamples.hasFallbackSpeechEnergy()) {
      v.reset()
      return listOf(audioSamples.toList())
    }

    v.reset()
    return emptyList()
  }

  fun reset() {
    vad?.reset()
  }

  fun cleanup() {
    vad?.release()
    vad = null
    isReady = false
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
