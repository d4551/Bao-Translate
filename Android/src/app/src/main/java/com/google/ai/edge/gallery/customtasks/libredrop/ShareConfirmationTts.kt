package com.google.ai.edge.gallery.customtasks.libredrop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TtsEvent {
  FILE_SENT,
  TRANSFER_COMPLETE,
  PEER_DISCOVERED,
  TRANSFER_FAILED,
  READY,
}

data class TtsState(
  val isSpeaking: Boolean = false,
  val lastEvent: TtsEvent? = null,
  val lastMessage: String = "",
)

class ShareConfirmationTts {

  private val _state = MutableStateFlow(TtsState())
  val state: StateFlow<TtsState> = _state.asStateFlow()

  fun speakFileSent(peerName: String) {
    val message = "File sent to $peerName"
    _state.value = TtsState(isSpeaking = true, lastEvent = TtsEvent.FILE_SENT, lastMessage = message)
  }

  fun speakTransferComplete() {
    val message = "Transfer complete"
    _state.value = TtsState(isSpeaking = true, lastEvent = TtsEvent.TRANSFER_COMPLETE, lastMessage = message)
  }

  fun speakPeerDiscovered(peerName: String) {
    val message = "Peer discovered: $peerName"
    _state.value = TtsState(isSpeaking = true, lastEvent = TtsEvent.PEER_DISCOVERED, lastMessage = message)
  }

  fun speakTransferFailed(reason: String) {
    val message = "Transfer failed: $reason"
    _state.value = TtsState(isSpeaking = true, lastEvent = TtsEvent.TRANSFER_FAILED, lastMessage = message)
  }

  fun onSpeakingComplete() {
    _state.value = _state.value.copy(isSpeaking = false)
  }
}
