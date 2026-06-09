package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class TtsRouterTest {

  @Test
  fun `kokoro routing matrix unchanged`() {
    listOf("en", "es", "fr", "hi", "it", "pt", "zh").forEach {
      assertTrue("$it must be Kokoro-native", KokoroTtsPipeline.supportsLanguage(it))
    }
    listOf("de", "ja", "ko", "ru", "ar").forEach {
      assertFalse("$it must NOT be Kokoro-native", KokoroTtsPipeline.supportsLanguage(it))
    }
  }

  @Test
  fun `supertonic supports supplemental languages only`() {
    listOf("de", "ja", "ko", "ru", "ar").forEach {
      assertTrue(SupertonicTtsPipeline.supportsLanguage(it))
    }
    assertFalse(SupertonicTtsPipeline.supportsLanguage("en"))
  }
}