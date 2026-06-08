package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the enrollment-waveform fix (#1). The old enrollment formula was `(sumSquares/count)/32768²`
 * — squared, no sqrt, no gain — so normal speech mapped to ~0.01 and every bar was flat ("mic is
 * working" blob, no visible waveform). The shared [waveformAmplitude] uses RMS → sqrt perceptual
 * curve → 2.5× gain, so moderate speech becomes a clearly visible bar. These assertions pass for the
 * new formula and would FAIL for the old one.
 */
class WaveformAmplitudeTest {

  @Test
  fun emptyOrZeroCount_isZero() {
    assertEquals(0f, waveformAmplitude(ShortArray(0), 0), 0f)
    assertEquals(0f, waveformAmplitude(ShortArray(16), 0), 0f)
  }

  @Test
  fun silence_isZero() {
    val silence = ShortArray(160)
    assertEquals(0f, waveformAmplitude(silence, silence.size), 0.0001f)
  }

  @Test
  fun moderateSpeech_isVisible_whereOldFormulaWasFlat() {
    // ~0.1 of full scale (typical speech RMS). OLD: 0.1² = 0.01 (flat). NEW: sqrt(0.1)*2.5 ≈ 0.79.
    val a: Short = 3276 // 0.1 * 32768
    val sig = ShortArray(160) { if (it % 2 == 0) a else (-a).toShort() }
    val amp = waveformAmplitude(sig, sig.size)
    assertTrue("moderate speech must render a visible (non-flat) bar, got $amp", amp > 0.2f)
    assertTrue("amplitude must stay in 0..1, got $amp", amp <= 1f)
  }

  @Test
  fun loudSignal_isClampedToOne() {
    val a: Short = 16000
    val loud = ShortArray(160) { if (it % 2 == 0) a else (-a).toShort() }
    val amp = waveformAmplitude(loud, loud.size)
    assertTrue("loud signal yields a tall, clamped bar, got $amp", amp > 0.3f)
    assertEquals("must clamp at 1.0", 1f, amp, 0.0001f)
  }
}
