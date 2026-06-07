package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.ai.edge.gallery.common.BaoLog
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs an OpenVoice ONNX graph (converter / ref_enc) on Microsoft ONNX Runtime
 * ([ai.onnxruntime]). Unlike the TFLite path, ORT runs the graph at the EXACT utterance length
 * (dynamic time dim), so the dilated WaveNet receptive field never reaches padding — the converted
 * audio stays crisp/intelligible. The dynamic ONNX is validated at 96+ dB vs PyTorch.
 *
 * Its `libonnxruntime.so` (v1.24.3) is the single ONNX Runtime in the APK; sherpa-onnx's bundled
 * copy is stripped at packaging time and sherpa's JNI binds to it. The version is pinned to 1.24.3
 * because sherpa's JNI imports the ELF-versioned symbol `OrtGetApiBase@VERS_1.24.3`. See
 * app/build.gradle.kts and libs/README.
 *
 * ORT sessions are internally thread-safe for [run]; callers still serialize via the pipeline lock.
 */
class OrtOpenVoiceModel private constructor(
  private val env: OrtEnvironment,
  private val session: OrtSession,
) {

  val inputNames: Set<String> get() = session.inputNames

  /**
   * Runs the graph with named float inputs (each a flat row-major [FloatArray] + its shape) and
   * returns the first output flattened plus its shape. All ONNX tensors are closed before return.
   */
  fun run(inputs: Map<String, Pair<FloatArray, LongArray>>): Pair<FloatArray, LongArray> {
    val tensors = LinkedHashMap<String, OnnxTensor>(inputs.size)
    for ((name, value) in inputs) {
      tensors[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(value.first), value.second)
    }
    val result = session.run(tensors)
    val output = result.get(0) as OnnxTensor
    val shape = output.info.shape
    val buffer = output.floatBuffer
    val flat = FloatArray(buffer.remaining())
    buffer.get(flat)
    result.close()
    for (tensor in tensors.values) tensor.close()
    return flat to shape
  }

  fun close() = session.close()

  companion object {
    private const val TAG = "OrtOpenVoiceModel"

    // One process-wide ONNX Runtime environment (the native runtime is a singleton).
    private val sharedEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun load(modelFile: File): OrtOpenVoiceModel {
      val options = OrtSession.SessionOptions()
      options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
      val session = sharedEnv.createSession(modelFile.readBytes(), options)
      BaoLog.i(TAG, "ORT session loaded: inputs=${session.inputNames} outputs=${session.outputNames}")
      return OrtOpenVoiceModel(sharedEnv, session)
    }
  }
}
