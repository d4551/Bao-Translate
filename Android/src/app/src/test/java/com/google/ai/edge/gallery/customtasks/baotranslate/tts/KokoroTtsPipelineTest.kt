package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Strict
class KokoroTtsPipelineTest {

  @Test
  fun `getVoiceForLanguage returns correct language voice`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("en", "female"))
    assertEquals("am_adam", KokoroTtsPipeline.getVoiceForLanguage("en", "male"))
    assertEquals("ef_dora", KokoroTtsPipeline.getVoiceForLanguage("es", "female"))
    assertEquals("em_alex", KokoroTtsPipeline.getVoiceForLanguage("es", "male"))
    assertEquals("ff_siwis", KokoroTtsPipeline.getVoiceForLanguage("fr", "female"))
    // Japanese voice IS exposed (the speakers live in voices.bin), but the caller MUST check
    // supportsLanguage("ja") first and route to platformTts — getVoiceForLanguage is a pure data
    // lookup, it does not gate on phonemization. This test pins the data, not the routing.
    assertEquals("jf_alpha", KokoroTtsPipeline.getVoiceForLanguage("ja", "female"))
    assertEquals("jm_kumo", KokoroTtsPipeline.getVoiceForLanguage("ja", "male"))
    assertEquals("zf_xiaobei", KokoroTtsPipeline.getVoiceForLanguage("zh", "female"))
  }

  @Test
  fun `getVoiceForLanguage falls back to English for unknown language`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("xx", "female"))
    assertEquals("am_adam", KokoroTtsPipeline.getVoiceForLanguage("unknown", "male"))
  }

  @Test
  fun `getVoiceForLanguage handles full language names`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("English", "female"))
    assertEquals("ef_dora", KokoroTtsPipeline.getVoiceForLanguage("Spanish", "female"))
    assertEquals("ff_siwis", KokoroTtsPipeline.getVoiceForLanguage("French", "female"))
  }

  @Test
  fun `available voices list is complete and authoritative`() {
    // Pinned to 24: every speaker physically present in voices.bin MUST appear here. The ja
    // speakers (jf_alpha, jm_kumo) are part of the model — they are merely not *phonemizable* on
    // the v1_0 espeak-ng/lexicon set, so the routing layer (supportsLanguage) routes ja text to
    // platformTts. Removing ja voices from the data list would mask that distinction.
    assertEquals(24, KokoroTtsPipeline.AVAILABLE_VOICES.size)
  }

  @Test
  fun `voice sids match kokoro-multi-lang-v1_0 authoritative speaker ids`() {
    // Pins the name->sid mapping to the model's real speaker order so a future list edit cannot
    // silently reintroduce the position-as-sid bug (which spoke the wrong gender/language voice).
    val sid = KokoroTtsPipeline.AVAILABLE_VOICES.associate { it.id to it.sid }
    assertEquals(3, sid["af_heart"])
    assertEquals(2, sid["af_bella"])
    assertEquals(11, sid["am_adam"])
    assertEquals(28, sid["ef_dora"])
    assertEquals(29, sid["em_alex"])
    assertEquals(30, sid["ff_siwis"])
    assertEquals(37, sid["jf_alpha"])
    assertEquals(41, sid["jm_kumo"])
    assertEquals(49, sid["zm_yunjian"])
  }

  @Test
  fun `all voice sids are within the model speaker range`() {
    // kokoro-multi-lang-v1_0 has 53 speakers (ids 0-52).
    KokoroTtsPipeline.AVAILABLE_VOICES.forEach {
      assertTrue("${it.id} sid ${it.sid} out of range", it.sid in 0..52)
    }
  }

  // ----- BRUTALISATION -----

  // ----- Available voices: no two voices share an `id`. Catches a copy-paste
  // regression where someone duplicated a voice entry.
  @Test
  fun `availableVoices_noDuplicateIds`() {
    val ids = KokoroTtsPipeline.AVAILABLE_VOICES.map { it.id }
    assertEquals("voice ids must be unique", ids.size, ids.toSet().size)
  }

  // ----- Available voices: no two voices share a `sid`. Catches the position-as-sid
  // regression in a different way (the previous test catches the values; this catches
  // the uniqueness of the underlying integers).
  @Test
  fun `availableVoices_noDuplicateSids`() {
    val sids = KokoroTtsPipeline.AVAILABLE_VOICES.map { it.sid }
    assertEquals("voice sids must be unique", sids.size, sids.toSet().size)
  }

  // ----- Language prefix coverage: at least one voice per language prefix
  // (a=American English, b=British English, e=European Spanish, f=French, j=Japanese, etc).
  @Test
  fun `availableVoices_languagePrefixMapping_unique`() {
    val prefixes = listOf("a", "b", "e", "f", "i", "j", "p", "z", "h")
    for (prefix in prefixes) {
      val voices = KokoroTtsPipeline.AVAILABLE_VOICES.filter { it.id.startsWith(prefix) }
      assertTrue("prefix '$prefix' has at least one voice", voices.isNotEmpty())
    }
  }

  // ----- Empty language: must fall back to a known default. Document.
  @Test
  fun `getVoiceForLanguage_emptyLanguage_fallsBack`() {
    val emptyResult = KokoroTtsPipeline.getVoiceForLanguage("", "female")
    // Production guards `language.isBlank()` — the result is the English female voice.
    assertEquals("af_heart", emptyResult)
  }

  // ----- Whitespace-only language: same fallback path.
  @Test
  fun `getVoiceForLanguage_whitespaceLanguage_fallsBack`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("   ", "female"))
    assertEquals("am_adam", KokoroTtsPipeline.getVoiceForLanguage("\t", "male"))
  }

  // ----- Case insensitivity: documented behavior. The existing test asserts full
  // names like "English" resolve; the production code may or may not handle
  // uppercase. Document the contract.
  @Test
  fun `getVoiceForLanguage_caseInsensitive_forLanguageCodes`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("EN", "female"))
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("en", "female"))
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("English", "female"))
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("ENGLISH", "female"))
  }

  // ----- Whitespace-trimming: " en " should still resolve to English. Production
  // typically does `.trim()` on the input. Document the contract.
  @Test
  fun `getVoiceForLanguage_whitespaceTrimming`() {
    val result = KokoroTtsPipeline.getVoiceForLanguage(" en ", "female")
    // Pin the contract: trimming yields the expected voice.
    assertEquals("af_heart", result)
  }

  // ----- Unknown gender falls back to DEFAULT_VOICE ("af_heart"). The implementation
  // filters by (language, gender) and falls through to DEFAULT_VOICE on no match — it does NOT
  // normalize the gender to "female" first. This test pins that contract exactly so a future
  // "smart" fallback (e.g. "if gender unknown, retry as female") cannot silently change it.
  @Test
  fun `getVoiceForLanguage_unknownGender_fallsBackToDefaultVoice`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("es", "alien"))
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("en", "u"))
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("ja", ""))
  }

  // ----- Two different languages must resolve to different female voices (sanity).
  @Test
  fun `getVoiceForLanguage_distinctLanguages_differentVoices`() {
    val en = KokoroTtsPipeline.getVoiceForLanguage("en", "female")
    val es = KokoroTtsPipeline.getVoiceForLanguage("es", "female")
    val fr = KokoroTtsPipeline.getVoiceForLanguage("fr", "female")
    val ja = KokoroTtsPipeline.getVoiceForLanguage("ja", "female")
    val voices = setOf(en, es, fr, ja)
    assertEquals("4 distinct languages must yield 4 distinct voices", 4, voices.size)
  }

  // ----- Male and female voices for the same language differ.
  @Test
  fun `getVoiceForLanguage_maleAndFemale_differ()`() {
    val en_f = KokoroTtsPipeline.getVoiceForLanguage("en", "female")
    val en_m = KokoroTtsPipeline.getVoiceForLanguage("en", "male")
    assertNotEquals("English male != female", en_f, en_m)
  }

  // ============================================================================
  // ROUTING CONTRACT — THIS IS THE BUG-REGRESSION SUITE.
  //
  // The original "Japanese Letter" bug existed because [supportsLanguage] returned
  // true for "ja" even though the bundled sherpa-onnx kokoro-multi-lang-v1_0 has
  // no Japanese lexicon or ja espeak-ng-data. The recording pipeline therefore
  // routed Japanese text into Kokoro.synthesize(), which fell into espeak-ng's
  // English character-name fallback ("Japanese Letter"). These tests pin the
  // routing predicate so a future edit to [NATIVE_LANGUAGES] / [supportsLanguage]
  // that re-admits ja will fail the build, not the user.
  // ============================================================================

  // ----- supportsLanguage: full 11-language matrix pinned to current behavior.
  // Phonemizable on the bundled model: en / es / fr / hi / it / pt / zh.
  // NOT phonemizable (must route to platformTts): de / ko / ru / ar / ja.
  @Test
  fun `supportsLanguage full matrix pins phonemization contract`() {
    val phonemizable = listOf("en", "es", "fr", "hi", "it", "pt", "zh")
    val notPhonemizable = listOf("de", "ko", "ru", "ar", "ja", "japanese", "Japanese", "JA")
    phonemizable.forEach { code ->
      assertTrue("supportsLanguage('$code') must be true (Kokoro phonemizes + voices it)", KokoroTtsPipeline.supportsLanguage(code))
    }
    notPhonemizable.forEach { code ->
      assertFalse(
        "supportsLanguage('$code') must be false (no ${code.lowercase().take(2)} phonemization on the bundled model; " +
          "returning true would route to Kokoro and produce espeak-ng's character-name fallback)",
        KokoroTtsPipeline.supportsLanguage(code),
      )
    }
  }

  // ----- Anti-regression for the original "Japanese Letter" bug: supportsLanguage
  // must return false for ja and japanese (the exact inputs the bug used). If a
  // future change re-adds "ja" to NATIVE_LANGUAGES, this test fails immediately.
  @Test
  fun `japanese does not regress to native — original Japanese Letter bug`() {
    assertFalse(
      "supportsLanguage('ja') MUST be false. Re-enabling it would route Japanese text into " +
        "Kokoro with English espeak-ng and reproduce the 'Japanese Letter' character-name " +
        "fallback. See https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html " +
        "for why the bundled multi-lang-v1_0 model ships no Japanese phonemization.",
      KokoroTtsPipeline.supportsLanguage("ja"),
    )
    assertFalse("supportsLanguage('japanese') MUST be false", KokoroTtsPipeline.supportsLanguage("japanese"))
    assertFalse("supportsLanguage('Japanese') MUST be false (case-insensitive)", KokoroTtsPipeline.supportsLanguage("Japanese"))
    assertFalse("supportsLanguage('JA') MUST be false (case-insensitive)", KokoroTtsPipeline.supportsLanguage("JA"))
  }

  // ----- Voice lookup must still resolve ja to a real speaker id from the model
  // (the voice lives in voices.bin and getVoiceForLanguage is a pure data lookup).
  // This proves the data model is intact even though the routing layer excludes ja.
  @Test
  fun `japanese voice lookup resolves to bundled model speaker`() {
    val jfAlpha = AVAILABLE_VOICES.first { it.id == "jf_alpha" }
    assertEquals("ja", jfAlpha.language)
    assertEquals(37, jfAlpha.sid)
    assertEquals("female", jfAlpha.gender)
    val jmKumo = AVAILABLE_VOICES.first { it.id == "jm_kumo" }
    assertEquals("ja", jmKumo.language)
    assertEquals(41, jmKumo.sid)
    assertEquals("male", jmKumo.gender)
  }

  // ----- The j-prefix coverage test (was removed when ja was dropped) is back,
  // and it now comes with an explicit assertion that the speakers exist with the
  // authoritative sids. Future edits cannot silently drop the ja speakers.
  @Test
  fun `japanese prefix has both female and male speakers with authoritative sids`() {
    val jf = AVAILABLE_VOICES.filter { it.id.startsWith("jf_") }
    val jm = AVAILABLE_VOICES.filter { it.id.startsWith("jm_") }
    assertFalse("model has at least one jf_ (Japanese female) speaker", jf.isEmpty())
    assertFalse("model has at least one jm_ (Japanese male) speaker", jm.isEmpty())
    assertEquals("jf_alpha sid 37 (authoritative per kokoro-multi-lang-v1_0)", 37, jf.first { it.id == "jf_alpha" }.sid)
    assertEquals("jm_kumo sid 41 (authoritative per kokoro-multi-lang-v1_0)", 41, jm.first { it.id == "jm_kumo" }.sid)
  }

  // ----- Combined anti-regression: the 2-axis (routing + data) invariant. The
  // data layer exposes ja speakers; the routing layer refuses to use them. A
  // future edit that breaks EITHER axis flips one of these two assertions.
  @Test
  fun `routing and data are orthogonal invariants`() {
    // Data axis: ja speakers exist.
    assertTrue(AVAILABLE_VOICES.any { it.language == "ja" && it.gender == "female" })
    assertTrue(AVAILABLE_VOICES.any { it.language == "ja" && it.gender == "male" })
    // Routing axis: ja is NOT speakable via this model.
    assertFalse(KokoroTtsPipeline.supportsLanguage("ja"))
    // The two together prove the model is wired correctly: the speakers are
    // present, but the path to them is gated by phonemization availability.
  }
}
