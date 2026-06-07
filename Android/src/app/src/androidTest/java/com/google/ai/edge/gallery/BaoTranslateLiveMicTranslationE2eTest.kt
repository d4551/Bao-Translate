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

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateViewModel
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.PipelineStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioResampler
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleTranscriptMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import androidx.compose.ui.test.hasContentDescription as hasContentDescriptionMatcher

@RunWith(AndroidJUnit4::class)
class BaoTranslateLiveMicTranslationE2eTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()
  private val liveMicScreenshotRunDir =
    "$LIVE_MIC_SCREENSHOT_ROOT/${System.currentTimeMillis()}"

  @get:Rule
  val ruleChain: TestRule =
    RuleChain.outerRule(LiveMicPermissionRule())
      .around(composeRule)

  @Test
  fun injectedSpeechProducesLiveTranslation_beforeStop() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    // Inject the English prompt PCM straight into the live-translation pipeline instead of relying
    // on a speaker->own-mic acoustic loopback (the device echo canceller cancels the app's own
    // playback captured by its own mic, so self-loopback can't drive STT). This exercises the real
    // VAD -> Whisper -> translate -> UI-marker path deterministically. See RecordingController.testPcmSource.
    val promptPcm16k = synthesizeEnglishPromptAsSttPcm(context)
    RecordingController.testPcmSource = promptPcm16k

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )
    assertTrue(
      "Live microphone test must start without stale Spanish translation markers; markerCount=${composeRule.spanishMarkerCount()}",
      composeRule.spanishMarkerCount() == 0,
    )

    var recordingStarted = false
    try {
      composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start))
        .assertIsDisplayed()
        .performClick()
      recordingStarted = true
      composeRule.waitForText(R.string.bao_translate_listening, timeoutMillis = 30_000)
      composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))
        .assertIsDisplayed()

      composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.spanishMarkerCount() >= 0 }
      captureLiveMicScreenshot("live_mic_recording_with_injected_prompt")

      assertTrue(
        "Bao Translate did not show live Spanish translation markers while still recording; markerCount=${composeRule.spanishMarkerCount()}",
        composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 120_000),
      )
    } finally {
      if (recordingStarted && composeRule.hasContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))) {
        composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))
          .performClick()
        composeRule.waitForContentDescription(
          composeRule.stringResource(R.string.cd_bao_translate_start),
          timeoutMillis = 30_000,
        )
      }
      RecordingController.testPcmSource = null
    }

    assertTrue(
      "Bao Translate live transcript did not retain Spanish translation markers after stop; markerCount=${composeRule.spanishMarkerCount()}",
      composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 30_000),
    )
  }

  /** Platform-TTS English prompt resampled to the STT pipeline's 16 kHz mono PCM for injection. */
  private fun synthesizeEnglishPromptAsSttPcm(context: Context): ShortArray {
    val prompt = synthesizeEnglishPrompt(context)
    val resampled = AudioResampler.resample(prompt.samples, prompt.sampleRate, PipelineConfig.STT_SAMPLE_RATE)
    val pcm = ShortArray(resampled.size) { i -> (resampled[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    Log.i(TAG, "injected prompt PCM samples=${pcm.size} @${PipelineConfig.STT_SAMPLE_RATE}Hz")
    return pcm
  }

  /**
   * Real-time MULTI-SPEAKER receive path: a remote peer (English) sends a transcript; the local
   * device (target Spanish) must translate it into Spanish, attribute it to the speaker, and speak
   * it — the conversation-mode routing that turns the app from single-user into multi-party. Driven
   * by injecting a peer message into the live BleConversationManager (no second device needed; the
   * device's own echo canceller makes acoustic multi-device infeasible to automate anyway).
   */
  @Test
  fun receivedPeerMessageIsTranslatedToLocalLanguageAndAttributed() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    // Load the Bao Translate screen (creates the ViewModel + starts model init via LaunchedEffect).
    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    // The record control only appears once required pipelines (incl. translation) are ready.
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )

    val viewModel = BaoTranslateViewModel.testInstance
    assertNotNull("Bao Translate ViewModel was not created", viewModel)

    // A remote English speaker says "Good morning." over the conversation channel.
    val peer = BleTranscriptMessage(
      text = "Good morning.",
      senderId = "peer-test-1",
      senderName = "Alex",
      sourceLanguage = "English",
      targetLanguage = "Spanish",
    )
    runBlocking { viewModel!!.bleManager.simulateIncomingTranscriptForTest(peer) }

    // The local device must surface the peer's words translated into ITS language (Spanish),
    // attributed to the remote speaker (isUser=false, speakerName), preserving the original.
    val deadline = System.currentTimeMillis() + 60_000
    var routed: TranslationMessage? = null
    while (System.currentTimeMillis() < deadline && routed == null) {
      routed = viewModel!!.uiState.value.transcripts.firstOrNull {
        !it.isUser && it.speakerName == "Alex" && it.originalText.contains("Good morning", ignoreCase = true)
      }
      if (routed == null) Thread.sleep(500)
    }
    assertNotNull("Peer message was not routed into the local transcript", routed)
    val spanish = java.text.Normalizer.normalize(routed!!.translatedText, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    Log.i(TAG, "MULTI-SPEAKER routed: speaker=${routed.speakerName} original=\"${routed.originalText}\" translated=\"${routed.translatedText}\"")
    assertTrue(
      "Peer English was not translated to Spanish: \"${routed.translatedText}\"",
      spanish.contains("buen") || spanish.contains("dias") || spanish.contains("manana"),
    )
    assertTrue("Translated text equals the English source (not translated)", !routed.translatedText.equals(peer.text, ignoreCase = true))
  }

  private data class LiveLang(val key: String, val code: String, val locale: Locale, val phrase: String)

  /**
   * Live-mic E2E for EVERY source language: for each, pick it at runtime (-> Whisper re-inits forced
   * to that language), inject real platform-TTS speech in that language through the live recording
   * pipeline, and assert the live transcript shows an English translation. Covers the user-selectable
   * source languages whose speech the device can synthesize for the probe.
   */
  @Test
  fun liveMic_everySourceLanguage_translatesToEnglish_endToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    val langs = listOf(
      LiveLang("Spanish", "es", Locale("es", "ES"), "Buenos días, ¿cómo estás hoy? Es un placer conocerte."),
      LiveLang("French", "fr", Locale.FRANCE, "Bonjour, comment allez-vous aujourd'hui mon ami?"),
      LiveLang("German", "de", Locale.GERMANY, "Guten Morgen, wie geht es dir heute mein Freund?"),
      LiveLang("Italian", "it", Locale.ITALY, "Buongiorno, come stai oggi amico mio?"),
      LiveLang("Russian", "ru", Locale("ru", "RU"), "Доброе утро, как дела сегодня, мой друг?"),
    )

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start), timeoutMillis = 180_000)
    val vm = BaoTranslateViewModel.testInstance
    assertNotNull("Bao Translate ViewModel not created", vm)
    composeRule.runOnUiThread { vm!!.setTargetLanguage("English") }

    val failures = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (l in langs) {
      val pcmF = platformTtsPcm16k(context, l.locale, l.phrase)
      if (pcmF == null) { skipped.add(l.key); Log.w(TAG, "LIVE-EVERY [${l.key}] skipped: no device voice"); continue }

      composeRule.runOnUiThread { vm!!.setSourceLanguage(l.key) }
      waitForSttReady(vm!!)

      RecordingController.testPcmSource = ShortArray(pcmF.size) { (pcmF[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
      composeRule.runOnUiThread { vm.startRecording() }
      val deadline = System.currentTimeMillis() + 90_000
      var english: TranslationMessage? = null
      while (System.currentTimeMillis() < deadline && english == null) {
        // Scope to THIS iteration's language (transcripts accumulate): the message whose source is
        // the current language, target English, with a real (non-echo) translation.
        english = vm.uiState.value.transcripts.firstOrNull { m ->
          m.isUser && m.sourceLanguage == l.code && m.targetLanguage == "en" &&
            m.translatedText.isNotBlank() && !m.translatedText.trim().equals(m.originalText.trim(), ignoreCase = true)
        }
        if (english == null) Thread.sleep(500)
      }
      composeRule.runOnUiThread { vm.stopRecording() }
      composeRule.waitForContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start), timeoutMillis = 30_000)
      RecordingController.testPcmSource = null

      if (english == null) {
        val got = vm.uiState.value.transcripts.lastOrNull { it.isUser }
        failures.add("${l.key}: no English translation (last=\"${got?.originalText?.take(30)}\"->\"${got?.translatedText?.take(30)}\")")
      } else {
        Log.i(TAG, "LIVE-EVERY [${l.key}] -> \"${english.originalText.take(30)}\" => \"${english.translatedText.take(40)}\"")
      }
    }
    Log.i(TAG, "LIVE-EVERY SUMMARY failures=$failures skipped=$skipped")
    assertTrue("No device voices available to probe any source language", skipped.size < langs.size)
    assertTrue("Live-mic per-language failures: $failures", failures.isEmpty())
  }

  /** Waits for the STT re-init (triggered by setSourceLanguage) to complete: Initializing -> Idle. */
  private fun waitForSttReady(vm: BaoTranslateViewModel) {
    val deadline = System.currentTimeMillis() + 90_000
    var sawInit = false
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
      val st = vm.uiState.value.pipelineStatus
      if (st == PipelineStatus.Initializing) sawInit = true
      if (st == PipelineStatus.Idle && (sawInit || System.currentTimeMillis() - start > 12_000)) break
      Thread.sleep(200)
    }
  }

  /**
   * The real fix flow, cross-language, exercising the live-window path: the user picks Spanish as
   * the source at runtime (-> Whisper re-inits forced to Spanish), then speaks >8 s of Spanish; the
   * app must produce English live-translation. >8 s is essential — shorter clips only hit the
   * final tail flush, never the 8 s-window / 4 s-stride realtime loop.
   */
  @Test
  fun liveMic_runtimeSpanishSource_producesEnglishTranslation_windowPath() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    // >8 s of Spanish so the realtime window/stride loop fires (not just the tail flush).
    val spanish = platformTtsPcm16k(
      context,
      Locale("es", "ES"),
      "Buenos días. ¿Cómo estás hoy? Es un placer conocerte. " +
        "Espero que tengas un día maravilloso y lleno de mucha alegría y paz.",
    )
    assertNotNull("Device has no Spanish TTS voice to drive the test", spanish)
    assertTrue("Spanish prompt must exceed the 8 s live window (got ${spanish!!.size} samples)", spanish.size > 16000 * 9)

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start), timeoutMillis = 180_000)

    val vm = BaoTranslateViewModel.testInstance
    assertNotNull("Bao Translate ViewModel not created", vm)

    // Runtime language change — the actual fix wiring (setSourceLanguage -> Whisper re-init).
    composeRule.runOnUiThread {
      vm!!.setTargetLanguage("English")
      vm.setSourceLanguage("Spanish")
    }
    // Wait for the STT re-init triggered by setSourceLanguage to complete (Initializing -> Idle).
    val initDeadline = System.currentTimeMillis() + 90_000
    var sawInit = false
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() < initDeadline) {
      val st = vm!!.uiState.value.pipelineStatus
      if (st == PipelineStatus.Initializing) sawInit = true
      if (st == PipelineStatus.Idle && (sawInit || System.currentTimeMillis() - start > 15_000)) break
      Thread.sleep(200)
    }

    RecordingController.testPcmSource = ShortArray(spanish.size) { (spanish[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    try {
      composeRule.runOnUiThread { vm!!.startRecording() }
      // Poll the live transcript for an English translation of the Spanish audio.
      val deadline = System.currentTimeMillis() + 120_000
      var english: TranslationMessage? = null
      while (System.currentTimeMillis() < deadline && english == null) {
        english = vm!!.uiState.value.transcripts.firstOrNull { m ->
          val t = m.translatedText.lowercase()
          t.contains("morning") || t.contains("good") || t.contains("how") || t.contains("day") || t.contains("pleasure") || t.contains("nice")
        }
        if (english == null) Thread.sleep(500)
      }
      val userMsgs = vm!!.uiState.value.transcripts.filter { it.isUser }
      Log.i(TAG, "LIVE-MIC es->en: ${userMsgs.size} segment(s); sample orig=\"${userMsgs.firstOrNull()?.originalText?.take(40)}\" -> en=\"${userMsgs.firstOrNull()?.translatedText?.take(40)}\"")
      assertNotNull("Spanish live audio produced no English translation", english)
    } finally {
      composeRule.runOnUiThread { vm!!.stopRecording() }
      RecordingController.testPcmSource = null
    }
  }

  private fun platformTtsPcm16k(context: Context, locale: Locale, text: String): FloatArray? {
    val initLatch = CountDownLatch(1)
    var st = TextToSpeech.ERROR
    val tts = TextToSpeech(context.applicationContext) { s -> st = s; initLatch.countDown() }
    try {
      if (!initLatch.await(30, TimeUnit.SECONDS) || st != TextToSpeech.SUCCESS) return null
      if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return null
      tts.language = locale
      tts.setSpeechRate(0.9f)
      val uid = "live-${locale.language}"
      val f = File(context.cacheDir, "$uid.wav").also { if (it.exists()) it.delete() }
      val done = CountDownLatch(1); var err = false
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
      return AudioResampler.resample(WavUtils.extractSamplesFromWav(bytes), rate, PipelineConfig.STT_SAMPLE_RATE)
    } finally {
      tts.shutdown()
    }
  }

  private fun prepareDeviceForLiveMicTest(context: Context) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    listOf(
      "input keyevent KEYCODE_WAKEUP",
      "wm dismiss-keyguard",
      "svc power stayon true",
      "cmd statusbar collapse",
    ).forEach { command ->
      instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.setStreamVolume(
      AudioManager.STREAM_MUSIC,
      audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
      0,
    )
  }

  private fun ensureRequiredModelsReady(context: Context) {
    listOf("whisper_base", "silero_vad", "qwen25_1b", "kokoro_tts").forEach { modelId ->
      if (BaoTranslateModelManager.checkModelStatus(context, modelId) != ModelStatus.Ready) {
        val result = runBlocking {
          BaoTranslateModelManager.downloadModel(context, modelId, wifiOnly = false)
        }
        assertTrue("Failed to download $modelId: ${result.exceptionOrNull()?.message}", result.isSuccess)
      }

      val status = BaoTranslateModelManager.checkModelStatus(context, modelId)
      assertTrue("$modelId is not ready after live-mic provisioning; status=$status", status == ModelStatus.Ready)
    }
  }

  private fun captureLiveMicScreenshot(name: String) {
    runLiveMicShell("mkdir -p $liveMicScreenshotRunDir")
    val path = "$liveMicScreenshotRunDir/$name.png"
    runLiveMicShell("screencap -p $path")
    val listing = runLiveMicShell("ls -ln $path")
    val size = parseRemoteFileSize(listing)
    assertTrue("Live microphone screenshot $path was not created. ls output: $listing", listing.contains(path))
    assertTrue(
      "Live microphone screenshot $path was too small to be useful: $size bytes. ls output: $listing",
      size >= MIN_LIVE_MIC_SCREENSHOT_BYTES,
    )
    Log.i(TAG, "Captured live microphone screenshot $path ($size bytes)")
  }

  private fun synthesizeEnglishPrompt(context: Context): SynthesizedAudio {
    val initLatch = CountDownLatch(1)
    var initStatus = TextToSpeech.ERROR
    val tts = TextToSpeech(context.applicationContext) { status ->
      initStatus = status
      initLatch.countDown()
    }

    try {
      assertTrue(
        "Android TextToSpeech did not initialize for live microphone speaker prompt",
        initLatch.await(30, TimeUnit.SECONDS) && initStatus == TextToSpeech.SUCCESS,
      )
      assertTrue(
        "Android TextToSpeech does not expose a usable US English voice",
        tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE,
      )
      tts.language = Locale.US
      tts.setSpeechRate(0.9f)
      tts.setPitch(1.0f)

      val prompt = (1..4).joinToString(" ") { "Good night." }
      val utteranceId = "bao-live-mic-prompt"
      val promptFile = File(context.cacheDir, "$utteranceId.wav")
      if (promptFile.exists()) {
        assertTrue("Could not replace old live microphone prompt WAV", promptFile.delete())
      }

      val doneLatch = CountDownLatch(1)
      var synthError: String? = null
      tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit

          override fun onDone(utteranceId: String?) {
            doneLatch.countDown()
          }

          @Deprecated("Deprecated in Android framework")
          override fun onError(utteranceId: String?) {
            synthError = "TextToSpeech failed for utterance=$utteranceId"
            doneLatch.countDown()
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            synthError = "TextToSpeech failed for utterance=$utteranceId errorCode=$errorCode"
            doneLatch.countDown()
          }
        }
      )

      val params = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
      }
      assertTrue(
        "Android TextToSpeech rejected live microphone prompt synthesis",
        tts.synthesizeToFile(prompt, params, promptFile, utteranceId) == TextToSpeech.SUCCESS,
      )
      assertTrue(
        "Android TextToSpeech did not finish live microphone prompt synthesis; error=$synthError",
        doneLatch.await(60, TimeUnit.SECONDS) && synthError == null,
      )

      val wavBytes = promptFile.readBytes()
      assertTrue("Android TextToSpeech did not produce a valid WAV prompt", WavUtils.isValidWav(wavBytes))
      val sampleRate = WavUtils.extractSampleRateFromWav(wavBytes) ?: 0
      val rawSamples = WavUtils.extractSamplesFromWav(wavBytes)
      assertTrue("Android TextToSpeech prompt WAV had invalid sample rate: $sampleRate", sampleRate > 0)
      assertTrue("Android TextToSpeech prompt WAV had no PCM samples", rawSamples.isNotEmpty())

      val samples = rawSamples.withTrailingSilence(sampleRate / 2).normalizedToPeak(0.8f)
      Log.i(TAG, "speaker prompt synthesized samples=${samples.size} sampleRate=$sampleRate peak=${samples.peakAbs()}")
      return SynthesizedAudio(samples = samples, sampleRate = sampleRate)
    } finally {
      tts.shutdown()
    }
  }

  private fun FloatArray.withTrailingSilence(sampleCount: Int): FloatArray =
    copyOf(size + sampleCount)

  private fun FloatArray.normalizedToPeak(targetPeak: Float): FloatArray {
    val peak = peakAbs()
    if (peak <= 0f || peak <= targetPeak) return this
    val gain = targetPeak / peak
    return FloatArray(size) { index -> this[index] * gain }
  }

  private fun FloatArray.peakAbs(): Float =
    maxOfOrNull { abs(it) } ?: 0f

}

private class LiveMicPermissionRule : ExternalResource() {
  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName

    // Pin a fast, deterministic config BEFORE MainActivity launches (the ViewModel reads these in
    // its init). Without this the test inherits persisted settings: a heavy model (gemma4_e2b)
    // that exceeds the marker timeout, and a wrong target language (so "Good night" never becomes
    // "Buenas noches"). English->Spanish with qwen25_1b makes the live translation deterministic.
    // The test process cannot rewrite the target app's private DataStore (different uid), so these
    // in-process overrides are used.
    BaoTranslateViewModel.testForcedTranslationModel = "qwen25_1b"
    BaoTranslateViewModel.testForcedSourceLanguage = "English"
    BaoTranslateViewModel.testForcedTargetLanguage = "Spanish"
    val permissions =
      buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(Manifest.permission.BLUETOOTH_SCAN)
          add(Manifest.permission.BLUETOOTH_CONNECT)
          add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
    permissions.forEach { permission ->
      instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
    }
  }

  override fun after() {
    BaoTranslateViewModel.testForcedTranslationModel = null
    BaoTranslateViewModel.testForcedSourceLanguage = null
    BaoTranslateViewModel.testForcedTargetLanguage = null
  }
}

private typealias LiveMicComposeRule =
  AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private const val TAG = "BaoTranslateLiveMicE2E"
private const val LIVE_MIC_SCREENSHOT_ROOT = "/sdcard/Download/gallery-baotranslate-live-mic"
private const val MIN_LIVE_MIC_SCREENSHOT_BYTES = 10_000L
// Roots, not full words: "Good night" translates to either "Buenas noches" or "Buena noche"
// depending on the model; both contain the substrings "buena" and "noche".
private val SPANISH_TRANSLATION_MARKERS = listOf("buena", "noche")

private fun runLiveMicShell(command: String): String {
  val descriptor =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    .bufferedReader()
    .use { it.readText() }
}

private fun parseRemoteFileSize(listing: String): Long {
  val firstLine = listing.lineSequence().firstOrNull { it.isNotBlank() } ?: return 0L
  val columns = firstLine.trim().split(Regex("\\s+"))
  return columns.getOrNull(4)?.toLongOrNull() ?: 0L
}

private fun LiveMicComposeRule.stringResource(@StringRes resId: Int): String =
  activity.getString(resId)

private fun LiveMicComposeRule.prepareHome(): String {
  InstrumentationRegistry.getInstrumentation()
    .uiAutomation
    .executeShellCommand("cmd statusbar collapse")
    .close()

  val taskDescriptionPrefix = "${stringResource(R.string.bao_translate)} task"
  repeat(8) {
    if (clickTextIfPresent(R.string.tos_dialog_accept_and_continue_button_label, 2_000)) {
      return@repeat
    }
    if (clickTextIfPresent(R.string.dismiss, timeoutMillis = 1_000)) {
      return@repeat
    }
    if (hasContentDescription(taskDescriptionPrefix, substring = true)) {
      return taskDescriptionPrefix
    }
  }
  waitForContentDescription(taskDescriptionPrefix, timeoutMillis = 30_000, substring = true)
  return taskDescriptionPrefix
}

private fun LiveMicComposeRule.openTaskByDescription(taskDescriptionPrefix: String) {
  waitForContentDescription(taskDescriptionPrefix, substring = true)
  onNode(
      hasContentDescriptionMatcher(taskDescriptionPrefix, substring = true),
      useUnmergedTree = true,
    )
    .performClick()
}

private fun LiveMicComposeRule.waitForSpanishTranslationMarkers(timeoutMillis: Long): Boolean =
  runCatching { waitUntil(timeoutMillis) { spanishMarkerCount() >= 2 } }.isSuccess

private fun LiveMicComposeRule.spanishMarkerCount(): Int =
  SPANISH_TRANSLATION_MARKERS.count { marker ->
    hasText(marker, substring = true, ignoreCase = true)
  }

private fun LiveMicComposeRule.waitForText(
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForText(stringResource(resId), timeoutMillis, substring)
}

private fun LiveMicComposeRule.waitForText(
  text: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) { hasText(text, substring = substring) }
}

private fun LiveMicComposeRule.waitForContentDescription(
  contentDescription: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) { hasContentDescription(contentDescription, substring) }
}

private fun LiveMicComposeRule.clickTextIfPresent(
  @StringRes resId: Int,
  timeoutMillis: Long = 3_000,
): Boolean {
  val text = stringResource(resId)
  val appeared = runCatching { waitForText(text, timeoutMillis) }.isSuccess
  if (appeared) {
    onNodeWithText(text).performClick()
    waitForIdle()
  }
  return appeared
}

private fun LiveMicComposeRule.hasText(
  text: String,
  substring: Boolean = false,
  ignoreCase: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithText(
          text = text,
          substring = substring,
          ignoreCase = ignoreCase,
          useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun LiveMicComposeRule.hasContentDescription(
  contentDescription: String,
  substring: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithContentDescription(contentDescription, substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)
