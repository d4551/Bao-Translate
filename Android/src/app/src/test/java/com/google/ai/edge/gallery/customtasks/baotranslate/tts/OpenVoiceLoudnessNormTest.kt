package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.math.abs

/**
 * Unit coverage for the cloned-OUTPUT loudness normalization ([OpenVoiceVoiceConverter.normalizePeak]):
 * the cloned audio must land at a consistent playback peak across Kokoro/Supertonic engines and
 * languages, WITHOUT clipping. (Input-side RMS normalization was tried and reverted — it garbled cloned
 * speech content on-device — so only the content-safe output peak-norm remains.) Runs on the JVM.
 */
@Category(Strict::class)
class OpenVoiceLoudnessNormTest {

  private fun peak(a: FloatArray): Float {
    var p = 0f
    for (s in a) p = maxOf(p, abs(s))
    return p
  }

  @Test
  fun normalizePeak_scalesPeakExactlyToTarget() {
    val sig = FloatArray(1000) { if (it % 2 == 0) 0.1f else -0.1f } // peak 0.1
    val out = OpenVoiceVoiceConverter.normalizePeak(sig, targetPeak = 0.95f)
    assertEquals("peak should land on the target", 0.95f, peak(out), 1e-4f)
  }

  @Test
  fun normalizePeak_isIdempotentAtTarget() {
    val atTarget = FloatArray(100) { if (it == 0) 0.95f else 0.2f }
    // Already at the target peak → gain ≈ 1 → return the same array (no needless reallocation/scale).
    assertSame(atTarget, OpenVoiceVoiceConverter.normalizePeak(atTarget, 0.95f))
  }

  @Test
  fun normalizePeak_leavesSilenceUntouched() {
    val silence = FloatArray(256) { 0f }
    assertSame(silence, OpenVoiceVoiceConverter.normalizePeak(silence, 0.95f))
  }

  @Test
  fun normalizePeak_capsUpwardBoost_soQuietOutputNoiseFloorIsNotAmplified() {
    // A quiet conversion (peak 0.1) normalized to 0.95 would need x9.5 gain (+19.6 dB), lifting the
    // decoder's noise floor into audible buzz/hiss. The OUTPUT path caps the boost (here x4 = +12 dB).
    val quiet = FloatArray(1000) { if (it % 2 == 0) 0.1f else -0.1f }
    val out = OpenVoiceVoiceConverter.normalizePeak(quiet, targetPeak = 0.95f, maxBoost = 4f)
    assertEquals("boost must be capped at x4, not 0.95/0.1", 0.4f, peak(out), 1e-4f)
  }

  @Test
  fun normalizePeak_capDoesNotAffectAttenuation() {
    // The cap limits BOOST only; bringing a too-loud buffer DOWN to the target must be unaffected.
    val loud = FloatArray(100) { if (it == 0) 1.9f else 0.2f }
    val out = OpenVoiceVoiceConverter.normalizePeak(loud, targetPeak = 0.95f, maxBoost = 4f)
    assertEquals("attenuation must reach the target exactly", 0.95f, peak(out), 1e-4f)
  }
}
