package com.google.ai.edge.gallery.customtasks.baotranslate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Conversation turn state machine for hands-free bidirectional translation.
 * Tracks mic gating phases around TTS playback and acoustic tail mute.
 *
 * Public (not internal) because [BaoTranslateUiState.conversationPhase] mirrors it for the UI.
 */
enum class ConversationPhase {
  Idle,
  Listening,
  Processing,
  Speaking,
  Cooldown,
}

internal class ConversationManager {
  private val _phaseFlow = MutableStateFlow(ConversationPhase.Idle)
  val phaseFlow: StateFlow<ConversationPhase> = _phaseFlow.asStateFlow()

  val phase: ConversationPhase
    get() = _phaseFlow.value

  // Each event moves the machine only from its specific source phase. This is what keeps
  // late/cancelled pipeline callbacks (a segment's finally-block firing after the user stopped,
  // a duplicate playback-end from the playback finally) from resurrecting a closed session:
  // once onRecordingStop lands on Idle, no completion event can leave Idle.
  private fun transition(from: ConversationPhase, to: ConversationPhase) {
    if (phase == from) {
      _phaseFlow.value = to
    }
  }

  fun onRecordingStart() = transition(ConversationPhase.Idle, ConversationPhase.Listening)

  fun onProcessingStart() = transition(ConversationPhase.Listening, ConversationPhase.Processing)

  /** A processed segment produced no playback (no speech / blank decode); resume listening. */
  fun onSegmentComplete() = transition(ConversationPhase.Processing, ConversationPhase.Listening)

  fun onPlaybackStart() = transition(ConversationPhase.Processing, ConversationPhase.Speaking)

  fun onPlaybackEnd() = transition(ConversationPhase.Speaking, ConversationPhase.Cooldown)

  fun onTailMuteComplete() = transition(ConversationPhase.Cooldown, ConversationPhase.Listening)

  fun onRecordingStop() {
    // Stop is terminal from every phase.
    _phaseFlow.value = ConversationPhase.Idle
  }
}
