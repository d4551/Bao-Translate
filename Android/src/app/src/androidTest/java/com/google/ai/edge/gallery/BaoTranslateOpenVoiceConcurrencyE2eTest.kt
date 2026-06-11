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

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OOS-BRUTALISE-005: the converter's `inferenceLock` must make concurrent
 * computeSpeakerEmbedding()/convert() and cleanup() use-after-free-safe — a cleanup that races an
 * in-flight native run must serialize behind it (no SIGSEGV on a freed ONNX handle), and calls after
 * cleanup must return null gracefully rather than dereferencing a closed session.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateOpenVoiceConcurrencyE2eTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext

  private fun refSamples(): Pair<FloatArray, Int> {
    val bytes = instrumentation.context.assets.open("bao_voice_ref.wav").use { it.readBytes() }
    assertTrue("reference voice wav invalid", WavUtils.isValidWav(bytes))
    val rate = WavUtils.extractSampleRateFromWav(bytes) ?: 16000
    return WavUtils.extractSamplesFromWav(bytes) to rate
  }

  @Test
  fun concurrentEmbedAndCleanup_noNativeCrash_andGracefulAfterClose() {
    BaoTranslateLiveTestSupport.prepareDevice(context)
    runBlocking { BaoTranslateModelManager.downloadModel(context, "openvoice") }
    val converter = OpenVoiceVoiceConverter()
    assertTrue(
      "OpenVoice converter init",
      converter.initialize(
        BaoTranslateModelManager.getOpenVoiceConverterFile(context),
        BaoTranslateModelManager.getOpenVoiceRefEncFile(context),
      ),
    )

    val (samples, rate) = refSamples()
    val errors = AtomicInteger(0)

    // 4 workers hammer the native run path while one worker frees the handles mid-flight.
    val workers = (0 until 4).map {
      Thread {
        repeat(25) {
          runCatching { converter.computeSpeakerEmbedding(samples, rate) }
            .onFailure { errors.incrementAndGet() }
        }
      }
    }
    val closer = Thread {
      Thread.sleep(40)
      runCatching { converter.cleanup() }.onFailure { errors.incrementAndGet() }
    }

    workers.forEach { it.start() }
    closer.start()
    workers.forEach { it.join() }
    closer.join()

    // The lock guarantees no thread threw (a freed-handle deref would crash the process, not throw),
    // and the process is still alive to make this assertion.
    assertTrue("inferenceLock must serialize run vs cleanup with no thrown failures", errors.get() == 0)
    // After cleanup the converter must answer null, not dereference a closed ONNX session.
    assertNull("post-cleanup computeSpeakerEmbedding must return null", converter.computeSpeakerEmbedding(samples, rate))
  }
}
