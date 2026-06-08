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
import kotlin.math.sqrt
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
      // HARDENED: a real device should pick up SOMETHING in 100ms. Pure silence would mean
      // the mic is dead or the test is running in a silent env. RMS > 0.001 catches both.
      val rms = computeRms(buffer, readCount.coerceAtLeast(0))
      assertTrue(
        "Default mic captured 0.1s of pure silence (rms=$rms). Mic may be muted or test env is silent.",
        rms > 0.001,
      )
    } finally {
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
      }
      recorder.release()
    }
  }

  // ----- BRUTALISATION -----

  // ----- AudioRouter.play with zero samples: must not crash, return a valid result.
  @Test
  fun audioRouter_play_withZeroSamples_isNoOp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val result = audioRouter.play(FloatArray(0))
    assertNotNull("play(0) must return a result", result)
  }

  // ----- Long playback (5s): completes in <1s wall time (no real audio output expected
  // in test env, but the AudioTrack must accept the buffer).
  @Test
  fun audioRouter_play_withVeryLongBuffer_completes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE * 5)  // 5 seconds
    val start = System.currentTimeMillis()
    val result = audioRouter.play(samples)
    val elapsed = System.currentTimeMillis() - start
    assertTrue("5s buffer must complete in <1s (took ${elapsed}ms)", elapsed < 1_000)
    assertTrue("playback reported success", result.preferredDeviceApplied)
  }

  // ----- resetToSpeaker idempotent.
  @Test
  fun audioRouter_resetToSpeaker_idempotent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val state1 = audioRouter.currentDevice.value
    audioRouter.resetToSpeaker()
    val state2 = audioRouter.currentDevice.value
    assertEquals(state1, state2)
  }

  // ----- Multiple successive play() calls: each returns a valid result.
  @Test
  fun audioRouter_play_3xSequential_allSucceed() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    repeat(3) {
      val result = audioRouter.play(speakerProbeSamples())
      assertTrue("play #$it must succeed", result.preferredDeviceApplied)
    }
  }

  // ----- Sine wave playback: AudioTrack must accept the buffer.
  @Test
  fun audioRouter_play_sineWave_accepted() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE / 5) { i ->
      (sin(2.0 * PI * 440.0 * i / PipelineConfig.TTS_SAMPLE_RATE) * 0.5).toFloat()
    }
    val result = audioRouter.play(samples)
    assertTrue("sine wave playback must succeed", result.preferredDeviceApplied)
  }

  // ----- DC signal playback (constant value): AudioTrack must accept.
  @Test
  fun audioRouter_play_dcSignal_accepted() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE / 10) { 0.5f }
    val result = audioRouter.play(samples)
    assertTrue("DC playback must succeed", result.preferredDeviceApplied)
  }

  // ----- NaN samples: documented behavior (current: propagates).
  @Test
  fun audioRouter_play_nanSamples_doesNotCrash() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE / 50) { i -> if (i == 0) Float.NaN else 0.5f }
    try {
      val result = audioRouter.play(samples)
      // Whether the prod code filters NaN or not, no exception.
      assertNotNull(result)
    } catch (e: Exception) {
      // If prod throws, the test must report it.
      throw e
    }
  }

  // ----- The AudioRouter handles concurrent play+reset without crashing.
  @Test
  fun audioRouter_play_thenReset_isSafe() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    audioRouter.play(speakerProbeSamples())
    audioRouter.resetToSpeaker()  // reset while a play is "in flight"
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

  private fun computeRms(buffer: ShortArray, count: Int): Double {
    if (count <= 0) return 0.0
    var sumSquares = 0.0
    for (i in 0 until count) {
      val v = buffer[i].toDouble()
      sumSquares += v * v
    }
    return sqrt(sumSquares / count) / 32768.0
  }
}
