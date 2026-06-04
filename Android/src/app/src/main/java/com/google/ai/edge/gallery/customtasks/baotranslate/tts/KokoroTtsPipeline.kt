package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioPlayback
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

private const val TAG = "KokoroTtsPipeline"

data class KokoroVoice(val id: String, val language: String, val gender: String)

class KokoroTtsPipeline(private val context: Context) : TtsEngine {
  private var tts: OfflineTts? = null
  private var isReady = false
  private var currentVoiceId: String = DEFAULT_VOICE

  override fun initialize(modelDir: String): Boolean {
    val dir = File(modelDir)
    val int8Model = File(dir, "model.int8.onnx")
    val modelFile = if (int8Model.exists()) int8Model else File(dir, "model.onnx")
    val voicesFile = File(dir, "voices.bin")
    val tokensFile = File(dir, "tokens.txt")
    val dataDir = File(dir, "espeak-ng-data")
    val lexiconUsEn = File(dir, "lexicon-us-en.txt")
    val lexiconZh = File(dir, "lexicon-zh.txt")
    val lexiconGbEn = File(dir, "lexicon-gb-en.txt")

    if (!modelFile.exists() || !voicesFile.exists() || !tokensFile.exists()) {
      return false
    }

    val lexiconParts = buildList {
      if (lexiconUsEn.exists()) add(lexiconUsEn.absolutePath)
      if (lexiconGbEn.exists()) add(lexiconGbEn.absolutePath)
      if (lexiconZh.exists()) add(lexiconZh.absolutePath)
    }.joinToString(",")

    val ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst")
      .map { File(modelDir, it) }
      .filter { it.exists() }
      .joinToString(",") { it.absolutePath }

    val config = OfflineTtsConfig(
      model = OfflineTtsModelConfig(
        kokoro = OfflineTtsKokoroModelConfig(
          model = modelFile.absolutePath,
          voices = voicesFile.absolutePath,
          tokens = tokensFile.absolutePath,
          dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
          lexicon = lexiconParts,
          lang = "en",
        ),
        debug = false,
        numThreads = PipelineConfig.TTS_THREADS,
        provider = "cpu",
      ),
      ruleFsts = ruleFsts,
    )

    tts = OfflineTts(null, config)
    isReady = true
    return true
  }

  override fun synthesize(text: String, voiceId: String?, speed: Float): FloatArray? {
    val engine = tts ?: return null
    if (!isReady) return null
    val voice = voiceId ?: currentVoiceId

    val audio = engine.generate(
      text = text,
      sid = getVoiceIndex(voice),
      speed = speed,
    )

    if (audio.samples.isEmpty()) {
      return null
    }

    return audio.samples
  }

  override fun play(samples: FloatArray, sampleRate: Int) {
    AudioPlayback.playPcmFloat(samples, sampleRate)
  }

  fun setVoice(voiceId: String) {
    currentVoiceId = voiceId
  }

  override fun cleanup() {
    tts?.release()
    tts = null
    isReady = false
  }

  private fun getVoiceIndex(voiceId: String): Int {
    return AVAILABLE_VOICES.indexOfFirst { it.id == voiceId }.coerceAtLeast(0)
  }

  companion object {
    private const val DEFAULT_VOICE = "af_heart"

    val AVAILABLE_VOICES = listOf(
      KokoroVoice("af_heart", "en", "female"),
      KokoroVoice("af_bella", "en", "female"),
      KokoroVoice("af_nicole", "en", "female"),
      KokoroVoice("af_sarah", "en", "female"),
      KokoroVoice("af_sky", "en", "female"),
      KokoroVoice("am_adam", "en", "male"),
      KokoroVoice("am_michael", "en", "male"),
      KokoroVoice("bf_emma", "en-gb", "female"),
      KokoroVoice("bf_isabella", "en-gb", "female"),
      KokoroVoice("bm_george", "en-gb", "male"),
      KokoroVoice("bm_lewis", "en-gb", "male"),
      KokoroVoice("ef_dora", "es", "female"),
      KokoroVoice("em_alex", "es", "male"),
      KokoroVoice("ff_siwis", "fr", "female"),
      KokoroVoice("hf_alpha", "hi", "female"),
      KokoroVoice("hm_omega", "hi", "male"),
      KokoroVoice("if_sara", "it", "female"),
      KokoroVoice("im_nicola", "it", "male"),
      KokoroVoice("jf_alpha", "ja", "female"),
      KokoroVoice("jm_gongitsune", "ja", "male"),
      KokoroVoice("pf_dora", "pt", "female"),
      KokoroVoice("pm_alex", "pt", "male"),
      KokoroVoice("zf_xiaobei", "zh", "female"),
      KokoroVoice("zm_yunjian", "zh", "male"),
    )

    fun getVoiceForLanguage(language: String, gender: String = "female"): String {
      val normalizedLang = when (language.lowercase()) {
        "en", "english" -> "en"
        "es", "spanish" -> "es"
        "fr", "french" -> "fr"
        "hi", "hindi" -> "hi"
        "it", "italian" -> "it"
        "ja", "japanese" -> "ja"
        "pt", "portuguese" -> "pt"
        "zh", "chinese" -> "zh"
        "de", "german", "ko", "korean", "ar", "arabic", "ru", "russian" -> {
          BaoLog.w(TAG, "Language '$language' not natively supported, falling back to English")
          "en"
        }
        else -> {
          BaoLog.w(TAG, "Unknown language '$language', falling back to English")
          "en"
        }
      }

      return AVAILABLE_VOICES
        .firstOrNull { it.language == normalizedLang && it.gender == gender }
        ?.id ?: DEFAULT_VOICE
    }
  }
}
