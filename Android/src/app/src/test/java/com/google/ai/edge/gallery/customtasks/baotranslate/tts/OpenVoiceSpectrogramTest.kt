package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies the Kotlin [OpenVoiceSpectrogram] reproduces PyTorch's `spectrogram_torch` output
 * bit-closely (fixture generated from the OpenVoice reference). This is the on-device front-end for
 * the voice-clone converter; a wrong window/pad/FFT convention would silently corrupt the timbre.
 */
class OpenVoiceSpectrogramTest {

  private fun loadFixture(): Pair<FloatArray, Array<FloatArray>> {
    val text = javaClass.getResourceAsStream("/baotranslate/stft_fixture.txt")!!
      .bufferedReader().readText().trim().lines()
    val wav = text[0].split(",").map { it.toFloat() }.toFloatArray()
    val (f, c) = text[1].split(" ").map { it.toInt() }
    // spec[frame][freq], frame-major lines
    val spec = Array(c) { row -> text[2 + row].split(",").map { it.toFloat() }.toFloatArray() }
    assertEquals(513, f)
    return wav to spec
  }

  @Test
  fun `matches pytorch spectrogram_torch`() {
    val (wav, ref) = loadFixture()
    val frames = ref.size
    val out = OpenVoiceSpectrogram.compute(wav) // [freq][frame]
    assertEquals("freq bins", OpenVoiceSpectrogram.FREQ_BINS, out.size)
    assertEquals("frame count", frames, out[0].size)

    var maxAbs = 0f
    var maxRel = 0f
    for (frame in 0 until frames) {
      for (k in 0 until OpenVoiceSpectrogram.FREQ_BINS) {
        val expected = ref[frame][k]
        val got = out[k][frame]
        val d = abs(expected - got)
        maxAbs = maxOf(maxAbs, d)
        if (expected > 0.1f) maxRel = maxOf(maxRel, d / expected)
      }
    }
    // Reference stored at 5-decimal precision; allow small tolerance for that + float FFT order.
    assertTrue("max abs diff $maxAbs too large", maxAbs < 2e-2f)
    assertTrue("max rel diff $maxRel too large", maxRel < 1e-2f)
  }
}
