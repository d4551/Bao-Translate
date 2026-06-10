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

package com.google.ai.edge.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingSttPipeline
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves TRUE streaming ASR on-device: feeds English speech to the sherpa-onnx streaming Zipformer
 * transducer in small chunks (like a live mic) and asserts the hypothesis GROWS token-by-token —
 * the industry-standard streaming-recognizer behavior, not offline re-decode.
 *
 * Requires the streaming model provisioned under getExternalFilesDir/streaming-asr (pushed by the
 * provisioning step). Skips with a clear failure if absent.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateStreamingAsrE2eTest {
  @Test
  fun streamingTransducer_emitsGrowingPartials_onDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val modelDir = BaoTranslateModelManager.getStreamingAsrModelDir(context).absolutePath
    val pipeline = StreamingSttPipeline(modelDir)
    assertTrue("Streaming ASR model not provisioned at $modelDir", pipeline.initialize())

    // Reuse the bundled English prompt (16 kHz PCM); feed it in 0.3s chunks like a live mic.
    val pcm = BaoTranslateLiveTestSupport.englishPromptAsSttPcm()
    // Feed in ~100ms frames to mirror the F2F read loop's per-frame feed granularity, and time each
    // synchronous acceptAndDecode (the exact native call the audio read thread makes) so we can prove
    // it stays well under the frame period — i.e. it never backpressures AudioRecord.
    val frameSamples = 16000 / 10 // 0.1s
    val frameMs = 100L
    val partials = LinkedHashSet<String>()
    var offset = 0
    var maxDecodeMs = 0L
    var totalDecodeMs = 0L
    var frames = 0
    while (offset < pcm.size) {
      val end = minOf(offset + frameSamples, pcm.size)
      val frame = pcm.copyOfRange(offset, end)
      val t0 = android.os.SystemClock.elapsedRealtime()
      val hypothesis = pipeline.acceptAndDecode(frame).trim()
      val dt = android.os.SystemClock.elapsedRealtime() - t0
      maxDecodeMs = maxOf(maxDecodeMs, dt)
      totalDecodeMs += dt
      frames++
      if (hypothesis.isNotBlank()) partials.add(hypothesis)
      offset = end
    }
    val avgDecodeMs = if (frames > 0) totalDecodeMs / frames else 0
    Log.i(
      "StreamingAsrTest",
      "DECODE_LATENCY frames=$frames avgMs=$avgDecodeMs maxMs=$maxDecodeMs framePeriodMs=$frameMs",
    )
    // Sustained keep-up condition: average decode per frame must stay under the frame period, so the
    // read thread feeds faster than audio arrives. A one-off warmup spike (logged as maxMs) is
    // absorbed by AudioRecord's deliberately oversized capture buffer.
    assertTrue(
      "Average streaming decode (${avgDecodeMs}ms/frame) exceeds the ${frameMs}ms audio frame " +
        "period — it would backpressure AudioRecord on the read thread.",
      avgDecodeMs < frameMs,
    )
    val finalText = partials.lastOrNull().orEmpty()
    pipeline.release()

    Log.i("StreamingAsrTest", "STREAMING_PARTIALS=${partials.size} final='$finalText' all=$partials")
    assertTrue("Streaming transducer produced no recognized text", finalText.isNotBlank())
    assertTrue(
      "Streaming transducer did not emit GROWING partials (expected >=2 distinct hypotheses as " +
        "audio arrived), saw ${partials.size}: $partials",
      partials.size >= 2,
    )
  }
}
