package com.google.ai.edge.gallery.customtasks.baotranslate.data

import androidx.annotation.StringRes
import com.google.ai.edge.gallery.R

data class SupportedLanguage(
  val key: String,
  @StringRes val displayNameRes: Int,
)

object SupportedLanguages {
  val AUTO = SupportedLanguage("Auto", R.string.bao_translate_auto_detect)

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
  )

  val CODE_TO_KEY: Map<String, String> = CODE_MAP.entries.associate { (key, code) -> code to key }

  fun codeFor(languageKey: String): String = CODE_MAP[languageKey] ?: languageKey

  fun keyForCode(languageCode: String): String? =
    CODE_TO_KEY[languageCode.lowercase()]

  fun normalizeDetectedCode(languageCode: String): String? {
    val normalized = languageCode.lowercase().substringBefore('-')
    return if (CODE_TO_KEY.containsKey(normalized)) normalized else null
  }
}
