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

  private var selectedOutputDevice: AudioDevice = AudioDevice.Speaker
  private var hasUserSelectedOutput = false
  private val handler = Handler(Looper.getMainLooper())
  private var cachedInputDevices: Array<out AudioDeviceInfo> = emptyArray()

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
  @Volatile private var pendingRouteDeviceId: Int = 0
  @Volatile private var pendingRouteExpected: AudioDevice? = null

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

  init {
    audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    refreshDevice()
  }

  fun detectCurrentDevice(): AudioDevice {
    refreshDevice()
    return _currentDevice.value
  }

  private fun detectBestAvailableDevice(): AudioDevice {
    val devices: Array<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return pickOutputDevice(devices) ?: AudioDevice.Speaker
  }

  private fun pickOutputDevice(devices: Array<AudioDeviceInfo>): AudioDevice? {
    val ble = devices.firstOrNull { isBleOutput(it) }
    if (ble != null) {
      return AudioDevice.BluetoothHeadset(
        name = productNameOrFallback(ble, R.string.bao_translate_audio_device_bluetooth),
        transport = BluetoothTransport.BLE_AUDIO,
        supportsInput = ble.hasInputSupport(),
      )
    }
    val a2dp = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    if (a2dp != null) {
      return AudioDevice.BluetoothHeadset(
        name = productNameOrFallback(a2dp, R.string.bao_translate_audio_device_bluetooth),
        transport = BluetoothTransport.A2DP,
        supportsInput = false,
      )
    }
    val sco = devices.firstOrNull { isSelectableScoOutput(it) }
    if (sco != null) {
      return AudioDevice.BluetoothHeadset(
        name = productNameOrFallback(sco, R.string.bao_translate_audio_device_bluetooth),
        transport = BluetoothTransport.SCO,
        supportsInput = true,
      )
    }
    val wired = devices.firstOrNull { isWiredOutput(it) }
    if (wired != null) {
      return AudioDevice.WiredHeadset(
        name = productNameOrFallback(wired, R.string.bao_translate_audio_device_wired),
      )
    }
    return null
  }

  fun getAvailableOutputDevices(): List<AudioDevice> {
    val devices: Array<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val result = linkedSetOf<AudioDevice>(AudioDevice.Speaker)

    val bluetooth = linkedMapOf<String, AudioDevice.BluetoothHeadset>()
    devices.forEach { info ->
      when {
        isBleOutput(info) -> {
          val name = productNameOrFallback(info, R.string.bao_translate_audio_device_bluetooth)
          bluetooth.getOrPut(endpointKey(name, BluetoothTransport.BLE_AUDIO)) {
            AudioDevice.BluetoothHeadset(name, BluetoothTransport.BLE_AUDIO, supportsInput = info.hasInputSupport())
          }
        }
        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
          val name = productNameOrFallback(info, R.string.bao_translate_audio_device_bluetooth)
          bluetooth.getOrPut(endpointKey(name, BluetoothTransport.A2DP)) {
            AudioDevice.BluetoothHeadset(name, BluetoothTransport.A2DP, supportsInput = false)
          }
        }
        isSelectableScoOutput(info) -> {
          val name = productNameOrFallback(info, R.string.bao_translate_audio_device_bluetooth)
          bluetooth.getOrPut(endpointKey(name, BluetoothTransport.SCO)) {
            AudioDevice.BluetoothHeadset(name, BluetoothTransport.SCO, supportsInput = info.hasInputSupport())
          }
        }
        isWiredOutput(info) -> {
          val name = productNameOrFallback(info, R.string.bao_translate_audio_device_wired)
          result.add(AudioDevice.WiredHeadset(name))
        }
      }
    }
    result.addAll(bluetooth.values)
    return result.toList()
  }

  fun getAvailableInputDevices(): List<AudioInputOption> {
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val preferred = (_currentDevice.value as? AudioDevice.BluetoothHeadset)?.takeIf { it.supportsInput }
    val seen = linkedMapOf<String, AudioInputOption>()
    (outputDevices + inputDevices).forEach { info ->
      if (!isBluetoothOutput(info)) return@forEach
      if (!info.isSource) return@forEach
      val name = bluetoothInputName(info, outputDevices) ?: return@forEach
      val transport = when (info.type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> BluetoothTransport.BLE_AUDIO
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BluetoothTransport.SCO
        else -> return@forEach
      }
      if (!info.hasInputSupport()) return@forEach
      seen.getOrPut(endpointKey(name, transport)) {
        AudioInputOption(
          device = AudioDevice.BluetoothHeadset(
            name = name,
            transport = transport,
            supportsInput = true,
          ),
          isPreferred = preferred?.name == name,
        )
      }
    }
    return seen.values.toList()
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
      return true
    }
    _routingStatus.value = RoutingStatus.ROUTING
    val success = configureCommunicationDevice(candidate)
    if (success) {
      startRoutingTimeout(selectedOutputDevice, candidate.id)
    } else {
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
    _preferredInputDevice.value = null
    _currentDevice.value = selectedOutputDevice
  }

  fun selectWired(device: AudioDevice.WiredHeadset) {
    cancelRoutingTimeoutIfMatches(selectedOutputDevice)
    selectedOutputDevice = device
    hasUserSelectedOutput = true
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.clearCommunicationDevice()
    _routingStatus.value = RoutingStatus.IDLE
    _preferredInputDevice.value = null
    _currentDevice.value = selectedOutputDevice
  }

  fun selectPreferredInput(device: AudioDevice.BluetoothHeadset?): Boolean {
    if (device != null && getInputDeviceInfo(device) == null) {
      BaoLog.w(TAG, "Ignoring unavailable Bluetooth input: $device")
      return false
    }
    _preferredInputDevice.value = device
    return true
  }

  fun play(
    samples: FloatArray,
    sampleRate: Int = PipelineConfig.TTS_SAMPLE_RATE,
  ): AudioPlayback.RouteResult {
    val selected = selectedOutputDevice
    val outputInfo = findDeviceInfo(selected)
    if (selected !is AudioDevice.Speaker && outputInfo == null) {
      BaoLog.w(TAG, "Selected output route is no longer available: $selected")
      _routingStatus.value = RoutingStatus.FAILED
      return AudioPlayback.RouteResult(
        preferredDeviceApplied = false,
        preferredDeviceName = null,
        routedDeviceName = null,
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
    if (outputInfo != null && !result.preferredDeviceApplied) {
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

    val inputs = getAvailableInputDevices()
    _availableInputDevices.value = inputs
    val currentPreferred = _preferredInputDevice.value
    if (currentPreferred == null) {
      _preferredInputDevice.value = inputs.firstOrNull { it.isPreferred }?.device
        ?: inputs.firstOrNull()?.device
    } else if (inputs.none { it.device == currentPreferred }) {
      _preferredInputDevice.value = inputs.firstOrNull()?.device
    }
  }

  private fun isWiredOutput(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
      device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET

  private fun isBleOutput(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

  private fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
    isBleOutput(device) ||
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

  private fun isSelectableBluetoothOutput(device: AudioDeviceInfo): Boolean =
    isBleOutput(device) ||
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
      isSelectableScoOutput(device)

  private fun isSelectableScoOutput(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !isPlaceholderBluetoothEndpoint(device)

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

  private fun endpointKey(name: String, transport: BluetoothTransport): String =
    "$name:${transport.name}"

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
      it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    }?.let(::bluetoothDeviceName)
      ?: context.getString(R.string.bao_translate_audio_device_bluetooth)
  }

  private fun isPlaceholderBluetoothEndpoint(info: AudioDeviceInfo): Boolean {
    val productName = info.productName?.toString()
    val address = info.address
    val hasRealAddress = !address.isNullOrBlank() && address != "00:00:00:00:00:00"
    if (hasRealAddress) return false
    if (productName.isNullOrBlank() || productName == "null") return true
    return productName == Build.MODEL || productName == Build.DEVICE
  }

  private fun AudioDeviceInfo.hasInputSupport(): Boolean {
    if (isSource) return true
    if (cachedInputDevices.isEmpty()) {
      cachedInputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    }
    val matchName = productName?.toString()
    return cachedInputDevices.any { input ->
      input.productName?.toString() == matchName
    }
  }

  fun cleanup() {
    handler.removeCallbacks(routingTimeoutRunnable)
    pendingRouteExpected = null
    audioManager.unregisterAudioDeviceCallback(deviceCallback)
    AudioPlayback.releaseTrack()
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.clearCommunicationDevice()
    _preferredInputDevice.value = null
    _routingStatus.value = RoutingStatus.IDLE
  }

  companion object {
    val COMMUNICATION_TIMEOUT_MS: Long = COMMUNICATION_DEVICE_TIMEOUT_MS
  }
}

enum class RoutingStatus { IDLE, ROUTING, CONNECTED, FAILED, NO_BLUETOOTH_OUTPUT }
