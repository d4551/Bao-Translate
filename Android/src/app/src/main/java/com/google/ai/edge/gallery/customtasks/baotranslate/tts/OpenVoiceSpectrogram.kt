package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * On-device magnitude STFT that exactly matches OpenVoice's `spectrogram_torch`
 * (mel_processing.py): reflect-pad by (n_fft - hop)/2 each side, periodic Hann window,
 * onesided FFT, magnitude = sqrt(re^2 + im^2 + 1e-6). This is the front-end that turns Kokoro's
 * (resampled 22.05 kHz) audio into the spectrogram the OpenVoice ToneColorConverter ONNX graph
 * consumes. Verified against a PyTorch reference fixture by OpenVoiceSpectrogramTest.
 */
object OpenVoiceSpectrogram {
  const val N_FFT = 1024
  const val HOP = 256
  const val WIN = 1024
  const val FREQ_BINS = N_FFT / 2 + 1 // 513
  private const val EPS = 1e-6f

  private val hann: FloatArray = FloatArray(WIN) { n ->
    // torch.hann_window default is periodic: 0.5 * (1 - cos(2*pi*n / N))
    (0.5 * (1.0 - cos(2.0 * Math.PI * n / WIN))).toFloat()
  }

  /**
   * @return spectrogram as [FREQ_BINS][frames] (freq-major, matching torch [513, T]).
   */
  fun compute(samples: FloatArray): Array<FloatArray> {
    // Empty input has no content to transform: return the [FREQ_BINS] freq-major rows with zero
    // frames rather than failing the reflect-pad precondition. (A non-empty but too-short signal,
    // 1..pad samples, still throws from reflectPad — that is a caller error, not "nothing to do".)
    if (samples.isEmpty()) return Array(FREQ_BINS) { FloatArray(0) }
    require(samples.all { it.isFinite() }) { "Spectrogram input contains non-finite values (NaN/Inf)" }
    val pad = (N_FFT - HOP) / 2 // 384
    val padded = reflectPad(samples, pad)
    val frames = if (padded.size < N_FFT) 0 else 1 + (padded.size - N_FFT) / HOP
    val out = Array(FREQ_BINS) { FloatArray(frames) }
    val re = FloatArray(N_FFT)
    val im = FloatArray(N_FFT)
    for (f in 0 until frames) {
      val start = f * HOP
      for (i in 0 until N_FFT) {
        re[i] = padded[start + i] * hann[i]
        im[i] = 0f
      }
      fftRadix2(re, im)
      for (k in 0 until FREQ_BINS) {
        out[k][f] = sqrt(re[k] * re[k] + im[k] * im[k] + EPS)
      }
    }
    return out
  }

  /** Flattened freq-major [1, FREQ_BINS, frames] row-major (freq outer). */
  fun computeFlat(samples: FloatArray): Pair<FloatArray, Int> {
    val spec = compute(samples)
    val frames = if (spec.isNotEmpty()) spec[0].size else 0
    return flatten(spec, frames, timeMajor = false) to frames
  }

  /**
   * Flattens a [FREQ_BINS][frames] spectrogram to a row-major buffer, truncated to [frames].
   *  - freq-major -> [1, FREQ_BINS, frames] : the converter's `audio` input (channel = frequency).
   *  - time-major -> [1, frames, FREQ_BINS] : the ref-encoder's `input` (last dim = spec_channels).
   * The two OpenVoice graphs need opposite layouts. Verified on-device: the ref-encoder only yields
   * a speaker-discriminative embedding when fed time-major, matching OpenVoice's ReferenceEncoder
   * which reshapes its input to [N, 1, T, spec_channels]; feeding it freq-major collapses distinct
   * speakers to near-identical embeddings (no timbre transfer).
   */
  fun flatten(spec: Array<FloatArray>, frames: Int, timeMajor: Boolean): FloatArray {
    val f0 = frames.coerceAtMost(if (spec.isNotEmpty()) spec[0].size else 0).coerceAtLeast(0)
    val out = FloatArray(FREQ_BINS * f0)
    var idx = 0
    if (timeMajor) {
      for (t in 0 until f0) for (k in 0 until FREQ_BINS) out[idx++] = spec[k][t]
    } else {
      for (k in 0 until FREQ_BINS) {
        val row = spec[k]
        for (t in 0 until f0) out[idx++] = row[t]
      }
    }
    return out
  }

  /** torch reflect pad: edges are mirrored without repeating the boundary sample. Requires pad < size. */
  private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
    val n = x.size
    if (pad == 0) return x.copyOf()
    require(pad < n) { "reflect pad $pad requires signal length > pad (got $n)" }
    val out = FloatArray(n + 2 * pad)
    for (k in 0 until n) out[pad + k] = x[k]
    for (i in 0 until pad) out[i] = x[pad - i]              // left: x[pad], x[pad-1], ..., x[1]
    for (j in 0 until pad) out[pad + n + j] = x[n - 2 - j]  // right: x[n-2], x[n-3], ...
    return out
  }

  /** In-place iterative radix-2 Cooley-Tukey FFT (size must be a power of two). */
  private fun fftRadix2(re: FloatArray, im: FloatArray) {
    val n = re.size
    // bit-reversal permutation
    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j or bit
      if (i < j) {
        var t = re[i]; re[i] = re[j]; re[j] = t
        t = im[i]; im[i] = im[j]; im[j] = t
      }
    }
    var len = 2
    while (len <= n) {
      val ang = -2.0 * Math.PI / len
      val wlenRe = cos(ang).toFloat()
      val wlenIm = kotlin.math.sin(ang).toFloat()
      var i = 0
      while (i < n) {
        var wRe = 1f
        var wIm = 0f
        val half = len / 2
        for (k in 0 until half) {
          val uRe = re[i + k]
          val uIm = im[i + k]
          val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
          val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
          re[i + k] = uRe + vRe
          im[i + k] = uIm + vIm
          re[i + k + half] = uRe - vRe
          im[i + k + half] = uIm - vIm
          val nwRe = wRe * wlenRe - wIm * wlenIm
          wIm = wRe * wlenIm + wIm * wlenRe
          wRe = nwRe
        }
        i += len
      }
      len = len shl 1
    }
  }
}
