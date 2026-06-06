package com.google.ai.edge.gallery.customtasks.baotranslate.stt

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

private const val TAG = "WhisperPipeline"
private const val SAMPLE_RATE = PipelineConfig.STT_SAMPLE_RATE

data class TranscriptionResult(
  val text: String,
  val language: String,
)

sealed class WhisperOutcome {
  data class Transcribed(val result: TranscriptionResult) : WhisperOutcome()
  data class Failed(val reason: String) : WhisperOutcome()
}

class WhisperPipeline(private val context: Context) {
  private var recognizer: OfflineRecognizer? = null
  private var isReady = false

  // Serializes native decode against release() so the recognizer handle is never freed while a
  // decode runs, and serializes concurrent decodes (OfflineRecognizer is not concurrency-safe).
  private val inferenceLock = Any()

  fun initialize(modelPath: String): Boolean {
    val modelDir = File(modelPath)
    if (!modelDir.exists()) {
      BaoLog.w(TAG, "Whisper model dir not found: $modelPath")
      return false
    }

    val encoderFile = modelDir.listFiles { file ->
      file.name.endsWith("-encoder.int8.onnx") || file.name.endsWith("-encoder.onnx")
    }?.sortedBy { if (it.name.endsWith(".int8.onnx")) 0 else 1 }?.firstOrNull()
    val decoderFile = modelDir.listFiles { file ->
      file.name.endsWith("-decoder.int8.onnx") || file.name.endsWith("-decoder.onnx")
    }?.sortedBy { if (it.name.endsWith(".int8.onnx")) 0 else 1 }?.firstOrNull()
    val tokensFile = modelDir.listFiles { file ->
      file.name.endsWith("-tokens.txt") || file.name == "tokens.txt"
    }?.firstOrNull()

    if (encoderFile == null || decoderFile == null || tokensFile == null) {
      BaoLog.w(TAG, "Whisper files missing: encoder=${encoderFile != null}, decoder=${decoderFile != null}, tokens=${tokensFile != null}")
      return false
    }

    val whisperConfig = OfflineWhisperModelConfig(
      encoder = encoderFile.absolutePath,
      decoder = decoderFile.absolutePath,
      language = "",
      task = "transcribe",
      tailPaddings = PipelineConfig.WHISPER_TAIL_PADDINGS,
      enableTokenTimestamps = false,
      enableSegmentTimestamps = false,
    )

    val modelConfig = OfflineModelConfig(
      whisper = whisperConfig,
      numThreads = PipelineConfig.STT_THREADS,
      debug = false,
      provider = "cpu",
      tokens = tokensFile.absolutePath,
    )

    val config = OfflineRecognizerConfig(
      modelConfig = modelConfig,
    )

    recognizer = OfflineRecognizer(null, config)
    isReady = true
    BaoLog.i(TAG, "Whisper (Sherpa-ONNX) initialized from: $modelDir")
    return true
  }

  fun transcribeBlocking(audioSamples: ShortArray): Result<TranscriptionResult> = synchronized(inferenceLock) {
    val recog = recognizer
    if (!isReady || recog == null) {
      return Result.failure(IllegalStateException("Whisper not initialized"))
    }

    if (audioSamples.isEmpty()) {
      return Result.failure(IllegalArgumentException("Empty audio"))
    }

    val floatSamples = FloatArray(audioSamples.size) { i ->
      audioSamples[i].toFloat() / 32768f
    }

    val stream = recog.createStream()
    try {
      stream.acceptWaveform(floatSamples, SAMPLE_RATE)

      recog.decode(stream)

      val result = recog.getResult(stream)
      val text = result.text.trim()
      val lang = result.lang.ifBlank { "en" }

      return if (text.isNotBlank()) {
        Result.success(
          TranscriptionResult(
            text = text,
            language = lang,
          )
        )
      } else {
        Result.failure(Exception("Empty transcription"))
      }
    } finally {
      stream.release()
    }
  }

  fun cleanup() {
    synchronized(inferenceLock) {
      recognizer?.release()
      recognizer = null
      isReady = false
    }
  }
}
