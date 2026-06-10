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
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the app's PRIMARY audio playback (Kokoro on-device ONNX TTS) produces real, non-silent
 * audio for every Kokoro-native language — device-independent (no reliance on a device platform-TTS
 * engine, which these test devices lack). This is the audio a listener actually hears after a
 * translation, for en/es/fr/it/pt/zh/hi.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateKokoroAudioE2eTest {
  @Test
  fun kokoroSpeaksRealAudio_forEveryNativeLanguage() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    BaoTranslateLiveTestSupport.ensureRequiredModelsReady(context)

    val kokoro = KokoroTtsPipeline(context)
    assertTrue(
      "Kokoro failed to initialize",
      kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(context).absolutePath),
    )

    val phrases =
      mapOf(
        "en" to "Hello, how are you today?",
        "es" to "Hola, ¿cómo estás hoy?",
        "fr" to "Bonjour, comment allez-vous aujourd'hui?",
        "it" to "Ciao, come stai oggi?",
        "pt" to "Olá, como você está hoje?",
        "zh" to "你好，你今天好吗？",
        "hi" to "नमस्ते, आप आज कैसे हैं?",
      )

    val failures = mutableListOf<String>()
    for ((code, text) in phrases) {
      assertTrue("$code should be a Kokoro-native language", KokoroTtsPipeline.supportsLanguage(code))
      val audio = kokoro.synthesizeAudio(text, KokoroTtsPipeline.getVoiceForLanguage(code), 1.0f)
      if (audio == null || audio.samples.isEmpty()) {
        failures.add("$code: no audio")
        continue
      }
      val durSec = audio.samples.size.toFloat() / audio.sampleRate
      var peak = 0f
      for (s in audio.samples) peak = maxOf(peak, abs(s))
      Log.i("KokoroAudioTest", "KOKORO_AUDIO [$code] durSec=$durSec peak=$peak samples=${audio.samples.size}")
      if (durSec < 0.3f) failures.add("$code: audio too short (${durSec}s)")
      if (peak <= 0.02f) failures.add("$code: audio silent (peak=$peak)")
    }
    kokoro.cleanup()

    assertTrue("Kokoro audio playback failures: $failures", failures.isEmpty())
  }
}
