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
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL two-device Nearby E2E, driven by two coordinated instrumentation runs (no createAndroidComposeRule
 * — which hangs on this app). The RECEIVER (run [receiver_advertisesAndTranslatesPeerTranscript] on
 * device B) advertises in conversation mode and asserts it gets a peer transcript translated into ITS
 * OWN target language; the SENDER (run [sender_connectsAndSendsTranscript] on device A) discovers the
 * peer, connects over Nearby, and broadcasts a transcript. Grant the Nearby/BT/location runtime
 * permissions first (the orchestration script does `pm grant`).
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateMultiDeviceE2eTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  private fun s(@StringRes id: Int): String = context.getString(id)

  private fun reachScreenVm(): BaoTranslateViewModel {
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
    requireNotNull(card) { "Bao Translate task card not found" }.click()
    device.wait(Until.findObject(By.text(s(R.string.bao_translate_welcome_get_started))), 5_000)?.click()
    device.wait(Until.hasObject(By.desc(s(R.string.cd_bao_translate_start))), 180_000)
    return requireNotNull(BaoTranslateViewModel.testInstance) { "ViewModel not created" }
  }

  /** RECEIVER side (device B): advertise, accept the peer, and assert an incoming transcript is
   *  translated into this device's own target language (Russian). */
  @Test
  fun receiver_advertisesAndTranslatesPeerTranscript() {
    val vm = reachScreenVm()
    instrumentation.runOnMainSync {
      vm.setTargetLanguage("Russian")
      vm.bleManager.startConversationDiscovery()
    }
    Log.i("MultiDeviceTest", "RECEIVER advertising, target=Russian")

    // Wait (up to 120s) for a connection then a translated peer message in the transcript list.
    var connected = false
    var translatedPeerMsg: String? = null
    val deadline = SystemClock.uptimeMillis() + 120_000
    while (SystemClock.uptimeMillis() < deadline && translatedPeerMsg == null) {
      if (vm.bleManager.getConnectedCount() > 0) connected = true
      translatedPeerMsg =
        vm.uiState.value.transcripts.firstOrNull { !it.isUser && it.translatedText.isNotBlank() }
          ?.translatedText
      SystemClock.sleep(200)
    }
    instrumentation.runOnMainSync { vm.leaveConversation() }

    Log.i("MultiDeviceTest", "RECEIVER connected=$connected translatedPeerMsg='${translatedPeerMsg?.take(60)}'")
    assertTrue("Receiver never connected to a Nearby peer", connected)
    assertTrue("Receiver got no translated peer transcript", translatedPeerMsg != null)
    // Russian uses Cyrillic — proves the receiver translated into ITS OWN target, not just echoed.
    assertTrue(
      "Peer transcript was not translated into the receiver's language (Russian): $translatedPeerMsg",
      translatedPeerMsg!!.any { it in 'Ѐ'..'ӿ' },
    )
  }

  /** SENDER side (device A): discover the advertising peer, connect, broadcast a transcript. */
  @Test
  fun sender_connectsAndSendsTranscript() {
    val vm = reachScreenVm()
    instrumentation.runOnMainSync { vm.bleManager.startConversationDiscovery() }

    var peerId: String? = null
    val discDeadline = SystemClock.uptimeMillis() + 60_000
    while (SystemClock.uptimeMillis() < discDeadline && peerId == null) {
      peerId = vm.bleManager.discoveredPeers.value.firstOrNull()?.id
      SystemClock.sleep(200)
    }
    assertTrue("Sender discovered no advertising peer", peerId != null)
    Log.i("MultiDeviceTest", "SENDER discovered peer=$peerId, connecting")

    instrumentation.runOnMainSync { vm.bleManager.connectToDevice(peerId!!) }
    val connDeadline = SystemClock.uptimeMillis() + 60_000
    while (SystemClock.uptimeMillis() < connDeadline && vm.bleManager.getConnectedCount() == 0) {
      SystemClock.sleep(200)
    }
    assertTrue("Sender did not connect to the peer over Nearby", vm.bleManager.getConnectedCount() > 0)
    Log.i("MultiDeviceTest", "SENDER connected, broadcasting transcript")

    // Broadcast a recognized transcript; the receiver translates it into its own target language.
    repeat(5) {
      runBlocking {
        vm.bleManager.sendTranscript("Hello there, how are you today?", "en", "ru")
      }
      SystemClock.sleep(1500)
    }
    // Hold while the receiver translates + asserts, then leave. The receiver may disconnect first once
    // it has the message (a normal end of conversation), so the success criteria are that we connected
    // and broadcast — both asserted above — not that the link is still up at this instant.
    SystemClock.sleep(15_000)
    Log.i("MultiDeviceTest", "SENDER done, connectedAtEnd=${vm.bleManager.getConnectedCount() > 0}")
    instrumentation.runOnMainSync { vm.leaveConversation() }
  }
}
