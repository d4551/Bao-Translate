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

  // Serializes init, synthesis, and cleanup against one another. The shared [TextToSpeech] engine
  // holds a SINGLE UtteranceProgressListener and a single mutable `language`, so two overlapping
  // synthesizeAudio calls (the live recording path under segmentProcessingMutex and the BLE-peer
  // path on Dispatchers.IO) would clobber each other's listener — the victim's `done` latch is
  // never counted down and it stalls the full 30s, then falsely reports failure. Holding this lock
  // for the whole synthesis guarantees exactly one utterance owns the listener + language at a time,
  // and also prevents cleanup() from shutting the engine down mid-synthesis (use-after-free).
  private val engineLock = Any()

  fun ensureReady(): Boolean {
    if (ready) return true
    synchronized(engineLock) {
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
  fun synthesizeAudio(text: String, languageCode: String): SynthesizedAudio? = synchronized(engineLock) {
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
    // Count the latch down only for OUR utterance id. The lock already guarantees no concurrent
    // synth, but a previous call that timed out at 30s could still deliver a late callback after
    // its listener was replaced; the uid match makes that stale callback inert instead of
    // prematurely releasing this call. Android delivers the same utteranceId passed below.
    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit
      override fun onDone(utteranceId: String?) { if (utteranceId == uid) done.countDown() }
      @Deprecated("Deprecated in Android framework")
      override fun onError(utteranceId: String?) { if (utteranceId == uid) { failed = true; done.countDown() } }
      override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == uid) { failed = true; done.countDown() } }
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
    synchronized(engineLock) {
      tts?.shutdown()
      tts = null
      ready = false
    }
  }

  private companion object { const val TAG = "PlatformTtsPipeline" }
}
