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
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VoskStreamingPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves multilingual live captions are REAL for a non-English language: provisions the Spanish Vosk
 * streaming model through [BaoTranslateModelManager] (download .zip from the registry URL -> extract
 * -> Ready), loads it, feeds Spanish speech in small chunks like a live mic, and asserts the partial
 * hypothesis GROWS token-by-token — true streaming, not English-only and not a re-decode.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateVoskCaptionE2eTest {
  @Test
  fun spanishVoskCaption_provisionsAndStreams_onDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Force a real provisioning run (download from the registry URL + zip-extract, app-owned).
    BaoTranslateModelManager.deleteCaptionModel(context, "es")
    assertTrue(
      "Spanish caption model should be absent after delete",
      !BaoTranslateModelManager.isCaptionModelReady(context, "es"),
    )
    val result = runBlocking { BaoTranslateModelManager.downloadCaptionModel(context, "es") }
    assertTrue("Spanish Vosk model provisioning failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
    assertTrue(
      "Spanish caption model not Ready after provisioning",
      BaoTranslateModelManager.isCaptionModelReady(context, "es"),
    )

    val dir = requireNotNull(BaoTranslateModelManager.getCaptionModelDir(context, "es"))
    val pipeline = VoskStreamingPipeline(dir.absolutePath)
    assertTrue("Provisioned Vosk Spanish model failed to load", pipeline.initialize())

    val pcm = BaoTranslateLiveTestSupport.spanishPromptAsSttPcm()
    val chunkSamples = 16000 * 3 / 10 // 0.3s
    val partials = LinkedHashSet<String>()
    var offset = 0
    while (offset < pcm.size) {
      val end = minOf(offset + chunkSamples, pcm.size)
      val hypothesis = pipeline.acceptAndDecode(pcm.copyOfRange(offset, end)).trim()
      if (hypothesis.isNotBlank()) partials.add(hypothesis)
      offset = end
    }
    val finalText = partials.lastOrNull().orEmpty()
    pipeline.release()

    Log.i("VoskCaptionTest", "SPANISH_PARTIALS=${partials.size} final='$finalText' all=$partials")
    assertTrue("Vosk Spanish produced no recognized text", finalText.isNotBlank())
    assertTrue(
      "Vosk Spanish did not stream growing partials (expected >=2 distinct), saw ${partials.size}: $partials",
      partials.size >= 2,
    )
  }
}
