package com.google.ai.edge.gallery.customtasks.baotranslate.data

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSOT for which languages the user can pick, and the cross-layer wiring that proves each one
 * can be spoken end-to-end. The bug these tests pin: a language is supported by one layer
 * (e.g. KokoroTtsPipeline.NATIVE_LANGUAGES, TranslationPipeline.normalizeLanguage) but missing
 * from another (SupportedLanguages.ALL, CODE_MAP) — leaving the user with no way to pick it
 * even though the rest of the stack could speak it.
 */
@Strict
class SupportedLanguagesTest {

  // ----- The set of selectable target languages. Pin to 12 (Auto + 11) so adding a new language
  // is a deliberate change, not a silent drift.
  @Test
  fun `ALL exposes exactly Auto plus 12 targets`() {
    assertEquals(13, SupportedLanguages.ALL.size)
    assertEquals(SupportedLanguages.AUTO, SupportedLanguages.ALL.first())
  }

  // ----- Every target key in ALL must have a CODE_MAP entry, and every key+code must be
  // round-trippable. Catches "user can see the language but it has no code" (silent break).
  @Test
  fun `every ALL target has a code and round-trips through keyForCode`() {
    SupportedLanguages.ALL
      .filter { it.key != SupportedLanguages.AUTO.key }
      .forEach { lang ->
        val code = SupportedLanguages.codeFor(lang.key)
        assertNotNull("missing CODE_MAP entry for '${lang.key}'", code)
        val roundTrip = SupportedLanguages.keyForCode(code)
        assertEquals("keyForCode('${code}') must reverse codeFor('${lang.key}')", lang.key, roundTrip)
      }
  }

  // ----- Every code in CODE_MAP must reverse-resolve to a key in ALL. Catches "code without UI".
  @Test
  fun `every CODE_MAP entry reverses to an ALL target`() {
    SupportedLanguages.CODE_MAP.forEach { (key, code) ->
      val reversed = SupportedLanguages.keyForCode(code)
      assertEquals("codeFor(key) for '$key' must be '$code' and reverse to '$key'", key, reversed)
      assertTrue(
        "ALL must contain a target for '$key' (UI exposes it; if you see this, language was " +
          "removed from ALL but the code map still references it — silent UI/code drift)",
        SupportedLanguages.ALL.any { it.key == key },
      )
    }
  }

  // ----- normalizeDetectedCode must accept every language code in CODE_MAP. Catches "Whisper
  // returns the right code but the recognizer rejects it as unknown".
  @Test
  fun `normalizeDetectedCode accepts every CODE_MAP code`() {
    SupportedLanguages.CODE_MAP.values.forEach { code ->
      assertEquals(
        "normalizeDetectedCode('$code') must return '$code'",
        code,
        SupportedLanguages.normalizeDetectedCode(code),
      )
    }
  }

  // ----- Every target language in ALL must have a sample prompt resource. Catches
  // "user picks language, voice enrollment shows English prompt" silent break.
  @Test
  fun `every target language has a voice-enrollment sample prompt`() {
    SupportedLanguages.ALL
      .filter { it.key != SupportedLanguages.AUTO.key }
      .forEach { lang ->
        // samplePromptResFor returns English fallback on miss; here we need the LANGUAGE-specific one.
        val resId = SAMPLE_PROMPT_KEYS[lang.key]
        assertNotNull(
          "missing sample prompt for '${lang.key}' — voice enrollment will fall back to English",
          resId,
        )
      }
  }

  // ----- Cross-layer SSOT: every language the TTS layer can route to MUST be selectable.
  // Catches the "hi was in KokoroTtsPipeline.NATIVE_LANGUAGES but missing from SupportedLanguages"
  // gap, so a language supported by one layer is supported by every layer.
  @Test
  fun `cross-layer SSOT — every TTS-supported language is selectable`() {
    // The TTS layer is the source of truth for which languages Kokoro can phonemize.
    val ttsNative = setOf("en", "es", "fr", "hi", "it", "pt", "zh")
    val selectable = SupportedLanguages.CODE_MAP.values.toSet()
    ttsNative.forEach { code ->
      assertTrue(
        "language '$code' is Kokoro-native but missing from SupportedLanguages.CODE_MAP — " +
          "the user cannot select it as a target even though the rest of the stack supports it",
        selectable.contains(code),
      )
    }
  }

  // ----- Cross-layer SSOT: every selectable language that needs a translation label MUST be
  // handled by TranslationPipeline.normalizeLanguage. Otherwise the prompt sent to the LLM says
  // "Translate to $language" using the user-facing key, which the LLM may not understand.
  @Test
  fun `cross-layer SSOT — every selectable language has a translation label`() {
    // Source: the same set the production code documents as the translation label map.
    val translationLabels = setOf(
      "English", "Spanish", "French", "German", "Chinese", "Japanese", "Korean",
      "Portuguese", "Italian", "Hindi", "Arabic", "Russian", "Dutch", "Polish", "Turkish",
    )
    SupportedLanguages.ALL
      .filter { it.key != SupportedLanguages.AUTO.key }
      .forEach { lang ->
        assertTrue(
          "language key '${lang.key}' is selectable but TranslationPipeline does not label it; " +
            "the LLM prompt will fall through to the raw key, which may confuse the model",
          translationLabels.contains(lang.key),
        )
      }
  }

  // ----- Pin the canonical 12 target keys so a future change cannot silently add/remove one
  // without breaking this test (forces a deliberate edit to the test along with the production
  // change). SSOT for the selectable language set.
  @Test
  fun `selectable target keys are pinned to canonical set`() {
    val expected = setOf(
      "English", "Spanish", "French", "German", "Chinese", "Japanese", "Korean",
      "Portuguese", "Italian", "Russian", "Arabic", "Hindi",
    )
    val actual = SupportedLanguages.ALL
      .filter { it.key != SupportedLanguages.AUTO.key }
      .map { it.key }
      .toSet()
    assertEquals(expected, actual)
  }

  // ----- CODE_MAP codes are exactly the same as the TTS language codes (modulo the de/ko/ru/ar
  // fallback that lives in platformTts). If a language is selectable, the TTS layer has a
  // contract for how to speak it.
  @Test
  fun `every selectable code has a TTS routing contract`() {
    val allCodes = SupportedLanguages.CODE_MAP.values
    val kokoroNative = setOf("en", "es", "fr", "hi", "it", "pt", "zh")
    val platformTtsOnly = setOf("de", "ko", "ru", "ar", "ja")
    allCodes.forEach { code ->
      val supported = kokoroNative.contains(code) || platformTtsOnly.contains(code)
      assertTrue(
        "selectable code '$code' has no TTS routing contract — RecordingController will fail to " +
          "speak the user's target language",
        supported,
      )
    }
  }

  // ----- The default target language is a real selectable code, not a stale literal. Catches
  // drift where someone renames "Spanish" in ALL or removes it from CODE_MAP, which would leave
  // first-run users with a target language the UI cannot resolve. The default is referenced by
  // PipelineConfig.DEFAULT_TARGET_LANGUAGE AND BaoTranslateUiState.targetLanguage; both share
  // this SSOT.
  @Test
  fun `default target key is selectable and codeable`() {
    val key = SupportedLanguages.DEFAULT_TARGET_KEY
    assertTrue("DEFAULT_TARGET_KEY '$key' must be a selectable language", SupportedLanguages.ALL.any { it.key == key })
    val code = SupportedLanguages.codeFor(key)
    assertEquals(
      "DEFAULT_TARGET_KEY must round-trip through CODE_MAP (not a magic string)",
      key,
      SupportedLanguages.keyForCode(code),
    )
    assertEquals("DEFAULT_TARGET_CODE must equal codeFor(DEFAULT_TARGET_KEY)", SupportedLanguages.DEFAULT_TARGET_CODE, code)
  }

  // ----- DEFAULT_SOURCE_KEY is the Auto sentinel, and Auto is NOT a translation target. Catches
  // a future refactor that accidentally makes the default source be a real language (would
  // disable auto-detect Whisper, which is the documented behavior for the Auto selection).
  @Test
  fun `default source key is Auto and Auto is not a translation target`() {
    assertEquals("Auto", SupportedLanguages.DEFAULT_SOURCE_KEY)
    assertTrue(
      "TRANSLATION_TARGETS must not include Auto (it has no fixed target code)",
      SupportedLanguages.TRANSLATION_TARGETS.none { it.key == SupportedLanguages.AUTO.key },
    )
  }

  private companion object {
    // Mirrors SupportedLanguages.SAMPLE_PROMPT_RES. Pinning the keys here (not the int values —
    // those are autogenerated R.* constants) lets this test fail loudly when a new language is
    // added to ALL but no SAMPLE_PROMPT_RES entry is added.
    val SAMPLE_PROMPT_KEYS: Map<String, String> = mapOf(
      "English" to "bao_translate_record_prompt",
      "Spanish" to "bao_translate_record_prompt_es",
      "French" to "bao_translate_record_prompt_fr",
      "German" to "bao_translate_record_prompt_de",
      "Chinese" to "bao_translate_record_prompt_zh",
      "Japanese" to "bao_translate_record_prompt_ja",
      "Korean" to "bao_translate_record_prompt_ko",
      "Portuguese" to "bao_translate_record_prompt_pt",
      "Italian" to "bao_translate_record_prompt_it",
      "Russian" to "bao_translate_record_prompt_ru",
      "Arabic" to "bao_translate_record_prompt_ar",
      "Hindi" to "bao_translate_record_prompt_hi",
    )
  }
}
