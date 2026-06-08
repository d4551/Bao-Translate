package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Anti-aliased float-PCM resampler (windowed-sinc low-pass before linear interpolation).
 *
 * Needed by the on-device voice-clone chain: Kokoro emits 24 kHz, the OpenVoice ToneColorConverter
 * and its STFT front-end ([OpenVoiceSpectrogram]) operate at 22.05 kHz, and playback may use yet
 * another rate. Downsampling without the low-pass folds >Nyquist energy back as audible distortion
 * that corrupts the converter's spectrogram input, so the filter is essential, not cosmetic.
 */
object AudioResampler {

  fun resample(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
    if (samples.isEmpty() || srcRate == dstRate) return samples.copyOf()
    // Band-limit before the linear interpolation in BOTH directions (margin below Nyquist):
    //  - downsample: anti-alias below the destination Nyquist so content above it does not fold back;
    //  - upsample:   anti-image below the source Nyquist so the triangular (sinc^2) interpolation
    //    kernel does not pass spectral images through as scratchy high-frequency artifacts.
    val src = when {
      dstRate < srcRate -> lowPass(samples, srcRate, dstRate * 0.45)
      dstRate > srcRate -> lowPass(samples, srcRate, srcRate * 0.45)
      else -> samples
    }
    val outLen = (src.size.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
    val out = FloatArray(outLen)
    for (i in 0 until outLen) {
      val pos = i.toDouble() * srcRate / dstRate
      val i0 = pos.toInt()
      val i1 = (i0 + 1).coerceAtMost(src.size - 1)
      val frac = (pos - i0).toFloat()
      out[i] = src[i0] * (1f - frac) + src[i1] * frac
    }
    return out
  }

  /** Hamming-windowed-sinc FIR low-pass at [cutoffHz], zero-phase (centered) convolution. */
  private fun lowPass(x: FloatArray, sampleRate: Int, cutoffHz: Double): FloatArray {
    val taps = 64
    val fc = cutoffHz / sampleRate
    val h = DoubleArray(taps + 1)
    var sum = 0.0
    for (i in 0..taps) {
      val n = i - taps / 2.0
      val sinc = if (n == 0.0) 2.0 * fc else sin(2.0 * PI * fc * n) / (PI * n)
      val w = 0.54 - 0.46 * cos(2.0 * PI * i / taps)
      h[i] = sinc * w
      sum += h[i]
    }
    for (i in h.indices) h[i] /= sum
    val half = taps / 2
    return FloatArray(x.size) { idx ->
      var acc = 0.0
      for (k in 0..taps) {
        val j = idx + k - half
        if (j in x.indices) acc += x[j] * h[k]
      }
      acc.toFloat()
    }
  }
}
