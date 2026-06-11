package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Category(Strict::class)
class DeviceProbeTest {

  @Test
  fun `pickOutput falls back to speaker when no devices`() {
    val result = DeviceProbe.pickOutput(sinks = emptyList(), speakerFallback = "Speaker")
    assertEquals(AudioDevice.Speaker, result)
  }

  @Test
  fun `pickOutput prefers BLE Audio over A2DP over SCO`() {
    val a2dp = device(type = DeviceProbe.TYPE_BLUETOOTH_A2DP, name = "A2DP", isSource = false, isSink = true)
    val ble = device(type = DeviceProbe.TYPE_BLE_HEADSET, name = "Buds", isSource = true, isSink = true)
    val sco = device(type = DeviceProbe.TYPE_BLUETOOTH_SCO, name = "HFP", isSource = true, isSink = true)
    val result = DeviceProbe.pickOutput(listOf(a2dp, sco, ble), speakerFallback = "Speaker")
    val bt = result as AudioDevice.BluetoothHeadset
    assertEquals("Buds", bt.name)
    assertEquals(BluetoothTransport.BLE_AUDIO, bt.transport)
    assertTrue(bt.supportsInput)
  }

  @Test
  fun `pickOutput falls back to A2DP when no BLE`() {
    val a2dp = device(type = DeviceProbe.TYPE_BLUETOOTH_A2DP, name = "A2DP", isSource = false, isSink = true)
    val result = DeviceProbe.pickOutput(listOf(a2dp), speakerFallback = "Speaker")
    val bt = result as AudioDevice.BluetoothHeadset
    assertEquals("A2DP", bt.name)
    assertEquals(BluetoothTransport.A2DP, bt.transport)
    assertFalse(bt.supportsInput)
  }

  @Test
  fun `pickOutput falls back to wired when no BT`() {
    val wired = device(type = DeviceProbe.TYPE_WIRED_HEADPHONES, name = "Wired", isSource = false, isSink = true)
    val result = DeviceProbe.pickOutput(listOf(wired), speakerFallback = "Speaker")
    assertEquals(AudioDevice.WiredHeadset("Wired"), result)
  }

  @Test
  fun `listOutputs deduplicates by name and includes speaker`() {
    val buds1 = device(type = DeviceProbe.TYPE_BLE_HEADSET, name = "Buds", isSource = true, isSink = true)
    val buds2 = device(type = DeviceProbe.TYPE_BLE_HEADSET, name = "Buds", isSource = true, isSink = true)
    val q2 = device(type = DeviceProbe.TYPE_BLUETOOTH_A2DP, name = "QuietComfort", isSource = false, isSink = true)
    val result = DeviceProbe.listOutputs(
      sinks = listOf(buds1, buds2, q2),
      speakerFallback = "Phone speaker",
      wiredFallback = "Wired",
    )
    assertTrue(result.contains(AudioDevice.Speaker))
    assertEquals(3, result.size)
    val bud = result.first { it is AudioDevice.BluetoothHeadset && it.name == "Buds" } as AudioDevice.BluetoothHeadset
    assertEquals(BluetoothTransport.BLE_AUDIO, bud.transport)
  }

  @Test
  fun `listInputs returns only BT sources and dedupes by name`() {
    val buds = device(type = DeviceProbe.TYPE_BLE_HEADSET, name = "Buds", isSource = true, isSink = true)
    val a2dp = device(type = DeviceProbe.TYPE_BLUETOOTH_A2DP, name = "A2DP", isSource = false, isSink = true)
    val sco = device(type = DeviceProbe.TYPE_BLUETOOTH_SCO, name = "HFP", isSource = true, isSink = true)
    val result = DeviceProbe.listInputs(listOf(buds, a2dp, sco), fallback = "BT")
    assertEquals(2, result.size)
    val names = result.map { it.device.name }
    assertTrue(names.contains("Buds"))
    assertTrue(names.contains("HFP"))
  }

  @Test
  fun `findByName returns null when device not present`() {
    val none = DeviceProbe.findByName(
      devices = listOf(device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", true, true)),
      name = "Unknown",
      transport = BluetoothTransport.BLE_AUDIO,
    )
    assertNull(none)
  }

  @Test
  fun `findByName matches by type and name`() {
    val buds = device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", true, true)
    val found = DeviceProbe.findByName(
      devices = listOf(buds, device(DeviceProbe.TYPE_BLUETOOTH_A2DP, "Buds", false, true)),
      name = "Buds",
      transport = BluetoothTransport.BLE_AUDIO,
    )
    assertNotNull(found)
    assertEquals(DeviceProbe.TYPE_BLE_HEADSET, found?.type)
  }

  @Test
  fun `isBluetoothOutput covers BLE A2DP and SCO`() {
    assertTrue(DeviceProbe.isBluetoothOutput(DeviceProbe.TYPE_BLE_HEADSET))
    assertTrue(DeviceProbe.isBluetoothOutput(DeviceProbe.TYPE_BLUETOOTH_A2DP))
    assertTrue(DeviceProbe.isBluetoothOutput(DeviceProbe.TYPE_BLUETOOTH_SCO))
    assertFalse(DeviceProbe.isBluetoothOutput(DeviceProbe.TYPE_WIRED_HEADPHONES))
    assertFalse(DeviceProbe.isBluetoothOutput(DeviceProbe.TYPE_BUILTIN_SPEAKER))
  }

  @Test
  fun `listOutputs includes wired after BT`() {
    val wired = device(type = DeviceProbe.TYPE_WIRED_HEADPHONES, name = "Cable", false, true)
    val result = DeviceProbe.listOutputs(listOf(wired), speakerFallback = "Speaker", wiredFallback = "Wired")
    assertTrue(result.contains(AudioDevice.Speaker))
    assertTrue(result.contains(AudioDevice.WiredHeadset("Cable")))
  }

  @Test
  fun `picker UI mapping renders all 3 BT transports with correct attributes`() {
    val ble = DeviceProbe.transportDescriptor(BluetoothTransport.BLE_AUDIO)
    val a2dp = DeviceProbe.transportDescriptor(BluetoothTransport.A2DP)
    val sco = DeviceProbe.transportDescriptor(BluetoothTransport.SCO)

    assertTrue(ble.supportsInput)
    assertTrue(ble.isModern)
    assertEquals("ble", ble.key)

    assertFalse(a2dp.supportsInput)
    assertFalse(a2dp.isModern)
    assertEquals("a2dp", a2dp.key)

    assertTrue(sco.supportsInput)
    assertFalse(sco.isModern)
    assertEquals("sco", sco.key)
  }

  @Test
  fun `multi BT picker scenario - 2 BLE headsets + 1 A2DP + 1 SCO + wired`() {
    val devices = listOf(
      device(DeviceProbe.TYPE_BLE_HEADSET, "Pixel Buds Pro", isSource = true, isSink = true),
      device(DeviceProbe.TYPE_BLE_HEADSET, "Sony WF-1000XM5", isSource = true, isSink = true),
      device(DeviceProbe.TYPE_BLUETOOTH_A2DP, "Sonos Roam", isSource = false, isSink = true),
      device(DeviceProbe.TYPE_BLUETOOTH_SCO, "Jabra Evolve2", isSource = true, isSink = true),
      device(DeviceProbe.TYPE_WIRED_HEADPHONES, "Wired", isSource = false, isSink = true),
    )
    val outputs = DeviceProbe.listOutputs(
      sinks = devices,
      speakerFallback = "Phone speaker",
      wiredFallback = "Wired headset",
    )
    // Speaker (always first) + 4 BT (2 BLE + 1 A2DP + 1 SCO, each as BluetoothHeadset) + 1 wired = 6
    assertEquals(6, outputs.size)
    assertTrue(outputs[0] is AudioDevice.Speaker)

    val btDevices = outputs.filterIsInstance<AudioDevice.BluetoothHeadset>()
    assertEquals("should have Pixel Buds + Sony + Sonos (A2DP) + Jabra (SCO)", 4, btDevices.size)
    val names = btDevices.map { it.name }
    assertTrue(names.contains("Pixel Buds Pro"))
    assertTrue(names.contains("Sony WF-1000XM5"))
    assertTrue(names.contains("Jabra Evolve2"))
    assertTrue(names.contains("Sonos Roam"))

    // Inputs: only BT source types (BLE + SCO) — 3 (2 BLE + 1 SCO)
    val inputs = DeviceProbe.listInputs(devices, fallback = "BT")
    assertEquals(3, inputs.size)
    val inputNames = inputs.map { it.device.name }
    assertTrue(inputNames.contains("Pixel Buds Pro"))
    assertTrue(inputNames.contains("Sony WF-1000XM5"))
    assertTrue(inputNames.contains("Jabra Evolve2"))
  }

  @Test
  fun `multi BT picker scenario - 3 identical BLE headsets dedup to 1`() {
    val devices = listOf(
      device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", isSource = true, isSink = true),
      device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", isSource = true, isSink = true),
      device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", isSource = true, isSink = true),
    )
    val outputs = DeviceProbe.listOutputs(devices, speakerFallback = "Phone speaker", wiredFallback = "Wired")
    val bt = outputs.filterIsInstance<AudioDevice.BluetoothHeadset>()
    assertEquals(1, bt.size)
    assertEquals("Buds", bt[0].name)
    assertEquals(BluetoothTransport.BLE_AUDIO, bt[0].transport)
  }

  @Test
  fun `multi BT picker scenario - same headset keeps media and call endpoints separate`() {
    val devices = listOf(
      device(DeviceProbe.TYPE_BLUETOOTH_A2DP, "WF-1000XM6", isSource = false, isSink = true),
      device(DeviceProbe.TYPE_BLUETOOTH_SCO, "WF-1000XM6", isSource = true, isSink = true),
    )

    val outputs = DeviceProbe.listOutputs(
      devices,
      speakerFallback = "Phone speaker",
      wiredFallback = "Wired",
    )
    val btOutputs = outputs.filterIsInstance<AudioDevice.BluetoothHeadset>()

    assertEquals(2, btOutputs.size)
    assertTrue(btOutputs.any { it.name == "WF-1000XM6" && it.transport == BluetoothTransport.A2DP && !it.supportsInput })
    assertTrue(btOutputs.any { it.name == "WF-1000XM6" && it.transport == BluetoothTransport.SCO && it.supportsInput })

    val inputs = DeviceProbe.listInputs(devices, fallback = "BT")
    assertEquals(1, inputs.size)
    assertEquals(BluetoothTransport.SCO, inputs.single().device.transport)
  }

  @Test
  fun `multi BT picker scenario - mixed transport gets correct descriptors`() {
    val ble = AudioDevice.BluetoothHeadset("Buds", BluetoothTransport.BLE_AUDIO, supportsInput = true)
    val a2dp = AudioDevice.BluetoothHeadset("Speaker", BluetoothTransport.A2DP, supportsInput = false)
    val sco = AudioDevice.BluetoothHeadset("HFP", BluetoothTransport.SCO, supportsInput = true)
    for (device in listOf(ble, a2dp, sco)) {
      val desc = DeviceProbe.transportDescriptor(device.transport)
      when (device.transport) {
        BluetoothTransport.BLE_AUDIO -> {
          assertEquals("ble", desc.key)
          assertTrue(desc.supportsInput)
          assertTrue(desc.isModern)
        }
        BluetoothTransport.A2DP -> {
          assertEquals("a2dp", desc.key)
          assertFalse(desc.supportsInput)
          assertFalse(desc.isModern)
        }
        BluetoothTransport.SCO -> {
          assertEquals("sco", desc.key)
          assertTrue(desc.supportsInput)
          assertFalse(desc.isModern)
        }
      }
    }
  }

  @Test
  fun `generic SCO output matching local device is filtered`() {
    val devices = listOf(
      device(
        DeviceProbe.TYPE_BLUETOOTH_SCO,
        "V23001960",
        isSource = true,
        isSink = true,
        address = "",
      ),
    )

    val outputs = DeviceProbe.listOutputs(
      devices,
      speakerFallback = "Phone speaker",
      wiredFallback = "Wired",
      bluetoothFallback = "Bluetooth",
      localModel = "V23001960",
      localDevice = "VTL-202403",
    )

    assertEquals(listOf(AudioDevice.Speaker), outputs)
  }

  @Test
  fun `generic SCO input without real media endpoint is filtered`() {
    val devices = listOf(
      device(
        DeviceProbe.TYPE_BLUETOOTH_SCO,
        "V23001960",
        isSource = true,
        isSink = false,
        address = "",
      ),
    )

    val inputs = DeviceProbe.listInputs(
      devices,
      fallback = "Bluetooth",
      outputDevices = emptyList(),
      localModel = "V23001960",
      localDevice = "VTL-202403",
    )

    assertTrue(inputs.isEmpty())
  }

  @Test
  fun `generic SCO input inherits real paired media endpoint name`() {
    val input = device(
      DeviceProbe.TYPE_BLUETOOTH_SCO,
      "V23001960",
      isSource = true,
      isSink = false,
      address = "",
    )
    val output = device(
      DeviceProbe.TYPE_BLUETOOTH_A2DP,
      "WF-1000XM6",
      isSource = false,
      isSink = true,
    )

    val inputs = DeviceProbe.listInputs(
      devices = listOf(input),
      fallback = "Bluetooth",
      outputDevices = listOf(output),
      localModel = "V23001960",
      localDevice = "VTL-202403",
    )

    assertEquals(1, inputs.size)
    assertEquals("WF-1000XM6", inputs.single().device.name)
    assertEquals(BluetoothTransport.SCO, inputs.single().device.transport)
  }

  // ----- BRUTALISATION -----

  // ----- Empty fallback strings: production uses them as device-name substitutes.
  // They should not crash; the resolved name should be the empty string.
  @Test
  fun `pickOutput_emptyWiredFallback_substitutesEmptyString`() {
    val wired = device(type = DeviceProbe.TYPE_WIRED_HEADPHONES, name = "", isSource = false, isSink = true)
    val result = DeviceProbe.pickOutput(listOf(wired), speakerFallback = "Speaker", wiredFallback = "")
    // Wired path: productName.ifBlank { wiredFallback } = "" because productName is blank.
    // Pin the contract: an empty fallback yields an empty device name, not a crash.
    assertEquals(AudioDevice.WiredHeadset(""), result)
  }

  @Test
  fun `pickOutput_emptyBluetoothFallback_substitutesEmptyString`() {
    val ble = device(type = DeviceProbe.TYPE_BLE_HEADSET, name = "", isSource = true, isSink = true)
    val result = DeviceProbe.pickOutput(
      listOf(ble),
      speakerFallback = "Speaker",
      bluetoothFallback = "",
    )
    val bt = result as AudioDevice.BluetoothHeadset
    assertEquals("", bt.name)
    assertEquals(BluetoothTransport.BLE_AUDIO, bt.transport)
  }

  // ----- All three BT transport descriptors have non-blank keys.
  @Test
  fun `transportDescriptor_allKeysNonBlank`() {
    assertTrue(DeviceProbe.transportDescriptor(BluetoothTransport.BLE_AUDIO).key.isNotBlank())
    assertTrue(DeviceProbe.transportDescriptor(BluetoothTransport.A2DP).key.isNotBlank())
    assertTrue(DeviceProbe.transportDescriptor(BluetoothTransport.SCO).key.isNotBlank())
  }

  // ----- Transport descriptor keys are unique.
  @Test
  fun `transportDescriptor_keysUnique`() {
    val keys = listOf(
      DeviceProbe.transportDescriptor(BluetoothTransport.BLE_AUDIO).key,
      DeviceProbe.transportDescriptor(BluetoothTransport.A2DP).key,
      DeviceProbe.transportDescriptor(BluetoothTransport.SCO).key,
    )
    assertEquals("all keys distinct", 3, keys.toSet().size)
  }

  // ----- Same name across two transports: dedup must keep both (one per transport).
  @Test
  fun `listOutputs_sameNameAcrossTransports_keptSeparately`() {
    val budsBle = device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", isSource = true, isSink = true)
    val budsA2dp = device(DeviceProbe.TYPE_BLUETOOTH_A2DP, "Buds", isSource = false, isSink = true)
    val outputs = DeviceProbe.listOutputs(
      listOf(budsBle, budsA2dp),
      speakerFallback = "Speaker",
      wiredFallback = "Wired",
    )
    val bt = outputs.filterIsInstance<AudioDevice.BluetoothHeadset>()
    assertEquals(2, bt.size)
    assertTrue(bt.any { it.transport == BluetoothTransport.BLE_AUDIO })
    assertTrue(bt.any { it.transport == BluetoothTransport.A2DP })
  }

  // ----- Multiple SCO outputs with same name: dedup keeps 1.
  @Test
  fun `listOutputs_multipleScoSameName_dedupeToOne`() {
    val sco1 = device(DeviceProbe.TYPE_BLUETOOTH_SCO, "HFP", isSource = true, isSink = true, address = "AA")
    val sco2 = device(DeviceProbe.TYPE_BLUETOOTH_SCO, "HFP", isSource = true, isSink = true, address = "BB")
    val outputs = DeviceProbe.listOutputs(listOf(sco1, sco2), speakerFallback = "Speaker", wiredFallback = "Wired")
    val bt = outputs.filterIsInstance<AudioDevice.BluetoothHeadset>()
    assertEquals("SCO dedupes by name+transport", 1, bt.size)
  }

  // ----- Empty input list: listInputs returns empty.
  @Test
  fun `listInputs_empty_returnsEmpty`() {
    val result = DeviceProbe.listInputs(emptyList(), fallback = "X")
    assertTrue(result.isEmpty())
  }

  // ----- Empty sinks list: listOutputs returns at least the speaker.
  @Test
  fun `listOutputs_emptySinks_returnsSpeakerOnly`() {
    val result = DeviceProbe.listOutputs(emptyList(), speakerFallback = "Phone speaker", wiredFallback = "Wired")
    assertEquals(1, result.size)
    assertEquals(AudioDevice.Speaker, result[0])
  }

  // ----- findByName across multiple transport types: only the matching transport is returned.
  @Test
  fun `findByName_a2dpTransport_findsOnlyA2dp`() {
    val budsBle = device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", true, true)
    val budsA2dp = device(DeviceProbe.TYPE_BLUETOOTH_A2DP, "Buds", false, true)
    val result = DeviceProbe.findByName(
      listOf(budsBle, budsA2dp),
      "Buds",
      BluetoothTransport.A2DP,
    )
    assertEquals(DeviceProbe.TYPE_BLUETOOTH_A2DP, result?.type)
  }

  // ----- Built-in speaker is NOT a Bluetooth output.
  @Test
  fun `isBluetoothOutput_builtInSpeaker_returnsFalse`() {
    assertFalse(DeviceProbe.isBluetoothOutput(DeviceProbe.TYPE_BUILTIN_SPEAKER))
  }

  // ----- pickOutput: if a sink is BOTH source and sink, it's still considered (supported).
  @Test
  fun `pickOutput_bleSourceAndSink_supported`() {
    val ble = device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", isSource = true, isSink = true)
    val result = DeviceProbe.pickOutput(
      listOf(ble),
      speakerFallback = "Speaker",
      inputDevices = listOf(ble),
    )
    val bt = result as AudioDevice.BluetoothHeadset
    assertTrue("input-supported when source bit set", bt.supportsInput)
  }

  // ----- findByName case sensitivity: must be exact match.
  @Test
  fun `findByName_caseSensitive_matchRequired`() {
    val buds = device(DeviceProbe.TYPE_BLE_HEADSET, "Buds", true, true)
    val found = DeviceProbe.findByName(listOf(buds), "buds", BluetoothTransport.BLE_AUDIO)
    assertNull("case mismatch should not match", found)
  }

  // ----- listInputs with no fallback substitution when productName is blank: should fall
  // back to the fallback string.
  @Test
  fun `listInputs_blankName_usesFallback`() {
    val buds = device(DeviceProbe.TYPE_BLE_HEADSET, "", isSource = true, isSink = true)
    val result = DeviceProbe.listInputs(listOf(buds), fallback = "DefaultBT")
    assertEquals(1, result.size)
    assertEquals("DefaultBT", result.single().device.name)
  }

  // ----- Per-speaker output routing (OOS-AUDIT-015a). The pure decision the on-device E2E asserts,
  // verified here on the JVM so the routing invariant is RUN-checked without the full pipeline/device.
  @Test
  fun `resolveOutputOverride routes per-speaker only in face-to-face`() {
    val earbuds = AudioDevice.WiredHeadset("Earbuds")
    val outputs = mapOf("es" to earbuds)
    assertEquals(earbuds, DeviceProbe.resolveOutputOverride(true, outputs, "es"))
    assertNull(DeviceProbe.resolveOutputOverride(true, outputs, "fr"))   // no override for that lang
    assertNull(DeviceProbe.resolveOutputOverride(false, outputs, "es"))  // not f2f -> global route
    assertNull(DeviceProbe.resolveOutputOverride(true, emptyMap(), "es")) // empty map -> global route
  }

  private fun device(
    type: Int,
    name: String,
    isSource: Boolean,
    isSink: Boolean,
    address: String = "00:11:22:33:44:55",
  ) = DeviceDescriptor(
    type = type,
    productName = name,
    address = address,
    isSource = isSource,
    isSink = isSink,
  )
}
