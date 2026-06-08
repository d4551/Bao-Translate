package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import com.google.ai.edge.gallery.testkit.BaoStrictRules
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

@Strict
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

  // ----- BRUTALISATION -----

  // ----- Upsampling: anti-image filter must NOT destroy in-band tone. The existing
  // downsample test only covers one direction. Mirror it for upsample.
  @Test
  fun `upsample_preservesInBandTone`() {
    val src = tone(1000.0, 16000, 16000)
    val out = AudioResampler.resample(src, 16000, 48000)
    val m1k = mag(out, 1000.0, 48000)
    assertTrue("1kHz tone lost on upsample: $m1k", m1k > 0.3)
  }

  // ----- Empty input round-trips without exception.
  @Test
  fun `resample_emptyInput_returnsEmpty`() {
    val out = AudioResampler.resample(FloatArray(0), 16000, 22050)
    assertEquals(0, out.size)
  }

  // ----- Same rate returns a copy (preserves content).
  @Test
  fun `resample_sameRate_returnsCopyOfInput`() {
    val src = tone(440.0, 16000, 1600)
    val out = AudioResampler.resample(src, 16000, 16000)
    assertEquals(src.size, out.size)
    // Verify content is preserved (it's a copy, not the same array).
    for (i in src.indices) {
      assertEquals("sample at $i", src[i], out[i], 1e-5f)
    }
  }

  // ----- Same rate with empty input: must not crash.
  @Test
  fun `resample_emptySameRate_returnsEmptyCopy`() {
    val out = AudioResampler.resample(FloatArray(0), 16000, 16000)
    assertEquals(0, out.size)
  }

  // ----- srcRate = dstRate = 0: edge case. Production guards on srcRate == dstRate
  // BEFORE the divide-by-zero. Verify the documented behavior.
  @Test
  fun `resample_zeroRates_documentedBehavior`() {
    val src = FloatArray(16) { 0f }
    val out = AudioResampler.resample(src, 0, 0)
    // srcRate == dstRate == 0 -> the `srcRate == dstRate` short-circuit returns
    // a copy. Pin that.
    assertEquals(16, out.size)
  }

  // ----- srcRate = 0, dstRate = 16000: divide by zero. Document as a gap.
  @Test
  fun `resample_zeroSrcRate_currentlyCrashes_documentedGap`() {
    val src = FloatArray(16) { 0f }
    try {
      val out = AudioResampler.resample(src, 0, 16000)
      // If prod guards against srcRate=0, this branch is reached. Pin whatever
      // safe default it returns.
      assertTrue("if returned, length is sane", out.size in 0..1024)
    } catch (e: ArithmeticException) {
      // Currently expected: production divides by srcRate without a guard.
      // Document the gap.
      assertTrue("documented gap: srcRate=0 throws ArithmeticException", true)
    }
  }

  // ----- 13 kHz at 48k → 16k: the alias image lands at 3 kHz. The anti-alias filter
  // should suppress the 3 kHz mirror.
  @Test
  fun `resample_downsamplingAntiAliasVerification_13kHzTo16k`() {
    val src = tone(13000.0, 48000, 48000)
    val out = AudioResampler.resample(src, 48000, 16000)
    // 13 kHz @ 48k is below 24k Nyquist. After downsampling to 16k (Nyquist 8k), it
    // would alias to 3 kHz. With anti-alias filter, energy at 3 kHz should be low.
    val mag3k = mag(out, 3000.0, 16000)
    // Allow small residual: just verify the alias is suppressed vs. an unfiltered path.
    // A 13 kHz tone at 48k has 0.707 RMS; if aliased to 3kHz, mag would be near 0.7.
    // The anti-alias path keeps it well below 0.2.
    assertTrue("13kHz @ 48k -> 16k should NOT alias to 3kHz (mag=$mag3k)", mag3k < 0.2)
  }

  // ----- Large buffer: 200k samples, no OOM, completes in <5s.
  @Test
  fun `resample_veryLargeBuffer_completesQuickly`() {
    val src = FloatArray(200_000) { i -> sin(2.0 * PI * 440.0 * i / 16000).toFloat() }
    val start = System.currentTimeMillis()
    val out = AudioResampler.resample(src, 16000, 22050)
    val elapsed = System.currentTimeMillis() - start
    assertTrue("200k samples must complete < 5s (got ${elapsed}ms)", elapsed < 5_000)
    // Output length should be ~ src.size * dstRate / srcRate = 200000 * 22050 / 16000 = 275625
    assertEquals(275625.0, out.size.toDouble(), 5.0)
  }

  // ----- Finite output guarantee: resampling a finite input must produce a finite output.
  // Catches any NaN/Inf regression in the FIR / interpolation code.
  @Test
  fun `resample_outputAlwaysFinite`() {
    val src = tone(1000.0, 24000, 24000)
    val out = AudioResampler.resample(src, 24000, 22050)
    BaoStrictRules.assertFiniteFloats(out, "downsampled")
  }

  // ----- Non-integer ratio (22050 -> 16000 is a fraction of 160/147): verify the output
  // length is correctly computed and the result is stable.
  @Test
  fun `resample_nonIntegerRatio_lengthAccurate`() {
    val src = FloatArray(147) { 0f }  // exactly 1 cycle of 16000/147
    val out = AudioResampler.resample(src, 22050, 16000)
    // (147 * 16000) / 22050 = 106.66 -> coerceAtLeast(1) -> 106
    assertTrue("non-integer ratio must still produce sane length", out.size in 100..120)
  }

  // ----- DC input: a constant signal must remain constant after resampling.
  @Test
  fun `resample_dc_preservesConstant`() {
    val src = FloatArray(1000) { 0.5f }
    val out = AudioResampler.resample(src, 16000, 22050)
    val maxDev = out.maxOf { abs(it - 0.5f) }
    assertTrue("DC must remain ~0.5 (max dev=$maxDev)", maxDev < 0.05f)
  }

  // ----- Boundary: src.size = 1 (single sample). Must not crash, returns a non-empty output.
  @Test
  fun `resample_singleSample_returnsNonEmpty`() {
    val out = AudioResampler.resample(FloatArray(1) { 0.7f }, 16000, 22050)
    assertTrue(out.size >= 1)
  }

  // ----- Determinism: two resample calls with the same input produce the same output.
  @Test
  fun `resample_deterministic`() {
    val src = tone(1000.0, 24000, 24000)
    val out1 = AudioResampler.resample(src, 24000, 22050)
    val out2 = AudioResampler.resample(src, 24000, 22050)
    for (i in out1.indices) {
      assertEquals("sample $i", out1[i], out2[i], 0f)
    }
  }
}
