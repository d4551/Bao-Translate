package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AudioRouter"
private const val COMMUNICATION_DEVICE_TIMEOUT_MS = 30_000L

sealed class AudioDevice {
  data object Speaker : AudioDevice()

  data class BluetoothHeadset(
    val name: String,
    val transport: BluetoothTransport,
    val supportsInput: Boolean,
  ) : AudioDevice()

  data class WiredHeadset(val name: String) : AudioDevice()
}

enum class BluetoothTransport { BLE_AUDIO, A2DP, SCO }

data class AudioInputOption(
  val device: AudioDevice.BluetoothHeadset,
  val isPreferred: Boolean,
)

class AudioRouter(private val context: Context) {
  private val audioManager: AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private val _currentDevice = MutableStateFlow<AudioDevice>(AudioDevice.Speaker)
  val currentDevice: StateFlow<AudioDevice> = _currentDevice.asStateFlow()

  private val _availableOutputDevices = MutableStateFlow<List<AudioDevice>>(listOf(AudioDevice.Speaker))
  val availableOutputDevices: StateFlow<List<AudioDevice>> = _availableOutputDevices.asStateFlow()

  private val _availableInputDevices = MutableStateFlow<List<AudioInputOption>>(emptyList())
  val availableInputDevices: StateFlow<List<AudioInputOption>> = _availableInputDevices.asStateFlow()

  private val _preferredInputDevice = MutableStateFlow<AudioDevice.BluetoothHeadset?>(null)
  val preferredInputDevice: StateFlow<AudioDevice.BluetoothHeadset?> = _preferredInputDevice.asStateFlow()

  private val _routingStatus = MutableStateFlow(RoutingStatus.IDLE)
  val routingStatus: StateFlow<RoutingStatus> = _routingStatus.asStateFlow()

  // @Volatile: written on the main/handler thread (device callbacks, user selection) and read from
  // the playback worker thread in play(); the annotation guarantees cross-thread visibility.
  @Volatile private var selectedOutputDevice: AudioDevice = AudioDevice.Speaker
  @Volatile private var hasUserSelectedOutput = false
  @Volatile private var hasUserSelectedInput = false
  private val handler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { command -> handler.post(command) }
  @Volatile private var cachedInputDevices: Array<out AudioDeviceInfo> = emptyArray()

  private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
      BaoLog.i(TAG, "Devices added: ${addedDevices.size}")
      refreshDevice()
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
      BaoLog.i(TAG, "Devices removed: ${removedDevices.size}")
      refreshDevice()
    }
  }

  private val routingTimeoutRunnable = Runnable { onRoutingTimeout() }
  private val communicationDeviceChangedListener =
    AudioManager.OnCommunicationDeviceChangedListener { device ->
      onCommunicationDeviceChanged(device)
    }
  @Volatile private var pendingRouteDeviceId: Int = 0
  @Volatile private var pendingRouteExpected: AudioDevice? = null
  // Gate listener teardown so cleanup() is idempotent: removeOnCommunicationDeviceChangedListener
  // throws IllegalArgumentException if the listener was never registered or was already removed
  // (e.g. cleanup() called twice on lifecycle teardown + GC). Flip false after the single teardown.
  @Volatile private var listenersRegistered = false

  private fun onRoutingTimeout() {
    val expected = pendingRouteExpected
    pendingRouteExpected = null
    if (expected == null) return
    if (_currentDevice.value == expected) return
    BaoLog.w(TAG, "setCommunicationDevice timeout after ${COMMUNICATION_DEVICE_TIMEOUT_MS}ms; falling back to ${_currentDevice.value}")
    _routingStatus.value = RoutingStatus.FAILED
    audioManager.clearCommunicationDevice()
    audioManager.mode = AudioManager.MODE_NORMAL
  }

  private fun startRoutingTimeout(expected: AudioDevice, deviceId: Int) {
    pendingRouteExpected = expected
    pendingRouteDeviceId = deviceId
    handler.removeCallbacks(routingTimeoutRunnable)
    handler.postDelayed(routingTimeoutRunnable, COMMUNICATION_DEVICE_TIMEOUT_MS)
  }

  private fun cancelRoutingTimeoutIfMatches(expected: AudioDevice?) {
    if (pendingRouteExpected == expected) {
      handler.removeCallbacks(routingTimeoutRunnable)
      pendingRouteExpected = null
    }
  }

  private fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
    val expected = pendingRouteExpected ?: return

    if (device?.id == pendingRouteDeviceId) {
      BaoLog.i(TAG, "Communication route connected: $expected")
      _routingStatus.value = RoutingStatus.CONNECTED
      _currentDevice.value = expected
      refreshInputDevices()
      cancelRoutingTimeoutIfMatches(expected)
      return
    }

    if (device != null) {
      BaoLog.w(TAG, "Communication route changed to unexpected device while waiting for $expected: ${device.productName}")
    }
  }

  init {
    audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    audioManager.addOnCommunicationDeviceChangedListener(
      mainExecutor,
      communicationDeviceChangedListener,
    )
    listenersRegistered = true
    refreshDevice()
  }

  fun detectCurrentDevice(): AudioDevice {
    refreshDevice()
    return _currentDevice.value
  }

  private fun detectBestAvailableDevice(): AudioDevice {
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return DeviceProbe.pickOutput(
      sinks = outputDevices.map(DeviceDescriptor::from),
      speakerFallback = context.getString(R.string.bao_translate_phone_speaker),
      bluetoothFallback = context.getString(R.string.bao_translate_audio_device_bluetooth),
      wiredFallback = context.getString(R.string.bao_translate_audio_device_wired),
      inputDevices = inputDevices.map(DeviceDescriptor::from),
      localModel = Build.MODEL,
      localDevice = Build.DEVICE,
    )
  }

  fun getAvailableOutputDevices(): List<AudioDevice> {
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return DeviceProbe.listOutputs(
      sinks = outputDevices.map(DeviceDescriptor::from),
      speakerFallback = context.getString(R.string.bao_translate_phone_speaker),
      wiredFallback = context.getString(R.string.bao_translate_audio_device_wired),
      bluetoothFallback = context.getString(R.string.bao_translate_audio_device_bluetooth),
      inputDevices = inputDevices.map(DeviceDescriptor::from),
      localModel = Build.MODEL,
      localDevice = Build.DEVICE,
    )
  }

  fun getAvailableInputDevices(): List<AudioInputOption> {
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val preferred = (_currentDevice.value as? AudioDevice.BluetoothHeadset)?.takeIf { it.supportsInput }
    return DeviceProbe.listInputs(
      devices = (outputDevices + inputDevices).map(DeviceDescriptor::from),
      fallback = context.getString(R.string.bao_translate_audio_device_bluetooth),
      outputDevices = outputDevices.map(DeviceDescriptor::from),
      localModel = Build.MODEL,
      localDevice = Build.DEVICE,
    ).map { option ->
      option.copy(
        isPreferred =
          preferred?.let {
            it.name == option.device.name && it.transport == option.device.transport
          } == true,
      )
    }
  }

  fun getInputDeviceInfo(device: AudioDevice.BluetoothHeadset): AudioDeviceInfo? {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val match: (AudioDeviceInfo) -> Boolean = { info ->
      val name = if (device.transport == BluetoothTransport.SCO) {
        bluetoothInputName(info, outputs)
      } else {
        bluetoothDeviceName(info)
      }
      isBluetoothOutput(info) && info.isSource && info.hasInputSupport() &&
        name == device.name &&
        matchesTransport(info, device.transport)
    }
    return inputs.firstOrNull(match) ?: outputs.firstOrNull(match)
  }

  private fun matchesTransport(info: AudioDeviceInfo, transport: BluetoothTransport): Boolean = when (transport) {
    BluetoothTransport.BLE_AUDIO -> info.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    BluetoothTransport.SCO -> info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    BluetoothTransport.A2DP -> info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
  }

  fun preferBluetooth(target: AudioDevice.BluetoothHeadset? = null): Boolean {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val candidate = when {
      target != null -> {
        devices.firstOrNull { info ->
          isSelectableBluetoothOutput(info) &&
            bluetoothDeviceName(info) == target.name &&
            matchesTransport(info, target.transport)
        }
      }
      else -> devices.firstOrNull { isBleOutput(it) }
        ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        ?: devices.firstOrNull { isSelectableScoOutput(it) }
    }
    if (candidate == null) {
      _routingStatus.value = RoutingStatus.NO_BLUETOOTH_OUTPUT
      return false
    }
    selectedOutputDevice = AudioDevice.BluetoothHeadset(
      name = productNameOrFallback(candidate, R.string.bao_translate_audio_device_bluetooth),
      transport = when (candidate.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> BluetoothTransport.BLE_AUDIO
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BluetoothTransport.SCO
        else -> BluetoothTransport.A2DP
      },
      supportsInput = candidate.hasInputSupport(),
    )
    hasUserSelectedOutput = true
    val isA2dp = candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    if (isA2dp) {
      audioManager.mode = AudioManager.MODE_NORMAL
      audioManager.clearCommunicationDevice()
      _routingStatus.value = RoutingStatus.CONNECTED
      _currentDevice.value = selectedOutputDevice
      refreshInputDevices()
      return true
    }
    _routingStatus.value = RoutingStatus.ROUTING
    val success = configureCommunicationDevice(candidate)
    if (success) {
      startRoutingTimeout(selectedOutputDevice, candidate.id)
      if (audioManager.communicationDevice?.id == candidate.id) {
        onCommunicationDeviceChanged(candidate)
      }
    } else {
      // configureCommunicationDevice set MODE_IN_COMMUNICATION before setCommunicationDevice
      // returned false; with no routing timeout armed (that only happens on success), the device
      // would otherwise stay stuck in call-audio mode. Restore normal audio state on failure.
      audioManager.mode = AudioManager.MODE_NORMAL
      audioManager.clearCommunicationDevice()
      _routingStatus.value = RoutingStatus.FAILED
    }
    return success
  }

  fun resetToSpeaker() {
    cancelRoutingTimeoutIfMatches(selectedOutputDevice)
    selectedOutputDevice = AudioDevice.Speaker
    hasUserSelectedOutput = true
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.clearCommunicationDevice()
    _routingStatus.value = RoutingStatus.IDLE
    _currentDevice.value = selectedOutputDevice
    refreshInputDevices()
  }

  fun selectWired(device: AudioDevice.WiredHeadset) {
    cancelRoutingTimeoutIfMatches(selectedOutputDevice)
    selectedOutputDevice = device
    hasUserSelectedOutput = true
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.clearCommunicationDevice()
    _routingStatus.value = RoutingStatus.IDLE
    _currentDevice.value = selectedOutputDevice
    refreshInputDevices()
  }

  fun selectPreferredInput(device: AudioDevice.BluetoothHeadset?): Boolean {
    if (device != null && getInputDeviceInfo(device) == null) {
      BaoLog.w(TAG, "Ignoring unavailable Bluetooth input: $device")
      return false
    }
    hasUserSelectedInput = true
    _preferredInputDevice.value = device
    if (device != null) routeOutputToCommunicationFor(device)
    return true
  }

  // A BT mic only delivers audio while ITS headset is on an ACTIVE communication (SCO/BLE) link; A2DP
  // is output-only, so a BT mic is SILENT whenever the output sits on A2DP — the historical "mic broke
  // when using a BT mic + BT speaker" bug (A2DP stereo and an SCO mic are mutually exclusive on Android).
  // This is a real-time conversation app (it always captures), so the industry-best-practice route for a
  // BT headset is its bidirectional communication endpoint. When a BT mic is chosen, move the SAME
  // headset's OUTPUT onto its SCO/BLE comm route so the mic and speaker share ONE link (mono SCO for
  // classic headsets; full-duplex for LE Audio). No-op if already on that headset's comm route, so an
  // A2DP-only "music, no BT mic" selection is untouched.
  private fun routeOutputToCommunicationFor(device: AudioDevice.BluetoothHeadset) {
    val current = selectedOutputDevice
    if (current is AudioDevice.BluetoothHeadset &&
      current.name == device.name &&
      current.transport != BluetoothTransport.A2DP
    ) {
      return
    }
    val commEndpoint = audioManager.availableCommunicationDevices.firstOrNull { info ->
      (info.type == AudioDeviceInfo.TYPE_BLE_HEADSET || info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
        bluetoothDeviceName(info) == device.name
    } ?: run {
      BaoLog.w(TAG, "No SCO/BLE communication endpoint for BT mic ${device.name}; mic may be silent on A2DP")
      return
    }
    selectedOutputDevice = AudioDevice.BluetoothHeadset(
      name = device.name,
      transport =
        if (commEndpoint.type == AudioDeviceInfo.TYPE_BLE_HEADSET) BluetoothTransport.BLE_AUDIO
        else BluetoothTransport.SCO,
      supportsInput = true,
    )
    hasUserSelectedOutput = true
    _routingStatus.value = RoutingStatus.ROUTING
    if (configureCommunicationDevice(commEndpoint)) {
      startRoutingTimeout(selectedOutputDevice, commEndpoint.id)
      if (audioManager.communicationDevice?.id == commEndpoint.id) {
        onCommunicationDeviceChanged(commEndpoint)
      }
    } else {
      audioManager.mode = AudioManager.MODE_NORMAL
      audioManager.clearCommunicationDevice()
      _routingStatus.value = RoutingStatus.FAILED
    }
  }

  fun play(
    samples: FloatArray,
    sampleRate: Int = PipelineConfig.TTS_SAMPLE_RATE,
    // Per-turn output override (face-to-face: route each speaker's translation to THEIR assigned
    // device — e.g. one person's earbuds, the other the phone speaker). Null => the global selection.
    override: AudioDevice? = null,
  ): AudioPlayback.RouteResult {
    val selected = override ?: selectedOutputDevice
    val outputInfo = findDeviceInfo(selected)
    if (selected !is AudioDevice.Speaker && outputInfo == null) {
      BaoLog.w(TAG, "Selected output route is no longer available: $selected")
      _routingStatus.value = RoutingStatus.FAILED
      return AudioPlayback.RouteResult(
        preferredDeviceApplied = false,
        preferredDeviceName = null,
        preferredDeviceId = null,
        routedDeviceName = null,
        routedDeviceId = null,
      )
    }
    val communicationRoute =
      selected is AudioDevice.BluetoothHeadset &&
        selected.transport != BluetoothTransport.A2DP
    val result = AudioPlayback.playPcmFloat(
      samples = samples,
      sampleRate = sampleRate,
      preferredDevice = outputInfo,
      useCommunicationRoute = communicationRoute,
    )
    if (outputInfo != null && result.routedDeviceId != outputInfo.id) {
      BaoLog.w(TAG, "AudioTrack rejected preferred output route: $selected")
      _routingStatus.value = RoutingStatus.FAILED
    }
    return result
  }

  private fun findDeviceInfo(device: AudioDevice): AudioDeviceInfo? {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return when (device) {
      is AudioDevice.BluetoothHeadset -> {
        devices.firstOrNull {
          isSelectableBluetoothOutput(it) &&
            bluetoothDeviceName(it) == device.name &&
            matchesTransport(it, device.transport)
        }
      }
      is AudioDevice.WiredHeadset -> devices.firstOrNull {
        isWiredOutput(it) &&
          productNameOrFallback(it, R.string.bao_translate_audio_device_wired) == device.name
      } ?: devices.firstOrNull { isWiredOutput(it) }
      AudioDevice.Speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }
  }

  private fun refreshDevice() {
    cachedInputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val availableOutputs = getAvailableOutputDevices()
    _availableOutputDevices.value = availableOutputs

    if (hasUserSelectedOutput && isSelectedDeviceAvailable(selectedOutputDevice, availableOutputs)) {
      if (_routingStatus.value == RoutingStatus.ROUTING) {
        _routingStatus.value = RoutingStatus.CONNECTED
        cancelRoutingTimeoutIfMatches(selectedOutputDevice)
      }
      _currentDevice.value = selectedOutputDevice
    } else {
      hasUserSelectedOutput = false
      selectedOutputDevice = detectBestAvailableDevice()
      _currentDevice.value = selectedOutputDevice
    }

    refreshInputDevices()
  }

  private fun refreshInputDevices() {
    val inputs = getAvailableInputDevices()
    _availableInputDevices.value = inputs
    val currentPreferred = _preferredInputDevice.value
    if (!hasUserSelectedInput) {
      _preferredInputDevice.value = inputs.firstOrNull { it.isPreferred }?.device
    } else if (currentPreferred != null && inputs.none { it.device == currentPreferred }) {
      _preferredInputDevice.value = null
    }
  }

  private fun isWiredOutput(device: AudioDeviceInfo): Boolean =
    DeviceProbe.isWiredOutput(device.type)

  private fun isBleOutput(device: AudioDeviceInfo): Boolean =
    DeviceProbe.isBleOutput(device.type)

  private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
    DeviceProbe.isBluetoothOutput(device.type)

  private fun isSelectableBluetoothOutput(device: AudioDeviceInfo): Boolean =
    isBleOutput(device) ||
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
      isSelectableScoOutput(device)

  private fun isSelectableScoOutput(device: AudioDeviceInfo): Boolean =
    DeviceProbe.isSelectableScoOutput(
      DeviceDescriptor.from(device),
      localModel = Build.MODEL,
      localDevice = Build.DEVICE,
    )

  private fun isSelectedDeviceAvailable(selected: AudioDevice, available: List<AudioDevice>): Boolean =
    available.any { device ->
      when {
        selected is AudioDevice.Speaker && device is AudioDevice.Speaker -> true
        selected is AudioDevice.BluetoothHeadset && device is AudioDevice.BluetoothHeadset ->
          device.name == selected.name && device.transport == selected.transport
        selected is AudioDevice.WiredHeadset && device is AudioDevice.WiredHeadset -> device.name == selected.name
        else -> false
      }
    }

  private fun configureCommunicationDevice(device: AudioDeviceInfo): Boolean {
    return when (device.type) {
      AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.setCommunicationDevice(device)
      }
      else -> {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.clearCommunicationDevice()
        false
      }
    }
  }

  private fun productNameOrFallback(info: AudioDeviceInfo, fallbackRes: Int): String {
    val productName = info.productName?.toString()
    if (!productName.isNullOrBlank() && productName != "null") return productName
    val address = info.address
    if (!address.isNullOrBlank() && address != "00:00:00:00:00:00") return address
    return context.getString(fallbackRes)
  }

  private fun bluetoothDeviceName(info: AudioDeviceInfo): String =
    productNameOrFallback(info, R.string.bao_translate_audio_device_bluetooth)

  private fun bluetoothInputName(
    info: AudioDeviceInfo,
    outputDevices: Array<out AudioDeviceInfo>,
  ): String? {
    if (!isBluetoothOutput(info)) return null
    if (info.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || !isPlaceholderBluetoothEndpoint(info)) {
      return bluetoothDeviceName(info)
    }
    return outputDevices.firstOrNull {
      (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) &&
        !isPlaceholderBluetoothEndpoint(it)
    }?.let(::bluetoothDeviceName)
  }

  private fun isPlaceholderBluetoothEndpoint(info: AudioDeviceInfo): Boolean {
    return DeviceProbe.isPlaceholderBluetoothEndpoint(
      DeviceDescriptor.from(info),
      localModel = Build.MODEL,
      localDevice = Build.DEVICE,
    )
  }

  private fun AudioDeviceInfo.hasInputSupport(): Boolean {
    if (cachedInputDevices.isEmpty()) {
      cachedInputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    }
    return DeviceProbe.hasInputSupport(
      DeviceDescriptor.from(this),
      cachedInputDevices.map(DeviceDescriptor::from),
    )
  }

  fun cleanup() {
    handler.removeCallbacks(routingTimeoutRunnable)
    pendingRouteExpected = null
    if (listenersRegistered) {
      listenersRegistered = false
      audioManager.unregisterAudioDeviceCallback(deviceCallback)
      audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceChangedListener)
    }
    AudioPlayback.releaseTrack()
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.clearCommunicationDevice()
    _preferredInputDevice.value = null
    hasUserSelectedInput = false
    _routingStatus.value = RoutingStatus.IDLE
  }

  companion object {
    val COMMUNICATION_TIMEOUT_MS: Long = COMMUNICATION_DEVICE_TIMEOUT_MS
  }
}

enum class RoutingStatus { IDLE, ROUTING, CONNECTED, FAILED, NO_BLUETOOTH_OUTPUT }
