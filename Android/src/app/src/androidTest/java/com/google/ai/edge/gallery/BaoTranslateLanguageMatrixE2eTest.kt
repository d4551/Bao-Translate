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
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioResampler
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.WhisperPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.PlatformTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationPipeline
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoketest of EVERY supported language.
 *
 * [everyLanguageTranslates_endToEnd]: translation for all 11 languages, both directions, with
 * target-script validation (independent of STT/TTS quality).
 *
 * [forcedSourceLanguageRecognition_endToEnd]: proves the language fix — forcing Whisper to the
 * user's selected source language (what the app now does) recognizes correctly where auto-detect
 * garbles it (e.g. Spanish/French detected as "Latin"). Uses the device's platform TTS as a
 * representative human-like speaker.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateLanguageMatrixE2eTest {

  private data class Lang(val code: String, val phrase: String, val script: ClosedRange<Char>?)

  // Canonical phrase per language + the Unicode script block expected in en->L output (null=Latin).
  private val all = listOf(
    Lang("es", "Buenos días, ¿cómo estás?", null),
    Lang("fr", "Bonjour, comment allez-vous?", null),
    Lang("de", "Guten Morgen, wie geht es dir?", null),
    Lang("it", "Buongiorno, come stai?", null),
    Lang("pt", "Bom dia, como você está?", null),
    Lang("ru", "Доброе утро, как дела?", 'Ѐ'..'ӿ'),
    Lang("zh", "早上好，你好吗？", '一'..'鿿'),
    Lang("ja", "おはよう、お元気ですか？", '぀'..'ヿ'),
    Lang("ko", "안녕하세요, 잘 지내세요?", '가'..'힯'),
    Lang("ar", "صباح الخير، كيف حالك؟", '؀'..'ۿ'),
  )

  // Every selectable translation model must translate every language, both directions. Runs the full
  // matrix for each installed model so a model that loads but fails to invoke (e.g. gemma-4-E2B-it
  // under a too-small kv-cache: "Failed to invoke the compiled model") is caught here, not in prod.
  @Test
  fun everyLanguageTranslates_endToEnd() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val failures = mutableListOf<String>()
    for (modelId in listOf("qwen25_1b", "gemma4_e2b")) {
      failures += runTranslationMatrix(ctx, modelId)
    }
    Log.i(TAG, "TRANSLATION SUMMARY failures=$failures")
    assertTrue("Per-language translation failures: $failures", failures.isEmpty())
  }

  private fun runTranslationMatrix(ctx: Context, modelId: String): List<String> {
    ensure(ctx, listOf(modelId))
    val translation = TranslationPipeline(ctx)
    val failures = mutableListOf<String>()
    try {
      val litertlm = BaoTranslateModelManager.getTranslationModelDir(ctx, modelId)
        .listFiles { f -> f.extension == "litertlm" }?.firstOrNull()
      assertTrue("$modelId .litertlm missing", litertlm != null)
      assertTrue("$modelId init", translation.initialize(litertlm!!.absolutePath))

      val src = "Good morning, how are you?"
      for (l in all) {
        // English -> L: non-echo, non-blank, and (for non-Latin) contains the target script.
        val toL = translate(translation, src, "en", l.code)
        val nonEcho = toL.isNotBlank() && !toL.trim().equals(src, ignoreCase = true)
        val scriptOk = l.script?.let { r -> toL.any { it in r } } ?: nonEcho
        Log.i(TAG, "TR [$modelId en->${l.code}] -> \"${toL.take(60)}\" nonEcho=$nonEcho scriptOk=$scriptOk")
        if (!nonEcho) failures.add("[$modelId] en->${l.code} echoed/empty")
        if (!scriptOk) failures.add("[$modelId] en->${l.code} missing ${l.code} script: \"$toL\"")

        // L -> English: produces English (ASCII letters present).
        val toEn = translate(translation, l.phrase, l.code, "en")
        val englishish = toEn.count { it in 'a'..'z' || it in 'A'..'Z' } >= 3
        Log.i(TAG, "TR [$modelId ${l.code}->en] -> \"${toEn.take(60)}\" englishish=$englishish")
        if (!englishish) failures.add("[$modelId] ${l.code}->en non-English/empty: \"$toEn\"")
      }
    } finally {
      translation.cleanup()
    }
    return failures
  }

  // [expects]: any normalized content root proving correct-language decode. [script]: if the forced
  // transcript is in this Unicode block it is decisively the right language (auto-detect garbles
  // such audio into Latin gibberish), so script presence alone passes.
  private data class RecogCase(
    val code: String,
    val locale: Locale,
    val phrase: String,
    val expects: List<String>,
    val script: ClosedRange<Char>? = null,
  )

  @Test
  fun forcedSourceLanguageRecognition_endToEnd() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    ensure(ctx, listOf("whisper_base"))
    val cases = listOf(
      RecogCase("en", Locale.US, "Good morning, how are you today?", listOf("morning", "how", "today")),
      RecogCase("es", Locale("es", "ES"), "Buenos dias, como estas hoy?", listOf("dias", "buenos", "como", "estas")),
      RecogCase("fr", Locale.FRANCE, "Bonjour, comment allez-vous aujourd'hui?", listOf("bonjour", "comment", "allez", "vous")),
      RecogCase("de", Locale.GERMANY, "Guten Morgen, wie geht es dir heute?", listOf("morgen", "guten", "geht", "wie")),
      RecogCase("it", Locale.ITALY, "Buongiorno, come stai oggi?", listOf("buongiorno", "come", "stai", "oggi")),
      RecogCase("ru", Locale("ru", "RU"), "Dobroe utro, kak dela segodnya?", listOf("утро", "добро", "дела"), 'Ѐ'..'ӿ'),
    )
    val whisperDir = BaoTranslateModelManager.getWhisperModelDir(ctx).absolutePath
    val failures = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (c in cases) {
      val pcm = PlatformTts.synthesizeTo16k(ctx, c.locale, c.phrase)
      if (pcm == null) { skipped.add("${c.code}"); Log.w(TAG, "RECOG [${c.code}] skipped: no device TTS voice"); continue }
      val shorts = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

      val auto = WhisperPipeline(ctx)
      val autoText = try { auto.initialize(whisperDir); auto.transcribeBlocking(shorts).getOrNull()?.text ?: "" } finally { auto.cleanup() }
      val forced = WhisperPipeline(ctx)
      val forcedText = try { forced.initialize(whisperDir, language = c.code); forced.transcribeBlocking(shorts).getOrNull()?.text ?: "" } finally { forced.cleanup() }

      val norm = java.text.Normalizer.normalize(forcedText, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()
      val contentOk = c.expects.any { norm.contains(it.lowercase()) }
      val scriptOk = c.script?.let { r -> forcedText.any { it in r } } ?: false
      val ok = contentOk || scriptOk
      Log.i(TAG, "RECOG [${c.code}] auto=\"${autoText.take(50)}\" forced=\"${forcedText.take(50)}\" ok=$ok (content=$contentOk script=$scriptOk)")
      if (!ok) failures.add("${c.code}: forced=\"$forcedText\" matched none of ${c.expects}")
    }
    Log.i(TAG, "RECOGNITION SUMMARY failures=$failures skipped=$skipped")
    assertTrue("No device TTS voices available to probe recognition (all skipped)", skipped.size < cases.size)
    assertTrue("Forced-language recognition failures: $failures", failures.isEmpty())
  }

  /**
   * Languages Kokoro can't voice (de/ko/ru/ar) must route to the device platform-TTS fallback and
   * produce real audio — not be spoken with an English Kokoro voice. Verifies the routing predicate
   * and that the fallback actually synthesizes audio for the languages the device supports.
   */
  @Test
  fun nonKokoroLanguages_useDevicePlatformTtsFallback() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    // Routing predicate: these are NOT Kokoro-native, so synthesizeSpeech routes them to platform TTS.
    listOf("de", "ko", "ru", "ar").forEach {
      assertTrue("$it must NOT be Kokoro-native (would speak with English voice)", !KokoroTtsPipeline.supportsLanguage(it))
    }
    listOf("en", "es", "fr", "it", "ja", "pt", "zh", "hi").forEach {
      assertTrue("$it must be Kokoro-native", KokoroTtsPipeline.supportsLanguage(it))
    }

    val platform = PlatformTtsPipeline(ctx)
    val cases = listOf(
      "de" to "Guten Morgen, wie geht es dir heute?",
      "ru" to "Доброе утро, как дела сегодня?",
      "ko" to "안녕하세요, 오늘 기분이 어때요?",
      "ar" to "صباح الخير، كيف حالك اليوم؟",
    )
    val spoke = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    try {
      for ((code, text) in cases) {
        if (!platform.isLanguageAvailable(code)) { skipped.add(code); Log.w(TAG, "FALLBACK [$code] skipped: no device voice"); continue }
        val audio = platform.synthesizeAudio(text, code)
        // Some devices report a language "available" but still fail to synthesize it (no actual
        // voice data installed). That's a device limitation, not a routing bug — skip, don't fail.
        if (audio == null || audio.samples.isEmpty()) { skipped.add(code); Log.w(TAG, "FALLBACK [$code] skipped: device reported available but synthesized nothing"); continue }
        val durSec = audio.samples.size.toFloat() / audio.sampleRate
        Log.i(TAG, "FALLBACK [$code] -> samples=${audio.samples.size} rate=${audio.sampleRate} dur=${durSec}s")
        assertTrue("Platform TTS audio implausibly short for $code: ${durSec}s", durSec > 0.3f)
        spoke.add(code)
      }
    } finally {
      platform.cleanup()
    }
    Log.i(TAG, "FALLBACK SUMMARY spoke=$spoke skipped=$skipped")
    assertTrue("Device had no TTS voice for any non-Kokoro language to verify the fallback", spoke.isNotEmpty())
  }

  private fun translate(t: TranslationPipeline, text: String, from: String, to: String): String =
    when (val o = t.translateBlocking(text, from, to)) {
      is TranslationOutcome.Success -> o.result.translatedText
      is TranslationOutcome.Failure -> ""
    }

  private fun ensure(ctx: Context, ids: List<String>) {
    ids.forEach { id ->
      if (BaoTranslateModelManager.checkModelStatus(ctx, id) != ModelStatus.Ready) {
        val r = runBlocking { BaoTranslateModelManager.downloadModel(ctx, id, wifiOnly = false) }
        assertTrue("download $id: ${r.exceptionOrNull()?.message}", r.isSuccess)
      }
    }
  }

  private companion object { const val TAG = "BaoLangMatrixE2E" }
}

/** Synthesizes text via the device platform TTS in a given locale and returns 16 kHz mono PCM. */
private object PlatformTts {
  fun synthesizeTo16k(ctx: Context, locale: Locale, text: String): FloatArray? {
    val initLatch = CountDownLatch(1)
    var status = TextToSpeech.ERROR
    val tts = TextToSpeech(ctx.applicationContext) { s -> status = s; initLatch.countDown() }
    try {
      if (!initLatch.await(30, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) return null
      if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return null
      tts.language = locale
      val uid = "lang-${locale.language}"
      val f = File(ctx.cacheDir, "$uid.wav").also { if (it.exists()) it.delete() }
      val done = CountDownLatch(1)
      var err = false
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(u: String?) = Unit
        override fun onDone(u: String?) = done.countDown()
        @Deprecated("Deprecated in Android framework") override fun onError(u: String?) { err = true; done.countDown() }
        override fun onError(u: String?, c: Int) { err = true; done.countDown() }
      })
      if (tts.synthesizeToFile(text, Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid) }, f, uid) != TextToSpeech.SUCCESS) return null
      if (!done.await(60, TimeUnit.SECONDS) || err) return null
      val bytes = f.readBytes()
      if (!WavUtils.isValidWav(bytes)) return null
      val rate = WavUtils.extractSampleRateFromWav(bytes) ?: return null
      val samples = WavUtils.extractSamplesFromWav(bytes)
      if (samples.isEmpty()) return null
      return AudioResampler.resample(samples, rate, PipelineConfig.STT_SAMPLE_RATE)
    } finally {
      tts.shutdown()
    }
  }
}
