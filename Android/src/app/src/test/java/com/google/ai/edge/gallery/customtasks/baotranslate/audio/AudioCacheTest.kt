package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import com.google.ai.edge.gallery.customtasks.baotranslate.tts.SynthesizedAudio
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class AudioCacheTest {

  @Test
  fun `put and get returns same audio`() {
    AudioCache.invalidate()
    val audio = SynthesizedAudio(samples = floatArrayOf(0.1f, 0.2f, 0.3f), sampleRate = 24000)
    AudioCache.put(text = "hello", language = "en", voiceId = "default", speed = 1.0f, audio = audio)
    val hit = AudioCache.get(text = "hello", language = "en", voiceId = "default", speed = 1.0f)
    assertNotNull(hit)
    assertEquals(3, hit!!.samples.size)
    assertEquals(24000, hit.sampleRate)
  }

  @Test
  fun `different voiceId is a cache miss`() {
    AudioCache.invalidate()
    val audio = SynthesizedAudio(samples = floatArrayOf(1f), sampleRate = 24000)
    AudioCache.put(text = "hi", language = "en", voiceId = "default", audio = audio)
    assertNull(AudioCache.get(text = "hi", language = "en", voiceId = "cloned"))
  }

  @Test
  fun `invalidate clears entries`() {
    AudioCache.invalidate()
    AudioCache.put(text = "x", language = "en", audio = SynthesizedAudio(floatArrayOf(1f), 24000))
    AudioCache.invalidate()
    assertNull(AudioCache.get(text = "x", language = "en"))
    assertEquals(0, AudioCache.stats().entryCount)
  }
}