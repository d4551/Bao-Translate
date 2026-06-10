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
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.SupertonicTtsPipeline
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves on-device audio playback for the languages Kokoro cannot voice — de/ja/ko/ru/ar — via the
 * supplemental sherpa-onnx Supertonic TTS (the router's second tier, before the platform-TTS last
 * resort). Together with [BaoTranslateKokoroAudioE2eTest] this covers spoken audio for ALL app
 * languages with no dependency on a device platform-TTS engine.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateSupertonicAudioE2eTest {
  @Test
  fun supertonicSpeaksRealAudio_forEveryKokoroGapLanguage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val modelDir = BaoTranslateModelManager.getSupertonicModelDir(context)
    // Provision only if not already present (a re-download would re-extract a stale archive).
    if (!SupertonicTtsPipeline.isModelReady(modelDir)) {
      val dl = runBlocking { BaoTranslateModelManager.downloadModel(context, "supertonic_tts") }
      assertTrue("Supertonic model provisioning failed: ${dl.exceptionOrNull()?.message}", dl.isSuccess)
    }
    assertTrue("Supertonic model not ready at $modelDir", SupertonicTtsPipeline.isModelReady(modelDir))

    val supertonic = SupertonicTtsPipeline(context)
    assertTrue("Supertonic failed to initialize", supertonic.initialize(modelDir.absolutePath))

    val phrases =
      mapOf(
        "de" to "Hallo, wie geht es Ihnen heute?",
        "ja" to "こんにちは、お元気ですか？",
        "ko" to "안녕하세요, 어떻게 지내세요?",
        "ru" to "Здравствуйте, как ваши дела?",
        "ar" to "مرحبا، كيف حالك اليوم؟",
      )

    val failures = mutableListOf<String>()
    for ((code, text) in phrases) {
      assertTrue("$code should be a Supertonic supplemental language", SupertonicTtsPipeline.supportsLanguage(code))
      val audio = supertonic.synthesizeAudio(text, voiceId = code, speed = 1.0f)
      if (audio == null || audio.samples.isEmpty()) {
        failures.add("$code: no audio")
        continue
      }
      val durSec = audio.samples.size.toFloat() / audio.sampleRate
      var peak = 0f
      for (s in audio.samples) peak = maxOf(peak, abs(s))
      Log.i("SupertonicAudioTest", "SUPERTONIC_AUDIO [$code] durSec=$durSec peak=$peak samples=${audio.samples.size}")
      if (durSec < 0.3f) failures.add("$code: audio too short (${durSec}s)")
      if (peak <= 0.02f) failures.add("$code: audio silent (peak=$peak)")
    }
    supertonic.cleanup()

    assertTrue("Supertonic audio playback failures: $failures", failures.isEmpty())
  }
}
