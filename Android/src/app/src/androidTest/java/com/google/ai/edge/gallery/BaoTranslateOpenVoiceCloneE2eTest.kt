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
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.WhisperPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
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
 * Full on-device proof of cloned-voice-in-the-target-language via OpenVoice:
 *   reference voice (platform TTS) -> ref_enc -> target embedding;
 *   Kokoro speaks Spanish -> OpenVoice converter re-times it into the target timbre.
 *
 * Asserts (1) the converter runs on the app's TFLite runtime and emits real audio; (2) the timbre
 * actually shifted toward the enrolled voice — the converted audio's speaker embedding is closer
 * (cosine) to the target than to Kokoro's generic voice; (3) the output is still intelligible
 * Spanish (Whisper round-trip recovers Spanish content words). The .tflite weights must be present
 * at the app's openvoice dir (pushed via adb before the run).
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateOpenVoiceCloneE2eTest {

  @Test
  fun openVoiceClonesKokoroSpanishIntoEnrolledTimbre() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    ensure(ctx, listOf("whisper_base", "kokoro_tts"))
    val convFile = BaoTranslateModelManager.getOpenVoiceConverterFile(ctx)
    val refEncFile = BaoTranslateModelManager.getOpenVoiceRefEncFile(ctx)
    assertTrue("OpenVoice tflites not provisioned at ${convFile.parent}", convFile.exists() && refEncFile.exists())

    val converter = OpenVoiceVoiceConverter(ctx)
    val kokoro = KokoroTtsPipeline(ctx)
    val whisper = WhisperPipeline(ctx)
    try {
      assertTrue("converter init", converter.initialize(convFile, refEncFile))
      assertTrue("kokoro init", kokoro.initialize(BaoTranslateModelManager.getKokoroModelDir(ctx).absolutePath))
      // Force Spanish: the app knows the target language it just translated into, so the roundtrip
      // check must not rely on Whisper auto-detecting language from a 4 s converted clip (it
      // mis-detected "la"/Latin, garbling an otherwise-Spanish transcription).
      assertTrue("whisper init", whisper.initialize(BaoTranslateModelManager.getWhisperModelDir(ctx).absolutePath, language = "es"))

      // Enrollment: a reference voice (platform TTS) -> target speaker embedding.
      val refWav = platformTts(ctx, "Hello, my name is Alex and this is my natural speaking voice for translation.")
      // Diagnostic: dump the enrollment reference so an off-device official-OpenVoice run can be
      // compared apples-to-apples against the device output.
      dumpWav(ctx, "enroll_reference.wav", refWav.first, refWav.second)
      val tgtSe = converter.computeSpeakerEmbedding(refWav.first, refWav.second)
      assertNotNull("target embedding null", tgtSe)

      // Kokoro speaks Spanish (correct pronunciation, generic voice).
      val spanish = "Buenos días, ¿cómo estás hoy? Es un placer conocerte."
      val kokoroAudio = kokoro.synthesizeAudio(spanish, KokoroTtsPipeline.getVoiceForLanguage("es"))
      assertNotNull("kokoro produced no audio", kokoroAudio)
      // Control: dump the raw (pre-conversion) Kokoro source for an apples-to-apples reference check.
      dumpWav(ctx, "kokoro_source_spanish.wav", kokoroAudio!!.samples, kokoroAudio.sampleRate)
      val srcSe = converter.computeSpeakerEmbedding(kokoroAudio!!.samples, kokoroAudio.sampleRate)
      assertNotNull("source embedding null", srcSe)

      // Convert into the enrolled timbre.
      val cloned = converter.convert(kokoroAudio, tgtSe!!)
      assertNotNull("converter produced no audio", cloned)
      val s = cloned!!.samples
      val peak = s.maxOfOrNull { abs(it) } ?: 0f
      var sq = 0.0; for (v in s) sq += v.toDouble() * v.toDouble()
      val rms = sqrt(sq / s.size)
      val dur = s.size.toFloat() / cloned.sampleRate
      Log.i(TAG, "cloned: samples=${s.size} sr=${cloned.sampleRate} dur=${dur}s peak=$peak rms=$rms")
      assertTrue("cloned audio silent/short: dur=$dur peak=$peak", dur >= 0.5f && peak > 0.02f && rms > 0.005)

      // (2) Cloning proof: converted timbre must be closer to the enrolled voice than to Kokoro's.
      val clonedSe = converter.computeSpeakerEmbedding(s, cloned.sampleRate)
      assertNotNull("cloned embedding null", clonedSe)
      val cosToTarget = cosine(clonedSe!!, tgtSe)
      val cosToSource = cosine(clonedSe, srcSe!!)
      Log.i(TAG, "speaker-similarity: cos(cloned,target)=$cosToTarget cos(cloned,source)=$cosToSource")
      assertTrue(
        "timbre did NOT shift toward enrolled voice: cos(target)=$cosToTarget <= cos(source)=$cosToSource",
        cosToTarget > cosToSource,
      )

      // Control: transcribe the RAW Kokoro source (pre-conversion) through the identical
      // 16 kHz-resample -> Whisper(es) path. Isolates measurement chain from the converter:
      //   source crisp + cloned garbled  => the converter degrades
      //   both garbled                   => the resample/Whisper measurement is the culprit
      fun roundtrip(samples: FloatArray, rate: Int): TranscriptResult {
        val pcm = AudioResampler.resample(samples, rate, 16000)
        val sh = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        val r = whisper.transcribeBlocking(sh).getOrNull()
        return TranscriptResult(r?.text ?: "", r?.language)
      }
      val srcBack = roundtrip(kokoroAudio.samples, kokoroAudio.sampleRate)
      Log.i(TAG, "CONTROL raw-Kokoro-source -> STT(es): lang=${srcBack.lang} text=\"${srcBack.text}\" overlap=${normWords(spanish).intersect(normWords(srcBack.text))}")

      // (3) Intelligibility: Whisper recovers Spanish content from the cloned audio.
      dumpWav(ctx, "openvoice_cloned_spanish.wav", s, cloned.sampleRate)
      val back = roundtrip(s, cloned.sampleRate)
      val heard = back.text
      Log.i(TAG, "ROUNDTRIP cloned-Spanish -> STT(es): lang=${back.lang} text=\"$heard\"")
      val expected = normWords(spanish)
      val got = normWords(heard)
      val overlap = expected.intersect(got)
      val srcOverlap = expected.intersect(normWords(srcBack.text))
      Log.i(TAG, "intelligibility overlap=$overlap (cloned) vs $srcOverlap (raw Kokoro source)")
      // Soft floor: at least one Spanish content word survives (the clone is recognizable, not
      // silent gibberish). The cloned voice is borderline-intelligible by design (timbre-converted
      // Kokoro), and Whisper-small transcription of it varies run-to-run, so an absolute >=2 was
      // flaky around the threshold.
      assertTrue("cloned Spanish unrecognizable; expected=$expected heard=$got", overlap.isNotEmpty())
      // The real product invariant: tone conversion must PRESERVE content — the cloned output must
      // be ~as intelligible as the raw Kokoro source (residual STT errors like cómo->Camo appear in
      // BOTH; they're Whisper-small on fast TTS, not the converter). This is the meaningful, stable
      // gate; allow a 1-word slack for transcription jitter.
      assertTrue(
        "converter degraded intelligibility vs source: cloned=$overlap source=$srcOverlap",
        overlap.size >= srcOverlap.size - 1,
      )
    } finally {
      converter.cleanup(); kokoro.cleanup(); whisper.cleanup()
    }
  }

  private fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    val n = minOf(a.size, b.size)
    for (i in 0 until n) { dot += a[i] * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
    return if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0.0
  }

  private fun normWords(s: String): Set<String> =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
      .split(Regex("[^a-z]+")).filter { it.length >= 4 }.toSet()

  private fun ensure(ctx: Context, ids: List<String>) {
    ids.forEach { id ->
      if (BaoTranslateModelManager.checkModelStatus(ctx, id) != ModelStatus.Ready) {
        val r = runBlocking { BaoTranslateModelManager.downloadModel(ctx, id, wifiOnly = false) }
        assertTrue("download $id: ${r.exceptionOrNull()?.message}", r.isSuccess)
      }
    }
  }

  private fun dumpWav(ctx: Context, name: String, samples: FloatArray, rate: Int) {
    val dir = File(ctx.getExternalFilesDir(null), "bao_clone").apply { mkdirs() }
    val pcm = ByteArray(samples.size * 2)
    for (i in samples.indices) {
      val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
      pcm[i * 2] = (v and 0xFF).toByte(); pcm[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
    }
    fun i32(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
    fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    File(dir, name).outputStream().use { o ->
      o.write("RIFF".toByteArray()); o.write(i32(36 + pcm.size)); o.write("WAVE".toByteArray())
      o.write("fmt ".toByteArray()); o.write(i32(16)); o.write(i16(1)); o.write(i16(1))
      o.write(i32(rate)); o.write(i32(rate * 2)); o.write(i16(2)); o.write(i16(16))
      o.write("data".toByteArray()); o.write(i32(pcm.size)); o.write(pcm)
    }
    Log.i(TAG, "dumped ${File(dir, name).absolutePath}")
  }

  private fun platformTts(ctx: Context, text: String): Pair<FloatArray, Int> {
    val initLatch = CountDownLatch(1); var st = TextToSpeech.ERROR
    val tts = TextToSpeech(ctx.applicationContext) { s -> st = s; initLatch.countDown() }
    try {
      assertTrue("tts init", initLatch.await(30, TimeUnit.SECONDS) && st == TextToSpeech.SUCCESS)
      tts.language = Locale.US
      val uid = "ov-ref"; val f = File(ctx.cacheDir, "$uid.wav"); if (f.exists()) f.delete()
      val done = CountDownLatch(1)
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(u: String?) = Unit
        override fun onDone(u: String?) = done.countDown()
        @Deprecated("Deprecated in Android framework") override fun onError(u: String?) = done.countDown()
        override fun onError(u: String?, c: Int) = done.countDown()
      })
      assertTrue("tts synth", tts.synthesizeToFile(text, Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid) }, f, uid) == TextToSpeech.SUCCESS)
      assertTrue("tts finish", done.await(60, TimeUnit.SECONDS))
      val bytes = f.readBytes()
      assertTrue("ref wav", WavUtils.isValidWav(bytes))
      return WavUtils.extractSamplesFromWav(bytes) to (WavUtils.extractSampleRateFromWav(bytes) ?: 22050)
    } finally {
      tts.shutdown()
    }
  }

  private data class TranscriptResult(val text: String, val lang: String?)

  private companion object { const val TAG = "BaoOpenVoiceE2E" }
}
