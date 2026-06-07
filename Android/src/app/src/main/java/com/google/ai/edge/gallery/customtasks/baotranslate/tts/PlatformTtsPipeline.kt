package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fallback text-to-speech using the device's on-device platform TTS (Android [TextToSpeech]) for
 * languages Kokoro cannot voice (e.g. German, Korean, Russian, Arabic). Without this, those targets
 * would be spoken with an English Kokoro voice — i.e. unintelligible foreign text in the wrong
 * accent. Produces a [SynthesizedAudio] so it flows through the same audio-routing/playback path.
 *
 * No voice cloning here (the OpenVoice converter only re-times Kokoro output); these languages get a
 * correct, generic device voice — a clear improvement over wrong-language audio.
 */
class PlatformTtsPipeline(private val context: Context) {

  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var ready = false
  private val initLock = Any()

  fun ensureReady(): Boolean {
    if (ready) return true
    synchronized(initLock) {
      if (ready) return true
      val latch = CountDownLatch(1)
      var status = TextToSpeech.ERROR
      val engine = TextToSpeech(context.applicationContext) { s -> status = s; latch.countDown() }
      val ok = latch.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS
      if (!ok) {
        engine.shutdown()
        BaoLog.w(TAG, "Platform TTS failed to initialize (status=$status)")
        return false
      }
      tts = engine
      ready = true
      BaoLog.i(TAG, "Platform TTS ready")
      return true
    }
  }

  /** Returns true if the device platform TTS can speak [languageCode] (e.g. "de", "ko", "ru"). */
  fun isLanguageAvailable(languageCode: String): Boolean {
    if (!ensureReady()) return false
    val engine = tts ?: return false
    return engine.isLanguageAvailable(Locale.forLanguageTag(languageCode)) >= TextToSpeech.LANG_AVAILABLE
  }

  /** Synthesizes [text] in [languageCode] to PCM. Returns null if unavailable or synthesis failed. */
  fun synthesizeAudio(text: String, languageCode: String): SynthesizedAudio? {
    if (text.isBlank() || !ensureReady()) return null
    val engine = tts ?: return null
    val locale = Locale.forLanguageTag(languageCode)
    if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
      BaoLog.w(TAG, "Platform TTS has no voice for '$languageCode'")
      return null
    }
    engine.language = locale

    val uid = "bao-platform-tts-${System.nanoTime()}"
    val outFile = File(context.cacheDir, "$uid.wav")
    if (outFile.exists()) outFile.delete()

    val done = CountDownLatch(1)
    var failed = false
    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit
      override fun onDone(utteranceId: String?) = done.countDown()
      @Deprecated("Deprecated in Android framework")
      override fun onError(utteranceId: String?) { failed = true; done.countDown() }
      override fun onError(utteranceId: String?, errorCode: Int) { failed = true; done.countDown() }
    })

    val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid) }
    if (engine.synthesizeToFile(text, params, outFile, uid) != TextToSpeech.SUCCESS) {
      BaoLog.w(TAG, "Platform TTS rejected synthesis for '$languageCode'")
      return null
    }
    val finished = done.await(30, TimeUnit.SECONDS)
    if (!finished || failed || !outFile.exists()) {
      BaoLog.w(TAG, "Platform TTS synthesis did not complete for '$languageCode' (finished=$finished failed=$failed)")
      return null
    }

    val bytes = outFile.readBytes()
    outFile.delete()
    if (!WavUtils.isValidWav(bytes)) return null
    val rate = WavUtils.extractSampleRateFromWav(bytes) ?: return null
    val samples = WavUtils.extractSamplesFromWav(bytes)
    if (samples.isEmpty()) return null
    return SynthesizedAudio(samples = samples, sampleRate = rate)
  }

  fun cleanup() {
    synchronized(initLock) {
      tts?.shutdown()
      tts = null
      ready = false
    }
  }

  private companion object { const val TAG = "PlatformTtsPipeline" }
}
