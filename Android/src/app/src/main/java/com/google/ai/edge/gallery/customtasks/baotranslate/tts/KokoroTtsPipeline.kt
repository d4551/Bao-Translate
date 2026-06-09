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

data class KokoroVoice(val id: String, val language: String, val gender: String, val sid: Int)

class KokoroTtsPipeline(private val context: Context) : TtsEngine {
  private var tts: OfflineTts? = null
  private var isReady = false
  private var currentVoiceId: String = DEFAULT_VOICE

  // Serializes native generate() against release() so the OfflineTts handle is never freed
  // mid-synthesis (model delete / switch / onCleared), and serializes concurrent synth calls —
  // the recording path and the BLE peer path both synthesize on this single shared engine.
  private val inferenceLock = Any()

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
    return synthesizeAudio(text, voiceId, speed)?.samples
  }

  override fun synthesizeAudio(text: String, voiceId: String?, speed: Float): SynthesizedAudio? = synchronized(inferenceLock) {
    val engine = tts ?: return null
    if (!isReady) return null
    val voice = voiceId ?: currentVoiceId

    // The native sherpa-onnx generate() is a JNI call; a native fault would otherwise escape the
    // recording/peer coroutine (no CoroutineExceptionHandler) and crash the app. Funnel it into a
    // null result so the caller degrades to "TTS not ready" instead — mirroring the Whisper/
    // Translation/Platform-TTS pipelines.
    val audio = runCatching {
      engine.generate(text = text, sid = sidForVoice(voice), speed = speed)
    }.getOrElse { e ->
      BaoLog.w(TAG, "Kokoro native generate failed: ${e.message}")
      return null
    }

    if (audio.samples.isEmpty()) {
      return null
    }

    return SynthesizedAudio(samples = audio.samples, sampleRate = audio.sampleRate)
  }

  override fun play(samples: FloatArray, sampleRate: Int) {
    AudioPlayback.playPcmFloat(samples, sampleRate)
  }

  fun setVoice(voiceId: String) {
    currentVoiceId = voiceId
  }

  override fun cleanup() {
    synchronized(inferenceLock) {
      tts?.release()
      tts = null
      isReady = false
    }
  }

  // The native sid is the speaker index baked into the model's voices.bin, NOT the position in this
  // list. Resolve to the stored, authoritative sid; fall back to the default voice's sid (never 0,
  // which would silently substitute af_alloy) if the id is unknown.
  private fun sidForVoice(voiceId: String): Int {
    return AVAILABLE_VOICES.firstOrNull { it.id == voiceId }?.sid ?: DEFAULT_SID
  }

  companion object {
    private const val DEFAULT_VOICE = "af_heart"
    private const val DEFAULT_SID = 3 // af_heart in kokoro-multi-lang-v1_0

    // sid values are the authoritative speaker ids for sherpa-onnx kokoro-multi-lang-v1_0 (53
    // speakers, 0-52), per https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html.
    // Using list position as the sid (the previous behaviour) selected the wrong speaker for nearly
    // every voice. Pinned by KokoroTtsPipelineTest so future edits can't reintroduce the drift.
    //
    // The full 24-voice inventory (en/en-gb/es/fr/hi/it/ja/pt/zh) is exposed because EVERY speaker
    // is physically present in the bundled voices.bin. Whether a given speaker is actually USABLE
    // is decided at the routing layer by [NATIVE_LANGUAGES] / [supportsLanguage], NOT here: the
    // data model preserves every voice the model ships, so the sids and prefix coverage the test
    // suite pins remain authoritative even when a language (e.g. ja) is rerouted to platformTts
    // because the bundled espeak-ng/lexicon set cannot phonemize it. See [supportsLanguage] for the
    // phonemization contract.
    val AVAILABLE_VOICES = listOf(
      KokoroVoice("af_heart", "en", "female", 3),
      KokoroVoice("af_bella", "en", "female", 2),
      KokoroVoice("af_nicole", "en", "female", 6),
      KokoroVoice("af_sarah", "en", "female", 9),
      KokoroVoice("af_sky", "en", "female", 10),
      KokoroVoice("am_adam", "en", "male", 11),
      KokoroVoice("am_michael", "en", "male", 16),
      KokoroVoice("bf_emma", "en-gb", "female", 21),
      KokoroVoice("bf_isabella", "en-gb", "female", 22),
      KokoroVoice("bm_george", "en-gb", "male", 26),
      KokoroVoice("bm_lewis", "en-gb", "male", 27),
      KokoroVoice("ef_dora", "es", "female", 28),
      KokoroVoice("em_alex", "es", "male", 29),
      KokoroVoice("ff_siwis", "fr", "female", 30),
      KokoroVoice("hf_alpha", "hi", "female", 31),
      KokoroVoice("hm_omega", "hi", "male", 33),
      KokoroVoice("if_sara", "it", "female", 35),
      KokoroVoice("im_nicola", "it", "male", 36),
      KokoroVoice("jf_alpha", "ja", "female", 37),
      KokoroVoice("jm_kumo", "ja", "male", 41),
      KokoroVoice("pf_dora", "pt", "female", 42),
      KokoroVoice("pm_alex", "pt", "male", 43),
      KokoroVoice("zf_xiaobei", "zh", "female", 45),
      KokoroVoice("zm_yunjian", "zh", "male", 49),
    )

    // Languages Kokoro can phonemize + speak on the bundled multi-lang-v1_0 model. The model is
    // multi-lingual in name only: sherpa-onnx ships English + Chinese espeak-ng-data + lexicons
    // for v1_0 (https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html — "we only
    // add English and Chinese support for it"). The Japanese speakers (jf_alpha, jm_kumo) live in
    // voices.bin but the model has no Japanese lexicon or ja espeak-ng-data; feeding ja text into
    // the configured English espeak-ng falls into the character-name fallback ("Japanese Letter").
    // ja is therefore NOT in this set, and the caller (RecordingController.synthesizeSpeech) must
    // route ja to platformTts — the same shape as de/ko/ru/ar. [AVAILABLE_VOICES] still lists the
    // ja speakers (they exist in voices.bin) so the data model stays complete; the constraint is
    // on which languages are *natively speakable* by this model, enforced here.
    private val NATIVE_LANGUAGES = setOf("en", "es", "fr", "hi", "it", "pt", "zh")

    fun supportsLanguage(language: String): Boolean {
      val code = when (language.lowercase()) {
        "english" -> "en"; "spanish" -> "es"; "french" -> "fr"; "hindi" -> "hi"; "italian" -> "it"
        "portuguese" -> "pt"; "chinese" -> "zh"
        else -> language.lowercase().substringBefore('-')
      }
      return code in NATIVE_LANGUAGES
    }

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
          // Phonemization is not supported on the bundled espeak-ng/lexicon set, so the caller
          // (RecordingController.synthesizeSpeech) already routes these to platformTts. This
          // branch keeps getVoiceForLanguage total in case a future caller bypasses that gate.
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
