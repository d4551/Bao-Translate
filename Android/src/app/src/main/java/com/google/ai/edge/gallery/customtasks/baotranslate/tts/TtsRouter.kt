package com.google.ai.edge.gallery.customtasks.baotranslate.tts

/**
 * Central on-device TTS routing: Kokoro (phonemizable langs) → Supertonic (optional supplemental)
 * → Platform TTS (device engine fallback chain).
 */
internal class TtsRouter(
  private val kokoro: KokoroTtsPipeline?,
  private val supertonic: SupertonicTtsPipeline?,
  private val platform: PlatformTtsPipeline?,
) {
  fun synthesize(
    text: String,
    language: String,
    kokoroVoiceId: String,
    speed: Float = 1.0f,
  ): SynthesizedAudio? {
    if (text.isBlank()) return null
    val code = language.lowercase().substringBefore('-')

    return when {
      KokoroTtsPipeline.supportsLanguage(code) ->
        kokoro?.synthesizeAudio(text, kokoroVoiceId, speed = speed)
      supertonic != null && SupertonicTtsPipeline.supportsLanguage(code) ->
        supertonic.synthesizeAudio(text, voiceId = code, speed = speed)
      else ->
        platform?.synthesizeAudio(text, code, speed = speed)
    }
  }
}