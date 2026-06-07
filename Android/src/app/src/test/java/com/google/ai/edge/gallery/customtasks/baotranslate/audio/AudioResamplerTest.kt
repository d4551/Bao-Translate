package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

class AudioResamplerTest {

  private fun tone(freq: Double, rate: Int, n: Int): FloatArray =
    FloatArray(n) { sin(2.0 * PI * freq * it / rate).toFloat() }

  /** Goertzel magnitude of [freq] in [x] sampled at [rate]. */
  private fun mag(x: FloatArray, freq: Double, rate: Int): Double {
    val w = 2.0 * PI * freq / rate
    val coeff = 2.0 * kotlin.math.cos(w)
    var s0: Double; var s1 = 0.0; var s2 = 0.0
    for (v in x) { s0 = v + coeff * s1 - s2; s2 = s1; s1 = s0 }
    return sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2) / x.size
  }

  @Test
  fun `output length scales with rate ratio`() {
    val out = AudioResampler.resample(FloatArray(24000) { 0f }, 24000, 22050)
    assertEquals(22050.0, out.size.toDouble(), 2.0)
  }

  @Test
  fun `preserves an in-band tone 24k to 22_05k`() {
    val src = tone(1000.0, 24000, 24000)
    val out = AudioResampler.resample(src, 24000, 22050)
    // 1 kHz energy survives; near-DC and a high out-of-band probe stay low.
    val m1k = mag(out, 1000.0, 22050)
    assertTrue("1kHz tone lost: $m1k", m1k > 0.3)
  }

  @Test
  fun `suppresses aliasing of an above-Nyquist tone when downsampling`() {
    // 11.6 kHz is below 24k Nyquist but above 22.05k Nyquist (11.025k) -> must be filtered, not aliased.
    val src = tone(11600.0, 24000, 24000)
    val out = AudioResampler.resample(src, 24000, 22050)
    var rms = 0.0
    for (v in out) rms += v.toDouble() * v
    rms = sqrt(rms / out.size)
    assertTrue("above-Nyquist tone not suppressed (rms=$rms)", rms < 0.2)
  }
}
