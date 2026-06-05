package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.media.AudioDeviceInfo

data class DeviceDescriptor(
  val type: Int,
  val productName: String,
  val address: String,
  val isSource: Boolean,
  val isSink: Boolean,
) {
  companion object {
    fun from(info: AudioDeviceInfo): DeviceDescriptor {
      val name = info.productName?.toString()
      val resolvedName = if (name.isNullOrBlank() || name == "null") info.address ?: "" else name
      return DeviceDescriptor(
        type = info.type,
        productName = resolvedName,
        address = info.address ?: "",
        isSource = info.isSource,
        isSink = info.isSink,
      )
    }
  }
}

object DeviceProbe {
  const val TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET
  const val TYPE_BLUETOOTH_A2DP = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
  const val TYPE_BLUETOOTH_SCO = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
  const val TYPE_WIRED_HEADPHONES = AudioDeviceInfo.TYPE_WIRED_HEADPHONES
  const val TYPE_WIRED_HEADSET = AudioDeviceInfo.TYPE_WIRED_HEADSET
  const val TYPE_BUILTIN_SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

  fun isBleOutput(type: Int): Boolean = type == TYPE_BLE_HEADSET

  fun isBluetoothOutput(type: Int): Boolean =
    type == TYPE_BLE_HEADSET || type == TYPE_BLUETOOTH_A2DP || type == TYPE_BLUETOOTH_SCO

  fun isWiredOutput(type: Int): Boolean =
    type == TYPE_WIRED_HEADPHONES || type == TYPE_WIRED_HEADSET

  fun isBuiltInSpeaker(type: Int): Boolean = type == TYPE_BUILTIN_SPEAKER

  fun isPlaceholderBluetoothEndpoint(
    device: DeviceDescriptor,
    localModel: String,
    localDevice: String,
  ): Boolean {
    val hasRealAddress = device.address.isNotBlank() && device.address != "00:00:00:00:00:00"
    if (hasRealAddress) return false
    if (device.productName.isBlank() || device.productName == "null") return true
    return device.productName == localModel || device.productName == localDevice
  }

  fun isSelectableScoOutput(
    device: DeviceDescriptor,
    localModel: String,
    localDevice: String,
  ): Boolean =
    device.type == TYPE_BLUETOOTH_SCO &&
      device.isSink &&
      !isPlaceholderBluetoothEndpoint(device, localModel, localDevice)

  fun hasInputSupport(device: DeviceDescriptor, inputDevices: List<DeviceDescriptor>): Boolean {
    if (device.isSource) return true
    return inputDevices.any { input ->
      input.isSource && input.productName == device.productName
    }
  }

  fun pickOutput(
    sinks: List<DeviceDescriptor>,
    speakerFallback: String,
    bluetoothFallback: String = speakerFallback,
    wiredFallback: String = speakerFallback,
    inputDevices: List<DeviceDescriptor> = emptyList(),
    localModel: String = "",
    localDevice: String = "",
  ): AudioDevice {
    sinks.forEach { d ->
      if (isBleOutput(d.type) && d.isSink) {
        return AudioDevice.BluetoothHeadset(
          name = d.productName.ifBlank { bluetoothFallback },
          transport = BluetoothTransport.BLE_AUDIO,
          supportsInput = hasInputSupport(d, inputDevices),
        )
      }
    }
    sinks.forEach { d ->
      if (d.type == TYPE_BLUETOOTH_A2DP && d.isSink) {
        return AudioDevice.BluetoothHeadset(
          name = d.productName.ifBlank { bluetoothFallback },
          transport = BluetoothTransport.A2DP,
          supportsInput = false,
        )
      }
    }
    sinks.forEach { d ->
      if (isSelectableScoOutput(d, localModel, localDevice)) {
        return AudioDevice.BluetoothHeadset(
          name = d.productName.ifBlank { bluetoothFallback },
          transport = BluetoothTransport.SCO,
          supportsInput = hasInputSupport(d, inputDevices),
        )
      }
    }
    sinks.forEach { d ->
      if (isWiredOutput(d.type) && d.isSink) {
        return AudioDevice.WiredHeadset(name = d.productName.ifBlank { wiredFallback })
      }
    }
    return AudioDevice.Speaker
  }

  fun listOutputs(
    sinks: List<DeviceDescriptor>,
    speakerFallback: String,
    wiredFallback: String,
    bluetoothFallback: String = speakerFallback,
    inputDevices: List<DeviceDescriptor> = emptyList(),
    localModel: String = "",
    localDevice: String = "",
  ): List<AudioDevice> {
    val out = linkedSetOf<AudioDevice>(AudioDevice.Speaker)
    val bluetooth = linkedMapOf<String, AudioDevice.BluetoothHeadset>()
    sinks.forEach { d ->
      if (!d.isSink) return@forEach
      when {
        isBleOutput(d.type) -> bluetooth.getOrPut(
          endpointKey(d.productName.ifBlank { bluetoothFallback }, BluetoothTransport.BLE_AUDIO)
        ) {
          AudioDevice.BluetoothHeadset(
            name = d.productName.ifBlank { bluetoothFallback },
            transport = BluetoothTransport.BLE_AUDIO,
            supportsInput = hasInputSupport(d, inputDevices),
          )
        }
        d.type == TYPE_BLUETOOTH_A2DP -> bluetooth.getOrPut(
          endpointKey(d.productName.ifBlank { bluetoothFallback }, BluetoothTransport.A2DP)
        ) {
          AudioDevice.BluetoothHeadset(
            name = d.productName.ifBlank { bluetoothFallback },
            transport = BluetoothTransport.A2DP,
            supportsInput = false,
          )
        }
        isSelectableScoOutput(d, localModel, localDevice) -> bluetooth.getOrPut(
          endpointKey(d.productName.ifBlank { bluetoothFallback }, BluetoothTransport.SCO)
        ) {
          AudioDevice.BluetoothHeadset(
            name = d.productName.ifBlank { bluetoothFallback },
            transport = BluetoothTransport.SCO,
            supportsInput = hasInputSupport(d, inputDevices),
          )
        }
        isWiredOutput(d.type) -> out.add(
          AudioDevice.WiredHeadset(name = d.productName.ifBlank { wiredFallback })
        )
      }
    }
    out.addAll(bluetooth.values)
    return out.toList()
  }

  fun listInputs(
    devices: List<DeviceDescriptor>,
    fallback: String,
    outputDevices: List<DeviceDescriptor> = emptyList(),
    localModel: String = "",
    localDevice: String = "",
  ): List<AudioInputOption> {
    val seen = linkedMapOf<String, AudioInputOption>()
    devices.forEach { d ->
      if (!isBluetoothOutput(d.type)) return@forEach
      if (!d.isSource) return@forEach
      val name = when {
        d.type == TYPE_BLUETOOTH_SCO && isPlaceholderBluetoothEndpoint(d, localModel, localDevice) ->
          outputDevices.firstOrNull { output ->
            output.isSink &&
              (output.type == TYPE_BLE_HEADSET || output.type == TYPE_BLUETOOTH_A2DP) &&
              !isPlaceholderBluetoothEndpoint(output, localModel, localDevice)
          }?.productName ?: return@forEach
        else -> d.productName.ifBlank { fallback }
      }
      val transport = when (d.type) {
        TYPE_BLE_HEADSET -> BluetoothTransport.BLE_AUDIO
        TYPE_BLUETOOTH_SCO -> BluetoothTransport.SCO
        else -> return@forEach
      }
      seen.getOrPut(endpointKey(name, transport)) {
        AudioInputOption(
          device = AudioDevice.BluetoothHeadset(
            name = name,
            transport = transport,
            supportsInput = true,
          ),
          isPreferred = false,
        )
      }
    }
    return seen.values.toList()
  }

  fun findByName(
    devices: List<DeviceDescriptor>,
    name: String,
    transport: BluetoothTransport,
  ): DeviceDescriptor? {
    val targetType = when (transport) {
      BluetoothTransport.BLE_AUDIO -> TYPE_BLE_HEADSET
      BluetoothTransport.SCO -> TYPE_BLUETOOTH_SCO
      BluetoothTransport.A2DP -> TYPE_BLUETOOTH_A2DP
    }
    return devices.firstOrNull { it.type == targetType && it.productName == name }
  }

  /**
   * Stable identifier for grouping picker rows by transport. Used to verify the
   * picker renders the right icon + label for every BT subtype. Pure logic —
   * no Android dependency, fully unit-testable.
   */
  fun transportDescriptor(transport: BluetoothTransport): TransportDescriptor =
    when (transport) {
      BluetoothTransport.BLE_AUDIO -> TransportDescriptor(
        key = "ble",
        supportsInput = true,
        isModern = true,
      )
      BluetoothTransport.A2DP -> TransportDescriptor(
        key = "a2dp",
        supportsInput = false,
        isModern = false,
      )
      BluetoothTransport.SCO -> TransportDescriptor(
        key = "sco",
        supportsInput = true,
        isModern = false,
      )
    }

  private fun endpointKey(name: String, transport: BluetoothTransport): String =
    "$name:${transport.name}"
}

data class TransportDescriptor(
  val key: String,
  val supportsInput: Boolean,
  val isModern: Boolean,
)
