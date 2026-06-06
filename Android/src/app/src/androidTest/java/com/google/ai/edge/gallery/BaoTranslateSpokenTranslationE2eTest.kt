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
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.WhisperPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.SynthesizedAudio
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof that real-time translation is actually SPOKEN in a voice (Kokoro, the
 * multilingual engine), not just rendered as text:
 *
 *   English speech (platform TTS) → Whisper STT → Qwen translation (en→es) → Kokoro TTS (Spanish
 *   voice) → captured WAV → fed back through Whisper, which must transcribe intelligible Spanish.
 *
 * The closing round-trip is the key: if Whisper hears Spanish words ("buenas"/"noches") in Kokoro's
 * output, the spoken translation is genuinely intelligible Spanish — proven by an independent model,
 * not by trusting the TTS. The produced WAV is also dumped for a human to listen to.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateSpokenTranslationE2eTest {

  @Test
  fun englishSpeechIsTranslatedAndSpokenAsIntelligibleSpanish() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensureReady(context, listOf("whisper_base", "qwen25_1b", "kokoro_tts"))

    val whisper = WhisperPipeline(context)
    val translation = TranslationPipeline(context)
    val kokoro = KokoroTtsPipeline(context)
    try {
      assertTrue(
        "Whisper init failed",
        whisper.initialize(BaoTranslateModelManager.getWhisperModelDir(context).absolutePath),
      )
      val litertlm = BaoTranslateModelManager.getTranslationModelDir(context, "qwen25_1b")
        .listFiles { f -> f.extension == "litertlm" }?.firstOrNull()
      assertNotNull("No .litertlm translation model present", litertlm)
      assertTrue("Translation init failed", translation.initialize(litertlm!!.absolutePath))
      assertTrue(
        "Kokoro init failed",
        kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(context).absolutePath),
      )

      // 1. English speech in. A richer sentence gives the round-trip ASR more to work with.
      val enShorts = synthesizeEnglish16k(context, "Good morning. How are you today? It is very nice to meet you.")

      // 2. STT.
      val stt = whisper.transcribeBlocking(enShorts).getOrNull()
      assertNotNull("Whisper produced no transcription of the English input", stt)
      Log.i(TAG, "STT in: lang=${stt!!.language} text=\"${stt.text}\"")
      assertTrue("STT did not recognize the English input", stt.text.isNotBlank())

      // 3. Translate en -> es.
      val outcome = translation.translateBlocking(stt.text, "en", "es")
      assertTrue("Translation failed: $outcome", outcome is TranslationOutcome.Success)
      val spanishText = (outcome as TranslationOutcome.Success).result.translatedText
      Log.i(TAG, "TRANSLATE en->es: \"${stt.text}\" -> \"$spanishText\"")

      // 4. Speak the Spanish translation with Kokoro's Spanish voice (the [6] sid fix path).
      val voice = KokoroTtsPipeline.getVoiceForLanguage("es")
      Log.i(TAG, "Kokoro voice for es = $voice")
      val spoken = kokoro.synthesizeAudio(spanishText, voice)
      assertSpoken(spoken)

      val dumpDir = File(context.getExternalFilesDir(null), "bao_clone").apply { mkdirs() }
      val wav = File(dumpDir, "kokoro_spanish_translation.wav")
      writeWav(wav, spoken!!.samples, spoken.sampleRate)
      Log.i(TAG, "ARTIFACT spoken Spanish -> ${wav.absolutePath} (${wav.length()} bytes)")

      // 5. Round-trip: independently confirm the SPOKEN output is intelligible Spanish.
      val spoken16k = resampleToShort16k(spoken.samples, spoken.sampleRate)
      val back = whisper.transcribeBlocking(spoken16k).getOrNull()
      assertNotNull("Whisper could not transcribe the spoken Spanish output", back)
      Log.i(TAG, "ROUNDTRIP spoken-Spanish -> STT: lang=${back!!.language} text=\"${back.text}\"")
      // Proof of intelligibility: Whisper recovers the translated Spanish content words from Kokoro's
      // audio. Match accent-insensitively (Whisper drops diacritics: "días"->"dias") against the
      // actual translation, so accents / singular-plural choices don't cause false negatives.
      fun normWords(s: String): Set<String> =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
          .replace(Regex("\\p{Mn}+"), "")
          .lowercase(Locale.ROOT)
          .split(Regex("[^a-z]+"))
          .filter { it.length >= 4 }
          .toSet()
      val expectedWords = normWords(spanishText)
      val heardWords = normWords(back.text)
      val overlap = expectedWords.intersect(heardWords)
      Log.i(TAG, "intelligibility: lang=${back.language} expected=$expectedWords heard=$heardWords overlap=$overlap")
      // Proof = the round-trip ASR recovers the translated Spanish content words from Kokoro's audio.
      // (Whisper's language *label* is unreliable on short synthetic clips, so it is logged, not
      // asserted; the recovered content words are the meaningful, hard-to-fake signal.)
      assertTrue(
        "Spoken translation not intelligible: recovered too few content words. expected=$expectedWords heard=$heardWords overlap=$overlap",
        overlap.size >= 2,
      )
    } finally {
      whisper.cleanup()
      translation.cleanup()
      kokoro.cleanup()
    }
  }

  private fun assertSpoken(audio: SynthesizedAudio?) {
    assertNotNull("Kokoro produced no spoken audio", audio)
    val s = audio!!.samples
    assertTrue("Spoken audio invalid sample rate ${audio.sampleRate}", audio.sampleRate > 0)
    assertTrue("Spoken audio empty", s.isNotEmpty())
    val peak = s.maxOfOrNull { abs(it) } ?: 0f
    var sq = 0.0; for (v in s) sq += v.toDouble() * v.toDouble()
    val rms = sqrt(sq / s.size)
    val dur = s.size.toFloat() / audio.sampleRate
    Log.i(TAG, "spoken: samples=${s.size} sr=${audio.sampleRate} dur=${dur}s peak=$peak rms=$rms")
    assertTrue("Spoken audio too short: ${dur}s", dur >= 0.5f)
    assertTrue("Spoken audio silent: peak=$peak rms=$rms", peak > 0.02f && rms > 0.005)
  }

  private fun ensureReady(context: Context, ids: List<String>) {
    ids.forEach { id ->
      if (BaoTranslateModelManager.checkModelStatus(context, id) != ModelStatus.Ready) {
        val r = runBlocking { BaoTranslateModelManager.downloadModel(context, id, wifiOnly = false) }
        assertTrue("Failed to download $id: ${r.exceptionOrNull()?.message}", r.isSuccess)
      }
      assertTrue("$id not ready", BaoTranslateModelManager.checkModelStatus(context, id) == ModelStatus.Ready)
    }
  }

  private fun synthesizeEnglish16k(context: Context, text: String): ShortArray {
    val initLatch = CountDownLatch(1)
    var initStatus = TextToSpeech.ERROR
    val tts = TextToSpeech(context.applicationContext) { s -> initStatus = s; initLatch.countDown() }
    try {
      assertTrue("Platform TTS init failed", initLatch.await(30, TimeUnit.SECONDS) && initStatus == TextToSpeech.SUCCESS)
      assertTrue("No US English voice", tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE)
      tts.language = Locale.US
      tts.setSpeechRate(0.9f)
      val uid = "bao-spoken-input"
      val f = File(context.cacheDir, "$uid.wav")
      if (f.exists()) f.delete()
      val done = CountDownLatch(1)
      var err: String? = null
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(u: String?) = Unit
        override fun onDone(u: String?) = done.countDown()
        @Deprecated("Deprecated in Android framework")
        override fun onError(u: String?) { err = "tts error"; done.countDown() }
        override fun onError(u: String?, code: Int) { err = "tts error $code"; done.countDown() }
      })
      val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid) }
      assertTrue("TTS rejected synthesis", tts.synthesizeToFile(text, params, f, uid) == TextToSpeech.SUCCESS)
      assertTrue("TTS did not finish; err=$err", done.await(60, TimeUnit.SECONDS) && err == null)
      val bytes = f.readBytes()
      assertTrue("Invalid input WAV", WavUtils.isValidWav(bytes))
      val rate = WavUtils.extractSampleRateFromWav(bytes) ?: 0
      assertTrue("Bad input sample rate $rate", rate > 0)
      return resampleToShort16k(WavUtils.extractSamplesFromWav(bytes), rate)
    } finally {
      tts.shutdown()
    }
  }

  private fun resampleToShort16k(samples: FloatArray, srcRate: Int): ShortArray {
    val dst = 16000
    if (samples.isEmpty()) return ShortArray(0)
    if (srcRate == dst) return ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    // Anti-alias: low-pass below the destination Nyquist before decimating, otherwise downsampling
    // folds >8 kHz energy back as audible distortion and the ASR mishears the speech.
    val src = if (srcRate > dst) lowPass(samples, srcRate, dst * 0.45) else samples
    val outLen = (src.size.toLong() * dst / srcRate).toInt()
    return ShortArray(outLen) { i ->
      val pos = i.toDouble() * srcRate / dst
      val i0 = pos.toInt(); val i1 = (i0 + 1).coerceAtMost(src.size - 1); val frac = (pos - i0).toFloat()
      ((src[i0] * (1f - frac) + src[i1] * frac).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
  }

  /** Hamming-windowed-sinc FIR low-pass at [cutoffHz], zero-phase (centered) convolution. */
  private fun lowPass(x: FloatArray, sampleRate: Int, cutoffHz: Double): FloatArray {
    val taps = 64
    val fc = cutoffHz / sampleRate // normalized cutoff (cycles/sample)
    val h = DoubleArray(taps + 1)
    var sum = 0.0
    for (i in 0..taps) {
      val n = i - taps / 2.0
      val sinc = if (n == 0.0) 2.0 * fc else Math.sin(2.0 * Math.PI * fc * n) / (Math.PI * n)
      val w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / taps) // Hamming
      h[i] = sinc * w
      sum += h[i]
    }
    for (i in h.indices) h[i] /= sum // unity DC gain
    val half = taps / 2
    return FloatArray(x.size) { idx ->
      var acc = 0.0
      for (k in 0..taps) {
        val j = idx + k - half
        if (j in x.indices) acc += x[j] * h[k]
      }
      acc.toFloat()
    }
  }

  private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
    val pcm = ByteArray(samples.size * 2)
    for (i in samples.indices) {
      val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
      pcm[i * 2] = (s and 0xFF).toByte(); pcm[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
    }
    fun i32(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
    fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    file.outputStream().use { o ->
      o.write("RIFF".toByteArray()); o.write(i32(36 + pcm.size)); o.write("WAVE".toByteArray())
      o.write("fmt ".toByteArray()); o.write(i32(16)); o.write(i16(1)); o.write(i16(1))
      o.write(i32(sampleRate)); o.write(i32(sampleRate * 2)); o.write(i16(2)); o.write(i16(16))
      o.write("data".toByteArray()); o.write(i32(pcm.size)); o.write(pcm)
    }
  }

  private companion object {
    const val TAG = "BaoSpokenTransE2E"
  }
}
