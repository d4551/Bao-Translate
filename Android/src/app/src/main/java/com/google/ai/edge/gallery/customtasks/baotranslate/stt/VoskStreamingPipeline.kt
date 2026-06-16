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
import java.io.File
import org.vosk.Model
import org.vosk.Recognizer

/**
 * TRUE streaming ASR via Vosk/Kaldi for the languages sherpa-onnx has no streaming transducer for
 * (Spanish, French, German, Russian, Hindi, ...). Vosk's recognizer emits a growing partial result
 * token-by-token as audio arrives — the same live-caption behavior as the sherpa transducer, just for
 * a different language set.
 *
 * Vosk's native handles are not concurrency-safe, so every call is serialized on [lock].
 */
class VoskStreamingPipeline(private val modelDir: String) : StreamingCaptioner {
  private val lock = Any()
  @Volatile private var model: Model? = null
  private var recognizer: Recognizer? = null

  override val isReady: Boolean
    get() = recognizer != null

  /** Returns true if the Vosk model is present and the recognizer loaded. */
  fun initialize(): Boolean =
    synchronized(lock) {
      if (recognizer != null) return true
      val dir = File(modelDir)
      // Vosk ships two layouts: new (am/ + conf/ + graph/) and old-flat (final.mdl + mfcc.conf); both
      // carry ivector/. Require ivector/ + the acoustic model in either layout so a partial extraction
      // never hands Model() a half-written directory.
      if (
        !File(dir, "ivector").isDirectory ||
          !(File(dir, "am").isDirectory || File(dir, "final.mdl").exists())
      ) {
        BaoLog.w(TAG, "Vosk model not present/complete under $modelDir")
        return false
      }
      // Model()/Recognizer() declare IOException and can fail on a corrupt model; route that to a
      // null captioner (the read loop falls back to chunked-Whisper) instead of crashing the load.
      var openedModel: Model? = null
      val built =
        runCatching {
            val m = Model(modelDir)
            openedModel = m
            m to Recognizer(m, SAMPLE_RATE)
          }
          .getOrElse { e ->
            openedModel?.close()
            BaoLog.w(TAG, "Vosk model failed to load from $modelDir: ${e.message}")
            return false
          }
      model = built.first
      recognizer = built.second
      BaoLog.i(TAG, "Vosk streaming recognizer initialized from $modelDir")
      return true
    }

  override fun acceptAndDecode(samples: ShortArray): String =
    synchronized(lock) {
      val rec = recognizer ?: return ""
      rec.acceptWaveForm(samples, samples.size)
      return extractPartial(rec.partialResult)
    }

  override fun reset(): Unit =
    synchronized(lock) {
      recognizer?.reset()
    }

  override fun release(): Unit =
    synchronized(lock) {
      val rec = recognizer
      val mdl = model
      recognizer = null
      model = null
      try {
        rec?.close()
      } finally {
        mdl?.close()
      }
    }

  // Vosk getPartialResult() returns {"partial" : "recognized text"}. Pull the field by hand: a pure
  // string scan that can't throw (no JSON parse on the audio thread) and returns "" on any surprise.
  private fun extractPartial(json: String): String {
    val key = "\"partial\""
    val keyAt = json.indexOf(key)
    if (keyAt < 0) return ""
    val colon = json.indexOf(':', keyAt + key.length)
    if (colon < 0) return ""
    val open = json.indexOf('"', colon + 1)
    if (open < 0) return ""
    val close = json.indexOf('"', open + 1)
    if (close < 0) return ""
    return json.substring(open + 1, close).trim()
  }

  companion object {
    private const val TAG = "VoskStreamingPipeline"
    private const val SAMPLE_RATE = 16000.0f
  }
}
