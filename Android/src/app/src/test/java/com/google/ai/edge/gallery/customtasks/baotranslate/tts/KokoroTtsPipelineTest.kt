package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTtsPipelineTest {

  @Test
  fun `getVoiceForLanguage returns correct language voice`() {
    assertEquals("af_heart", KokoroTtsPipeline.getVoiceForLanguage("en", "female"))
    assertEquals("am_adam", KokoroTtsPipeline.getVoiceForLanguage("en", "male"))
    assertEquals("ef_dora", KokoroTtsPipeline.getVoiceForLanguage("es", "female"))
    assertEquals("em_alex", KokoroTtsPipeline.getVoiceForLanguage("es", "male"))
    assertEquals("ff_siwis", KokoroTtsPipeline.getVoiceForLanguage("fr", "female"))
    assertEquals("jf_alpha", KokoroTtsPipeline.getVoiceForLanguage("ja", "female"))
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
  fun `available voices list is not empty`() {
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

  @Test
  fun `no nonexistent jm_gongitsune voice`() {
    // jm_gongitsune does not exist in the model; the valid Japanese voices are jf_gongitsune
    // (female) and jm_kumo (male). The list must use a real Japanese male voice.
    assertTrue(KokoroTtsPipeline.AVAILABLE_VOICES.none { it.id == "jm_gongitsune" })
    assertTrue(KokoroTtsPipeline.AVAILABLE_VOICES.any { it.id == "jm_kumo" })
  }
}
