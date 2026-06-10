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

/**
 * Engine-agnostic streaming speech-to-text for live captions: feed incremental 16 kHz mono PCM and
 * read back the growing recognized hypothesis token-by-token, as the turn is spoken.
 *
 * Two implementations back the multilingual live caption:
 * - [StreamingSttPipeline] — sherpa-onnx zipformer transducer, used for English (highest accuracy).
 * - [VoskStreamingPipeline] — Vosk/Kaldi, the on-device engine that actually streams the app's other
 *   languages (Spanish/French/German/Russian/Hindi/...), which sherpa-onnx has no streaming model for.
 *
 * The recording read loop selects the captioner by spoken language; both share this contract so the
 * loop is engine-agnostic.
 */
interface StreamingCaptioner {
  /** True once the underlying recognizer is loaded and ready to accept audio. */
  val isReady: Boolean

  /** Feeds an incremental 16 kHz mono PCM chunk and returns the running recognized hypothesis. */
  fun acceptAndDecode(samples: ShortArray): String

  /** Resets recognizer state for the next turn (call after a turn endpoint / commit). */
  fun reset()

  /** Releases native resources. */
  fun release()
}
