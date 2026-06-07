package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioResampler
import java.io.File

/**
 * On-device cross-lingual voice cloning via the OpenVoice ToneColorConverter (ONNX Runtime).
 *
 * Kokoro (correct target-language pronunciation, all 8 languages) → resample 22.05 kHz →
 * [OpenVoiceSpectrogram] STFT → `ov_refenc.onnx` (dynamic length) gives the source speaker
 * embedding → `ov_converter.onnx` (dynamic length) re-times the spectrogram into the enrolled
 * user's timbre → 22.05 kHz audio in the user's voice. Decouples pronunciation (Kokoro) from
 * timbre (converter), covering languages end-to-end clone models can't.
 *
 * Both graphs run at the EXACT utterance length on ONNX Runtime ([OrtOpenVoiceModel]): the dilated
 * WaveNet receptive field never reaches padding, so the output is crisp/intelligible (fixed-length
 * TFLite smeared phonemes). Validated at 96+ dB vs PyTorch. tau=0.3 stochastic posterior via an
 * explicit Gaussian noise input. Not thread-safe; callers serialize.
 */
class OpenVoiceVoiceConverter(@Suppress("unused") private val context: Context) {
  private companion object {
    const val TAG = "OpenVoiceVC"
    const val OV_RATE = 22050
    const val FREQ = OpenVoiceSpectrogram.FREQ_BINS // 513
    const val SE_DIM = 256
    const val INTER = 192 // posterior latent channels (inter_channels); noise input shape [1,192,T]
    const val HOP = OpenVoiceSpectrogram.HOP // 256 (output samples per frame)
    const val MAX_FRAMES = 4096 // safety cap (~47 s at 22.05 kHz); longer utterances are truncated
  }

  private val noiseRng = java.util.Random()

  private var converter: OrtOpenVoiceModel? = null
  private var refEnc: OrtOpenVoiceModel? = null
  @Volatile private var ready = false

  fun initialize(converterOnnx: File, refEncOnnx: File): Boolean {
    if (!converterOnnx.exists() || !refEncOnnx.exists()) {
      BaoLog.w(TAG, "OpenVoice onnx missing: conv=${converterOnnx.exists()} refEnc=${refEncOnnx.exists()}")
      return false
    }
    converter = OrtOpenVoiceModel.load(converterOnnx)
    refEnc = OrtOpenVoiceModel.load(refEncOnnx)
    ready = true
    BaoLog.i(TAG, "OpenVoice converter + ref_enc loaded (ONNX Runtime, exact-length)")
    return true
  }

  /** Enrollment: derive the user's 256-d speaker embedding from a reference clip. */
  fun computeSpeakerEmbedding(wavSamples: FloatArray, sampleRate: Int): FloatArray? {
    val re = refEnc ?: return null
    val (spec, frames) = spectrogramFlat(wavSamples, sampleRate) ?: return null
    return runRefEnc(re, spec, frames)
  }

  /** Convert Kokoro audio into the enrolled timbre. [targetSe] from [computeSpeakerEmbedding]. */
  fun convert(audio: SynthesizedAudio, targetSe: FloatArray): SynthesizedAudio? {
    val conv = converter ?: return null
    val re = refEnc ?: return null
    if (!ready) return null
    val (specReal, framesReal) = spectrogramFlat(audio.samples, audio.sampleRate) ?: return null
    if (framesReal <= 0) return null
    val srcSe = runRefEnc(re, specReal, framesReal) ?: return null

    val frames = framesReal.coerceAtMost(MAX_FRAMES)
    if (framesReal > MAX_FRAMES) BaoLog.w(TAG, "utterance $framesReal frames > $MAX_FRAMES; truncating")

    // Exact-length inference: feed the real spectrogram (freq-major flat == row-major [1,FREQ,T]),
    // a Gaussian noise tensor for the tau=0.3 posterior, and the src/tgt speaker embeddings.
    val ySpec = if (frames == framesReal) specReal else specReal.copyOf(FREQ * frames)
    // Raw standard Gaussian: the ONNX graph applies the tau temperature internally
    // (z = m + noise * tau * exp(logs)); pre-scaling here would square it.
    val noise = FloatArray(INTER * frames) { noiseRng.nextGaussian().toFloat() }
    val inputs = linkedMapOf(
      "y" to (ySpec to longArrayOf(1, FREQ.toLong(), frames.toLong())),
      "src" to (srcSe.copyOf(SE_DIM) to longArrayOf(1, SE_DIM.toLong(), 1)),
      "tgt" to (targetSe.copyOf(SE_DIM) to longArrayOf(1, SE_DIM.toLong(), 1)),
      "noise" to (noise to longArrayOf(1, INTER.toLong(), frames.toLong())),
    )
    val (flat, _) = conv.run(inputs)
    return if (flat.isEmpty()) null else SynthesizedAudio(samples = flat, sampleRate = OV_RATE)
  }

  fun cleanup() {
    ready = false
    converter?.close(); converter = null
    refEnc?.close(); refEnc = null
  }

  // ---- helpers ----

  private fun spectrogramFlat(samples: FloatArray, sampleRate: Int): Pair<FloatArray, Int>? {
    if (samples.isEmpty()) return null
    val at22k = AudioResampler.resample(samples, sampleRate, OV_RATE)
    if (at22k.size < OpenVoiceSpectrogram.N_FFT) return null
    return OpenVoiceSpectrogram.computeFlat(at22k) // FloatArray[FREQ*frames] freq-major, frames
  }

  // ref_enc runs at exact length too: spec flat (freq-major) == row-major [1,FREQ,frames];
  // TAU/noise unused here (deterministic GRU pooling) -> output [1,SE_DIM,1].
  private fun runRefEnc(re: OrtOpenVoiceModel, flatSpec: FloatArray, frames: Int): FloatArray? {
    val name = re.inputNames.firstOrNull() ?: "spec"
    val (flat, _) = re.run(mapOf(name to (flatSpec to longArrayOf(1, FREQ.toLong(), frames.toLong()))))
    return flat.takeIf { it.size >= SE_DIM }?.copyOf(SE_DIM)
  }
}
