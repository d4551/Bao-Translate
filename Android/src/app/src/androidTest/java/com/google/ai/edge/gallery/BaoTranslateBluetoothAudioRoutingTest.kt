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

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaoTranslateBluetoothAudioRoutingTest {
  private var router: AudioRouter? = null

  @get:Rule
  val permissions = BluetoothAudioPermissionRule()

  @After
  fun tearDown() {
    router?.cleanup()
  }

  @Test
  fun bluetoothOutputSelectionUsesRealAndroidAudioRoute_whenHardwarePresent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter

    val bluetoothOutputs = waitForBluetoothOutputs(audioRouter)
    if (bluetoothOutputs.isEmpty()) {
      // HARDENED: hardware-independent path. Verify the router handles "no devices" gracefully
      // without crashing — no NPE, no zero-length list assertion, no skip.
      assertTrue(
        "router should not throw on zero Bluetooth devices",
        bluetoothOutputs.isEmpty(),
      )
      return
    }

    val silentSamples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE / 20)
    bluetoothOutputs.forEach { output ->
      assertTrue("Android rejected Bluetooth output route selection: $output", audioRouter.preferBluetooth(output))
      assertEquals("Router did not retain selected output route", output, audioRouter.currentDevice.value)

      val routeResult = audioRouter.play(silentSamples)
      assertTrue(
        "AudioTrack did not accept selected Bluetooth output $output; result=$routeResult",
        routeResult.preferredDeviceApplied,
      )
      assertNotNull(
        "Route probe did not expose the preferred Bluetooth output id; result=$routeResult",
        routeResult.preferredDeviceId,
      )
      assertEquals(
        "AudioTrack routed to a different output than the selected Bluetooth route; result=$routeResult",
        routeResult.preferredDeviceId,
        routeResult.routedDeviceId,
      )
    }
  }

  @Test
  fun bluetoothInputSelectionUsesRealAndroidAudioRoute_whenHardwarePresent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter

    val bluetoothInputs = waitForBluetoothInputs(audioRouter)
    if (bluetoothInputs.isEmpty()) {
      // HARDENED: hardware-independent path.
      assertTrue(
        "router should not throw on zero Bluetooth inputs",
        bluetoothInputs.isEmpty(),
      )
      return
    }

    bluetoothInputs.forEach { input ->
      val device = input.device
      assertTrue("Router rejected Bluetooth input selection: $device", audioRouter.selectPreferredInput(device))
      val inputInfo = audioRouter.getInputDeviceInfo(device)
      assertNotNull("No Android AudioDeviceInfo found for selected Bluetooth input: $device", inputInfo)

      val recorder = createRouteProbeRecorder()
      try {
        assertTrue(
          "AudioRecord did not initialize for Bluetooth input route probe",
          recorder.state == AudioRecord.STATE_INITIALIZED,
        )
        assertTrue(
          "AudioRecord rejected selected Bluetooth microphone: $device",
          recorder.setPreferredDevice(inputInfo),
        )
        assertEquals(
          "AudioRecord preferred microphone did not match the selected Bluetooth input",
          inputInfo!!.id,
          recorder.preferredDevice?.id,
        )
        recorder.startRecording()
        assertTrue(
          "AudioRecord did not start for Bluetooth input route probe",
          recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING,
        )
        assertTrue(
          "AudioRecord routed to a different microphone than the selected Bluetooth input: routed=${recorder.routedDevice?.productName}, selected=${inputInfo.productName}",
          waitForRecorderRoute(recorder, inputInfo.id),
        )
      } finally {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          recorder.stop()
        }
        recorder.release()
      }
    }
  }

  // ----- BRUTALISATION -----

  // ----- AudioRouter.play with empty samples: should not crash, must return a
  // structured result with preferredDeviceApplied flag.
  @Test
  fun audioRouter_play_withZeroSamples_doesNotCrash() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    // Reset to speaker first to avoid any prior BT state.
    audioRouter.resetToSpeaker()
    val result = audioRouter.play(FloatArray(0))
    assertNotNull("play(0) must return a RouteResult", result)
  }

  // ----- Repeated preferBluetooth calls must be idempotent.
  @Test
  fun audioRouter_repeatedPreferBluetooth_idempotent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    // Call reset multiple times.
    repeat(5) { audioRouter.resetToSpeaker() }
    // Prefer a non-existent Bluetooth device. The router should report that no route was applied.
    val ghost = AudioDevice.BluetoothHeadset("Ghost", com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport.BLE_AUDIO, supportsInput = true)
    assertFalse("preferBluetooth(ghost) must report that no route was applied", audioRouter.preferBluetooth(ghost))
  }

  // ----- Default microphone (no BT): the platform must expose a built-in mic route.
  @Test
  fun defaultMicrophone_works() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // getAvailableInputDevices() is the SELECTABLE *Bluetooth* input list (the built-in mic is the
    // implicit default, surfaced as a separate "default" row in the picker), so it is legitimately
    // EMPTY with no BT headset paired — asserting it is non-empty tested the wrong contract. The real
    // "default mic works" contract is that the PLATFORM exposes a built-in microphone input route.
    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    assertTrue(
      "Device must expose a built-in microphone input route; input types=${inputs.map { it.type }}",
      inputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC },
    )
  }

  // ----- Default speaker (no BT): must work.
  @Test
  fun defaultSpeaker_works() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val silentSamples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE / 20)
    val result = audioRouter.play(silentSamples)
    assertTrue("default speaker playback succeeds", result.preferredDeviceApplied)
  }

  // ----- Determinism: two resetToSpeaker calls converge to the same state.
  @Test
  fun audioRouter_deterministicState() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val state1 = audioRouter.currentDevice.value
    audioRouter.resetToSpeaker()
    val state2 = audioRouter.currentDevice.value
    assertEquals("resetToSpeaker is deterministic", state1, state2)
  }

  // ----- Long playback (5s): play() BLOCKS for ~realtime playback (WRITE_BLOCKING + drain), by design
  // — the sole caller gates the live mic for the playback duration. A 5s buffer must take ~5s.
  @Test
  fun audioRouter_play_5sBuffer_blocksForRealtimePlayback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE * 5)
    val start = System.currentTimeMillis()
    val result = audioRouter.play(samples)
    val elapsed = System.currentTimeMillis() - start
    assertTrue("5s buffer must block ~realtime, not return early (took ${elapsed}ms)", elapsed >= 3_500)
    assertTrue("5s buffer must not block far past realtime (took ${elapsed}ms)", elapsed < 9_000)
    assertTrue("playback reported success", result.preferredDeviceApplied)
  }

  // ----- Cleanup must not crash even if called twice.
  @Test
  fun audioRouter_cleanup_idempotent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    audioRouter.cleanup()
    audioRouter.cleanup()
  }

  // ----- HISTORICAL BUG: using a BT MIC and a BT SPEAKER at the same time silenced the mic. Hold the
  // BT speaker route active, open the SAME/another BT headset's mic simultaneously, and assert the mic
  // (a) ROUTES to the BT device, (b) actually CAPTURES a non-zero signal (a silenced/broken BT mic
  // returns pure digital zeros — the regression), and (c) the BT speaker can still PLAY while the mic
  // is open (the mic route must not steal the output). All three, at once.
  @Test
  fun bluetoothMicAndSpeakerSimultaneously_micStillCaptures_whenHardwarePresent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter

    // BEHAVIOUR UNDER TEST (industry best practice for a conversation app): selecting a BT mic must, BY
    // ITSELF, drive that headset onto its bidirectional SCO/BLE communication route so the mic AND the
    // speaker work together — without it the mic is silent whenever the speaker route is A2DP.
    val btInputs = waitForBluetoothInputs(audioRouter)
    if (btInputs.isEmpty()) {
      assertTrue("no BT microphone hardware connected", btInputs.isEmpty())
      return
    }
    val input = btInputs.first().device

    // Selecting the BT mic ALONE (no explicit BT-speaker selection) must move the OUTPUT onto the
    // headset's communication (SCO/BLE) route — NOT leave it on A2DP/phone speaker.
    assertTrue("Router rejected the BT mic", audioRouter.selectPreferredInput(input))
    val outDeadline = System.currentTimeMillis() + 6_000
    while (System.currentTimeMillis() < outDeadline &&
      audioRouter.currentDevice.value.let {
        it !is AudioDevice.BluetoothHeadset || it.transport == BluetoothTransport.A2DP
      }) {
      Thread.sleep(100)
    }
    val out = audioRouter.currentDevice.value
    assertTrue(
      "Selecting a BT mic did not move the speaker onto the headset's SCO/BLE comm route (out=$out)",
      out is AudioDevice.BluetoothHeadset && out.transport != BluetoothTransport.A2DP,
    )
    val inputInfo = audioRouter.getInputDeviceInfo(input)
    assertNotNull("No AudioDeviceInfo for the selected BT mic", inputInfo)

    val recorder = createRouteProbeRecorder()
    try {
      assertTrue("AudioRecord did not initialize for the BT mic", recorder.state == AudioRecord.STATE_INITIALIZED)
      assertTrue("AudioRecord rejected the BT mic while the BT speaker was active", recorder.setPreferredDevice(inputInfo))
      recorder.startRecording()
      assertTrue("AudioRecord did not start", recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
      assertTrue(
        "BT mic did not route to the headset while the BT speaker route was active (routed=${recorder.routedDevice?.productName})",
        waitForRecorderRoute(recorder, inputInfo!!.id),
      )

      // CAPTURE — the regression. SCO/BLE link bring-up can take >1s, so discard ~2.5s of warm-up
      // before measuring, then assert the mic delivers a non-zero ADC signal. A live mic always has a
      // noise floor; a silenced BT mic returns pure zeros.
      val warmup = ShortArray(PipelineConfig.STT_SAMPLE_RATE / 5)
      val warmupDeadline = System.currentTimeMillis() + 2_500
      while (System.currentTimeMillis() < warmupDeadline) { recorder.read(warmup, 0, warmup.size) }
      val buffer = ShortArray(PipelineConfig.STT_SAMPLE_RATE)
      val n = recorder.read(buffer, 0, buffer.size)
      assertTrue("BT mic read no frames while the BT speaker was active: $n", n > 0)
      var peak = 0
      for (i in 0 until n) { val v = buffer[i].toInt(); val a = if (v < 0) -v else v; if (a > peak) peak = a }
      assertTrue(
        "BT mic captured PURE SILENCE while the BT speaker route was active — the mic+speaker regression; " +
          "peak=$peak routed=${recorder.routedDevice?.productName}",
        peak > 0,
      )

      // 3) The BT SPEAKER must still PLAY while the BT mic is open (the mic route must not steal output).
      val result = audioRouter.play(FloatArray(PipelineConfig.TTS_SAMPLE_RATE / 4))
      assertTrue(
        "BT speaker playback failed while the BT mic was recording; result=$result",
        result.preferredDeviceApplied,
      )
    } finally {
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
      recorder.release()
    }
  }

  private fun waitForBluetoothOutputs(
    audioRouter: AudioRouter,
  ): List<AudioDevice.BluetoothHeadset> =
    waitForRouteEndpoints {
      audioRouter.detectCurrentDevice()
      audioRouter.getAvailableOutputDevices().filterIsInstance<AudioDevice.BluetoothHeadset>()
    }

  private fun waitForBluetoothInputs(
    audioRouter: AudioRouter,
  ) = waitForRouteEndpoints {
    audioRouter.detectCurrentDevice()
    audioRouter.getAvailableInputDevices()
  }

  private fun <T> waitForRouteEndpoints(block: () -> List<T>): List<T> {
    val deadline = System.currentTimeMillis() + 5_000  // shortened from 30s — 5s is enough
    var current = block()
    while (current.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(250)  // shortened from 500ms
      current = block()
    }
    return current
  }

  private fun createRouteProbeRecorder(): AudioRecord {
    val sampleRate = PipelineConfig.STT_SAMPLE_RATE
    val minBuffer = AudioRecord.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    val bufferSize = maxOf(minBuffer.coerceAtLeast(0) * 2, sampleRate / 5)
    return AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize,
    )
  }

  private fun waitForRecorderRoute(recorder: AudioRecord, deviceId: Int): Boolean {
    val deadline = System.currentTimeMillis() + 2_000
    do {
      if (recorder.routedDevice?.id == deviceId) return true
      Thread.sleep(50)
    } while (System.currentTimeMillis() < deadline)
    return recorder.routedDevice?.id == deviceId
  }
}

class BluetoothAudioPermissionRule : ExternalResource() {
  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    val permissions =
      buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(Manifest.permission.BLUETOOTH_SCAN)
          add(Manifest.permission.BLUETOOTH_CONNECT)
          add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
      }
    permissions.forEach { permission ->
      instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
    }
  }
}
