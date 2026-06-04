package com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.bluetooth.communicator.BluetoothCommunicator
import com.bluetooth.communicator.Message
import com.bluetooth.communicator.Peer
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private const val TAG = "BleConversationMgr"
private const val SERVICE_NAME = "bao_translate"
private const val MSG_TRANSCRIPT = "T"
private const val MSG_METADATA = "M"
private const val MAX_BLE_MESSAGE_SIZE = 8192
private const val MAX_TEXT_LENGTH = 4096

@Serializable
data class BleTranscriptMessage(
  val text: String,
  val senderId: String,
  val senderName: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class BleMetadataMessage(
  val participantId: String,
  val participantName: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val hasVoiceProfile: Boolean,
)

data class DiscoveredPeer(
  val id: String,
  val name: String,
  val deviceAddress: String,
)

enum class ConnectionState {
  DISCONNECTED,
  ADVERTISING,
  SCANNING,
  CONNECTING,
  CONNECTED,
}

class BleConversationManager(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }
  private val scopeJob = SupervisorJob()
  private val scope = CoroutineScope(scopeJob + Dispatchers.Main)

  private var communicator: BluetoothCommunicator? = null

  private val _participants = MutableStateFlow<List<Participant>>(emptyList())
  val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

  private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
  val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  private val _messages = MutableSharedFlow<BleTranscriptMessage>(replay = 0)
  val messages: SharedFlow<BleTranscriptMessage> = _messages

  private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private val _connectingPeers = MutableStateFlow<Set<String>>(emptySet())
  val connectingPeers: StateFlow<Set<String>> = _connectingPeers.asStateFlow()

  private var localParticipant: Participant? = null
  private val peerMap = ConcurrentHashMap<String, Peer>()
  private val discoveredPeerMap = ConcurrentHashMap<String, Peer>()

  private fun ensureCommunicator(): BluetoothCommunicator? {
    if (communicator == null) {
      initializeCommunicator()
    }
    return communicator
  }

  private fun initializeCommunicator() {
    communicator = BluetoothCommunicator(
      context,
      SERVICE_NAME,
      BluetoothCommunicator.STRATEGY_P2P_WITH_RECONNECTION,
    )

    communicator?.addCallback(object : BluetoothCommunicator.Callback() {
      override fun onPeerFound(peer: Peer) {
        BaoLog.i(TAG, "Peer found: ${peer.name}")
        discoveredPeerMap[peer.uniqueName] = peer
        val discovered = DiscoveredPeer(
          id = peer.uniqueName,
          name = peer.name ?: context.getString(R.string.bao_ble_unknown_device),
          deviceAddress = peer.uniqueName,
        )
        val current = _discoveredPeers.value.toMutableList()
        if (current.none { it.id == peer.uniqueName }) {
          current.add(discovered)
          _discoveredPeers.value = current
        }
      }

      override fun onPeerLost(peer: Peer) {
        BaoLog.i(TAG, "Peer lost: ${peer.name}")
        peerMap.remove(peer.uniqueName)
        _participants.value = _participants.value.filter { it.id != peer.uniqueName }
        _discoveredPeers.value = _discoveredPeers.value.filter { it.id != peer.uniqueName }
      }

      override fun onConnectionSuccess(peer: Peer, role: Int) {
        BaoLog.i(TAG, "Connected to: ${peer.name}")
        // Keep scanning to support multiple device connections
        peerMap[peer.uniqueName] = peer
        _connectingPeers.value = _connectingPeers.value - peer.uniqueName

        val remoteParticipant = Participant(
          id = peer.uniqueName,
          name = peer.name ?: context.getString(R.string.bao_ble_unknown_device),
          sourceLanguage = SupportedLanguages.AUTO.key,
          targetLanguage = PipelineConfig.DEFAULT_TARGET_LANGUAGE,
          isConnected = true,
          hasVoiceProfile = false,
          audioDeviceName = null,
        )

        val current = _participants.value.toMutableList()
        if (current.none { it.id == peer.uniqueName }) {
          current.add(remoteParticipant)
          _participants.value = current
        }

        if (_participants.value.any { it.isConnected }) {
          _connectionState.value = ConnectionState.CONNECTED
        }

        sendMetadata()
      }

      override fun onConnectionLost(peer: Peer) {
        BaoLog.w(TAG, "Connection lost: ${peer.name}")
        val current = _participants.value.toMutableList()
        val index = current.indexOfFirst { it.id == peer.uniqueName }
        if (index >= 0) {
          current[index] = current[index].copy(isConnected = false)
          _participants.value = current
        }
        if (_participants.value.none { it.isConnected }) {
          _connectionState.value = ConnectionState.DISCONNECTED
        }
      }

      override fun onConnectionResumed(peer: Peer) {
        BaoLog.i(TAG, "Connection resumed: ${peer.name}")
        val current = _participants.value.toMutableList()
        val index = current.indexOfFirst { it.id == peer.uniqueName }
        if (index >= 0) {
          current[index] = current[index].copy(isConnected = true)
          _participants.value = current
        }
        if (_participants.value.any { it.isConnected }) {
          _connectionState.value = ConnectionState.CONNECTED
        }
      }

      override fun onMessageReceived(message: Message, direction: Int) {
        handleIncomingMessage(message)
      }

      override fun onDataReceived(message: Message, direction: Int) {
        handleIncomingMessage(message)
      }

      override fun onConnectionFailed(peer: Peer, errorCode: Int) {
        BaoLog.e(TAG, "Connection failed: ${peer.name}, error=$errorCode")
        _connectingPeers.value = _connectingPeers.value - peer.uniqueName
        if (_participants.value.none { it.isConnected } && _connectingPeers.value.isEmpty()) {
          _connectionState.value = ConnectionState.DISCONNECTED
        }
      }

      override fun onDisconnected(peer: Peer, errorCode: Int) {
        BaoLog.i(TAG, "Disconnected: ${peer.name}")
        peerMap.remove(peer.uniqueName)
        _participants.value = _participants.value.filter { it.id != peer.uniqueName }
        if (_participants.value.isEmpty()) {
          _connectionState.value = ConnectionState.DISCONNECTED
        }
      }
    })
  }

  private fun isValidTranscriptJson(payload: String): Boolean {
    return payload.startsWith("{") &&
      payload.endsWith("}") &&
      payload.contains("\"text\"") &&
      payload.contains("\"senderId\"") &&
      payload.contains("\"senderName\"") &&
      payload.contains("\"sourceLanguage\"") &&
      payload.contains("\"targetLanguage\"")
  }

  private fun isValidMetadataJson(payload: String): Boolean {
    return payload.startsWith("{") &&
      payload.endsWith("}") &&
      payload.contains("\"participantId\"") &&
      payload.contains("\"participantName\"") &&
      payload.contains("\"sourceLanguage\"") &&
      payload.contains("\"targetLanguage\"") &&
      payload.contains("\"hasVoiceProfile\"")
  }

  private fun handleIncomingMessage(message: Message) {
    val text = message.text ?: return
    if (text.isEmpty() || text.length > MAX_BLE_MESSAGE_SIZE) {
      BaoLog.w(TAG, "Invalid message size: ${text.length}")
      return
    }

    val sender = message.sender
    if (sender == null || !peerMap.containsKey(sender.uniqueName)) {
      BaoLog.w(TAG, "Message from unknown sender: ${sender?.uniqueName}")
      return
    }

    val header = text.first().toString()
    val payload = text.drop(1)

    when (header) {
      MSG_TRANSCRIPT -> {
        if (!isValidTranscriptJson(payload)) {
          BaoLog.w(TAG, "Invalid transcript JSON structure")
          return
        }
        val transcript = decodeTranscript(payload) ?: return
        if (transcript.text.length > MAX_TEXT_LENGTH) {
          BaoLog.w(TAG, "Transcript text too long: ${transcript.text.length}")
          return
        }
        val trustedTranscript = transcript.copy(senderId = sender.uniqueName)
        scope.launch {
          _messages.emit(trustedTranscript)
        }
      }
      MSG_METADATA -> {
        if (!isValidMetadataJson(payload)) {
          BaoLog.w(TAG, "Invalid metadata JSON structure")
          return
        }
        val metadata = decodeMetadata(payload) ?: return
        updateParticipantFromMetadata(sender, metadata)
      }
    }
  }

  private fun decodeTranscript(payload: String): BleTranscriptMessage? {
    val element = json.decodeFromString<kotlinx.serialization.json.JsonElement>(payload)
    val obj = element as? kotlinx.serialization.json.JsonObject ?: run {
      BaoLog.w(TAG, "Transcript payload is not a JSON object")
      return null
    }
    val text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: run {
      BaoLog.w(TAG, "Transcript missing text field")
      return null
    }
    val senderId = (obj["senderId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val senderName = (obj["senderName"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val sourceLanguage = (obj["sourceLanguage"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val targetLanguage = (obj["targetLanguage"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val timestamp = (obj["timestamp"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
    return BleTranscriptMessage(text, senderId, senderName, sourceLanguage, targetLanguage, timestamp)
  }

  private fun decodeMetadata(payload: String): BleMetadataMessage? {
    val element = json.decodeFromString<kotlinx.serialization.json.JsonElement>(payload)
    val obj = element as? kotlinx.serialization.json.JsonObject ?: run {
      BaoLog.w(TAG, "Metadata payload is not a JSON object")
      return null
    }
    val participantId = (obj["participantId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: run {
      BaoLog.w(TAG, "Metadata missing participantId")
      return null
    }
    val participantName = (obj["participantName"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val sourceLanguage = (obj["sourceLanguage"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val targetLanguage = (obj["targetLanguage"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
    val hasVoiceProfile = (obj["hasVoiceProfile"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
    return BleMetadataMessage(participantId, participantName, sourceLanguage, targetLanguage, hasVoiceProfile)
  }

  private fun updateParticipantFromMetadata(source: Peer, metadata: BleMetadataMessage) {
    val current = _participants.value.toMutableList()
    val index = current.indexOfFirst { it.id == source.uniqueName }
    if (index >= 0) {
      current[index] = current[index].copy(
        name = metadata.participantName,
        sourceLanguage = metadata.sourceLanguage,
        targetLanguage = metadata.targetLanguage,
        hasVoiceProfile = metadata.hasVoiceProfile,
      )
      _participants.value = current
    }
  }

  fun setLocalParticipant(participant: Participant) {
    localParticipant = participant
    if (peerMap.isNotEmpty()) {
      sendMetadata()
    }
  }

  fun startAdvertising() {
    val comm = ensureCommunicator() ?: return
    localParticipant ?: return
    comm.startAdvertising()
    _connectionState.value = ConnectionState.ADVERTISING
    BaoLog.i(TAG, "Started advertising")
  }

  fun stopAdvertising() {
    ensureCommunicator()?.stopAdvertising(true)
    if (_participants.value.isEmpty()) {
      _connectionState.value = ConnectionState.DISCONNECTED
    }
  }

  fun startScanning() {
    val comm = ensureCommunicator() ?: return
    comm.startDiscovery()
    _isScanning.value = true
    _connectionState.value = ConnectionState.SCANNING
    BaoLog.i(TAG, "Started scanning")
  }

  fun stopScanning() {
    ensureCommunicator()?.stopDiscovery(true)
    _isScanning.value = false
    if (_participants.value.isEmpty()) {
      _connectionState.value = ConnectionState.DISCONNECTED
    }
  }

  fun connectToDevice(deviceAddress: String) {
    _connectingPeers.value = _connectingPeers.value + deviceAddress
    val peer = discoveredPeerMap[deviceAddress] ?: peerMap[deviceAddress]
    if (peer != null) {
      ensureCommunicator()?.connect(peer)
    } else {
      BaoLog.w(TAG, "No peer found for address: $deviceAddress")
      _connectingPeers.value = _connectingPeers.value - deviceAddress
    }
  }

  fun disconnectFromDevice(deviceAddress: String) {
    val peer = peerMap[deviceAddress]
    if (peer != null) {
      ensureCommunicator()?.disconnect(peer)
    }
    peerMap.remove(deviceAddress)
    _participants.value = _participants.value.filter { it.id != deviceAddress }
    if (_participants.value.isEmpty()) {
      _connectionState.value = ConnectionState.DISCONNECTED
    }
  }

  suspend fun sendTranscript(text: String, sourceLanguage: String, targetLanguage: String) {
    val local = localParticipant ?: return
    val comm = ensureCommunicator() ?: return

    val message = BleTranscriptMessage(
      text = text,
      senderId = local.id,
      senderName = local.name,
      sourceLanguage = sourceLanguage,
      targetLanguage = targetLanguage,
    )

    val jsonStr = MSG_TRANSCRIPT + json.encodeToString(BleTranscriptMessage.serializer(), message)
    comm.sendMessage(Message(context, jsonStr))
    BaoLog.i(TAG, "Sent transcript: ${text.take(50)}...")
  }

  private fun sendMetadata() {
    val local = localParticipant ?: return
    val comm = ensureCommunicator() ?: return

    val metadata = BleMetadataMessage(
      participantId = local.id,
      participantName = local.name,
      sourceLanguage = local.sourceLanguage,
      targetLanguage = local.targetLanguage,
      hasVoiceProfile = local.hasVoiceProfile,
    )

    val jsonStr = MSG_METADATA + json.encodeToString(BleMetadataMessage.serializer(), metadata)
    comm.sendMessage(Message(context, jsonStr))
  }

  fun getConnectedCount(): Int = _participants.value.count { it.isConnected }

  fun cleanup() {
    scopeJob.cancel()
    val comm = communicator
    communicator = null
    peerMap.clear()
    discoveredPeerMap.clear()
    _participants.value = emptyList()
    _discoveredPeers.value = emptyList()
    _isScanning.value = false
    _connectionState.value = ConnectionState.DISCONNECTED
    _connectingPeers.value = emptySet()
    comm?.destroy(object : BluetoothCommunicator.DestroyCallback {
      override fun onDestroyed() {
        BaoLog.i(TAG, "Communicator destroyed")
      }
    })
    BaoLog.i(TAG, "Cleaned up")
  }
}
