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

  fun pickOutput(sinks: List<DeviceDescriptor>, speakerFallback: String): AudioDevice {
    sinks.forEach { d ->
      if (isBleOutput(d.type) && d.isSink) {
        return AudioDevice.BluetoothHeadset(
          name = d.productName.ifBlank { speakerFallback },
          transport = BluetoothTransport.BLE_AUDIO,
          supportsInput = d.isSource,
        )
      }
    }
    sinks.forEach { d ->
      if (d.type == TYPE_BLUETOOTH_A2DP && d.isSink) {
        return AudioDevice.BluetoothHeadset(
          name = d.productName.ifBlank { speakerFallback },
          transport = BluetoothTransport.A2DP,
          supportsInput = false,
        )
      }
    }
    sinks.forEach { d ->
      if (d.type == TYPE_BLUETOOTH_SCO && d.isSink) {
        return AudioDevice.BluetoothHeadset(
          name = d.productName.ifBlank { speakerFallback },
          transport = BluetoothTransport.SCO,
          supportsInput = d.isSource,
        )
      }
    }
    sinks.forEach { d ->
      if (isWiredOutput(d.type) && d.isSink) {
        return AudioDevice.WiredHeadset(name = d.productName.ifBlank { speakerFallback })
      }
    }
    return AudioDevice.Speaker
  }

  fun listOutputs(sinks: List<DeviceDescriptor>, speakerFallback: String, wiredFallback: String): List<AudioDevice> {
    val out = linkedSetOf<AudioDevice>(AudioDevice.Speaker)
    val bluetooth = linkedMapOf<String, AudioDevice.BluetoothHeadset>()
    sinks.forEach { d ->
      if (!d.isSink) return@forEach
      when {
        isBleOutput(d.type) -> bluetooth.getOrPut(
          endpointKey(d.productName.ifBlank { speakerFallback }, BluetoothTransport.BLE_AUDIO)
        ) {
          AudioDevice.BluetoothHeadset(
            name = d.productName.ifBlank { speakerFallback },
            transport = BluetoothTransport.BLE_AUDIO,
            supportsInput = d.isSource,
          )
        }
        d.type == TYPE_BLUETOOTH_A2DP -> bluetooth.getOrPut(
          endpointKey(d.productName.ifBlank { speakerFallback }, BluetoothTransport.A2DP)
        ) {
          AudioDevice.BluetoothHeadset(
            name = d.productName.ifBlank { speakerFallback },
            transport = BluetoothTransport.A2DP,
            supportsInput = false,
          )
        }
        d.type == TYPE_BLUETOOTH_SCO -> bluetooth.getOrPut(
          endpointKey(d.productName.ifBlank { speakerFallback }, BluetoothTransport.SCO)
        ) {
          AudioDevice.BluetoothHeadset(
            name = d.productName.ifBlank { speakerFallback },
            transport = BluetoothTransport.SCO,
            supportsInput = d.isSource,
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

  fun listInputs(devices: List<DeviceDescriptor>, fallback: String): List<AudioInputOption> {
    val seen = linkedMapOf<String, AudioInputOption>()
    devices.forEach { d ->
      if (!isBluetoothOutput(d.type)) return@forEach
      if (!d.isSource) return@forEach
      val name = d.productName.ifBlank { fallback }
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
