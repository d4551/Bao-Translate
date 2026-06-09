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
 *
 * Engine selection uses a ranked fallback chain: if the best-scored engine fails to initialize or
 * lacks the requested language, the next engine is tried. Per-language speech rate tuning compensates
 * for platform TTS engines that speak CJK/Arabic too fast or too slow by default.
 */
/**
 * Scored TTS engine entry: package name + quality score for a given language.
 * Higher score = better language support.
 */
private data class ScoredEngine(val packageName: String, val score: Int)

class PlatformTtsPipeline(private val context: Context) {

  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var ready = false
  @Volatile private var selectedEnginePackage: String? = null

  // Cache of ranked engine list per language so scoring is only done once per session.
  private val engineCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
  // Cache of language availability scores per (enginePackage, languageCode) to avoid re-probing.
  private val probeCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

  // Serializes init, synthesis, and cleanup against one another. The shared [TextToSpeech] engine
  // holds a SINGLE UtteranceProgressListener and a single mutable `language`, so two overlapping
  // synthesizeAudio calls (the live recording path under segmentProcessingMutex and the BLE-peer
  // path on Dispatchers.IO) would clobber each other's listener — the victim's `done` latch is
  // never counted down and it stalls the full 30s, then falsely reports failure. Holding this lock
  // for the whole synthesis guarantees exactly one utterance owns the listener + language at a time,
  // and also prevents cleanup() from shutting the engine down mid-synthesis (use-after-free).
  private val engineLock = Any()

  /**
   * Queries all installed TTS engines and returns them ranked by quality for [languageCode].
   * Prefers: Google TTS > Samsung TTS > other engines with full locale data.
   * The ranked list enables fallback: if the best engine fails init or lacks the language,
   * the next one is tried.
   */
  private fun probeLanguageScore(enginePackage: String, locale: Locale): Int {
    val cacheKey = "$enginePackage|${locale.toLanguageTag()}"
    probeCache[cacheKey]?.let { return it }
    val engine = tryInitEngine(enginePackage) ?: return 0
    val score = when (engine.isLanguageAvailable(locale)) {
      TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> 200
      TextToSpeech.LANG_COUNTRY_AVAILABLE -> 150
      TextToSpeech.LANG_AVAILABLE -> 100
      else -> 0
    }
    engine.shutdown()
    probeCache[cacheKey] = score
    return score
  }

  private fun rankEngines(languageCode: String): List<String> {
    engineCache[languageCode]?.let { return it }

    val probe = tryInitEngine(null) ?: run {
      engineCache[languageCode] = emptyList()
      return emptyList()
    }
    val engines = probe.engines ?: emptyList()
    probe.shutdown()
    if (engines.isEmpty()) {
      engineCache[languageCode] = emptyList()
      return emptyList()
    }

    val locale = Locale.forLanguageTag(languageCode)
    val ranked = engines.map { info ->
      val packageName = info.name
      val baseScore = when {
        packageName.contains("google") -> 300
        packageName.contains("samsung") -> 200
        packageName.contains("com.svox") -> 150
        else -> 100
      }
      val localeScore = if (info.label?.contains(locale.displayLanguage, ignoreCase = true) == true) 50 else 0
      val langScore = probeLanguageScore(packageName, locale)
      ScoredEngine(packageName, baseScore + localeScore + langScore)
    }.sortedByDescending { it.score }.map { it.packageName }

    engineCache[languageCode] = ranked
    BaoLog.i(TAG, "Ranked ${ranked.size} TTS engines for '$languageCode': ${ranked.joinToString()}")
    return ranked
  }

  /**
   * Per-language speech rate multiplier. Platform TTS engines often speak CJK and Arabic scripts
   * too fast (syllable-timing differs from Latin), and some European languages sound better slightly
   * slower for comprehension. Returns a multiplier applied via [TextToSpeech.setSpeechRate].
   * Clamped to [MIN_SPEECH_RATE, MAX_SPEECH_RATE] to prevent engine rejection.
   */
  private fun speechRateForLanguage(languageCode: String): Float {
    val raw = when (languageCode.substringBefore('-')) {
      "ja", "ko", "zh" -> 0.85f  // CJK: slower for clarity
      "ar" -> 0.90f               // Arabic: slightly slower
      "de", "ru" -> 0.95f         // German/Russian: marginally slower for consonant clusters
      else -> 1.0f
    }
    return raw.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
  }

  /**
   * Tries to initialize a [TextToSpeech] engine from [enginePackage]. Returns the engine on success,
   * null on failure. The engine is NOT assigned to [tts] — the caller decides after verifying
   * language availability.
   */
  private fun tryInitEngine(enginePackage: String?): TextToSpeech? {
    val latch = CountDownLatch(1)
    var status = TextToSpeech.ERROR
    val engine = if (enginePackage != null) {
      TextToSpeech(context.applicationContext, { s -> status = s; latch.countDown() }, enginePackage)
    } else {
      TextToSpeech(context.applicationContext) { s -> status = s; latch.countDown() }
    }
    val ok = latch.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS
    if (!ok) {
      engine.shutdown()
      BaoLog.w(TAG, "Platform TTS engine init failed (status=$status engine=$enginePackage)")
      return null
    }
    return engine
  }

  fun ensureReady(languageCode: String? = null): Boolean {
    // Fast path: engine already initialized AND supports the requested language.
    if (ready && languageCode != null) {
      val engine = tts
      if (engine != null && engine.isLanguageAvailable(Locale.forLanguageTag(languageCode)) >= TextToSpeech.LANG_AVAILABLE) {
        return true
      }
      // Engine is ready but doesn't speak this language — shut down and re-init with fallback chain.
      BaoLog.i(TAG, "Current engine lacks voice for '$languageCode', trying fallback chain")
      synchronized(engineLock) {
        tts?.shutdown()
        tts = null
        ready = false
        selectedEnginePackage = null
      }
    } else if (ready) {
      return true
    }
    synchronized(engineLock) {
      return ensureReadyLocked(languageCode)
    }
  }

  /**
   * Internal: initializes the TTS engine with fallback chain. MUST be called while holding
   * [engineLock]. Tries ranked engines in order, verifying language availability before committing.
   */
  private fun ensureReadyLocked(languageCode: String?): Boolean {
    if (ready) return true

    val ranked = languageCode?.let { rankEngines(it) } ?: emptyList()
    for (enginePackage in ranked) {
      val engine = tryInitEngine(enginePackage) ?: continue
      if (languageCode != null &&
        engine.isLanguageAvailable(Locale.forLanguageTag(languageCode)) < TextToSpeech.LANG_AVAILABLE
      ) {
        BaoLog.i(TAG, "Engine '$enginePackage' lacks voice for '$languageCode', trying next")
        engine.shutdown()
        continue
      }
      engine.language = Locale.forLanguageTag(languageCode ?: "en")
      engine.setSpeechRate(speechRateForLanguage(languageCode ?: "en"))
      tts = engine
      ready = true
      selectedEnginePackage = enginePackage
      BaoLog.i(TAG, "Platform TTS ready (engine=$enginePackage lang=$languageCode rate=${speechRateForLanguage(languageCode ?: "en")})")
      return true
    }

    // Final fallback: system default engine (null package).
    val engine = tryInitEngine(null) ?: return false
    if (languageCode != null &&
      engine.isLanguageAvailable(Locale.forLanguageTag(languageCode)) < TextToSpeech.LANG_AVAILABLE
    ) {
      BaoLog.w(TAG, "System default TTS engine also lacks voice for '$languageCode'")
      engine.shutdown()
      return false
    }
    engine.language = Locale.forLanguageTag(languageCode ?: "en")
    engine.setSpeechRate(speechRateForLanguage(languageCode ?: "en"))
    tts = engine
    ready = true
    selectedEnginePackage = null
    BaoLog.i(TAG, "Platform TTS ready (system default lang=$languageCode)")
    return true
  }

  /** Returns true if the device platform TTS can speak [languageCode] (e.g. "de", "ko", "ru"). */
  fun isLanguageAvailable(languageCode: String): Boolean {
    if (!ensureReady(languageCode)) return false
    val engine = tts ?: return false
    return engine.isLanguageAvailable(Locale.forLanguageTag(languageCode)) >= TextToSpeech.LANG_AVAILABLE
  }

  /** Synthesizes [text] in [languageCode] to PCM. Returns null if unavailable or synthesis failed.
   *  [speed] overrides the per-language default speech rate when non-null. */
  fun synthesizeAudio(text: String, languageCode: String, speed: Float? = null): SynthesizedAudio? = synchronized(engineLock) {
    if (text.isBlank()) return null
    // ensureReady acquires engineLock (reentrant — same thread), but calling it here is a code
    // smell. Inline the fast-path check; the slow path (engine init) is handled by the caller
    // or by the first synthesizeAudio call after a language switch.
    if (!ready) {
      if (!ensureReadyLocked(languageCode)) return null
    } else {
      val engine = tts
      if (engine != null && engine.isLanguageAvailable(Locale.forLanguageTag(languageCode)) < TextToSpeech.LANG_AVAILABLE) {
        // Engine doesn't speak this language — re-init with fallback chain.
        tts?.shutdown(); tts = null; ready = false; selectedEnginePackage = null
        if (!ensureReadyLocked(languageCode)) return null
      }
    }
    val engine = tts ?: return null
    val locale = Locale.forLanguageTag(languageCode)
    if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
      BaoLog.w(TAG, "Platform TTS has no voice for '$languageCode'")
      return null
    }
    engine.language = locale
    val effectiveRate = (speed ?: speechRateForLanguage(languageCode)).coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    engine.setSpeechRate(effectiveRate)

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

  private companion object {
    const val TAG = "PlatformTtsPipeline"
    // Android TextToSpeech.setSpeechRate() accepts >= 0; clamp to sane bounds to prevent
    // engine rejection or unintelligible output from future code changes.
    const val MIN_SPEECH_RATE = 0.5f
    const val MAX_SPEECH_RATE = 2.0f
  }
}
