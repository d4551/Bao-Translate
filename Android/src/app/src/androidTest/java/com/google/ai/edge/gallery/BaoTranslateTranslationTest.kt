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
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full on-device BaoTranslate proof.
 *
 * These tests intentionally do not use Assume/skip. They self-provision missing artifacts over the
 * device network, then exercise the real LiteRT-LM and Kokoro runtimes through instrumentation.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateTranslationTest {

  @Test
  fun downloadsEveryBaoTranslateModel_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    BaoTranslateModelManager.ALL_MODELS.forEach { model ->
      ensureModelReady(context, model.id)
    }

    assertTrue(
      "Not every BaoTranslate model is ready after self-provisioning: ${currentStatuses(context)}",
      BaoTranslateModelManager.areAllModelsReady(context),
    )
  }

  @Test
  fun translationModelHandoff_qwenToGemma_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = "The cat drinks water."

    val qwenTranslation = translateWithModel(
      context = context,
      modelId = "qwen25_1b",
      source = source,
      sourceLanguage = "en",
      targetLanguage = "es",
    )
    assertContainsAtLeast(
      label = "Qwen English to Spanish",
      translated = qwenTranslation,
      markers = listOf("gato", "agua", "bebe", "toma"),
      minMatches = 2,
    )

    val gemmaTranslation = translateWithModel(
      context = context,
      modelId = "gemma4_e2b",
      source = source,
      sourceLanguage = "en",
      targetLanguage = "es",
    )
    assertContainsAtLeast(
      label = "Gemma English to Spanish after handoff",
      translated = gemmaTranslation,
      markers = listOf("gato", "agua", "bebe", "toma"),
      minMatches = 2,
    )
  }

  @Test
  fun translatesBidirectionalLanguagePairs_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    val spanish = translateWithModel(
      context = context,
      modelId = "qwen25_1b",
      source = "The cat drinks water.",
      sourceLanguage = "en",
      targetLanguage = "es",
    )
    assertContainsAtLeast(
      label = "English to Spanish",
      translated = spanish,
      markers = listOf("gato", "agua", "bebe", "toma"),
      minMatches = 2,
    )

    val english = translateWithModel(
      context = context,
      modelId = "qwen25_1b",
      source = "El gato bebe agua.",
      sourceLanguage = "es",
      targetLanguage = "en",
    )
    assertContainsAtLeast(
      label = "Spanish to English",
      translated = english,
      markers = listOf("cat", "water", "drink", "drinks", "drinking"),
      minMatches = 2,
    )
  }

  @Test
  fun kokoroMultiSpeakerVoicesProduceDistinctAudio_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensureModelReady(context, "kokoro_tts")

    val femaleVoice = KokoroTtsPipeline.getVoiceForLanguage("es", "female")
    val maleVoice = KokoroTtsPipeline.getVoiceForLanguage("es", "male")
    assertNotEquals("Spanish female and male voices resolved to the same speaker", femaleVoice, maleVoice)

    val kokoro = KokoroTtsPipeline(context)
    try {
      assertTrue(
        "Kokoro TTS failed to initialize",
        kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(context).absolutePath),
      )

      val text = "El gato bebe agua."
      val femaleAudio = kokoro.synthesize(text, femaleVoice)
      val maleAudio = kokoro.synthesize(text, maleVoice)

      assertNotNull("Kokoro produced no female-speaker audio", femaleAudio)
      assertNotNull("Kokoro produced no male-speaker audio", maleAudio)
      assertTrue("Female-speaker audio was empty", femaleAudio!!.isNotEmpty())
      assertTrue("Male-speaker audio was empty", maleAudio!!.isNotEmpty())
      assertDistinctAudio(femaleAudio, maleAudio)
    } finally {
      kokoro.cleanup()
    }
  }

  @Test
  fun liveConversation_translateThenSpeak_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensureModelReady(context, "qwen25_1b")
    ensureModelReady(context, "kokoro_tts")

    val translated = translateWithModel(
      context = context,
      modelId = "qwen25_1b",
      source = "Hello, how are you?",
      sourceLanguage = "en",
      targetLanguage = "es",
    )

    val kokoro = KokoroTtsPipeline(context)
    try {
      assertTrue(
        "Kokoro TTS failed to initialize",
        kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(context).absolutePath),
      )
      val samples = kokoro.synthesize(translated, KokoroTtsPipeline.getVoiceForLanguage("es"))
      assertNotNull("Kokoro produced no audio for translated peer text", samples)
      assertTrue("Kokoro produced empty audio for translated peer text", samples!!.isNotEmpty())
    } finally {
      kokoro.cleanup()
    }
  }

  // ----- BRUTALISATION -----

  // ----- Both translation models should produce the same kind of Spanish output, not be
  // aliased (e.g. one model silently never loaded and is returning the other's cache).
  @Test
  fun translation_qwen_and_gemma_produceDifferentOutput_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = "The cat drinks water."
    val qwen = translateWithModel(
      context = context, modelId = "qwen25_1b", source = source, sourceLanguage = "en", targetLanguage = "es",
    )
    val gemma = translateWithModel(
      context = context, modelId = "gemma4_e2b", source = source, sourceLanguage = "en", targetLanguage = "es",
    )
    // Pin: both produce valid Spanish (already covered), AND they differ from each other.
    // If they produce byte-identical output, one model was never actually loaded.
    assertTrue(
      "qwen and gemma produced byte-identical output — one model is likely aliased " +
        "to the other (qwen=\"$qwen\", gemma=\"$gemma\")",
      qwen.trim().lowercase() != gemma.trim().lowercase(),
    )
  }

  // ----- Translate + speak: the spoken audio length must be a reasonable function of
  // text length. ~40ms per character is a rough lower bound for clear TTS.
  @Test
  fun liveConversation_translateThenSpeak_actualAudioLength() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensureModelReady(context, "qwen25_1b")
    ensureModelReady(context, "kokoro_tts")
    val translated = translateWithModel(
      context = context, modelId = "qwen25_1b", source = "Good morning, how are you today, my friend?",
      sourceLanguage = "en", targetLanguage = "es",
    )
    val kokoro = KokoroTtsPipeline(context)
    try {
      assertTrue("kokoro init", kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(context).absolutePath))
      val samples = kokoro.synthesize(translated, KokoroTtsPipeline.getVoiceForLanguage("es"))!!
      // Rough lower bound: 40ms per character of text. Below that, something is wrong.
      val minExpectedSeconds = translated.length * 0.04f
      val actualSeconds = samples.size.toFloat() / 22050f  // Kokoro native rate
      assertTrue(
        "Kokoro audio too short for text length ${translated.length}: actual=${actualSeconds}s " +
          "(need >= ${minExpectedSeconds}s)",
        actualSeconds >= minExpectedSeconds,
      )
    } finally {
      kokoro.cleanup()
    }
  }

  // ----- Kokoro init+cleanup cycle: should be repeatable (no native handle leak).
  @Test
  fun cleanup_calledBetweenTests_noStateLeak() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensureModelReady(context, "kokoro_tts")
    repeat(3) {
      val kokoro = KokoroTtsPipeline(context)
      assertTrue("kokoro init #$it", kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(context).absolutePath))
      kokoro.cleanup()
    }
  }

  // ----- The two models are not aliased at the file level: distinct .litertlm files
  // with distinct content. Catches a copy-paste regression where one model was
  // accidentally replaced with the other.
  @Test
  fun translation_qwen_and_gemma_distinctModelFiles() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensureModelReady(context, "qwen25_1b")
    ensureModelReady(context, "gemma4_e2b")
    val qwenFile = BaoTranslateModelManager.getTranslationModelDir(context, "qwen25_1b")
      .listFiles { f -> f.extension == "litertlm" }?.firstOrNull()
    val gemmaFile = BaoTranslateModelManager.getTranslationModelDir(context, "gemma4_e2b")
      .listFiles { f -> f.extension == "litertlm" }?.firstOrNull()
    assertNotNull("qwen25_1b .litertlm missing", qwenFile)
    assertNotNull("gemma4_e2b .litertlm missing", gemmaFile)
    assertNotEquals(
      "qwen25_1b and gemma4_e2b resolve to the SAME file",
      qwenFile!!.absolutePath, gemmaFile!!.absolutePath,
    )
    assertNotEquals(
      "qwen25_1b and gemma4_e2b have the same size — one was probably copied from the other",
      qwenFile.length(), gemmaFile.length(),
    )
  }

  private fun ensureModelReady(context: Context, modelId: String) {
    if (BaoTranslateModelManager.checkModelStatus(context, modelId) != ModelStatus.Ready) {
      val result = runBlocking {
        BaoTranslateModelManager.downloadModel(context, modelId, wifiOnly = false)
      }
      assertTrue("Failed to download $modelId: ${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    val status = BaoTranslateModelManager.checkModelStatus(context, modelId)
    val size = BaoTranslateModelManager.getStorageBreakdown(context)[modelId] ?: 0L
    assertTrue("$modelId is not Ready after provisioning; status=$status", status == ModelStatus.Ready)
    assertTrue("$modelId has no stored bytes after provisioning", size > 0L)
  }

  private fun translateWithModel(
    context: Context,
    modelId: String,
    source: String,
    sourceLanguage: String,
    targetLanguage: String,
  ): String {
    ensureModelReady(context, modelId)
    val modelFile = translationModelFile(context, modelId)
    val pipeline = TranslationPipeline(context)

    try {
      assertTrue(
        "$modelId translation engine failed to initialize with ${modelFile.name}",
        pipeline.initialize(modelFile.absolutePath),
      )

      val outcome = pipeline.translateBlocking(source, sourceLanguage, targetLanguage)
      assertTrue("$modelId expected Success but got: $outcome", outcome is TranslationOutcome.Success)
      val translated = (outcome as TranslationOutcome.Success).result.translatedText
      assertTrue("$modelId translation was blank", translated.isNotBlank())
      assertNotEquals("$modelId output equals input; no translation occurred", source, translated)
      return translated
    } finally {
      pipeline.cleanup()
      System.gc()
    }
  }

  private fun translationModelFile(context: Context, modelId: String): File {
    val modelFile = BaoTranslateModelManager.getTranslationModelDir(context, modelId)
      .listFiles { file -> (file.extension == "litertlm" || file.extension == "task") && file.length() > 0 }
      ?.maxByOrNull { it.length() }

    if (modelFile != null) return modelFile
    fail("No non-empty .litertlm/.task model file present for $modelId")
    throw AssertionError("unreachable")
  }

  private fun assertContainsAtLeast(
    label: String,
    translated: String,
    markers: List<String>,
    minMatches: Int,
  ) {
    val normalized = normalize(translated)
    val matches = markers.count { normalized.contains(normalize(it)) }
    assertTrue(
      "$label did not look like the target language. Expected at least $minMatches of $markers, got '$translated'",
      matches >= minMatches,
    )
  }

  private fun assertDistinctAudio(first: FloatArray, second: FloatArray) {
    if (first.size != second.size) return

    val sampleCount = minOf(first.size, second.size, 4096)
    var totalDiff = 0.0
    for (i in 0 until sampleCount) {
      totalDiff += abs(first[i] - second[i]).toDouble()
    }
    // HARDENED: was `totalDiff > 0.001` (any tiny difference passes). New: at least
    // 1% of the samples must differ meaningfully, OR Pearson correlation must be < 0.99.
    val pearson = pearsonCorrelation(first, second, sampleCount)
    val distinctEnough = totalDiff > sampleCount * 0.01 || kotlin.math.abs(pearson) < 0.99
    assertTrue(
      "Two Kokoro speaker voices produced near-identical PCM samples: totalDiff=$totalDiff " +
        "pearson=$pearson (need totalDiff > ${sampleCount * 0.01} OR |pearson| < 0.99)",
      distinctEnough,
    )
  }

  private fun pearsonCorrelation(first: FloatArray, second: FloatArray, count: Int): Double {
    if (count <= 1) return 0.0
    var sumA = 0.0
    var sumB = 0.0
    for (i in 0 until count) {
      sumA += first[i].toDouble()
      sumB += second[i].toDouble()
    }
    val meanA = sumA / count
    val meanB = sumB / count
    var cov = 0.0
    var varA = 0.0
    var varB = 0.0
    for (i in 0 until count) {
      val dA = first[i].toDouble() - meanA
      val dB = second[i].toDouble() - meanB
      cov += dA * dB
      varA += dA * dA
      varB += dB * dB
    }
    return if (varA > 0 && varB > 0) cov / (kotlin.math.sqrt(varA) * kotlin.math.sqrt(varB)) else 0.0
  }

  private fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
      .replace("\\p{Mn}+".toRegex(), "")

  private fun currentStatuses(context: Context): String =
    BaoTranslateModelManager.ALL_MODELS.joinToString { model ->
      "${model.id}=${BaoTranslateModelManager.checkModelStatus(context, model.id)}"
    }
}
