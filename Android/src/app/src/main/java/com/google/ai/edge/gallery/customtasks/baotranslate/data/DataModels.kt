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
  val prosody: VoiceProsody? = null,
)

/**
 * Prosody metadata extracted from a voice enrollment clip. Used to adjust TTS synthesis parameters
 * so the cloned voice preserves the user's natural speaking characteristics:
 * - [speakingRate]: syllables per second (approximated from energy envelope zero-crossings)
 * - [averagePitchHz]: average fundamental frequency (F0) in Hz
 * - [speedMultiplier]: derived Kokoro speed parameter (1.0 = default, <1 = slower, >1 = faster)
 */
@Serializable
data class VoiceProsody(
  val speakingRate: Float = 0f,
  val averagePitchHz: Float = 0f,
  val speedMultiplier: Float = 1.0f,
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
