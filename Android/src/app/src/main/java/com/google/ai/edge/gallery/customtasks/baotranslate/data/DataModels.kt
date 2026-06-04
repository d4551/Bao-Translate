package com.google.ai.edge.gallery.customtasks.baotranslate.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TranslationMessage(
  val id: String = UUID.randomUUID().toString(),
  val originalText: String,
  val translatedText: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val timestamp: Long = System.currentTimeMillis(),
  val isUser: Boolean,
  val speakerName: String = "",
  val audioPlayed: Boolean? = null,
  val translationError: String? = null,
)

@Serializable
data class ConversationSession(
  val id: String = UUID.randomUUID().toString(),
  val messages: List<TranslationMessage> = emptyList(),
  val startTime: Long = System.currentTimeMillis(),
)

@Serializable
data class VoiceProfile(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val wavPath: String,
  val enrolledAt: Long = System.currentTimeMillis(),
  val durationSec: Float = 0f,
)

@Serializable
data class Participant(
  val id: String,
  val name: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val isConnected: Boolean = false,
  val hasVoiceProfile: Boolean = false,
  val audioDeviceName: String? = null,
)
