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
import com.google.ai.edge.gallery.customtasks.baotranslate.ConversationPhase
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.PipelineStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioResampler
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleMetadataMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleTranscriptMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.DiscoveredPeer
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
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

  // Freeze the Compose animation clock so the implicit waitForIdle() inside finders/assertions no
  // longer blocks on the app's infinite listening/recording pulse (rememberPulseFloat ->
  // infiniteRepeatable never reaches idle with autoAdvance on). With autoAdvance=false, waitForIdle
  // waits only on idling resources. The trade-off: a frozen clock dispatches no frames, so
  // StateFlow-driven UI does NOT recompose on its own and composeRule.waitUntil can never observe it.
  // Every state-driven wait therefore goes through pumpUntil(), which manually advances one frame per
  // iteration to drive recomposition (see pumpUntil). See Compose testing/synchronization docs.
  @Before
  fun freezeAnimationClock() {
    composeRule.mainClock.autoAdvance = false
  }

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

      composeRule.pumpUntil(timeoutMillis = 10_000) { composeRule.spanishMarkerCount() >= 0 }
      captureLiveMicScreenshot("live_mic_recording_with_injected_prompt")

      val liveMarkers = composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 120_000)
      assertTrue(
        "Bao Translate did not show live Spanish translation markers while still recording; " +
          "markerCount=${composeRule.spanishMarkerCount()}; transcript=${transcriptDump()}",
        liveMarkers,
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

    val retainedMarkers = composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 30_000)
    assertTrue(
      "Bao Translate live transcript did not retain Spanish translation markers after stop; " +
        "markerCount=${composeRule.spanishMarkerCount()}; transcript=${transcriptDump()}",
      retainedMarkers,
    )
  }

  /**
   * OOS-LIVE-001 regression: in continuous (non-F2F) mode an utterance SHORTER than the 8s live
   * window has no window-boundary commit — its only commit path is the stop-time tail flush, which
   * runs under `withContext(NonCancellable)` so the stop-button cancel can't drop it. Inject a ~6s
   * prompt (sub-window), record, stop, and assert a translation still lands.
   */
  @Test
  fun shortUtteranceEndedByStop_isStillTranslated_oosLive001() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    // Truncate to < 8s so NO live window fires; the tail-flush drain is the sole commit path.
    // Use the BUNDLED English prompt (not device TTS) so this runs on TTS-less test devices/emulators.
    val shortPrompt =
      BaoTranslateLiveTestSupport.englishPromptAsSttPcm().let { it.copyOf(minOf(it.size, 16_000 * 6)) }
    RecordingController.testPcmSource = shortPrompt

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )

    var recordingStarted = false
    try {
      composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start))
        .assertIsDisplayed()
        .performClick()
      recordingStarted = true
      composeRule.waitForText(R.string.bao_translate_listening, timeoutMillis = 120_000)
      // Let the ~6s prompt feed in full, then stop BEFORE the 8s window boundary.
      Thread.sleep(6_500)
    } finally {
      if (recordingStarted && composeRule.hasContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))) {
        composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))
          .performClick()
        composeRule.waitForContentDescription(
          composeRule.stringResource(R.string.cd_bao_translate_start),
          timeoutMillis = 120_000,
        )
      }
      RecordingController.testPcmSource = null
    }

    val stopFlushMarkers =
      composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 180_000, markers = GREETING_TRANSLATION_MARKERS)
    assertTrue(
      "Sub-window utterance ended by stop did not surface its Spanish translation in the UI; " +
        "markerCount=${composeRule.spanishMarkerCount(GREETING_TRANSLATION_MARKERS)}; transcript=${transcriptDump()}",
      stopFlushMarkers,
    )
  }

  /**
   * REAL two-device proof: this device connects to a SECOND physical device (whose app must already
   * be in Conversation Mode, scanning) over Google Nearby Connections, then transmits a transcript
   * across the live link. The peer device translates it into its own target language and speaks it
   * aloud (in this device's timbre, since metadata carries the enrolled voice embedding). This
   * asserts the SENDER side end-to-end (discover -> connect -> transmit over the real radio); observe
   * the peer's screen/logcat for the spoken translation. No-ops gracefully if no peer is present.
   */
  @Test
  fun twoDevice_sendsTranscriptToLivePeerOverNearby() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )

    val vm = BaoTranslateViewModel.testInstance
    assertNotNull("Bao Translate ViewModel was not created", vm)

    // Advertise + discover, then connect to the live peer (its app must be in Conversation Mode).
    composeRule.runOnUiThread { vm!!.bleManager.startConversationDiscovery() }
    val discDeadline = System.currentTimeMillis() + 60_000
    var peer: DiscoveredPeer? = null
    while (System.currentTimeMillis() < discDeadline && peer == null) {
      peer = vm!!.bleManager.discoveredPeers.value.firstOrNull()
      if (peer == null) Thread.sleep(500)
    }
    // Two-device test: SKIP (not fail) on single-device/CI runs where no peer is advertising.
    assumeTrue("Two-device proof requires a SECOND device in Conversation Mode (scanning)", peer != null)
    Log.i(TAG, "TWO-DEVICE: discovered peer name=${peer!!.name} id=${peer.id}")

    composeRule.runOnUiThread { vm!!.bleManager.connectToDevice(peer.id) }
    val connDeadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < connDeadline && vm!!.bleManager.getConnectedCount() == 0) {
      Thread.sleep(500)
    }
    assertTrue("Did not connect to the live peer over Nearby", vm!!.bleManager.getConnectedCount() > 0)
    Log.i(TAG, "TWO-DEVICE: CONNECTED count=${vm.bleManager.getConnectedCount()} state=${vm.bleManager.connectionState.value}")

    // Let metadata (incl. our voice embedding) propagate so the peer can speak in our timbre.
    Thread.sleep(2_000)

    // FULL CHAIN — no direct send: inject real English speech into THIS device's microphone pipeline,
    // so Whisper STT -> local translate -> RecordingController's `if (getConnectedCount() > 0)
    // sendTranscript(...)` path broadcasts the RECOGNIZED transcript to the connected peer over Nearby.
    // The transcript that crosses the link is produced by the real speech->text pipeline, not a literal.
    RecordingController.testPcmSource = synthesizeEnglishPromptAsSttPcm(context)
    composeRule.runOnUiThread { vm.startRecording() }
    val sttDeadline = System.currentTimeMillis() + 120_000
    var spoken: TranslationMessage? = null
    while (System.currentTimeMillis() < sttDeadline && spoken == null) {
      spoken = vm.uiState.value.transcripts.firstOrNull { it.isUser && it.originalText.isNotBlank() }
      if (spoken == null) Thread.sleep(500)
    }
    composeRule.runOnUiThread { vm.stopRecording() }
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 30_000,
    )
    RecordingController.testPcmSource = null
    assertNotNull("Sender STT produced no transcript to broadcast over Nearby", spoken)
    Log.i(
      TAG,
      "TWO-DEVICE FULL CHAIN: sender heard \"${spoken!!.originalText.take(40)}\" -> broadcast to peer over Nearby",
    )

    // The peer receives the transcript, translates it into its own language, and speaks it aloud.
    Thread.sleep(10_000)
    assertTrue("Connection dropped before transmit completed", vm.bleManager.getConnectedCount() > 0)
    Log.i(TAG, "TWO-DEVICE FULL CHAIN: complete; peer should have spoken the translation")
  }

  /** Platform-TTS English prompt resampled to the STT pipeline's 16 kHz mono PCM for injection. */
  private fun synthesizeEnglishPromptAsSttPcm(context: Context): ShortArray {
    val prompt = synthesizeEnglishPrompt(context)
    val resampled = AudioResampler.resample(prompt.samples, prompt.sampleRate, PipelineConfig.STT_SAMPLE_RATE)
    val pcm = ShortArray(resampled.size) { i -> (resampled[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    Log.i(TAG, "injected prompt PCM samples=${pcm.size} @${PipelineConfig.STT_SAMPLE_RATE}Hz")
    // The "live translation BEFORE stop" assertion requires a window-boundary commit, which only
    // fires for utterances LONGER than the 8 s live window. Fail loudly here if the device TTS
    // produced too-short audio, instead of a confusing downstream markerCount=0. Mirrors the
    // size guard in liveMic_runtimeSpanishSource_producesEnglishTranslation_windowPath.
    assertTrue(
      "English prompt must exceed the 8 s live window to drive a pre-stop translation; got ${pcm.size} samples (~${pcm.size / PipelineConfig.STT_SAMPLE_RATE}s)",
      pcm.size > PipelineConfig.STT_SAMPLE_RATE * 9,
    )
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

  /**
   * Multi-speaker BLE receive path with peer voice embedding: even when the local user has enrolled
   * their own timbre, a peer's translated turn must be cloned into the PEER's shared embedding, not
   * the local one.
   */
  @Test
  fun receivedPeerMessageUsesPeerTimbreWhenEmbeddingPresent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)
    ensureOpenVoiceReady(context)

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )

    val viewModel = BaoTranslateViewModel.testInstance
    assertNotNull("Bao Translate ViewModel was not created", viewModel)

    val converter = OpenVoiceVoiceConverter()
    try {
      assertTrue(
        "OpenVoice converter init",
        converter.initialize(
          BaoTranslateModelManager.getOpenVoiceConverterFile(context),
          BaoTranslateModelManager.getOpenVoiceRefEncFile(context),
        ),
      )

      val localSe = computeEmbedding(
        converter,
        platformTtsRef(context, "Hello my name is LocalUser and this is my natural speaking voice."),
      )
      val peerSe = computeEmbedding(
        converter,
        platformTtsRef(context, "Hello my name is PeerAlex and this is my natural speaking voice."),
      )
      assertNotNull("local speaker embedding null", localSe)
      assertNotNull("peer speaker embedding null", peerSe)

      composeRule.runOnUiThread { viewModel!!.setTestLocalVoiceEmbeddingForTest(localSe) }

      viewModel!!.bleManager.simulateIncomingMetadataForTest(
        "peer-test-1",
        BleMetadataMessage(
          participantId = "peer-test-1",
          participantName = "Alex",
          sourceLanguage = "English",
          targetLanguage = "Spanish",
          hasVoiceProfile = true,
          voiceEmbedding = peerSe!!.toList(),
        ),
      )

      val peer = BleTranscriptMessage(
        text = "Good morning.",
        senderId = "peer-test-1",
        senderName = "Alex",
        sourceLanguage = "English",
        targetLanguage = "Spanish",
      )
      runBlocking { viewModel.bleManager.simulateIncomingTranscriptForTest(peer) }

      val routed = waitForPeerTranscriptWithAudio(viewModel, peer)
      assertNotNull("Peer message was not spoken aloud (audioPlayed)", routed)

      assertTrue("OpenVoice clone was not attempted for peer message", RecordingController.testLastWasCloned)
      assertArrayEquals(
        "Clone target was not the peer embedding",
        peerSe,
        RecordingController.testLastCloneTargetSe,
        1e-5f,
      )
      assertFalse(
        "Clone target incorrectly used the local enrolled embedding",
        localSe!!.contentEquals(RecordingController.testLastCloneTargetSe),
      )
    } finally {
      converter.cleanup()
    }
  }

  /**
   * PeerOnly timbre routing: when a peer has NOT shared an embedding, the receive path must NOT fall
   * back to the locally-enrolled voice — it should speak un-cloned TTS instead.
   */
  @Test
  fun receivedPeerMessageWithoutEmbeddingDoesNotUseLocalVoice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)
    ensureOpenVoiceReady(context)

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )

    val viewModel = BaoTranslateViewModel.testInstance
    assertNotNull("Bao Translate ViewModel was not created", viewModel)

    val converter = OpenVoiceVoiceConverter()
    try {
      assertTrue(
        "OpenVoice converter init",
        converter.initialize(
          BaoTranslateModelManager.getOpenVoiceConverterFile(context),
          BaoTranslateModelManager.getOpenVoiceRefEncFile(context),
        ),
      )

      val localSe = computeEmbedding(
        converter,
        platformTtsRef(context, "Hello my name is LocalUser and this is my natural speaking voice."),
      )
      assertNotNull("local speaker embedding null", localSe)
      composeRule.runOnUiThread { viewModel!!.setTestLocalVoiceEmbeddingForTest(localSe) }

      viewModel!!.bleManager.simulateIncomingMetadataForTest(
        "peer-test-1",
        BleMetadataMessage(
          participantId = "peer-test-1",
          participantName = "Alex",
          sourceLanguage = "English",
          targetLanguage = "Spanish",
          hasVoiceProfile = false,
          voiceEmbedding = null,
        ),
      )

      val peer = BleTranscriptMessage(
        text = "Good morning.",
        senderId = "peer-test-1",
        senderName = "Alex",
        sourceLanguage = "English",
        targetLanguage = "Spanish",
      )
      runBlocking { viewModel.bleManager.simulateIncomingTranscriptForTest(peer) }

      val routed = waitForPeerTranscriptWithAudio(viewModel, peer)
      assertNotNull("Peer message was not spoken aloud (audioPlayed)", routed)

      assertNull(
        "PeerOnly path without peer embedding must not clone (and must not use localSe)",
        RecordingController.testLastCloneTargetSe,
      )
      assertFalse("OpenVoice clone must not run without a peer embedding", RecordingController.testLastWasCloned)
    } finally {
      converter.cleanup()
    }
  }

  private fun waitForPeerTranscriptWithAudio(
    viewModel: BaoTranslateViewModel,
    peer: BleTranscriptMessage,
  ): TranslationMessage? {
    val deadline = System.currentTimeMillis() + 60_000
    var routed: TranslationMessage? = null
    while (System.currentTimeMillis() < deadline && routed == null) {
      routed = viewModel.uiState.value.transcripts.firstOrNull {
        !it.isUser &&
          it.speakerName == peer.senderName &&
          it.originalText.contains(peer.text.substringBefore("."), ignoreCase = true) &&
          it.audioPlayed == true
      }
      if (routed == null) Thread.sleep(500)
    }
    return routed
  }

  private fun ensureOpenVoiceReady(ctx: Context) {
    val convFile = BaoTranslateModelManager.getOpenVoiceConverterFile(ctx)
    val refEncFile = BaoTranslateModelManager.getOpenVoiceRefEncFile(ctx)
    assumeTrue(
      "OpenVoice ONNX models not provisioned at ${convFile.parent}",
      convFile.exists() && refEncFile.exists(),
    )
  }

  private fun platformTtsRef(ctx: Context, text: String): Pair<FloatArray, Int> {
    val initLatch = CountDownLatch(1)
    var st = TextToSpeech.ERROR
    val tts = TextToSpeech(ctx.applicationContext) { s -> st = s; initLatch.countDown() }
    try {
      assertTrue("tts init", initLatch.await(30, TimeUnit.SECONDS) && st == TextToSpeech.SUCCESS)
      tts.language = Locale.US
      val uid = "ble-peer-ref"
      val f = File(ctx.cacheDir, "$uid.wav")
      if (f.exists()) f.delete()
      val done = CountDownLatch(1)
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(u: String?) = Unit
        override fun onDone(u: String?) = done.countDown()
        @Deprecated("Deprecated in Android framework") override fun onError(u: String?) = done.countDown()
        override fun onError(u: String?, c: Int) = done.countDown()
      })
      assertTrue(
        "tts synth",
        tts.synthesizeToFile(text, Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid) }, f, uid) == TextToSpeech.SUCCESS,
      )
      assertTrue("tts finish", done.await(60, TimeUnit.SECONDS))
      val bytes = f.readBytes()
      assertTrue("ref wav", WavUtils.isValidWav(bytes))
      return WavUtils.extractSamplesFromWav(bytes) to (WavUtils.extractSampleRateFromWav(bytes) ?: 22050)
    } finally {
      tts.shutdown()
    }
  }

  private fun computeEmbedding(converter: OpenVoiceVoiceConverter, wav: Pair<FloatArray, Int>): FloatArray? =
    converter.computeSpeakerEmbedding(wav.first, wav.second)

  private fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
      dot += a[i] * b[i]
      na += a[i].toDouble() * a[i]
      nb += b[i].toDouble() * b[i]
    }
    return if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0.0
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
      LiveLang("Spanish", "es", Locale.forLanguageTag("es-ES"), "Buenos días, ¿cómo estás hoy? Es un placer conocerte."),
      LiveLang("French", "fr", Locale.FRANCE, "Bonjour, comment allez-vous aujourd'hui mon ami?"),
      LiveLang("German", "de", Locale.GERMANY, "Guten Morgen, wie geht es dir heute mein Freund?"),
      LiveLang("Italian", "it", Locale.ITALY, "Buongiorno, come stai oggi amico mio?"),
      LiveLang("Russian", "ru", Locale.forLanguageTag("ru-RU"), "Доброе утро, как дела сегодня, мой друг?"),
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
      // Wait for any PRIOR language's playback (Speaking -> Cooldown -> Idle) to finish before this
      // language records: while a translation is being SPOKEN the live mic is gated (capturePaused), so
      // an overlapping next-language capture is silently dropped. That was the cause of the prior
      // alternating es✓/fr✗/de✓/it✗/ru✓ failures — every language right after a SUCCESSFUL one was gated.
      val idleDeadline = System.currentTimeMillis() + 30_000
      while (System.currentTimeMillis() < idleDeadline &&
        vm!!.uiState.value.conversationPhase != ConversationPhase.Idle) {
        Thread.sleep(200)
      }
      val pcmF = platformTtsPcm16k(context, l.locale, l.phrase)
      if (pcmF == null) { skipped.add(l.key); Log.w(TAG, "LIVE-EVERY [${l.key}] skipped: no device voice"); continue }

      composeRule.runOnUiThread { vm!!.setSourceLanguage(l.key) }
      waitForSttReady(vm!!)

      RecordingController.testPcmSource = ShortArray(pcmF.size) { (pcmF[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
      composeRule.runOnUiThread { vm.startRecording() }
      // Each phrase is a single sentence (~5-6s) = SUB-WINDOW, so it commits via the stop-time tail
      // flush, NOT a live window boundary (see shortUtterance...oosLive001); VAD does not endpoint on
      // the injected trailing silence mid-recording. So feed the whole clip in real time, then STOP,
      // then poll. Polling DURING recording (the prior design) could never observe a sub-window
      // translation — every language failed for that reason. The live-window path is covered by
      // liveMic_runtimeSpanishSource_producesEnglishTranslation_windowPath.
      val feedMs = pcmF.size * 1000L / 16_000 + 2_000
      Thread.sleep(feedMs)
      composeRule.runOnUiThread { vm.stopRecording() }
      composeRule.waitForContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start), timeoutMillis = 30_000)
      RecordingController.testPcmSource = null

      val deadline = System.currentTimeMillis() + 60_000
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
      Locale.forLanguageTag("es-ES"),
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

      // 16 reps so the synthesized utterance EXCEEDS the 8 s live window: the
      // injectedSpeechProducesLiveTranslation_beforeStop assertion needs a window-boundary commit
      // (a sub-window utterance only commits at the stop-time tail flush, producing NO live
      // pre-stop translation). One continuous TTS utterance => one VAD segment (verified: the prior
      // 4-rep prompt was a single segment), so the window/stride loop fires mid-recording. This was
      // regressed from 8 reps to 4 in 388a9cd, dropping it below the window. See
      // liveMic_runtimeSpanishSource_producesEnglishTranslation_windowPath for the symmetric Spanish case.
      val prompt = (1..16).joinToString(" ") { "Good night." }
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
        // Nearby Connections discovery permission (API 33+), or its FINE_LOCATION fallback below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
          add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
          add(Manifest.permission.ACCESS_FINE_LOCATION)
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

// The bundled bao_live_prompt_en.wav actually says "Hello there. How are you today? I am doing very
// well. Thank you." — NOT "good night" — which qwen25_1b renders to "Hola. ¿Cómo estás hoy? Estoy muy
// bien. Gracias." (verified on device via the transcript dump). "thank you"->"gracias" and "very
// well"->"... bien" are deterministic; the >=2 check tolerates minor wording drift across the set.
private val GREETING_TRANSLATION_MARKERS = listOf("hola", "bien", "gracias")

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

// Drive recomposition under the frozen animation clock. freezeAnimationClock() sets
// mainClock.autoAdvance=false so the app's infinite listening/recording pulse (rememberPulseFloat ->
// infiniteRepeatable) can never keep the recomposer non-idle and hang the implicit waitForIdle in
// finders. But a frozen clock also dispatches NO frames, so StateFlow-driven UI (translation markers,
// listening state) never recomposes and a bare composeRule.waitUntil can never observe it — the
// regression behind the prior ComposeTimeoutExceptions. So pump ONE frame per iteration to apply
// pending snapshot writes + recompose, with a real Thread.sleep so the real-dispatcher
// VAD/Whisper/translate pipeline progresses, against a REAL wall-clock deadline (the pipeline runs in
// real time, not virtual test-clock time). Per Compose testing/synchronization docs: under
// autoAdvance=false the test clock must be advanced manually for recomposition to occur.
private fun LiveMicComposeRule.pumpUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
  val deadline = System.currentTimeMillis() + timeoutMillis
  while (System.currentTimeMillis() < deadline) {
    if (condition()) return true
    mainClock.advanceTimeByFrame()
    Thread.sleep(16) // ~1 frame of real time so background pipeline coroutines advance + yield
  }
  return condition()
}

// Dump the ViewModel transcript (source -> translated) so a markerCount=0 failure is self-documenting:
// it separates a CONTENT problem (a translation is present but lacks the "buena"/"noche" markers) from
// a UI problem (the translation never reached the visible semantics tree).
private fun transcriptDump(): String =
  BaoTranslateViewModel.testInstance?.uiState?.value?.transcripts
    ?.takeIf { it.isNotEmpty() }
    ?.joinToString("  ||  ") { "src=\"${it.originalText}\" -> es=\"${it.translatedText}\"" }
    ?: "<no transcripts in VM>"

private fun LiveMicComposeRule.waitForSpanishTranslationMarkers(
  timeoutMillis: Long,
  markers: List<String> = SPANISH_TRANSLATION_MARKERS,
): Boolean = pumpUntil(timeoutMillis) { spanishMarkerCount(markers) >= 2 }

private fun LiveMicComposeRule.spanishMarkerCount(
  markers: List<String> = SPANISH_TRANSLATION_MARKERS,
): Int =
  markers.count { marker ->
    hasText(marker, substring = true, ignoreCase = true)
  }

private fun LiveMicComposeRule.waitForText(
  @StringRes resId: Int,
  timeoutMillis: Long = 60_000,
  substring: Boolean = false,
) {
  waitForText(stringResource(resId), timeoutMillis, substring)
}

private fun LiveMicComposeRule.waitForText(
  text: String,
  timeoutMillis: Long = 60_000,
  substring: Boolean = false,
) {
  if (!pumpUntil(timeoutMillis) { hasText(text, substring = substring) }) {
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for text: \"$text\" (substring=$substring)")
  }
}

private fun LiveMicComposeRule.waitForContentDescription(
  contentDescription: String,
  timeoutMillis: Long = 60_000,
  substring: Boolean = false,
) {
  if (!pumpUntil(timeoutMillis) { hasContentDescription(contentDescription, substring) }) {
    throw AssertionError(
      "Timed out after ${timeoutMillis}ms waiting for contentDescription: \"$contentDescription\" (substring=$substring)"
    )
  }
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
