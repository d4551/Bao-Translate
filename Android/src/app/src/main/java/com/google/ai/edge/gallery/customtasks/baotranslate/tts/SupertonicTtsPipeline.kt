package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioPlayback
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

private const val TAG = "SupertonicTtsPipeline"

/**
 * On-device supplemental TTS via sherpa-onnx SupertonicTTS 3 for languages Kokoro cannot
 * phonemize (de, ja, ko, ru, ar). Optional download — falls back to [PlatformTtsPipeline] when absent.
 */
class SupertonicTtsPipeline(private val context: Context) : TtsEngine {
  private var tts: OfflineTts? = null
  private var isReady = false
  private val inferenceLock = Any()

  override fun initialize(modelDir: String): Boolean {
    val dir = File(modelDir)
    val durationPredictor = File(dir, "duration_predictor.int8.onnx")
    val textEncoder = File(dir, "text_encoder.int8.onnx")
    val vectorEstimator = File(dir, "vector_estimator.int8.onnx")
    val vocoder = File(dir, "vocoder.int8.onnx")
    val ttsJson = File(dir, "tts.json")
    val unicodeIndexer = File(dir, "unicode_indexer.bin")
    val voiceStyle = File(dir, "voice.bin")

    if (!durationPredictor.exists() || !textEncoder.exists() || !vectorEstimator.exists() ||
      !vocoder.exists() || !ttsJson.exists() || !unicodeIndexer.exists() || !voiceStyle.exists()
    ) {
      BaoLog.w(TAG, "Supertonic model files incomplete in $modelDir")
      return false
    }

    val config = OfflineTtsConfig(
      model = OfflineTtsModelConfig(
        supertonic = OfflineTtsSupertonicModelConfig(
          durationPredictor = durationPredictor.absolutePath,
          textEncoder = textEncoder.absolutePath,
          vectorEstimator = vectorEstimator.absolutePath,
          vocoder = vocoder.absolutePath,
          ttsJson = ttsJson.absolutePath,
          unicodeIndexer = unicodeIndexer.absolutePath,
          voiceStyle = voiceStyle.absolutePath,
        ),
        debug = false,
        numThreads = PipelineConfig.TTS_THREADS,
        provider = "cpu",
      ),
    )

    tts = OfflineTts(null, config)
    isReady = true
    BaoLog.i(TAG, "Supertonic TTS ready from $modelDir")
    return true
  }

  override fun synthesize(text: String, voiceId: String?, speed: Float): FloatArray? =
    synthesizeAudio(text, voiceId, speed)?.samples

  override fun synthesizeAudio(text: String, voiceId: String?, speed: Float): SynthesizedAudio? {
    val languageCode = voiceId ?: return null
    if (!supportsLanguage(languageCode)) return null
    return synthesizeAudioLocked(text, languageCode, speed)
  }

  override fun play(samples: FloatArray, sampleRate: Int) {
    AudioPlayback.playPcmFloat(samples, sampleRate)
  }

  private fun synthesizeAudioLocked(text: String, languageCode: String, speed: Float): SynthesizedAudio? =
    synchronized(inferenceLock) {
      val engine = tts ?: return null
      if (!isReady || text.isBlank()) return null

      val code = normalizeLanguageCode(languageCode)

      val genConfig = GenerationConfig(
        // sherpa-onnx default (0.2f). 0f scales ALL predicted inter-phrase pause durations to zero,
        // producing rushed, pause-less "robotic" delivery on the supplemental languages.
        silenceScale = 0.2f,
        speed = speed,
        sid = 0,
        referenceAudio = floatArrayOf(),
        referenceSampleRate = 0,
        referenceText = "",
        numSteps = 8,
        extra = mapOf("lang" to code),
      )

      val audio = runCatching {
        engine.generateWithConfig(text = text, config = genConfig)
      }.getOrElse { e ->
        BaoLog.w(TAG, "Supertonic generate failed lang=$code: ${e.message}")
        return null
      }

      if (audio.samples.isEmpty()) return null
      SynthesizedAudio(samples = audio.samples, sampleRate = audio.sampleRate)
    }

  override fun cleanup() = synchronized(inferenceLock) {
    tts?.release()
    tts = null
    isReady = false
  }

  companion object {
    /** Languages routed here when the optional Supertonic model is downloaded. */
    private val SUPPLEMENTAL_LANGUAGES = setOf("de", "ja", "ko", "ru", "ar")

    fun supportsLanguage(language: String): Boolean {
      val code = normalizeLanguageCode(language)
      return code in SUPPLEMENTAL_LANGUAGES
    }

    fun isModelReady(modelDir: File): Boolean {
      if (!modelDir.isDirectory) return false
      return listOf(
        "duration_predictor.int8.onnx",
        "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx",
        "vocoder.int8.onnx",
        "tts.json",
        "unicode_indexer.bin",
        "voice.bin",
      ).all { File(modelDir, it).length() > 0L }
    }

    private fun normalizeLanguageCode(language: String): String =
      when (language.lowercase()) {
        "german" -> "de"
        "japanese" -> "ja"
        "korean" -> "ko"
        "russian" -> "ru"
        "arabic" -> "ar"
        else -> language.lowercase().substringBefore('-')
      }
  }
}