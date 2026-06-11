package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Anti-aliased float-PCM resampler: single-step Hamming-windowed-sinc FRACTIONAL resampling.
 *
 * Needed by the on-device voice-clone chain: Kokoro emits 24 kHz, the OpenVoice ToneColorConverter
 * and its STFT front-end ([OpenVoiceSpectrogram]) operate at 22.05 kHz, and playback may use yet
 * another rate. The windowed-sinc kernel evaluated at the exact fractional source position
 * band-limits AND reconstructs in ONE step (the reference pipeline uses librosa/soxr-class
 * resampling, alias/image suppression >80 dB). The previous low-pass + 2-point LINEAR interpolation
 * left the triangle kernel's spectral images attenuated only by sinc^2(f/f_src) — ~-15 dB images of
 * 8 kHz content folding back in-band at the near-unity 24k->22.05k ratio — heard as inharmonic,
 * METALLIC grit on sibilants that propagated straight into the clone spectrogram.
 */
object AudioResampler {

  // Kernel half-width in source samples. 32 taps/side keeps images and aliases below ~-80 dB
  // (soxr/librosa class) at the ratios used here, vs ~-15 dB for 2-point linear interpolation.
  private const val HALF_TAPS = 32

  fun resample(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
    require(srcRate > 0) { "srcRate must be > 0 (got $srcRate)" }
    require(dstRate > 0) { "dstRate must be > 0 (got $dstRate)" }
    if (samples.isEmpty() || srcRate == dstRate) return samples.copyOf()
    // Cutoff in cycles per SOURCE sample, 0.45 margin below the smaller Nyquist (matches the
    // previous passband edge and soxr_hq's ~0.91*Nyquist passband): anti-alias when downsampling,
    // anti-image when upsampling — one kernel covers both directions.
    val cutoff = 0.45 * minOf(srcRate, dstRate) / srcRate
    val outLen = (samples.size.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
    val out = FloatArray(outLen)
    val step = srcRate.toDouble() / dstRate
    for (i in 0 until outLen) {
      val pos = i * step
      val i0 = pos.toInt()
      val frac = pos - i0
      var acc = 0.0
      var norm = 0.0
      for (k in -HALF_TAPS + 1..HALF_TAPS) {
        val j = i0 + k
        if (j !in samples.indices) continue // edge: renormalize over the in-range taps below
        val t = k - frac // tap offset from the exact fractional source position
        val sinc = if (t == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * t) / (PI * t)
        val h = sinc * (0.54 + 0.46 * cos(PI * t / HALF_TAPS)) // Hamming across the kernel span
        norm += h
        acc += samples[j] * h
      }
      // Per-phase normalization over the IN-RANGE taps -> exact unity DC gain at every fractional
      // phase (no level ripple) AND at the buffer edges (no built-in fade that would violate DC).
      out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
    }
    return out
  }
}
