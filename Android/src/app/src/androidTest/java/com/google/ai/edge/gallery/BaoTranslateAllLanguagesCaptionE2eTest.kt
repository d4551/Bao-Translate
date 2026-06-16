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
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VoskStreamingPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Provisions and streams EVERY non-English caption language end-to-end on-device, so "all languages"
 * is proven per-language, not extrapolated from Spanish. For each Vosk language: download the model
 * from the registry, load it, feed that language's real speech prompt in mic-sized chunks, and assert
 * the recognized hypothesis grows token-by-token. Heavy (downloads ~10 models) but exhaustive.
 */
@LargeTest
@RunWith(Parameterized::class)
class BaoTranslateAllLanguagesCaptionE2eTest(private val lang: String) {
  companion object {
    // Every Vosk caption language in the registry (English uses the sherpa transducer, covered
    // separately; Arabic has no streaming model and uses chunked-Whisper).
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun languages(): Collection<Array<String>> =
      listOf("es", "fr", "de", "zh", "ja", "ko", "pt", "it", "ru", "hi").map { arrayOf(it) }
  }

  @Test
  fun voskCaptionLanguage_provisionsAndStreams_onDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dl = runBlocking { BaoTranslateModelManager.downloadCaptionModel(context, lang) }
    assertTrue("$lang: provisioning failed: ${dl.exceptionOrNull()?.message}", dl.isSuccess)

    val dir = BaoTranslateModelManager.getCaptionModelDir(context, lang)
    assertTrue("$lang: no caption model dir", dir != null)
    if (dir == null) return

    val pipeline = VoskStreamingPipeline(dir.absolutePath)
    assertTrue("$lang: Vosk model failed to load from ${dir.absolutePath}", pipeline.initialize())

    val partials = LinkedHashSet<String>()
    try {
      val pcm = BaoTranslateLiveTestSupport.promptForLanguage(lang)
      val chunk = 16000 * 3 / 10 // 0.3s
      var offset = 0
      while (offset < pcm.size) {
        val end = minOf(offset + chunk, pcm.size)
        val hyp = pipeline.acceptAndDecode(pcm.copyOfRange(offset, end)).trim()
        if (hyp.isNotBlank()) partials.add(hyp)
        offset = end
      }
    } finally {
      pipeline.release()
    }
    val finalText = partials.lastOrNull().orEmpty()
    Log.i("AllLangCaptionTest", "CAPTION [$lang] partials=${partials.size} final='${finalText.take(50)}'")
    assertTrue("$lang: produced no recognized text", finalText.isNotBlank())
    assertTrue("$lang: did not stream (partials=${partials.size}): $partials", partials.size >= 2)
  }
}
