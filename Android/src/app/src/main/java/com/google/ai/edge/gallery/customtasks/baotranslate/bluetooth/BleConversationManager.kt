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
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull

private const val TAG = "BleConversationMgr"
private const val SERVICE_NAME = "bao_translate"
private const val MSG_TRANSCRIPT = "T"
private const val MSG_METADATA = "M"
internal const val MAX_BLE_MESSAGE_SIZE = 8192
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
  // The sender's 256-d OpenVoice speaker embedding (~2.5 KB as JSON), shared so the receiver can
  // speak this peer's translated turns in THAT peer's own voice (multi-speaker voice cloning).
  // Null/omitted when the peer hasn't enrolled a voice. Well under MAX_BLE_MESSAGE_SIZE.
  val voiceEmbedding: List<Float>? = null,
)

/**
 * Pure (Context-free) encode/validate/decode for BLE conversation payloads. Decoding is DEFENSIVE:
 * a connected peer — or link corruption from a legitimate peer — can put arbitrary bytes on the
 * wire. The kotlinx decode is wrapped to return null on any SerializationException instead of
 * throwing out of the BluetoothCommunicator main-thread callback and crashing the process.
 * Standalone object so the decode contract is unit-testable without a Context.
 */
internal object BleMessageCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun encodeTranscript(message: BleTranscriptMessage): String =
    MSG_TRANSCRIPT + json.encodeToString(BleTranscriptMessage.serializer(), message)

  fun encodeMetadata(message: BleMetadataMessage): String =
    MSG_METADATA + json.encodeToString(BleMetadataMessage.serializer(), message)

  fun isValidTranscriptJson(payload: String): Boolean =
    payload.startsWith("{") && payload.endsWith("}") &&
      payload.contains("\"text\"") &&
      payload.contains("\"senderId\"") &&
      payload.contains("\"senderName\"") &&
      payload.contains("\"sourceLanguage\"") &&
      payload.contains("\"targetLanguage\"")

  fun isValidMetadataJson(payload: String): Boolean =
    payload.startsWith("{") && payload.endsWith("}") &&
      payload.contains("\"participantId\"") &&
      payload.contains("\"participantName\"") &&
      payload.contains("\"sourceLanguage\"") &&
      payload.contains("\"targetLanguage\"") &&
      payload.contains("\"hasVoiceProfile\"")

  fun decodeTranscript(payload: String): BleTranscriptMessage? {
    val obj = parseObject(payload) ?: return null
    val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return null
    val senderId = (obj["senderId"] as? JsonPrimitive)?.contentOrNull ?: ""
    val senderName = (obj["senderName"] as? JsonPrimitive)?.contentOrNull ?: ""
    val sourceLanguage = (obj["sourceLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val targetLanguage = (obj["targetLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val timestamp = (obj["timestamp"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
    return BleTranscriptMessage(text, senderId, senderName, sourceLanguage, targetLanguage, timestamp)
  }

  fun decodeMetadata(payload: String): BleMetadataMessage? {
    val obj = parseObject(payload) ?: return null
    val participantId = (obj["participantId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val participantName = (obj["participantName"] as? JsonPrimitive)?.contentOrNull ?: ""
    val sourceLanguage = (obj["sourceLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val targetLanguage = (obj["targetLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val hasVoiceProfile = (obj["hasVoiceProfile"] as? JsonPrimitive)?.booleanOrNull ?: false
    val voiceEmbedding = (obj["voiceEmbedding"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
      ?.takeIf { it.size == OpenVoiceVoiceConverter.SE_DIM }
    return BleMetadataMessage(participantId, participantName, sourceLanguage, targetLanguage, hasVoiceProfile, voiceEmbedding)
  }

  // Never throws: malformed JSON (passes the substring gate but isn't parseable) yields null.
  private fun parseObject(payload: String): JsonObject? =
    runCatching { json.decodeFromString<JsonElement>(payload) }.getOrNull() as? JsonObject
}

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

  /**
   * Test-only: deliver an incoming peer transcript through the exact same flow a real BLE message
   * would, so instrumentation can verify multi-speaker receive routing (translate peer language ->
   * local target, attribute to the speaker, speak) without a second physical device. Suspends until
   * the collector (the ViewModel) receives it.
   */
  internal suspend fun simulateIncomingTranscriptForTest(message: BleTranscriptMessage) {
    _messages.emit(message)
  }

  /**
   * Test-only: apply an incoming peer metadata update through the same path as a real BLE message,
   * so instrumentation can verify multi-speaker embedding routing without a second physical device.
   */
  internal fun simulateIncomingMetadataForTest(peerId: String, metadata: BleMetadataMessage) {
    applyMetadataForPeer(peerId, metadata)
  }

  private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private val _connectingPeers = MutableStateFlow<Set<String>>(emptySet())
  val connectingPeers: StateFlow<Set<String>> = _connectingPeers.asStateFlow()

  private var localParticipant: Participant? = null
  private val peerMap = ConcurrentHashMap<String, Peer>()
  private val discoveredPeerMap = ConcurrentHashMap<String, Peer>()

  // Multi-speaker voice cloning: each connected peer's timbre (received from their metadata, keyed
  // by peer uniqueName). The receive path looks up [voiceEmbeddingFor] so a peer's translated turn
  // is spoken in their voice. The local timbre is read on demand via [localEmbeddingProvider].
  private var localEmbeddingProvider: () -> FloatArray? = { null }
  private val peerVoiceEmbeddings = ConcurrentHashMap<String, FloatArray>()

  /** Supplies the local enrolled timbre when metadata is broadcast to peers. */
  fun setLocalEmbeddingProvider(provider: () -> FloatArray?) {
    localEmbeddingProvider = provider
  }

  /** Re-broadcasts local participant metadata (including the current embedding) to connected peers. */
  fun rebroadcastMetadata() {
    if (peerMap.isNotEmpty()) sendMetadata()
  }

  /** The connected peer's enrolled timbre (256-d), or null if they haven't shared/enrolled one. */
  fun voiceEmbeddingFor(peerId: String): FloatArray? = peerVoiceEmbeddings[peerId]

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
        peerVoiceEmbeddings.remove(peer.uniqueName)
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
        peerVoiceEmbeddings.remove(peer.uniqueName)
        _participants.value = _participants.value.filter { it.id != peer.uniqueName }
        if (_participants.value.isEmpty()) {
          _connectionState.value = ConnectionState.DISCONNECTED
        }
      }
    })
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
        if (!BleMessageCodec.isValidTranscriptJson(payload)) {
          BaoLog.w(TAG, "Invalid transcript JSON structure")
          return
        }
        val transcript = BleMessageCodec.decodeTranscript(payload) ?: run {
          BaoLog.w(TAG, "Dropping malformed transcript payload")
          return
        }
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
        if (!BleMessageCodec.isValidMetadataJson(payload)) {
          BaoLog.w(TAG, "Invalid metadata JSON structure")
          return
        }
        val metadata = BleMessageCodec.decodeMetadata(payload) ?: run {
          BaoLog.w(TAG, "Dropping malformed metadata payload")
          return
        }
        updateParticipantFromMetadata(sender, metadata)
      }
    }
  }

  private fun updateParticipantFromMetadata(source: Peer, metadata: BleMetadataMessage) {
    applyMetadataForPeer(source.uniqueName, metadata)
  }

  private fun applyMetadataForPeer(peerId: String, metadata: BleMetadataMessage) {
    // Store/clear the peer's shared timbre so their translated turns can be spoken in their voice.
    val embedding = metadata.voiceEmbedding?.takeIf { it.size == OpenVoiceVoiceConverter.SE_DIM }
    if (embedding != null) {
      peerVoiceEmbeddings[peerId] = embedding.toFloatArray()
    } else {
      peerVoiceEmbeddings.remove(peerId)
    }
    val hasVoiceProfile = embedding != null
    val current = _participants.value.toMutableList()
    val index = current.indexOfFirst { it.id == peerId }
    if (index >= 0) {
      current[index] = current[index].copy(
        name = metadata.participantName,
        sourceLanguage = metadata.sourceLanguage,
        targetLanguage = metadata.targetLanguage,
        hasVoiceProfile = hasVoiceProfile,
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

  // With STRATEGY_P2P_WITH_RECONNECTION two devices can only find each other if at least one
  // advertises while the other scans. Two scanners never pair. Doing both makes discovery symmetric
  // so a pair of phones reliably finds each other regardless of who taps first. Advertising is
  // best-effort — it no-ops until the local participant is set (see startAdvertising). Scanning is
  // started last so the user-facing connection state reads SCANNING.
  fun startConversationDiscovery() {
    startAdvertising()
    startScanning()
  }

  fun stopConversationDiscovery() {
    stopScanning()
    stopAdvertising()
  }

  fun connectToDevice(deviceAddress: String) {
    _connectingPeers.value = _connectingPeers.value + deviceAddress
    val peer = discoveredPeerMap[deviceAddress] ?: peerMap[deviceAddress]
    if (peer != null) {
      // Reflect the in-progress connect in the top-level status so the UI's CONNECTING branch is
      // reachable; don't downgrade an already-CONNECTED session.
      if (_connectionState.value != ConnectionState.CONNECTED) {
        _connectionState.value = ConnectionState.CONNECTING
      }
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
    peerVoiceEmbeddings.remove(deviceAddress)
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

    comm.sendMessage(Message(context, BleMessageCodec.encodeTranscript(message)))
    // Log length only — the transcript is user speech content (PII), not diagnostics.
    BaoLog.i(TAG, "Sent transcript: ${text.length} chars")
  }

  private fun sendMetadata() {
    val local = localParticipant ?: return
    val comm = ensureCommunicator() ?: return

    val rawEmbedding = localEmbeddingProvider()?.toList()
    val voiceEmbedding = rawEmbedding?.takeIf { it.size == OpenVoiceVoiceConverter.SE_DIM }
    var hasVoiceProfile = voiceEmbedding != null
    var metadata = BleMetadataMessage(
      participantId = local.id,
      participantName = local.name,
      sourceLanguage = local.sourceLanguage,
      targetLanguage = local.targetLanguage,
      hasVoiceProfile = hasVoiceProfile,
      voiceEmbedding = voiceEmbedding,
    )

    var encoded = BleMessageCodec.encodeMetadata(metadata)
    if (encoded.length > MAX_BLE_MESSAGE_SIZE) {
      BaoLog.w(TAG, "Metadata exceeds MAX_BLE_MESSAGE_SIZE (${encoded.length}); omitting voiceEmbedding")
      hasVoiceProfile = false
      metadata = metadata.copy(voiceEmbedding = null, hasVoiceProfile = false)
      encoded = BleMessageCodec.encodeMetadata(metadata)
    }

    comm.sendMessage(Message(context, encoded))
  }

  fun getConnectedCount(): Int = _participants.value.count { it.isConnected }

  fun cleanup() {
    scopeJob.cancel()
    localEmbeddingProvider = { null }
    val comm = communicator
    communicator = null
    // destroy() tears down channels but does not reliably stop the active LE advertiser/scanner,
    // so an in-progress discovery would leak its AdvertiseCallback/ScanCallback to the system stack
    // until process death. Stop them explicitly first.
    comm?.stopAdvertising(true)
    comm?.stopDiscovery(true)
    peerMap.clear()
    discoveredPeerMap.clear()
    peerVoiceEmbeddings.clear()
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
