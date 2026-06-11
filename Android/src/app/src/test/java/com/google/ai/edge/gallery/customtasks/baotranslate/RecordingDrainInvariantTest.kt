package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.testkit.Strict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * OOS-LIVE-001 invariant, RUN-verified on the JVM (the on-device E2E adds the real STT/translate
 * pipeline, but the *mechanism* is verified here). In continuous mode a sub-8s-window utterance's only
 * commit path is the stop-time tail flush; `stopRecording()` cancels the recording scope, so the flush
 * — a suspend point (segmentProcessingMutex.withLock + processAudioSegment) — would throw
 * CancellationException and silently drop the utterance UNLESS it runs under `withContext(NonCancellable)`
 * (RecordingController final-flush block). These pin that exact pattern.
 */
@Category(Strict::class)
class RecordingDrainInvariantTest {

  @Test
  fun nonCancellableTailFlush_completesDespiteScopeCancel() = runBlocking { withTimeout(5_000) {
      var flushed = false
      val scope = CoroutineScope(Dispatchers.Default)
      val job =
        scope.launch {
          try {
            delay(10_000) // blocked in the read loop, as if recording
          } finally {
            // The fix: the tail flush + its suspend points are shielded from the stop-cancel.
            withContext(NonCancellable) {
              delay(20) // stand-in for the mutex-guarded processAudioSegment suspend work
              flushed = true
            }
          }
        }
      delay(50)
      job.cancelAndJoin() // == stopRecording() cancelling recordingScope
      assertTrue("NonCancellable tail flush must complete despite the stop-cancel", flushed)
  } }

  @Test
  fun withoutNonCancellable_theFlushIsDropped_provingTheBug() = runBlocking { withTimeout(5_000) {
      // The pre-fix shape: the flush runs a suspend point in the cancelled scope and is dropped.
      var flushed = false
      val scope = CoroutineScope(Dispatchers.Default)
      val job =
        scope.launch {
          try {
            delay(10_000)
          } finally {
            // No NonCancellable: a suspend call in a cancelled coroutine throws and skips the assignment.
            runCatching {
              delay(20)
              flushed = true
            }
          }
        }
      delay(50)
      job.cancelAndJoin()
      assertFalse("a plain suspend flush in the cancelled scope is dropped (this is the OOS-LIVE-001 bug)", flushed)
  } }
}
