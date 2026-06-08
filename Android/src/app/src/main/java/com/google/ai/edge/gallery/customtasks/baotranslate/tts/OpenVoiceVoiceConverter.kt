package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioResampler
import java.io.File

/**
 * On-device cross-lingual voice cloning via the OpenVoice v2 ToneColorConverter (ONNX Runtime).
 *
 * Kokoro (correct target-language pronunciation, all 8 languages) → resample 22.05 kHz →
 * [OpenVoiceSpectrogram] STFT → the converter re-times the spectrogram into the enrolled speaker's
 * timbre → 22.05 kHz audio in that voice. Decouples pronunciation (Kokoro) from timbre (converter),
 * so a SINGLE model pair clones EVERY voice and every language — the speaker identity is the 256-d
 * embedding ([computeSpeakerEmbedding]), never baked into the model, so no per-voice download.
 *
 * Both graphs are the public, downloadable OpenVoice exports from
 * `huggingface.co/seasonstudio/openvoice_tone_clone_onnx` (provisioned by BaoTranslateModelManager):
 *  - ref encoder `ov_refenc.onnx`: input `input` = spectrogram TIME-major [1, T, 513] → 256-d
 *    speaker embedding. Time-major is required (OpenVoice's ReferenceEncoder reshapes to
 *    [N, 1, T, spec_channels]); feeding freq-major collapses distinct speakers to near-identical
 *    embeddings. Verified on-device (cos(out,target)=0.92 time-major vs no transfer freq-major).
 *  - converter `ov_converter.onnx`: inputs `audio` = spectrogram FREQ-major [1, 513, T],
 *    `audio_length` (int64, the frame count → sequence mask, so it runs at the EXACT utterance
 *    length with no fixed-size WaveNet padding smear), `src_tone`/`dest_tone` = [1, 256, 1] speaker
 *    embeddings, `tau` (posterior temperature; the graph samples its own Gaussian internally).
 *    Output 0 = the converted waveform [1, 1, samples].
 *
 * Thread-safety: an internal [inferenceLock] serializes [initialize]/[computeSpeakerEmbedding]/
 * [convert] against [cleanup], so the native ONNX handles are never closed mid-inference (model
 * delete / pipeline switch / onCleared while a convert is in flight). ONNX Runtime's `Run` is
 * concurrency-safe but session disposal must not overlap a `Run`; this mirrors the same lock
 * contract used by KokoroTtsPipeline/TranslationPipeline/WhisperPipeline/VadProcessor.
 */
class OpenVoiceVoiceConverter {
  companion object {
    const val SE_DIM = 256

    private const val TAG = "OpenVoiceVC"
    private const val OV_RATE = 22050
    private const val FREQ = OpenVoiceSpectrogram.FREQ_BINS // 513
    private const val TAU = 0.3f // posterior temperature; the converter samples its own noise
    private const val MAX_FRAMES = 4096 // safety cap (~47 s at 22.05 kHz); longer utterances are truncated
  }

  // Serializes native run() against close() so converter/refEnc are never freed mid-inference
  // (model delete / pipeline switch / onCleared during an in-flight convert), and serializes the
  // recording and BLE-peer synth paths that share this single converter instance.
  private val inferenceLock = Any()

  private var converter: OrtOpenVoiceModel? = null
  private var refEnc: OrtOpenVoiceModel? = null
  @Volatile private var ready = false

  fun initialize(converterOnnx: File, refEncOnnx: File): Boolean = synchronized(inferenceLock) {
    if (!converterOnnx.exists() || !refEncOnnx.exists()) {
      BaoLog.w(TAG, "OpenVoice onnx missing: conv=${converterOnnx.exists()} refEnc=${refEncOnnx.exists()}")
      return false
    }
    converter = OrtOpenVoiceModel.load(converterOnnx)
    refEnc = OrtOpenVoiceModel.load(refEncOnnx)
    ready = true
    BaoLog.i(TAG, "OpenVoice converter + ref_enc loaded (ONNX Runtime)")
    return true
  }

  /** Enrollment: derive the speaker's 256-d embedding from a reference clip. */
  fun computeSpeakerEmbedding(wavSamples: FloatArray, sampleRate: Int): FloatArray? = synchronized(inferenceLock) {
    val re = refEnc ?: return null
    val (spec, frames) = spectrogram(wavSamples, sampleRate) ?: return null
    if (frames <= 0) return null
    return runRefEnc(re, spec, frames.coerceAtMost(MAX_FRAMES))
  }

  /** Convert [audio] (e.g. Kokoro output) into the [targetSe] timbre. [targetSe] from [computeSpeakerEmbedding]. */
  fun convert(audio: SynthesizedAudio, targetSe: FloatArray): SynthesizedAudio? = synchronized(inferenceLock) {
    val conv = converter ?: return null
    val re = refEnc ?: return null
    if (!ready) return null
    val (spec, framesReal) = spectrogram(audio.samples, audio.sampleRate) ?: return null
    if (framesReal <= 0) return null

    val frames = framesReal.coerceAtMost(MAX_FRAMES)
    if (framesReal > MAX_FRAMES) BaoLog.w(TAG, "utterance $framesReal frames > $MAX_FRAMES; truncating")

    // Source timbre = embedding of the base audio itself (so the converter can remove it before
    // applying the target). Same ref encoder, same time-major layout as enrollment.
    val srcSe = runRefEnc(re, spec, frames) ?: return null

    // converter: audio FREQ-major [1, FREQ, T]; audio_length (int64) drives the exact-length mask.
    val audioFlat = OpenVoiceSpectrogram.flatten(spec, frames, timeMajor = false)
    val floatInputs = linkedMapOf(
      "audio" to (audioFlat to longArrayOf(1, FREQ.toLong(), frames.toLong())),
      "src_tone" to (srcSe.copyOf(SE_DIM) to longArrayOf(1, SE_DIM.toLong(), 1)),
      "dest_tone" to (targetSe.copyOf(SE_DIM) to longArrayOf(1, SE_DIM.toLong(), 1)),
      "tau" to (floatArrayOf(TAU) to longArrayOf(1)),
    )
    val longInputs = linkedMapOf(
      "audio_length" to (longArrayOf(frames.toLong()) to longArrayOf(1)),
    )
    val (flat, _) = conv.run(floatInputs, longInputs)
    return if (flat.isEmpty()) null else SynthesizedAudio(samples = flat, sampleRate = OV_RATE)
  }

  fun cleanup() {
    synchronized(inferenceLock) {
      ready = false
      converter?.close(); converter = null
      refEnc?.close(); refEnc = null
    }
  }

  // ---- helpers ----

  // Resamples to 22.05 kHz and computes the magnitude STFT once ([FREQ_BINS][frames]); callers
  // flatten it freq- or time-major as each graph requires.
  private fun spectrogram(samples: FloatArray, sampleRate: Int): Pair<Array<FloatArray>, Int>? {
    if (samples.isEmpty()) return null
    val at22k = AudioResampler.resample(samples, sampleRate, OV_RATE)
    if (at22k.size < OpenVoiceSpectrogram.N_FFT) return null
    val spec = OpenVoiceSpectrogram.compute(at22k) // [FREQ_BINS][frames]
    val frames = if (spec.isNotEmpty()) spec[0].size else 0
    return spec to frames
  }

  // ref encoder: spectrogram fed TIME-major [1, frames, FREQ] -> [1, SE_DIM] (flattened to SE_DIM).
  private fun runRefEnc(re: OrtOpenVoiceModel, spec: Array<FloatArray>, frames: Int): FloatArray? {
    val name = re.inputNames.firstOrNull() ?: "input"
    val timeMajor = OpenVoiceSpectrogram.flatten(spec, frames, timeMajor = true)
    val (flat, _) = re.run(mapOf(name to (timeMajor to longArrayOf(1, frames.toLong(), FREQ.toLong()))))
    return flat.takeIf { it.size >= SE_DIM }?.copyOf(SE_DIM)
  }
}
