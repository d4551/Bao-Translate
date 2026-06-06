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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    assumeTrue(
      "No connected Bluetooth output endpoints were available to verify; reconnect a headset to run the real route probe.",
      bluetoothOutputs.isNotEmpty(),
    )

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
    assumeTrue(
      "No connected Bluetooth microphone endpoints were available to verify; reconnect a headset microphone to run the real route probe.",
      bluetoothInputs.isNotEmpty(),
    )

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
    val deadline = System.currentTimeMillis() + 30_000
    var current = block()
    while (current.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(500)
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
