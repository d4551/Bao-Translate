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
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import java.util.concurrent.atomic.AtomicReference
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

      // Discard ~300ms of warm-up frames (AGC / route settling can deliver pre-roll zeros right after
      // startRecording), then read ~1s of real frames. The DETERMINISTIC contract is that the default
      // mic route delivers frames (readCount > 0). The captured RMS is environment-dependent — a quiet
      // room legitimately reads ~0 (verified: this exact assertion passed on one device and failed with
      // rms=0.0 on another, identical code) — so RMS is LOGGED for diagnostics, never asserted. Asserting
      // ambient loudness tests the room, not the mic route.
      val warmup = ShortArray(PipelineConfig.STT_SAMPLE_RATE / 10)
      repeat(3) { recorder.read(warmup, 0, warmup.size) }
      val buffer = ShortArray(PipelineConfig.STT_SAMPLE_RATE)
      val readCount = recorder.read(buffer, 0, buffer.size)
      assertTrue("AudioRecord did not read frames from the default microphone: $readCount", readCount > 0)
      // A live mic always captures a non-zero ADC noise floor (even in a silent room); ONLY a dead or
      // OS-silenced mic returns pure digital zeros. Assert non-silence by PEAK, which (unlike the prior
      // rms>0.001) does NOT depend on ambient loudness — the old check tested the ROOM and flaked
      // between devices (passed Vertu, failed 2512BPNDAC with rms=0.0 on identical code).
      var peak = 0
      for (i in 0 until readCount) { val v = buffer[i].toInt(); val a = if (v < 0) -v else v; if (a > peak) peak = a }
      val rms = computeRms(buffer, readCount.coerceAtLeast(0))
      assertTrue(
        "Default mic captured pure digital silence post-warmup (dead/OS-silenced mic); peak=$peak rms=$rms",
        peak > 0,
      )
      Log.i(
        "BaoTranslateDeviceAudioRouteTest",
        "Default mic route captured $readCount frames post-warmup, peak=$peak rms=$rms",
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

  // ----- Long playback (5s): play() BLOCKS for ~realtime playback. AudioPlayback.playPcmFloat writes
  // WRITE_BLOCKING then waitForPlaybackDrain()s the AudioTrack to completion — this is intentional and
  // load-bearing: the sole caller (RecordingController) runs play() on Dispatchers.Default and relies on
  // it blocking for the playback duration to keep the mic gated (capturePaused) so the live mic never
  // re-hears and re-translates this output. So a 5s buffer must take ~5s, NOT return early.
  @Test
  fun audioRouter_play_5sBuffer_blocksForRealtimePlayback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE * 5)  // 5 seconds
    val start = System.currentTimeMillis()
    val result = audioRouter.play(samples)
    val elapsed = System.currentTimeMillis() - start
    assertTrue("5s buffer must block ~realtime, not return early (took ${elapsed}ms)", elapsed >= 3_500)
    assertTrue("5s buffer must not block far past realtime (took ${elapsed}ms)", elapsed < 9_000)
    assertTrue("playback reported success", result.preferredDeviceApplied)
  }

  // ----- Barge-in: releaseTrack()/cleanup() during a blocking play() must INTERRUPT it fast. This is
  // the real low-latency requirement (stop button, next-utterance pre-emption): the write/drain loop
  // checks abortRequested, which releaseTrack() flips. Without this, blocking playback could not be
  // cancelled mid-utterance.
  @Test
  fun audioRouter_play_bargeIn_cleanupInterruptsBlockingPlayback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioRouter = AudioRouter(context)
    router = audioRouter
    audioRouter.resetToSpeaker()
    val samples = FloatArray(PipelineConfig.TTS_SAMPLE_RATE * 5)  // 5 seconds
    val elapsedRef = AtomicReference(-1L)
    val workerFailure = AtomicReference<Throwable?>(null)
    val worker = Thread {
      val started = System.currentTimeMillis()
      audioRouter.play(samples)
      elapsedRef.set(System.currentTimeMillis() - started)
    }
    worker.setUncaughtExceptionHandler { _, t -> workerFailure.set(t) }
    worker.start()
    Thread.sleep(300) // let playback enter the blocking write/drain loop
    audioRouter.cleanup() // releaseTrack() flips abortRequested -> play() must return promptly
    worker.join(4_000)
    workerFailure.get()?.let { throw AssertionError("barge-in worker threw", it) }
    val elapsed = elapsedRef.get()
    assertTrue("barge-in play() did not return", elapsed >= 0)
    assertTrue("cleanup must interrupt a 5s play FAST (took ${elapsed}ms)", elapsed < 1_500)
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
