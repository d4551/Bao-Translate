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

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.ai.edge.gallery.customtasks.baotranslate.ConversationModeScreen
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the #5 dead-in fix end-to-end at the UI layer: when a peer is connected, ConversationMode
 * renders the "Disconnect" control (previously [BleConversationManager.disconnectFromDevice] existed
 * but was unreachable from any UI), and tapping it reports the peer's address to the caller. Smoke
 * tests never reach this state (no BLE peer), so this is the only coverage of the rendered button.
 */
@RunWith(AndroidJUnit4::class)
class ConversationDisconnectUiTest {

  @get:Rule
  val compose = createComposeRule()

  @Test
  fun connectedPeer_showsDisconnect_andClickReportsAddress() {
    var disconnectedId: String? = null
    compose.setContent {
      MaterialTheme {
        ConversationModeScreen(
          localParticipant = null,
          remoteParticipants = listOf(
            Participant(
              id = "peer-XYZ",
              name = "Alice",
              sourceLanguage = "en",
              targetLanguage = "es",
              isConnected = true,
            ),
          ),
          discoveredPeers = emptyList(),
          isScanning = false,
          connectionState = ConnectionState.CONNECTED,
          connectingPeers = emptySet(),
          currentAudioDevice = AudioDevice.Speaker,
          onScanDevices = {},
          onStopScan = {},
          onConnectDevice = {},
          onDisconnectDevice = { disconnectedId = it },
          onStartConversation = {},
        )
      }
    }

    compose.onNodeWithText("Disconnect").assertIsDisplayed().performClick()
    assertEquals("peer-XYZ", disconnectedId)
  }

  // ----- BRUTALISATION -----

  // ----- Empty remote participants: no Disconnect button.
  @Test
  fun emptyRemoteParticipants_noDisconnectButton() {
    compose.setContent {
      MaterialTheme {
        ConversationModeScreen(
          localParticipant = null,
          remoteParticipants = emptyList(),
          discoveredPeers = emptyList(),
          isScanning = false,
          connectionState = ConnectionState.DISCONNECTED,
          connectingPeers = emptySet(),
          currentAudioDevice = AudioDevice.Speaker,
          onScanDevices = {},
          onStopScan = {},
          onConnectDevice = {},
          onDisconnectDevice = {},
          onStartConversation = {},
        )
      }
    }
    compose.onNodeWithText("Disconnect").assertDoesNotExist()
  }

  // ----- Disconnected peer: no Disconnect button.
  @Test
  fun disconnectedPeer_noDisconnectButton() {
    compose.setContent {
      MaterialTheme {
        ConversationModeScreen(
          localParticipant = null,
          remoteParticipants = listOf(
            Participant(
              id = "peer-XYZ",
              name = "Alice",
              sourceLanguage = "en",
              targetLanguage = "es",
              isConnected = false,  // disconnected
            ),
          ),
          discoveredPeers = emptyList(),
          isScanning = false,
          connectionState = ConnectionState.DISCONNECTED,
          connectingPeers = emptySet(),
          currentAudioDevice = AudioDevice.Speaker,
          onScanDevices = {},
          onStopScan = {},
          onConnectDevice = {},
          onDisconnectDevice = {},
          onStartConversation = {},
        )
      }
    }
    compose.onNodeWithText("Disconnect").assertDoesNotExist()
  }

  // ----- Multiple remote peers: each has its own Disconnect button.
  @Test
  fun multipleRemotePeers_eachHasItsOwnDisconnect() {
    var disconnectedId: String? = null
    compose.setContent {
      MaterialTheme {
        ConversationModeScreen(
          localParticipant = null,
          remoteParticipants = listOf(
            Participant(id = "peer-1", name = "Alice", sourceLanguage = "en", targetLanguage = "es", isConnected = true),
            Participant(id = "peer-2", name = "Bob", sourceLanguage = "en", targetLanguage = "es", isConnected = true),
          ),
          discoveredPeers = emptyList(),
          isScanning = false,
          connectionState = ConnectionState.CONNECTED,
          connectingPeers = emptySet(),
          currentAudioDevice = AudioDevice.Speaker,
          onScanDevices = {},
          onStopScan = {},
          onConnectDevice = {},
          onDisconnectDevice = { disconnectedId = it },
          onStartConversation = {},
        )
      }
    }
    val allDisconnects = compose.onAllNodesWithText("Disconnect")
    assertEquals(2, allDisconnects.fetchSemanticsNodes().size)

    allDisconnects[0].performClick()
    assertEquals("first click must report peer-1", "peer-1", disconnectedId)
    disconnectedId = null
    allDisconnects[1].performClick()
    assertEquals("second click must report peer-2", "peer-2", disconnectedId)
  }

  // ----- Connecting state: a connecting peer should NOT have a Disconnect button yet.
  @Test
  fun connectingPeers_noDisconnectButton() {
    compose.setContent {
      MaterialTheme {
        ConversationModeScreen(
          localParticipant = null,
          remoteParticipants = emptyList(),
          discoveredPeers = emptyList(),
          isScanning = false,
          connectionState = ConnectionState.CONNECTING,
          connectingPeers = setOf("peer-XYZ"),
          currentAudioDevice = AudioDevice.Speaker,
          onScanDevices = {},
          onStopScan = {},
          onConnectDevice = {},
          onDisconnectDevice = {},
          onStartConversation = {},
        )
      }
    }
    compose.onNodeWithText("Disconnect").assertDoesNotExist()
  }
}
