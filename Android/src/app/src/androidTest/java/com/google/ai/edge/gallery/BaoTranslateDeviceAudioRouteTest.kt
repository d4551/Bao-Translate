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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaoTranslateDeviceAudioRouteTest {
  private var router: AudioRouter? = null

  @get:Rule
  val permissions = BluetoothAudioPermissionRule()

  @After
  fun tearDown() {
    router?.cleanup()
  }

  @Test
  fun builtInSpeakerAndDefaultMicrophoneUseRealAndroidAudioRoutes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val audioRouter = AudioRouter(context)
    router = audioRouter

    val speaker =
      audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    assertNotNull("Device did not expose a built-in speaker route", speaker)

    audioRouter.resetToSpeaker()
    val routeResult = audioRouter.play(speakerProbeSamples())
    assertTrue(
      "AudioTrack did not accept the built-in speaker output route; result=$routeResult",
      routeResult.preferredDeviceApplied,
    )
    assertEquals(
      "AudioTrack routed to a different output than the built-in speaker; result=$routeResult",
      speaker!!.id,
      routeResult.routedDeviceId,
    )

    val recorder = createDefaultMicProbeRecorder()
    try {
      assertTrue(
        "AudioRecord did not initialize for default microphone route probe",
        recorder.state == AudioRecord.STATE_INITIALIZED,
      )
      recorder.startRecording()
      assertTrue(
        "AudioRecord did not start for default microphone route probe",
        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING,
      )
      assertTrue(
        "AudioRecord did not route to the built-in microphone; routed=${recorder.routedDevice?.productName}",
        waitForRecorderType(recorder, AudioDeviceInfo.TYPE_BUILTIN_MIC),
      )

      val buffer = ShortArray(PipelineConfig.STT_SAMPLE_RATE / 10)
      val readCount = recorder.read(buffer, 0, buffer.size)
      assertTrue("AudioRecord did not read frames from the default microphone: $readCount", readCount > 0)
    } finally {
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
      }
      recorder.release()
    }
  }

  private fun speakerProbeSamples(): FloatArray {
    val sampleRate = PipelineConfig.TTS_SAMPLE_RATE
    return FloatArray(sampleRate / 10) { index ->
      (sin(2.0 * PI * 440.0 * index / sampleRate) * 0.01).toFloat()
    }
  }

  private fun createDefaultMicProbeRecorder(): AudioRecord {
    val sampleRate = PipelineConfig.STT_SAMPLE_RATE
    val minBuffer = AudioRecord.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    val bufferSize = maxOf(minBuffer.coerceAtLeast(0) * 2, sampleRate / 5)
    return AudioRecord(
      MediaRecorder.AudioSource.MIC,
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize,
    )
  }

  private fun waitForRecorderType(recorder: AudioRecord, deviceType: Int): Boolean {
    val deadline = System.currentTimeMillis() + 2_000
    do {
      if (recorder.routedDevice?.type == deviceType) return true
      Thread.sleep(50)
    } while (System.currentTimeMillis() < deadline)
    return recorder.routedDevice?.type == deviceType
  }
}
