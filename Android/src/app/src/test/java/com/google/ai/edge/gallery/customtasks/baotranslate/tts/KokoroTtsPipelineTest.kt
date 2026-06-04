package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import org.junit.Assert.assertEquals
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
}
