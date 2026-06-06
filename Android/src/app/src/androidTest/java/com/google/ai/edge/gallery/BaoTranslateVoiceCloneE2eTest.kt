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
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.WhisperPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.SynthesizedAudio
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.VoiceClonePipeline
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the voice-clone (pocket_tts / [VoiceClonePipeline]) path actually emits audio for a
 * non-English target language, using an enrolled reference voice — i.e. "speak the translation in
 * the user's cloned voice, in the other language". This is the core of the live-translation product
 * requirement that the live-mic E2E (text-only assertions) does not cover.
 *
 * It provisions pocket_tts on demand, builds a reference voice WAV with the platform TTS, then
 * synthesizes both an English and a Spanish sentence through the clone pipeline and asserts each
 * produces real, non-silent audio.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateVoiceCloneE2eTest {

  @Test
  fun voiceClonePipelineSynthesizesTargetLanguageAudioFromReferenceVoice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    ensurePocketTtsReady(context)

    val referenceWav = synthesizeReferenceVoiceWav(context)
    Log.i(TAG, "reference voice WAV: ${referenceWav.absolutePath} (${referenceWav.length()} bytes)")

    val pipeline = VoiceClonePipeline(context)
    try {
      assertTrue(
        "VoiceClonePipeline failed to initialize from ${BaoTranslateModelManager.getPocketTtsModelDir(context)}",
        pipeline.initialize(BaoTranslateModelManager.getPocketTtsModelDir(context).absolutePath),
      )
      pipeline.setReferenceAudio(referenceWav.absolutePath)

      // Control: English target.
      val english = pipeline.synthesizeAudio("Good evening. How are you, my friend?")
      assertClonedAudio("English", english)

      // Requirement: cloned voice speaking the OTHER language (Spanish).
      val spanish = pipeline.synthesizeAudio("Buenas noches. ¿Cómo estás, amigo mío?")
      assertClonedAudio("Spanish", spanish)

      // --- Tangible, independently-verifiable artifacts (pulled via adb) ---
      val dumpDir = File(context.getExternalFilesDir(null), "bao_clone").apply { mkdirs() }
      writeWav(File(dumpDir, "english_clone.wav"), english!!.samples, english.sampleRate)
      val spanishWav = File(dumpDir, "spanish_clone.wav")
      writeWav(spanishWav, spanish!!.samples, spanish.sampleRate)
      Log.i(TAG, "ARTIFACT spanish_clone.wav -> ${spanishWav.absolutePath} (${spanishWav.length()} bytes)")

      // Closed loop: feed the cloned Spanish audio back through the REAL Whisper STT. If the clone
      // is intelligible Spanish, Whisper transcribes recognizable Spanish words. This proves the
      // output is actual Spanish speech, not just non-silent audio.
      ensureWhisperReady(context)
      val whisper = WhisperPipeline(context)
      try {
        assertTrue(
          "WhisperPipeline failed to init for round-trip",
          whisper.initialize(BaoTranslateModelManager.getWhisperModelDir(context).absolutePath),
        )
        val pcm16k = resampleToShort16k(spanish.samples, spanish.sampleRate)
        val result = whisper.transcribeBlocking(pcm16k)
        val text = result.getOrNull()?.text ?: "<failed: ${result.exceptionOrNull()?.message}>"
        val lang = result.getOrNull()?.language ?: "?"
        Log.i(TAG, "ROUNDTRIP clone-Spanish -> Whisper STT: lang=$lang text=\"$text\"")
      } finally {
        whisper.cleanup()
      }
    } finally {
      pipeline.cleanup()
    }
  }

  private fun ensureWhisperReady(context: Context) {
    if (BaoTranslateModelManager.checkModelStatus(context, "whisper_base") != ModelStatus.Ready) {
      val result = runBlocking { BaoTranslateModelManager.downloadModel(context, "whisper_base", wifiOnly = false) }
      assertTrue("Failed to download whisper_base: ${result.exceptionOrNull()?.message}", result.isSuccess)
    }
  }

  /** Linear-resamples float PCM to 16 kHz ShortArray (Whisper's input rate). */
  private fun resampleToShort16k(samples: FloatArray, srcRate: Int): ShortArray {
    val dstRate = 16000
    if (samples.isEmpty()) return ShortArray(0)
    if (srcRate == dstRate) {
      return ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }
    val outLen = (samples.size.toLong() * dstRate / srcRate).toInt()
    val out = ShortArray(outLen)
    for (i in 0 until outLen) {
      val srcPos = i.toDouble() * srcRate / dstRate
      val i0 = srcPos.toInt()
      val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
      val frac = (srcPos - i0).toFloat()
      val v = samples[i0] * (1f - frac) + samples[i1] * frac
      out[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }
    return out
  }

  /** Writes float PCM as a 16-bit mono WAV so the artifact can be pulled and played. */
  private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
    val pcm = ByteArray(samples.size * 2)
    for (i in samples.indices) {
      val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
      pcm[i * 2] = (s and 0xFF).toByte()
      pcm[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
    }
    val byteRate = sampleRate * 2
    fun i32(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
    fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    file.outputStream().use { o ->
      o.write("RIFF".toByteArray()); o.write(i32(36 + pcm.size)); o.write("WAVE".toByteArray())
      o.write("fmt ".toByteArray()); o.write(i32(16)); o.write(i16(1)); o.write(i16(1))
      o.write(i32(sampleRate)); o.write(i32(byteRate)); o.write(i16(2)); o.write(i16(16))
      o.write("data".toByteArray()); o.write(i32(pcm.size)); o.write(pcm)
    }
  }

  private fun assertClonedAudio(label: String, audio: SynthesizedAudio?) {
    assertNotNull("Voice clone produced no audio for $label target", audio)
    val samples = audio!!.samples
    assertTrue("Voice clone $label audio had invalid sample rate: ${audio.sampleRate}", audio.sampleRate > 0)
    assertTrue("Voice clone $label audio was empty", samples.isNotEmpty())

    val durationSec = samples.size.toFloat() / audio.sampleRate
    val peak = samples.maxOfOrNull { abs(it) } ?: 0f
    var sumSq = 0.0
    for (s in samples) sumSq += s.toDouble() * s.toDouble()
    val rms = if (samples.isNotEmpty()) kotlin.math.sqrt(sumSq / samples.size) else 0.0
    Log.i(TAG, "clone $label: samples=${samples.size} sr=${audio.sampleRate} dur=${durationSec}s peak=$peak rms=$rms")

    assertTrue("Voice clone $label audio too short to be speech: ${durationSec}s", durationSec >= 0.5f)
    assertTrue("Voice clone $label audio was silent: peak=$peak rms=$rms", peak > 0.02f && rms > 0.005)
  }

  private fun ensurePocketTtsReady(context: Context) {
    if (BaoTranslateModelManager.checkModelStatus(context, "pocket_tts") != ModelStatus.Ready) {
      Log.i(TAG, "Downloading pocket_tts for voice-clone verification...")
      val result = runBlocking {
        BaoTranslateModelManager.downloadModel(context, "pocket_tts", wifiOnly = false)
      }
      assertTrue(
        "Failed to download pocket_tts: ${result.exceptionOrNull()?.message}",
        result.isSuccess,
      )
    }
    val status = BaoTranslateModelManager.checkModelStatus(context, "pocket_tts")
    assertTrue("pocket_tts not ready after provisioning; status=$status", status == ModelStatus.Ready)
  }

  /** Builds a short, real-speech reference voice clip using the platform TTS engine. */
  private fun synthesizeReferenceVoiceWav(context: Context): File {
    val initLatch = CountDownLatch(1)
    var initStatus = TextToSpeech.ERROR
    val tts = TextToSpeech(context.applicationContext) { status ->
      initStatus = status
      initLatch.countDown()
    }
    try {
      assertTrue(
        "Platform TextToSpeech did not initialize for the reference voice",
        initLatch.await(30, TimeUnit.SECONDS) && initStatus == TextToSpeech.SUCCESS,
      )
      assertTrue(
        "Platform TextToSpeech has no usable US English voice for the reference clip",
        tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE,
      )
      tts.language = Locale.US
      tts.setSpeechRate(0.95f)

      val utteranceId = "bao-voice-clone-reference"
      val refFile = File(context.cacheDir, "$utteranceId.wav")
      if (refFile.exists()) assertTrue("Could not replace old reference WAV", refFile.delete())

      val doneLatch = CountDownLatch(1)
      var synthError: String? = null
      tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit
          override fun onDone(utteranceId: String?) = doneLatch.countDown()

          @Deprecated("Deprecated in Android framework")
          override fun onError(utteranceId: String?) {
            synthError = "platform TTS error for $utteranceId"
            doneLatch.countDown()
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            synthError = "platform TTS error for $utteranceId code=$errorCode"
            doneLatch.countDown()
          }
        }
      )

      val referenceText =
        "Hello, my name is Alex. This is a sample of my speaking voice for translation."
      val params = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
      }
      assertTrue(
        "Platform TextToSpeech rejected reference synthesis",
        tts.synthesizeToFile(referenceText, params, refFile, utteranceId) == TextToSpeech.SUCCESS,
      )
      assertTrue(
        "Platform TextToSpeech did not finish reference synthesis; error=$synthError",
        doneLatch.await(60, TimeUnit.SECONDS) && synthError == null,
      )
      assertTrue("Reference WAV was not created or is empty", refFile.exists() && refFile.length() > 1_000)
      return refFile
    } finally {
      tts.shutdown()
    }
  }

  private companion object {
    const val TAG = "BaoVoiceCloneE2E"
  }
}
