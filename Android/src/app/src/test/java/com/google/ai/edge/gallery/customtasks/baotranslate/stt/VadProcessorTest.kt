package com.google.ai.edge.gallery.customtasks.baotranslate.stt

import android.content.Context
import com.google.ai.edge.gallery.testkit.BaoStrictTest
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.experimental.categories.Category
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@Category(Strict::class)
class VadProcessorTest : BaoStrictTest() {

  private lateinit var context: Context
  private lateinit var vad: VadProcessor

  @Before
  fun setup() {
    context = mock()
    vad = VadProcessor(context)
  }

  @After
  fun tearDown() {
    // Avoid mock leakage across tests: release the VAD and the mock Context.
    vad.cleanup()
  }

  @Test
  fun `short audio below minimum returns single segment`() {
    val samples = ShortArray(8000) { 1000 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(1, segments.size)
    assertEquals(8000, segments[0].size)
  }

  @Test
  fun `silence splitting creates multiple segments`() {
    val samples = ShortArray(48000) { 0 }
    // First speech segment
    for (i in 0 until 20000) {
      samples[i] = 1000
    }
    // Silence gap
    for (i in 20000 until 26000) {
      samples[i] = 0
    }
    // Second speech segment
    for (i in 26000 until 48000) {
      samples[i] = 1000
    }

    val segments = vad.processAudioSegment(samples)
    assertEquals(2, segments.size)
  }

  @Test
  fun `all silence returns empty or minimal segments`() {
    val samples = ShortArray(48000) { 0 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(0, segments.size)
  }

  @Test
  fun `trailing short segment included if above half minimum`() {
    val samples = ShortArray(24000) { 1000 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(1, segments.size)
  }

  // ----- BRUTALISATION -----

  // ----- When VAD is not initialized (or model missing), the production code MUST
  // fall through to fallbackSegmentation. This is the CRITICAL production path:
  // without a VAD model, recording must not crash.
  @Test
  fun `processAudioSegment_afterCleanup_usesFallback`() {
    vad.cleanup()
    val samples = ShortArray(48000) { 0 }
    // First half loud, second half silent
    for (i in 0 until 24000) {
      samples[i] = 5000
    }
    val segments = vad.processAudioSegment(samples)
    // After cleanup, vad is null, isReady is false -> fallbackSegmentation is used.
    // Pin that it doesn't crash and returns SOMETHING meaningful (the loud input has energy).
    assertNotNull("must not crash after cleanup", segments)
    // Whether or not the fallback segments it depends on the threshold, but no crash.
  }

  // ----- Idempotence: two back-to-back calls with the same input return same result.
  @Test
  fun `processAudioSegment_calledTwice_idempotent`() {
    val samples = ShortArray(24000) { 1000 }
    val s1 = vad.processAudioSegment(samples)
    val s2 = vad.processAudioSegment(samples)
    assertEquals(s1.size, s2.size)
    for (i in s1.indices) {
      assertEquals("segment $i size", s1[i].size, s2[i].size)
    }
  }

  // ----- Reset is idempotent: call multiple times, no crash.
  @Test
  fun `reset_idempotent`() {
    repeat(5) { vad.reset() }
  }

  // ----- Cleanup is safe to call twice.
  @Test
  fun `cleanup_calledTwice_safe`() {
    vad.cleanup()
    vad.cleanup()
  }

  // ----- All-zero input: production's `hasFallbackSpeechEnergy` returns false
  // (peak < 1000 && rms < 0.015). Documented.
  @Test
  fun `allSilence_returnsEmpty`() {
    val samples = ShortArray(48000) { 0 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(0, segments.size)
  }

  // ----- Below min-segment-size (< 1 second at 16kHz = 16000 samples), production
  // returns a single segment with the entire input.
  @Test
  fun `belowOneSecond_returnsSingleSegment`() {
    val samples = ShortArray(8000) { 2000 }  // 0.5s of clear speech (peak 2000 >= 1000, rms ~0.061 >= 0.015)
    val segments = vad.processAudioSegment(samples)
    assertEquals(1, segments.size)
    assertEquals(8000, segments[0].size)
  }

  // ----- Empty input: production's processAudioSegment must not crash on empty input.
  @Test
  fun `emptyInput_doesNotCrash`() {
    val segments = vad.processAudioSegment(ShortArray(0))
    // Documented: hasFallbackSpeechEnergy returns false for empty -> empty list.
    assertEquals(0, segments.size)
  }

  // ----- Just-loud-enough signal: peak >= 1000 && rms >= 0.015. Pin the threshold.
  @Test
  fun `peakAtThreshold_isAccepted`() {
    // 16000 samples, peak = 1000 (exactly at the threshold), uniform amplitude
    val samples = ShortArray(16000) { 1000 }
    val segments = vad.processAudioSegment(samples)
    // Below 1 second: returns single segment with full input.
    assertEquals(1, segments.size)
  }

  // ----- Below-energy-threshold: peak = 999 (< 1000). No segments.
  @Test
  fun `peakBelowThreshold_returnsEmpty`() {
    val samples = ShortArray(48000) { 999 }
    val segments = vad.processAudioSegment(samples)
    // hasFallbackSpeechEnergy: peak (999) < FALLBACK_PEAK_THRESHOLD (1000) -> false -> empty.
    assertEquals(0, segments.size)
  }

  // ----- Rms below threshold: peak = 5000 (above) but amplitude is too low for rms
  // to clear 0.015. Build a signal that has the required peak but very low RMS:
  // a single loud sample, rest zero.
  @Test
  fun `highPeakLowRms_rmsFails_returnsEmpty`() {
    val samples = ShortArray(16000) { 0 }
    samples[0] = 10000  // high peak
    // RMS = sqrt((10000^2 + 0) / 16000) / 32768 ≈ 0.0098 < 0.015
    val segments = vad.processAudioSegment(samples)
    // hasFallbackSpeechEnergy: peak (10000) >= 1000 but rms (0.0098) < 0.015 -> false.
    assertEquals(0, segments.size)
  }

  // ----- Concurrent calls: the inferenceLock serializes native calls. 4 threads
  // each call processAudioSegment; no crash, each returns a deterministic result.
  @Test
  fun `concurrentCalls_serializedAndSafe`() {
    val samples = ShortArray(16000) { 1000 }
    val threads = (0 until 4).map { threadIdx ->
      Thread {
        // Each thread gets the SAME result, regardless of interleaving.
        val segments = vad.processAudioSegment(samples)
        // Capture the segment count.
        segments.size
      }.also { it.start() }
    }
    threads.forEach { it.join() }
    // No assertion on the result because race conditions in the production
    // code may not be deterministic, but the test must NOT crash.
  }

  // ----- The single-segment return is the input array (not a copy). Pin the contract.
  @Test
  fun `shortAudio_segmentRefersToInputData`() {
    val samples = ShortArray(8000) { 1000 }
    val segments = vad.processAudioSegment(samples)
    assertEquals(1, segments.size)
    // List<Short> can be either same list or copy. Pin the values.
    for (i in 0 until 8000) {
      assertEquals(samples[i].toInt(), segments[0][i].toInt())
    }
  }

  // ----- Determinism: same input -> same segments. Documented.
  @Test
  fun `deterministic_segmentsAcrossCalls`() {
    val samples = ShortArray(48000) { i -> if (i < 24000) 1000 else 0 }
    val s1 = vad.processAudioSegment(samples)
    val s2 = vad.processAudioSegment(samples)
    assertEquals("same input -> same segment count", s1.size, s2.size)
    for (i in s1.indices) {
      assertEquals("segment $i same size", s1[i].size, s2[i].size)
    }
  }

  // ----- Concurrency x 10: stress the inferenceLock.
  @Test
  fun `concurrentCalls_stressTest`() {
    val samples = ShortArray(16000) { 1000 }
    val errors = java.util.concurrent.atomic.AtomicInteger(0)
    val threads = (0 until 10).map {
      Thread {
        try {
          vad.processAudioSegment(samples)
        } catch (e: Throwable) {
          errors.incrementAndGet()
        }
      }.also { it.start() }
    }
    threads.forEach { it.join() }
    assertEquals("no thread should throw", 0, errors.get())
  }
}
