/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.baotranslate.stt

import com.google.ai.edge.gallery.common.BaoLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * TRUE streaming ASR: a sherpa-onnx streaming Zipformer transducer ([OnlineRecognizer]) that emits a
 * growing hypothesis token-by-token as audio arrives — the industry-standard approach for live
 * captions, unlike re-decoding a whole window with an offline model.
 *
 * Used for the live recognized caption while a turn is being spoken; the offline Whisper pass still
 * produces the final, higher-quality transcript that drives translation.
 *
 * sherpa-onnx's native recognizer is single-threaded per stream and not concurrency-safe, so every
 * native call here is serialized on [lock].
 */
class StreamingSttPipeline(private val modelDir: String) : StreamingCaptioner {
  private val lock = Any()
  @Volatile private var recognizer: OnlineRecognizer? = null
  private var stream: OnlineStream? = null

  override val isReady: Boolean
    get() = recognizer != null

  /** Returns true if the streaming model files are present and the recognizer loaded. */
  fun initialize(): Boolean =
    synchronized(lock) {
      if (recognizer != null) return true
      val encoder = File(modelDir, ENCODER)
      val decoder = File(modelDir, DECODER)
      val joiner = File(modelDir, JOINER)
      val tokens = File(modelDir, TOKENS)
      if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
        BaoLog.w(TAG, "Streaming ASR model not present under $modelDir")
        return false
      }
      val config =
        OnlineRecognizerConfig(
          featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
          modelConfig =
            OnlineModelConfig(
              transducer =
                OnlineTransducerModelConfig(
                  encoder = encoder.absolutePath,
                  decoder = decoder.absolutePath,
                  joiner = joiner.absolutePath,
                ),
              tokens = tokens.absolutePath,
              numThreads = 2,
              provider = "cpu",
              // No modelType: sherpa auto-detects (zipformer vs zipformer2) from the ONNX metadata,
              // so the loader matches the actual model and doesn't read fields it doesn't have.
            ),
          enableEndpoint = true,
          decodingMethod = "greedy_search",
        )
      val rec = OnlineRecognizer(null, config)
      recognizer = rec
      stream = rec.createStream("")
      BaoLog.i(TAG, "Streaming ASR (sherpa-onnx zipformer transducer) initialized from $modelDir")
      return true
    }

  /**
   * Feeds a 16 kHz mono PCM chunk and returns the current streaming hypothesis. Returns the running
   * recognized text so far — it grows token-by-token across calls.
   */
  override fun acceptAndDecode(samples: ShortArray): String =
    synchronized(lock) {
      val rec = recognizer ?: return ""
      val s = stream ?: return ""
      val floats = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
      s.acceptWaveform(floats, 16000)
      while (rec.isReady(s)) rec.decode(s)
      return rec.getResult(s).text
    }

  /** True when the recognizer has detected an utterance endpoint (trailing silence). */
  fun isEndpoint(): Boolean =
    synchronized(lock) {
      val rec = recognizer ?: return false
      val s = stream ?: return false
      return rec.isEndpoint(s)
    }

  /** Resets the stream for the next turn (call after committing/endpoint). */
  override fun reset(): Unit =
    synchronized(lock) {
      val rec = recognizer ?: return
      val s = stream ?: return
      rec.reset(s)
    }

  override fun release(): Unit =
    synchronized(lock) {
      stream?.release()
      recognizer?.release()
      stream = null
      recognizer = null
    }

  companion object {
    private const val TAG = "StreamingSttPipeline"
    const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
    const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    const val TOKENS = "tokens.txt"
  }
}
