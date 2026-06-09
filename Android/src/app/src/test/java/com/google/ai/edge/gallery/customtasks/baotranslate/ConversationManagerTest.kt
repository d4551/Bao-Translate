package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class ConversationManagerTest {

  @Test
  fun `phase transitions through recording and playback`() {
    val mgr = ConversationManager()
    assertEquals(ConversationPhase.Idle, mgr.phase)
    mgr.onRecordingStart()
    assertEquals(ConversationPhase.Listening, mgr.phase)
    mgr.onProcessingStart()
    assertEquals(ConversationPhase.Processing, mgr.phase)
    mgr.onPlaybackStart()
    assertEquals(ConversationPhase.Speaking, mgr.phase)
    mgr.onPlaybackEnd()
    assertEquals(ConversationPhase.Cooldown, mgr.phase)
    mgr.onTailMuteComplete()
    assertEquals(ConversationPhase.Listening, mgr.phase)
    mgr.onRecordingStop()
    assertEquals(ConversationPhase.Idle, mgr.phase)
  }

  @Test
  fun `segment without playback returns to listening`() {
    val mgr = ConversationManager()
    mgr.onRecordingStart()
    mgr.onProcessingStart()
    // VAD-empty / blank decode: no playback happened for this segment.
    mgr.onSegmentComplete()
    assertEquals(ConversationPhase.Listening, mgr.phase)
  }

  @Test
  fun `duplicate playback end is idempotent`() {
    val mgr = ConversationManager()
    mgr.onRecordingStart()
    mgr.onProcessingStart()
    mgr.onPlaybackStart()
    mgr.onPlaybackEnd()
    // The playback finally-block re-emits onPlaybackEnd on every exit path.
    mgr.onPlaybackEnd()
    assertEquals(ConversationPhase.Cooldown, mgr.phase)
    mgr.onTailMuteComplete()
    assertEquals(ConversationPhase.Listening, mgr.phase)
  }

  @Test
  fun `stop is terminal from every phase`() {
    for (setup in listOf<(ConversationManager) -> Unit>(
      { },
      { it.onRecordingStart() },
      { it.onRecordingStart(); it.onProcessingStart() },
      { it.onRecordingStart(); it.onProcessingStart(); it.onPlaybackStart() },
      { it.onRecordingStart(); it.onProcessingStart(); it.onPlaybackStart(); it.onPlaybackEnd() },
    )) {
      val mgr = ConversationManager()
      setup(mgr)
      mgr.onRecordingStop()
      assertEquals(ConversationPhase.Idle, mgr.phase)
    }
  }

  @Test
  fun `phase flow mirrors transitions`() {
    val mgr = ConversationManager()
    mgr.onRecordingStart()
    assertEquals(ConversationPhase.Listening, mgr.phaseFlow.value)
    mgr.onRecordingStop()
    assertEquals(ConversationPhase.Idle, mgr.phaseFlow.value)
  }

  @Test
  fun `late completion events cannot resurrect a stopped session`() {
    val mgr = ConversationManager()
    mgr.onRecordingStart()
    mgr.onProcessingStart()
    // User stops while a segment is mid-flight; its finally-block events arrive afterwards.
    mgr.onRecordingStop()
    mgr.onSegmentComplete()
    assertEquals(ConversationPhase.Idle, mgr.phase)
    mgr.onTailMuteComplete()
    assertEquals(ConversationPhase.Idle, mgr.phase)
    mgr.onPlaybackEnd()
    assertEquals(ConversationPhase.Idle, mgr.phase)
  }
}
