package com.google.ai.edge.gallery.customtasks.baotranslate.stt

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class VadProcessorTest {

  private lateinit var context: Context
  private lateinit var vad: VadProcessor

  @Before
  fun setup() {
    context = mock()
    vad = VadProcessor(context)
  }

  @Test
  fun `short audio below minimum returns single segment`() {
    val samples = ShortArray(8000) { 1000 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(1, segments.size)
    assertEquals(8000, segments[0].size)
  }

  @Test
  fun `silence splitting creates multiple segments`() {
    val samples = ShortArray(48000) { 0 }
    // First speech segment
    for (i in 0 until 20000) {
      samples[i] = 1000
    }
    // Silence gap
    for (i in 20000 until 26000) {
      samples[i] = 0
    }
    // Second speech segment
    for (i in 26000 until 48000) {
      samples[i] = 1000
    }

    val segments = vad.processAudioSegment(samples)
    assertEquals(2, segments.size)
  }

  @Test
  fun `all silence returns empty or minimal segments`() {
    val samples = ShortArray(48000) { 0 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(0, segments.size)
  }

  @Test
  fun `trailing short segment included if above half minimum`() {
    val samples = ShortArray(24000) { 1000 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(1, segments.size)
  }
}
