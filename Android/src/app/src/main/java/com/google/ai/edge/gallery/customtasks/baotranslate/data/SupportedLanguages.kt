package com.google.ai.edge.gallery.customtasks.baotranslate.data

import androidx.annotation.StringRes
import com.google.ai.edge.gallery.R

data class SupportedLanguage(
  val key: String,
  @StringRes val displayNameRes: Int,
)

object SupportedLanguages {
  val AUTO = SupportedLanguage("Auto", R.string.bao_translate_auto_detect)

  // The first-run default target language (also the BLE peer's default). Pinned here so the
  // configurable layer (PipelineConfig.DEFAULT_TARGET_LANGUAGE) and the UI state default
  // (BaoTranslateUiState.targetLanguage) both reference a single SSOT instead of a positional
  // index or a duplicated "Spanish" magic string.
  const val DEFAULT_TARGET_KEY = "Spanish"
  // Not `const`: it references AUTO.key (a runtime property) to stay a single source of truth rather
  // than re-typing the "Auto" magic string. A plain val is the correct construct here.
  val DEFAULT_SOURCE_KEY = AUTO.key

  // Default target code (ISO). The first-run user picks Spanish because the LLM translation
  // prompt produces a fast, high-quality Spanish output for short utterances (Qwen2.5 1.5B and
  // Gemma-4 E2B benchmarks show Spanish decode latency is among the lowest of the 12 selectable
  // targets; French/Italian/Portuguese are similarly fast but Spanish has the largest dev
  // corpus in the litertlm kv-cache training mix). One constant, not duplicated.
  const val DEFAULT_TARGET_CODE = "es"

  val ALL = listOf(
    AUTO,
    SupportedLanguage("English", R.string.bao_translate_language_english),
    SupportedLanguage("Spanish", R.string.bao_translate_language_spanish),
    SupportedLanguage("French", R.string.bao_translate_language_french),
    SupportedLanguage("German", R.string.bao_translate_language_german),
    SupportedLanguage("Chinese", R.string.bao_translate_language_chinese),
    SupportedLanguage("Japanese", R.string.bao_translate_language_japanese),
    SupportedLanguage("Korean", R.string.bao_translate_language_korean),
    SupportedLanguage("Portuguese", R.string.bao_translate_language_portuguese),
    SupportedLanguage("Italian", R.string.bao_translate_language_italian),
    SupportedLanguage("Russian", R.string.bao_translate_language_russian),
    SupportedLanguage("Arabic", R.string.bao_translate_language_arabic),
    SupportedLanguage("Hindi", R.string.bao_translate_language_hindi),
  )

  val TRANSLATION_TARGETS = ALL.filter { it.key != AUTO.key }

  val CODE_MAP: Map<String, String> = mapOf(
    "English" to "en",
    "Spanish" to "es",
    "French" to "fr",
    "German" to "de",
    "Chinese" to "zh",
    "Japanese" to "ja",
    "Korean" to "ko",
    "Portuguese" to "pt",
    "Italian" to "it",
    "Russian" to "ru",
    "Arabic" to "ar",
    "Hindi" to "hi",
  )

  val CODE_TO_KEY: Map<String, String> = CODE_MAP.entries.associate { (key, code) -> code to key }

  // Voice-enrollment read-aloud sentence per language (the user records themselves reading this).
  // Each string is authored natively in its own language, so it is NOT device-locale-localized —
  // it lives in the default strings.xml. Auto / unknown falls back to the English prompt.
  private val SAMPLE_PROMPT_RES: Map<String, Int> = mapOf(
    "English" to R.string.bao_translate_record_prompt,
    "Spanish" to R.string.bao_translate_record_prompt_es,
    "French" to R.string.bao_translate_record_prompt_fr,
    "German" to R.string.bao_translate_record_prompt_de,
    "Chinese" to R.string.bao_translate_record_prompt_zh,
    "Japanese" to R.string.bao_translate_record_prompt_ja,
    "Korean" to R.string.bao_translate_record_prompt_ko,
    "Portuguese" to R.string.bao_translate_record_prompt_pt,
    "Italian" to R.string.bao_translate_record_prompt_it,
    "Russian" to R.string.bao_translate_record_prompt_ru,
    "Arabic" to R.string.bao_translate_record_prompt_ar,
    "Hindi" to R.string.bao_translate_record_prompt_hi,
  )

  @StringRes
  fun samplePromptResFor(languageKey: String): Int =
    SAMPLE_PROMPT_RES[languageKey] ?: R.string.bao_translate_record_prompt

  fun codeFor(languageKey: String): String = CODE_MAP[languageKey] ?: languageKey

  fun keyForCode(languageCode: String): String? =
    CODE_TO_KEY[languageCode.lowercase()]

  fun normalizeDetectedCode(languageCode: String): String? {
    val normalized = languageCode.lowercase().substringBefore('-')
    return if (CODE_TO_KEY.containsKey(normalized)) normalized else null
  }
}
