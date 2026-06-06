package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioPlayback
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File

class VoiceClonePipeline(private val context: Context) : TtsEngine {
  private companion object {
    const val TAG = "VoiceClonePipeline"
  }
  private var tts: OfflineTts? = null
  private var isReady = false
  private var referenceAudioPath: String? = null
  private var cachedReferenceAudio: Pair<Int, FloatArray>? = null

  override fun initialize(modelDir: String): Boolean {
    val dirFile = File(modelDir)
    if (!dirFile.exists() || !dirFile.isDirectory) {
      return false
    }

    val requiredFiles = listOf(
      "lm_flow.int8.onnx",
      "lm_main.int8.onnx",
      "encoder.onnx",
      "decoder.int8.onnx",
      "text_conditioner.onnx",
      "vocab.json",
      "token_scores.json",
    )

    if (requiredFiles.any { !File(dirFile, it).exists() }) {
      return false
    }

    val config = OfflineTtsConfig(
      model = OfflineTtsModelConfig(
        pocket = OfflineTtsPocketModelConfig(
          lmFlow = File(dirFile, "lm_flow.int8.onnx").absolutePath,
          lmMain = File(dirFile, "lm_main.int8.onnx").absolutePath,
          encoder = File(dirFile, "encoder.onnx").absolutePath,
          decoder = File(dirFile, "decoder.int8.onnx").absolutePath,
          textConditioner = File(dirFile, "text_conditioner.onnx").absolutePath,
          vocabJson = File(dirFile, "vocab.json").absolutePath,
          tokenScoresJson = File(dirFile, "token_scores.json").absolutePath,
          voiceEmbeddingCacheCapacity = PipelineConfig.VOICE_EMBEDDING_CACHE_CAPACITY,
        ),
        debug = false,
        numThreads = PipelineConfig.TTS_THREADS,
        provider = "cpu",
      ),
    )

    tts = OfflineTts(null, config)
    isReady = true
    return true
  }

  fun setReferenceAudio(wavPath: String) {
    if (referenceAudioPath != wavPath) {
      cachedReferenceAudio = null
    }
    referenceAudioPath = wavPath
  }

  fun clearReferenceAudio() {
    referenceAudioPath = null
    cachedReferenceAudio = null
  }

  override fun synthesize(text: String, voiceId: String?, speed: Float): FloatArray? {
    return synthesizeAudio(text, voiceId, speed)?.samples
  }

  override fun synthesizeAudio(text: String, voiceId: String?, speed: Float): SynthesizedAudio? {
    val engine = tts ?: return null
    if (!isReady) return null
    val refPath = referenceAudioPath ?: return null

    val (sampleRate, samples) = cachedReferenceAudio ?: run {
      val refFile = File(refPath)
      if (!refFile.exists()) {
        return null
      }
      if (refFile.length() > 10_000_000L) {
        BaoLog.w(TAG, "Reference audio file too large: ${refFile.length()} bytes")
        return null
      }

      val wavBytes = refFile.readBytes()
      if (!WavUtils.isValidWav(wavBytes)) {
        BaoLog.e(TAG, "Invalid WAV file: $refPath")
        return null
      }

      val rate = WavUtils.extractSampleRateFromWav(wavBytes) ?: 16000
      val audioSamples = WavUtils.extractSamplesFromWav(wavBytes)

      if (audioSamples.isEmpty()) {
        return null
      }

      val cached = rate to audioSamples
      cachedReferenceAudio = cached
      cached
    }

    val genConfig = GenerationConfig(
      silenceScale = PipelineConfig.VOICE_CLONE_SILENCE_SCALE,
      speed = speed,
      sid = PipelineConfig.VOICE_CLONE_SID,
      referenceAudio = samples,
      referenceSampleRate = sampleRate,
      referenceText = "",
      numSteps = PipelineConfig.VOICE_CLONE_NUM_STEPS,
      extra = emptyMap(),
    )

    val audio = engine.generateWithConfig(text, genConfig)

    if (audio.samples.isEmpty()) {
      return null
    }

    return SynthesizedAudio(samples = audio.samples, sampleRate = audio.sampleRate)
  }

  override fun play(samples: FloatArray, sampleRate: Int) {
    AudioPlayback.playPcmFloat(samples, sampleRate)
  }

  override fun cleanup() {
    tts?.release()
    tts = null
    isReady = false
    referenceAudioPath = null
    cachedReferenceAudio = null
  }
}
