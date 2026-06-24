package com.google.ai.edge.gallery.customtasks.libredrop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceCommand {
  SEND_FILE,
  CANCEL_TRANSFER,
  LIST_PEERS,
  UNKNOWN,
}

data class VoiceShareState(
  val isListening: Boolean = false,
  val transcribedText: String = "",
  val detectedCommand: VoiceCommand = VoiceCommand.UNKNOWN,
  val targetPeer: String? = null,
)

class VoiceShareController {

  private val _state = MutableStateFlow(VoiceShareState())
  val state: StateFlow<VoiceShareState> = _state.asStateFlow()

  fun startListening() {
    _state.value = _state.value.copy(isListening = true, transcribedText = "")
  }

  fun stopListening() {
    _state.value = _state.value.copy(isListening = false)
  }

  fun onTranscription(text: String) {
    val command = parseCommand(text)
    val peer = extractPeerName(text)
    _state.value = VoiceShareState(
      isListening = _state.value.isListening,
      transcribedText = text,
      detectedCommand = command,
      targetPeer = peer,
    )
  }

  private fun parseCommand(text: String): VoiceCommand {
    val lower = text.lowercase()
    return when {
      lower.contains("send") || lower.contains("share") -> VoiceCommand.SEND_FILE
      lower.contains("cancel") || lower.contains("stop") -> VoiceCommand.CANCEL_TRANSFER
      lower.contains("list") || lower.contains("show") || lower.contains("who") -> VoiceCommand.LIST_PEERS
      else -> VoiceCommand.UNKNOWN
    }
  }

  private fun extractPeerName(text: String): String? {
    val sendPatterns = listOf(
      Regex("send (?:this |the file )?to (\\w+(?: \\w+)?)", RegexOption.IGNORE_CASE),
      Regex("share (?:this |the file )?with (\\w+(?: \\w+)?)", RegexOption.IGNORE_CASE),
    )
    for (pattern in sendPatterns) {
      val match = pattern.find(text)
      if (match != null) {
        return match.groupValues[1]
      }
    }
    return null
  }
}
