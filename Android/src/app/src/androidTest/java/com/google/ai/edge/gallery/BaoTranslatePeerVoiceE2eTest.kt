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
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateViewModel
import com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioCache
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleMetadataMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleTranscriptMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-speaker BLE RECEIVE-path voice routing — the privacy/cloning invariants for a peer's
 * translated turn. Migrated off createAndroidComposeRule (which hangs on this app) to a UiAutomator
 * driver, and off device platform-TTS to bundled reference voices, so it actually RUNS:
 *  - a peer turn is cloned into the PEER's shared embedding, never the local user's enrolled voice;
 *  - without a peer embedding the receive path speaks un-cloned TTS — never the local user's voice.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslatePeerVoiceE2eTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  private fun s(@StringRes id: Int): String = context.getString(id)

  private fun reachReadyScreen(): BaoTranslateViewModel {
    BaoTranslateLiveTestSupport.prepareDevice(context)
    BaoTranslateLiveTestSupport.ensureRequiredModelsReady(context)
    runBlocking { BaoTranslateModelManager.downloadModel(context, "openvoice") }
    // Disable system animations so the Compose semantics tree settles and UiAutomator can read it —
    // an always-animating tree yields an empty `uiautomator dump` (no nodes) on some emulator images.
    listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach {
      device.executeShellCommand("settings put global $it 0")
    }
    device.executeShellCommand(
      "am start -f 0x04000000 -n ${context.packageName}/com.google.ai.edge.gallery.MainActivity"
    )
    // Drive to the ready Bao Translate screen, tolerant of the entry state — restored state varies
    // (home task grid, the welcome/onboarding sheet, or already-ready) across launches and devices.
    val cdStart = s(R.string.cd_bao_translate_start)
    val getStarted = s(R.string.bao_translate_welcome_get_started)
    val navDeadline = SystemClock.uptimeMillis() + 120_000
    while (SystemClock.uptimeMillis() < navDeadline && !device.hasObject(By.desc(cdStart))) {
      val welcome = device.findObject(By.text(getStarted))
      if (welcome != null) { welcome.click(); device.waitForIdle(1_000); continue }
      val card =
        device.findObject(By.descContains("Bao Translate task"))
          ?: device.findObject(By.text(s(R.string.bao_translate)))
      if (card != null) { card.click(); device.waitForIdle(1_000); continue }
      device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
      device.waitForIdle(1_000)
    }
    assertTrue(
      "Bao Translate ready control never appeared",
      device.wait(Until.hasObject(By.desc(cdStart)), 180_000) != null,
    )
    // The ViewModel's init sets testInstance; poll briefly in case the UI element appears a beat
    // before init finishes (or the screen is still settling on a slow device).
    val vmDeadline = SystemClock.uptimeMillis() + 30_000
    while (BaoTranslateViewModel.testInstance == null && SystemClock.uptimeMillis() < vmDeadline) {
      SystemClock.sleep(250)
    }
    return requireNotNull(BaoTranslateViewModel.testInstance) { "ViewModel not created" }
  }

  /** Loads a bundled reference-voice WAV and returns its 256-d OpenVoice speaker embedding. */
  private fun embeddingFromAsset(converter: OpenVoiceVoiceConverter, asset: String): FloatArray {
    val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
    assertTrue("reference voice wav invalid: $asset", WavUtils.isValidWav(bytes))
    val samples = WavUtils.extractSamplesFromWav(bytes)
    val rate = WavUtils.extractSampleRateFromWav(bytes) ?: 16000
    return requireNotNull(converter.computeSpeakerEmbedding(samples, rate)) { "embedding null for $asset" }
  }

  private fun waitForPeerAudio(vm: BaoTranslateViewModel, peer: BleTranscriptMessage): TranslationMessage? {
    val deadline = SystemClock.uptimeMillis() + 60_000
    var routed: TranslationMessage? = null
    while (SystemClock.uptimeMillis() < deadline && routed == null) {
      routed =
        vm.uiState.value.transcripts.firstOrNull {
          !it.isUser &&
            it.speakerName == peer.senderName &&
            it.originalText.contains(peer.text.substringBefore("."), ignoreCase = true) &&
            it.audioPlayed == true
        }
      if (routed == null) SystemClock.sleep(400)
    }
    return routed
  }

  /** Like [waitForPeerAudio] but keyed on attribution + original text only (no audio gate). */
  private fun waitForRoutedPeerTranscript(
    vm: BaoTranslateViewModel,
    peer: BleTranscriptMessage,
  ): TranslationMessage? {
    val deadline = SystemClock.uptimeMillis() + 60_000
    var routed: TranslationMessage? = null
    while (SystemClock.uptimeMillis() < deadline && routed == null) {
      routed =
        vm.uiState.value.transcripts.firstOrNull {
          !it.isUser &&
            it.speakerName == peer.senderName &&
            it.originalText.contains(peer.text.substringBefore("."), ignoreCase = true)
        }
      if (routed == null) SystemClock.sleep(400)
    }
    return routed
  }

  private fun peerTranscript() =
    BleTranscriptMessage(
      text = "Good morning.",
      senderId = "peer-test-1",
      senderName = "Alex",
      sourceLanguage = "English",
      targetLanguage = "Spanish",
    )

  @Test
  fun receivedPeerMessageUsesPeerTimbre_notLocalVoice() {
    val vm = reachReadyScreen()
    val converter = OpenVoiceVoiceConverter()
    try {
      assertTrue(
        "OpenVoice converter init",
        converter.initialize(
          BaoTranslateModelManager.getOpenVoiceConverterFile(context),
          BaoTranslateModelManager.getOpenVoiceRefEncFile(context),
        ),
      )
      val localSe = embeddingFromAsset(converter, "bao_voice_ref.wav")
      val peerSe = embeddingFromAsset(converter, "bao_voice_ref2.wav")
      instrumentation.runOnMainSync { vm.setTestLocalVoiceEmbeddingForTest(localSe) }

      vm.bleManager.simulateIncomingMetadataForTest(
        "peer-test-1",
        BleMetadataMessage(
          participantId = "peer-test-1",
          participantName = "Alex",
          sourceLanguage = "English",
          targetLanguage = "Spanish",
          hasVoiceProfile = true,
          voiceEmbedding = peerSe.toList(),
        ),
      )
      // The OpenVoice clone converter is an OPTIONAL model that finishes initializing a few seconds
      // AFTER the required-models ready signal — wait for it so the peer turn is actually cloned, not
      // raced against a still-null converter (only matters for the very first turn after connecting).
      val cloneDeadline = SystemClock.uptimeMillis() + 60_000
      while (!vm.testOpenVoiceCloneReady && SystemClock.uptimeMillis() < cloneDeadline) {
        SystemClock.sleep(300)
      }
      assertTrue("OpenVoice clone converter did not initialize", vm.testOpenVoiceCloneReady)
      assertEquals("peer embedding must be 256-d (SE_DIM)", 256, peerSe.size)
      // Clear the TTS cache so this turn is actually synthesized + cloned, not served from a prior run's
      // cached entry (which would skip the clone path and leave testLastWasCloned false).
      AudioCache.invalidate()

      val peer = peerTranscript()
      runBlocking { vm.bleManager.simulateIncomingTranscriptForTest(peer) }

      assertNotNull("Peer message was not spoken aloud", waitForPeerAudio(vm, peer))
      assertTrue("OpenVoice clone was not attempted for peer message", RecordingController.testLastWasCloned)
      assertArrayEquals("Clone target was not the peer embedding", peerSe, RecordingController.testLastCloneTargetSe, 1e-5f)
      assertFalse(
        "Clone target incorrectly used the local enrolled embedding",
        localSe.contentEquals(RecordingController.testLastCloneTargetSe),
      )
    } finally {
      converter.cleanup()
    }
  }

  @Test
  fun receivedPeerMessageWithoutEmbedding_doesNotUseLocalVoice() {
    val vm = reachReadyScreen()
    val converter = OpenVoiceVoiceConverter()
    try {
      assertTrue(
        "OpenVoice converter init",
        converter.initialize(
          BaoTranslateModelManager.getOpenVoiceConverterFile(context),
          BaoTranslateModelManager.getOpenVoiceRefEncFile(context),
        ),
      )
      val localSe = embeddingFromAsset(converter, "bao_voice_ref.wav")
      instrumentation.runOnMainSync { vm.setTestLocalVoiceEmbeddingForTest(localSe) }

      vm.bleManager.simulateIncomingMetadataForTest(
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
      val peer = peerTranscript()
      runBlocking { vm.bleManager.simulateIncomingTranscriptForTest(peer) }

      assertNotNull("Peer message was not spoken aloud", waitForPeerAudio(vm, peer))
      assertNull(
        "PeerOnly path without a peer embedding must not clone (must not use localSe)",
        RecordingController.testLastCloneTargetSe,
      )
      assertFalse("Clone must not run without a peer embedding", RecordingController.testLastWasCloned)
    } finally {
      converter.cleanup()
    }
  }

  /**
   * Restored from the relocated composeRule LiveMic suite (it was NOT covered by the two-device
   * [BaoTranslateMultiDeviceE2eTest], which needs a real Nearby peer): a received peer turn must be
   * surfaced TRANSLATED into the local target language and ATTRIBUTED to the remote speaker, with the
   * original preserved and the output not an English echo. Single-device via the simulate seam.
   */
  @Test
  fun receivedPeerMessage_translatedToLocalLanguageAndAttributed() {
    val vm = reachReadyScreen()
    instrumentation.runOnMainSync { vm.setTargetLanguage("Spanish") }

    val peer = peerTranscript() // "Good morning." English -> Spanish, sender "Alex"
    runBlocking { vm.bleManager.simulateIncomingTranscriptForTest(peer) }

    val routed = waitForRoutedPeerTranscript(vm, peer)
    assertNotNull("Peer message was not routed into the local transcript", routed)
    // Accent-fold so "días"/"mañana" match regardless of diacritics.
    val spanish = java.text.Normalizer.normalize(routed!!.translatedText, java.text.Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .lowercase(java.util.Locale.ROOT)
    assertTrue(
      "Peer English was not translated into Spanish: \"${routed.translatedText}\"",
      spanish.contains("buen") || spanish.contains("dias") || spanish.contains("manana"),
    )
    assertFalse(
      "Translated text equals the English source (not actually translated)",
      routed.translatedText.equals(peer.text, ignoreCase = true),
    )
  }

  /**
   * Per-speaker output routing (OOS-AUDIT-015a): in face-to-face mode each speaker can be assigned a
   * distinct output device, and a turn for that speaker's language must be played on THEIR override,
   * not the global route. Asserts via the [RecordingController.testLastOutputOverride] seam (the
   * intent captured at the playback site), independent of whether the physical device is present.
   */
  @Test
  fun faceToFaceMode_routesPeerTurnToThatSpeakersAssignedOutput() {
    val vm = reachReadyScreen()
    val spanishCode = SupportedLanguages.codeFor("Spanish")
    val earbuds = AudioDevice.WiredHeadset("Test Earbuds")
    instrumentation.runOnMainSync {
      vm.setTargetLanguage("Spanish")
      vm.setFaceToFaceMode(true)
      vm.setFaceToFaceOutput(spanishCode, earbuds)
    }
    RecordingController.testLastOutputOverride = null

    val peer = peerTranscript() // English -> Spanish, so the synth language is the Spanish code
    runBlocking { vm.bleManager.simulateIncomingTranscriptForTest(peer) }

    assertNotNull("Peer message was not spoken aloud", waitForPeerAudio(vm, peer))
    assertEquals(
      "The Spanish speaker's turn must route to their assigned per-speaker output override",
      earbuds,
      RecordingController.testLastOutputOverride,
    )
  }

  /** Polls the transcript list for a committed, translated local utterance. */
  private fun waitForCommittedUserTranslation(vm: BaoTranslateViewModel): TranslationMessage? {
    val deadline = SystemClock.uptimeMillis() + 120_000
    var msg: TranslationMessage? = null
    while (SystemClock.uptimeMillis() < deadline && msg == null) {
      msg = vm.uiState.value.transcripts.firstOrNull { it.isUser && it.translatedText.isNotBlank() }
      if (msg == null) SystemClock.sleep(400)
    }
    return msg
  }

  /**
   * OOS-LIVE-001, driven via the ViewModel (UiAutomator/testInstance) rather than composeRule so it runs
   * on a CPU-only emulator: in continuous (non-F2F) mode a sub-8s-window utterance ended by the stop
   * button must STILL be translated — its only commit path is the stop-time tail flush, which runs under
   * `withContext(NonCancellable)` so the stop-cancel can't drop it.
   */
  @Test
  fun continuousMode_shortUtteranceEndedByStop_isStillTranslated_oosLive001() {
    val vm = reachReadyScreen() // continuous (non-face-to-face) by default
    instrumentation.runOnMainSync { vm.setTargetLanguage("Spanish") }
    // < 8s so NO live window fires; the NonCancellable tail flush is the sole commit path. Bundled prompt.
    RecordingController.testPcmSource =
      BaoTranslateLiveTestSupport.englishPromptAsSttPcm().let { it.copyOf(minOf(it.size, 16_000 * 6)) }
    try {
      instrumentation.runOnMainSync { vm.startRecording() }
      val recDeadline = SystemClock.uptimeMillis() + 30_000
      while (!vm.uiState.value.isRecording &&
        !vm.uiState.value.isStartingRecording &&
        SystemClock.uptimeMillis() < recDeadline) {
        SystemClock.sleep(200)
      }
      SystemClock.sleep(7_000) // let the ~6s prompt feed in full, staying under the 8s window
      instrumentation.runOnMainSync { vm.stopRecording() }
      assertNotNull(
        "Sub-window utterance ended by the stop button was dropped (OOS-LIVE-001 NonCancellable regression)",
        waitForCommittedUserTranslation(vm),
      )
    } finally {
      RecordingController.testPcmSource = null
    }
  }
}
