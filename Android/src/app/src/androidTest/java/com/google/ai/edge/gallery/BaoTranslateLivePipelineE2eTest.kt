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
import android.util.Log
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateViewModel
import com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification of the LIVE (hands-free, face-to-face) translation path through the REAL
 * recording read loop — VAD -> turn buffering -> TRUE streaming transducer partials -> final-segment
 * Whisper -> translate -> UI — driven by deterministic injected English speech on the device.
 *
 * The injected PCM is read by the production read loop itself (RecordingController.testPcmSource ->
 * injectedFrameSource), NOT a parallel test reimplementation, so the streaming caption code that only
 * runs in face-to-face mode is actually exercised. The assertion is on the streaming output (growing
 * liveSourcePreview captions during the turn) plus the committed translation, and the runner greps
 * LIVE_PARTIAL_BRANCH=streaming to confirm the transducer branch (not the chunked fallback) ran.
 *
 * Reaches the screen via UiAutomator (never createAndroidComposeRule, whose waitForIdle hangs here).
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateLivePipelineE2eTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  private fun s(@StringRes id: Int): String = context.getString(id)

  /** Boots the app to a model-ready Bao Translate screen via UiAutomator and returns the VM. */
  private fun reachReadyScreen(): BaoTranslateViewModel {
    BaoTranslateLiveTestSupport.prepareDevice(context)
    BaoTranslateLiveTestSupport.ensureRequiredModelsReady(context)
    device.executeShellCommand(
      "am start -f 0x04000000 -n ${context.packageName}/com.google.ai.edge.gallery.MainActivity"
    )
    assertTrue(
      "App home did not render",
      device.wait(Until.hasObject(By.pkg(context.packageName).desc(s(R.string.cd_menu))), 30_000) != null,
    )
    val card =
      device.wait(Until.findObject(By.descContains("Bao Translate task")), 15_000)
        ?: device.findObject(By.text(s(R.string.bao_translate)))
    requireNotNull(card) { "Bao Translate task card not found on home" }.click()
    device.wait(Until.findObject(By.text(s(R.string.bao_translate_welcome_get_started))), 5_000)?.click()
    assertTrue(
      "Start control never appeared — models did not finish loading",
      device.wait(Until.hasObject(By.desc(s(R.string.cd_bao_translate_start))), 180_000) != null,
    )
    return requireNotNull(BaoTranslateViewModel.testInstance) { "BaoTranslateViewModel not created" }
  }

  // Pad with 1.5s of trailing silence: a real mic keeps delivering quiet frames after you stop
  // talking, which is what fires the turn endpoint / silence auto-stop that commits the translation.
  private fun paddedEnglishPrompt(): ShortArray =
    BaoTranslateLiveTestSupport.englishPromptAsSttPcm() + ShortArray(16000 * 3 / 2)

  /**
   * Enters face-to-face with a known language pair, independent of any state a prior test persisted on
   * the shared ViewModel: toggle F2F OFF first so entering it again always runs a fresh STT reinit for
   * the chosen source. Waits for the reinit to settle.
   */
  private fun enterF2fWithLanguages(vm: BaoTranslateViewModel, sourceKey: String, targetKey: String) {
    // Sequence each state change with a settle-wait so the async STT reinits never overlap (two
    // in-flight reinits race and tear down the recording). Exit F2F -> set languages -> enter F2F.
    fun settle() {
      SystemClock.sleep(500)
      val deadline = SystemClock.uptimeMillis() + 60_000
      while (SystemClock.uptimeMillis() < deadline && vm.uiState.value.isInitializing) {
        SystemClock.sleep(100)
      }
      SystemClock.sleep(300)
    }
    instrumentation.runOnMainSync { vm.setFaceToFaceMode(false) }
    settle()
    instrumentation.runOnMainSync {
      vm.setTargetLanguage(targetKey)
      vm.setSourceLanguage(sourceKey)
    }
    settle()
    instrumentation.runOnMainSync { vm.setFaceToFaceMode(true) }
    settle()
    assertTrue("STT did not finish re-initialising for face-to-face mode", !vm.uiState.value.isInitializing)
  }

  @Test
  fun injectedEnglishSpeech_streamsThroughRealF2fLoop_andTranslates() {
    val promptPcm = paddedEnglishPrompt()
    val vm = reachReadyScreen()

    // English speaker -> sherpa transducer captions (LIVE_PARTIAL_BRANCH=streaming:en).
    enterF2fWithLanguages(vm, sourceKey = "English", targetKey = "Spanish")

    // Collect EVERY distinct streaming source caption emitted while the turn is spoken (a Flow
    // collector, not polling — the partials arrive in a sub-second burst).
    val captions = Collections.synchronizedList(mutableListOf<String>())
    val collectorScope = CoroutineScope(Dispatchers.Default)
    val collectorJob =
      collectorScope.launch {
        vm.uiState.map { it.liveSourcePreview?.trim() }.distinctUntilChanged().collect { caption ->
          if (!caption.isNullOrBlank()) captions.add(caption)
        }
      }

    RecordingController.testPcmSource = promptPcm
    val startNanos = SystemClock.elapsedRealtimeNanos()
    instrumentation.runOnMainSync { vm.startRecording() }

    // Let the real-time-paced audio play through the turn: the read loop streams growing captions,
    // and the F2F turn endpoint (trailing silence after the sentence) commits the translation — both
    // happen WITHIN the recording, exactly as on a live mic. Wait for both.
    var translated: String? = null
    val deadline = SystemClock.uptimeMillis() + 40_000
    while (SystemClock.uptimeMillis() < deadline && (captions.size < 2 || translated == null)) {
      translated =
        vm.uiState.value.transcripts.firstOrNull { it.isUser && it.translatedText.isNotBlank() }
          ?.translatedText
      SystemClock.sleep(40)
    }
    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000

    instrumentation.runOnMainSync { vm.stopRecording() }
    collectorJob.cancel()
    RecordingController.testPcmSource = null
    val seen = captions.toList()

    Log.i(
      "BaoLivePipelineTest",
      "REAL_LOOP_PARTIALS=${seen.size} ELAPSED_MS=$elapsedMs " +
        "first='${seen.firstOrNull()?.take(40)}' last='${seen.lastOrNull()?.take(40)}' " +
        "translated='${translated?.take(60)}'",
    )

    assertTrue(
      "Live F2F read loop streamed no growing source captions (expected >=2, saw ${seen.size}): $seen",
      seen.size >= 2,
    )
    // Growing hypothesis: a later caption strictly extends an earlier one (transducer streaming).
    assertTrue(
      "Captions did not GROW token-by-token (not streaming): $seen",
      seen.size >= 2 && seen.last().length > seen.first().length,
    )
    assertTrue("Live F2F loop produced no translation", translated != null)
  }

  /**
   * Non-face-to-face (single-speaker continuous) through the SAME real read loop, proving two things:
   * (1) streaming captions now appear in continuous mode too (not face-to-face only); and (2) a SHORT
   * utterance (under the 8s window, no turn endpoint) ended by tapping stop still commits its
   * translation — the NonCancellable tail flush (regression fix for the silently-dropped utterance).
   */
  @Test
  fun injectedEnglishSpeech_nonF2f_streamsCaptionAndTranslatesShortUtteranceOnStop() {
    // A SHORT prompt (~5s, under the 8s window) so neither a live window nor an endpoint fires — the
    // translation can only be committed by the stop-triggered tail flush.
    val promptPcm = paddedEnglishPrompt()
    val vm = reachReadyScreen()

    instrumentation.runOnMainSync {
      vm.setFaceToFaceMode(false)
      vm.setSourceLanguage("English")
      vm.setTargetLanguage("Spanish")
    }
    SystemClock.sleep(500)
    val reinitDeadline = SystemClock.uptimeMillis() + 60_000
    while (SystemClock.uptimeMillis() < reinitDeadline && vm.uiState.value.isInitializing) {
      SystemClock.sleep(100)
    }
    assertTrue("Expected non-face-to-face mode", !vm.uiState.value.faceToFaceMode)

    val captions = Collections.synchronizedList(mutableListOf<String>())
    val collectorScope = CoroutineScope(Dispatchers.Default)
    val collectorJob =
      collectorScope.launch {
        vm.uiState.map { it.liveSourcePreview?.trim() }.distinctUntilChanged().collect { c ->
          if (!c.isNullOrBlank()) captions.add(c)
        }
      }

    RecordingController.testPcmSource = promptPcm
    instrumentation.runOnMainSync { vm.startRecording() }

    // Let the ~5s paced prompt play out + captions stream; then stop (commits via the tail flush).
    val playDeadline = SystemClock.uptimeMillis() + 12_000
    while (SystemClock.uptimeMillis() < playDeadline && captions.size < 2) SystemClock.sleep(40)
    SystemClock.sleep(3_000)
    instrumentation.runOnMainSync { vm.stopRecording() }

    var translated: String? = null
    val transDeadline = SystemClock.uptimeMillis() + 30_000
    while (SystemClock.uptimeMillis() < transDeadline && translated == null) {
      translated =
        vm.uiState.value.transcripts.firstOrNull { it.isUser && it.translatedText.isNotBlank() }
          ?.translatedText
      SystemClock.sleep(50)
    }
    collectorJob.cancel()
    RecordingController.testPcmSource = null
    val seen = captions.toList()

    Log.i(
      "BaoLivePipelineTest",
      "NON_F2F_PARTIALS=${seen.size} first='${seen.firstOrNull()?.take(30)}' translated='${translated?.take(60)}'",
    )
    assertTrue("Non-F2F continuous mode streamed no live caption (saw ${seen.size}): $seen", seen.size >= 2)
    assertTrue("Short non-F2F utterance lost its translation on stop (NonCancellable flush)", translated != null)
  }

  /**
   * Multilingual proof: a SPANISH speaker through the real F2F read loop must caption via the Vosk
   * Spanish streaming engine (LIVE_PARTIAL_BRANCH=streaming:es) — not the English transducer and not
   * chunked — then translate. This is the difference between "first-class for English" and
   * "first-class for the app's languages."
   */
  @Test
  fun injectedSpanishSpeech_streamsViaVosk_throughRealF2fLoop() {
    // Ensure the Spanish caption model is provisioned (the read loop only streams a ready language).
    runBlocking { BaoTranslateModelManager.downloadCaptionModel(context, "es") }
    val promptPcm = BaoTranslateLiveTestSupport.spanishPromptAsSttPcm() + ShortArray(16000 * 3 / 2)
    val vm = reachReadyScreen()

    // Spanish speaker -> Vosk Spanish captions (streaming:es), translating to English.
    enterF2fWithLanguages(vm, sourceKey = "Spanish", targetKey = "English")

    val captions = Collections.synchronizedList(mutableListOf<String>())
    val collectorScope = CoroutineScope(Dispatchers.Default)
    val collectorJob =
      collectorScope.launch {
        vm.uiState.map { it.liveSourcePreview?.trim() }.distinctUntilChanged().collect { caption ->
          if (!caption.isNullOrBlank()) captions.add(caption)
        }
      }

    RecordingController.testPcmSource = promptPcm
    instrumentation.runOnMainSync { vm.startRecording() }

    var translated: String? = null
    val deadline = SystemClock.uptimeMillis() + 40_000
    while (SystemClock.uptimeMillis() < deadline && (captions.size < 2 || translated == null)) {
      translated =
        vm.uiState.value.transcripts.firstOrNull { it.isUser && it.translatedText.isNotBlank() }
          ?.translatedText
      SystemClock.sleep(40)
    }

    instrumentation.runOnMainSync { vm.stopRecording() }
    collectorJob.cancel()
    RecordingController.testPcmSource = null
    val seen = captions.toList()

    Log.i(
      "BaoLivePipelineTest",
      "ES_REAL_LOOP_PARTIALS=${seen.size} first='${seen.firstOrNull()?.take(40)}' " +
        "last='${seen.lastOrNull()?.take(40)}' translated='${translated?.take(60)}'",
    )
    assertTrue("Spanish live loop streamed no growing captions (saw ${seen.size}): $seen", seen.size >= 2)
    assertTrue(
      "Spanish captions did not grow token-by-token (not streaming): $seen",
      seen.size >= 2 && seen.last().length > seen.first().length,
    )
    assertTrue("Spanish live loop produced no translation", translated != null)
  }
}
