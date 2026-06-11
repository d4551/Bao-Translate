package com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth

import android.content.Context
import android.os.Build
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
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
 * Pure (Context-free) encode/validate/decode for conversation payloads. Decoding is DEFENSIVE:
 * a connected peer — or link corruption from a legitimate peer — can put arbitrary bytes on the
 * wire. The kotlinx decode is wrapped to return null on any SerializationException instead of
 * throwing out of the Nearby Connections main-thread callback and crashing the process.
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
    // A peer (or link corruption) can put non-finite floats on the wire. kotlinx parses bare
    // NaN/Infinity/-Infinity tokens into Float.NaN/±Float.INFINITY (floatOrNull does NOT drop them),
    // so size alone would not catch a 256-d array of NaN. Reject any element that is not a finite
    // float so a poisoned timbre never reaches peerVoiceEmbeddings / the OpenVoice clone pipeline.
    val voiceEmbedding = (obj["voiceEmbedding"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull?.takeIf { f -> f.isFinite() } }
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

/**
 * Multi-device conversation transport over Google Nearby Connections (play-services-nearby).
 *
 * Nearby Connections is the maintained, OEM-portable P2P API: it negotiates BLE + Bluetooth Classic
 * + Wi-Fi mediums, handles BLE address resolution, MTU and connection retries internally — the
 * layers a raw `connectGatt` cannot reliably establish across devices. Peers are identified by
 * Nearby's opaque, per-session `endpointId`, used as the participant/peer key throughout. The on-wire
 * message protocol ([BleMessageCodec]) is transport-agnostic and unchanged; only the transport moved.
 */
class BleConversationManager(private val context: Context) {
  private val scopeJob = SupervisorJob()
  private val scope = CoroutineScope(scopeJob + Dispatchers.Main)

  private val connectionsClient: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }

  private val _participants = MutableStateFlow<List<Participant>>(emptyList())
  val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

  private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
  val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  private val _messages = MutableSharedFlow<BleTranscriptMessage>(replay = 0)
  val messages: SharedFlow<BleTranscriptMessage> = _messages

  /**
   * Test-only: deliver an incoming peer transcript through the exact same flow a real message would,
   * so instrumentation can verify multi-speaker receive routing (translate peer language -> local
   * target, attribute to the speaker, speak) without a second physical device. Suspends until the
   * collector (the ViewModel) receives it.
   */
  internal suspend fun simulateIncomingTranscriptForTest(message: BleTranscriptMessage) {
    _messages.emit(message)
  }

  /**
   * Test-only: apply incoming peer metadata through the same path as a real message, so
   * instrumentation can verify multi-speaker embedding routing without a second physical device.
   */
  internal fun simulateIncomingMetadataForTest(peerId: String, metadata: BleMetadataMessage) {
    applyMetadataForPeer(peerId, metadata)
  }

  private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private val _connectingPeers = MutableStateFlow<Set<String>>(emptySet())
  val connectingPeers: StateFlow<Set<String>> = _connectingPeers.asStateFlow()

  private var localParticipant: Participant? = null
  @Volatile private var advertising = false

  // endpointId -> display name. Connected peers and discovered-but-not-yet-connected endpoints.
  private val connectedEndpoints = ConcurrentHashMap<String, String>()
  private val discoveredEndpoints = ConcurrentHashMap<String, String>()
  // endpointId -> name captured at onConnectionInitiated, so onConnectionResult can label the peer.
  private val pendingEndpointNames = ConcurrentHashMap<String, String>()

  // Multi-speaker voice cloning: each connected peer's timbre (received from their metadata, keyed
  // by endpointId). The receive path looks up [voiceEmbeddingFor] so a peer's translated turn is
  // spoken in their voice. The local timbre is read on demand via [localEmbeddingProvider].
  private var localEmbeddingProvider: () -> FloatArray? = { null }
  private val peerVoiceEmbeddings = ConcurrentHashMap<String, FloatArray>()

  /** Supplies the local enrolled timbre when metadata is broadcast to peers. */
  fun setLocalEmbeddingProvider(provider: () -> FloatArray?) {
    localEmbeddingProvider = provider
  }

  /** Re-broadcasts local participant metadata (including the current embedding) to connected peers. */
  fun rebroadcastMetadata() {
    if (connectedEndpoints.isNotEmpty()) sendMetadata()
  }

  /** The connected peer's enrolled timbre (256-d), or null if they haven't shared/enrolled one. */
  fun voiceEmbeddingFor(peerId: String): FloatArray? = peerVoiceEmbeddings[peerId]

  // The name this device shows to peers in their discovery list. Prefer the user's participant name;
  // fall back to the device model so two devices are never indistinguishable.
  private val localEndpointName: String
    get() = localParticipant?.name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Bao"

  private val payloadCallback = object : PayloadCallback() {
    override fun onPayloadReceived(endpointId: String, payload: Payload) {
      if (payload.type != Payload.Type.BYTES) return
      val bytes = payload.asBytes() ?: return
      handleIncomingMessage(endpointId, String(bytes, Charsets.UTF_8))
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
      // BYTES payloads arrive whole in onPayloadReceived; there is nothing to reassemble.
    }
  }

  private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
      // Nearby's mutual handshake delivers this to BOTH sides; both must accept for the link to form.
      // Conversation Mode pairing is explicit and user-initiated, so auto-accept and wire the payload
      // sink. The connection result arrives via onConnectionResult.
      BaoLog.i(TAG, "Connection initiated: ${info.endpointName}")
      pendingEndpointNames[endpointId] = info.endpointName
      _connectingPeers.value = _connectingPeers.value + endpointId
      if (_connectionState.value != ConnectionState.CONNECTED) {
        _connectionState.value = ConnectionState.CONNECTING
      }
      connectionsClient.acceptConnection(endpointId, payloadCallback)
    }

    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
      _connectingPeers.value = _connectingPeers.value - endpointId
      when (result.status.statusCode) {
        ConnectionsStatusCodes.STATUS_OK -> {
          val name = pendingEndpointNames.remove(endpointId)
            ?: discoveredEndpoints[endpointId]
            ?: context.getString(R.string.bao_ble_unknown_device)
          BaoLog.i(TAG, "Connected to: $name")
          connectedEndpoints[endpointId] = name
          val current = _participants.value.toMutableList()
          if (current.none { it.id == endpointId }) {
            current.add(
              Participant(
                id = endpointId,
                name = name,
                sourceLanguage = SupportedLanguages.AUTO.key,
                targetLanguage = PipelineConfig.DEFAULT_TARGET_LANGUAGE,
                isConnected = true,
                hasVoiceProfile = false,
                audioDeviceName = null,
              )
            )
            _participants.value = current
          }
          _connectionState.value = ConnectionState.CONNECTED
          sendMetadata()
        }
        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
          BaoLog.w(TAG, "Connection rejected: $endpointId")
          pendingEndpointNames.remove(endpointId)
          settleConnectionState()
        }
        else -> {
          BaoLog.e(TAG, "Connection failed: $endpointId, status=${result.status.statusCode}")
          pendingEndpointNames.remove(endpointId)
          settleConnectionState()
        }
      }
    }

    override fun onDisconnected(endpointId: String) {
      BaoLog.i(TAG, "Disconnected: $endpointId")
      connectedEndpoints.remove(endpointId)
      peerVoiceEmbeddings.remove(endpointId)
      _participants.value = _participants.value.filter { it.id != endpointId }
      settleConnectionState()
    }
  }

  private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
      BaoLog.i(TAG, "Endpoint found: ${info.endpointName}")
      discoveredEndpoints[endpointId] = info.endpointName
      if (_discoveredPeers.value.none { it.id == endpointId }) {
        _discoveredPeers.value = _discoveredPeers.value +
          DiscoveredPeer(id = endpointId, name = info.endpointName, deviceAddress = endpointId)
      }
    }

    override fun onEndpointLost(endpointId: String) {
      BaoLog.i(TAG, "Endpoint lost: $endpointId")
      discoveredEndpoints.remove(endpointId)
      _discoveredPeers.value = _discoveredPeers.value.filter { it.id != endpointId }
    }
  }

  /** Recomputes the top-level state once a connection settles: connected wins, else scanning/advertising, else idle. */
  private fun settleConnectionState() {
    if (_participants.value.any { it.isConnected }) {
      _connectionState.value = ConnectionState.CONNECTED
      return
    }
    if (_connectingPeers.value.isNotEmpty()) return
    _connectionState.value = when {
      _isScanning.value -> ConnectionState.SCANNING
      advertising -> ConnectionState.ADVERTISING
      else -> ConnectionState.DISCONNECTED
    }
  }

  private fun handleIncomingMessage(endpointId: String, text: String) {
    if (text.isEmpty() || text.length > MAX_BLE_MESSAGE_SIZE) {
      BaoLog.w(TAG, "Invalid message size: ${text.length}")
      return
    }

    // Only accept messages from an endpoint we have an established connection with.
    if (!connectedEndpoints.containsKey(endpointId)) {
      BaoLog.w(TAG, "Message from unknown sender: $endpointId")
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
        // Trust the transport-verified endpoint id, not the self-reported senderId (anti-spoofing):
        // the timbre lookup is keyed by endpointId, so a peer cannot impersonate another's voice.
        val trustedTranscript = transcript.copy(senderId = endpointId)
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
        applyMetadataForPeer(endpointId, metadata)
      }
    }
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
    if (connectedEndpoints.isNotEmpty()) {
      sendMetadata()
    }
  }

  fun startAdvertising() {
    localParticipant ?: return
    if (advertising) return
    val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
    connectionsClient
      .startAdvertising(localEndpointName, SERVICE_NAME, connectionLifecycleCallback, options)
      .addOnSuccessListener {
        advertising = true
        if (_connectionState.value == ConnectionState.DISCONNECTED) {
          _connectionState.value = ConnectionState.ADVERTISING
        }
        BaoLog.i(TAG, "Started advertising as $localEndpointName")
      }
      .addOnFailureListener { e ->
        advertising = false
        BaoLog.e(TAG, "startAdvertising failed: ${e.message}")
      }
  }

  fun stopAdvertising() {
    connectionsClient.stopAdvertising()
    advertising = false
    settleConnectionState()
  }

  fun startScanning() {
    val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
    connectionsClient
      .startDiscovery(SERVICE_NAME, endpointDiscoveryCallback, options)
      .addOnSuccessListener {
        _isScanning.value = true
        if (_participants.value.none { it.isConnected }) {
          _connectionState.value = ConnectionState.SCANNING
        }
        BaoLog.i(TAG, "Started scanning")
      }
      .addOnFailureListener { e ->
        _isScanning.value = false
        BaoLog.e(TAG, "startDiscovery failed: ${e.message}")
        settleConnectionState()
      }
  }

  fun stopScanning() {
    connectionsClient.stopDiscovery()
    _isScanning.value = false
    settleConnectionState()
  }

  // Symmetric discovery: every device both advertises and discovers under the same serviceId so a
  // pair reliably finds each other regardless of who taps first. P2P_CLUSTER allows the full M:N mesh
  // needed for multi-party conversation. Advertising starts only after the local participant is set.
  fun startConversationDiscovery() {
    startAdvertising()
    startScanning()
  }

  fun stopConversationDiscovery() {
    stopScanning()
    stopAdvertising()
  }

  fun connectToDevice(deviceAddress: String) {
    if (connectedEndpoints.containsKey(deviceAddress)) return
    _connectingPeers.value = _connectingPeers.value + deviceAddress
    if (_connectionState.value != ConnectionState.CONNECTED) {
      _connectionState.value = ConnectionState.CONNECTING
    }
    connectionsClient
      .requestConnection(localEndpointName, deviceAddress, connectionLifecycleCallback)
      .addOnFailureListener { e ->
        BaoLog.e(TAG, "requestConnection failed for $deviceAddress: ${e.message}")
        _connectingPeers.value = _connectingPeers.value - deviceAddress
        settleConnectionState()
      }
  }

  fun disconnectFromDevice(deviceAddress: String) {
    connectionsClient.disconnectFromEndpoint(deviceAddress)
    connectedEndpoints.remove(deviceAddress)
    peerVoiceEmbeddings.remove(deviceAddress)
    _participants.value = _participants.value.filter { it.id != deviceAddress }
    settleConnectionState()
  }

  suspend fun sendTranscript(text: String, sourceLanguage: String, targetLanguage: String) {
    val local = localParticipant ?: return
    if (connectedEndpoints.isEmpty()) return

    val message = BleTranscriptMessage(
      text = text,
      senderId = local.id,
      senderName = local.name,
      sourceLanguage = sourceLanguage,
      targetLanguage = targetLanguage,
    )

    broadcast(BleMessageCodec.encodeTranscript(message))
    // Log length only — the transcript is user speech content (PII), not diagnostics.
    BaoLog.i(TAG, "Sent transcript: ${text.length} chars")
  }

  private fun sendMetadata() {
    val local = localParticipant ?: return
    if (connectedEndpoints.isEmpty()) return

    val rawEmbedding = localEmbeddingProvider()?.toList()
    val voiceEmbedding = rawEmbedding?.takeIf { it.size == OpenVoiceVoiceConverter.SE_DIM }
    var metadata = BleMetadataMessage(
      participantId = local.id,
      participantName = local.name,
      sourceLanguage = local.sourceLanguage,
      targetLanguage = local.targetLanguage,
      hasVoiceProfile = voiceEmbedding != null,
      voiceEmbedding = voiceEmbedding,
    )

    var encoded = BleMessageCodec.encodeMetadata(metadata)
    if (encoded.length > MAX_BLE_MESSAGE_SIZE) {
      BaoLog.w(TAG, "Metadata exceeds MAX_BLE_MESSAGE_SIZE (${encoded.length}); omitting voiceEmbedding")
      metadata = metadata.copy(voiceEmbedding = null, hasVoiceProfile = false)
      encoded = BleMessageCodec.encodeMetadata(metadata)
    }

    broadcast(encoded)
  }

  /** Sends a UTF-8 BYTES payload to every connected endpoint. */
  private fun broadcast(payload: String) {
    if (connectedEndpoints.isEmpty()) return
    val bytes = payload.toByteArray(Charsets.UTF_8)
    for (endpointId in connectedEndpoints.keys) {
      connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }
  }

  fun getConnectedCount(): Int = _participants.value.count { it.isConnected }

  fun cleanup() {
    scopeJob.cancel()
    localEmbeddingProvider = { null }
    // Tears down advertising, discovery, and every endpoint connection in a single call.
    connectionsClient.stopAllEndpoints()
    advertising = false
    connectedEndpoints.clear()
    discoveredEndpoints.clear()
    pendingEndpointNames.clear()
    peerVoiceEmbeddings.clear()
    _participants.value = emptyList()
    _discoveredPeers.value = emptyList()
    _isScanning.value = false
    _connectionState.value = ConnectionState.DISCONNECTED
    _connectingPeers.value = emptySet()
    BaoLog.i(TAG, "Cleaned up")
  }
}
