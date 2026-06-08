package com.google.ai.edge.gallery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
}
