package com.google.ai.edge.gallery.customtasks.baotranslate.config

/**
 * Centralized configuration for all ML pipelines.
 *
 * These values are tuned for typical Android devices. Adjust based on:
 * - Device capabilities (CPU cores, RAM)
 * - Model requirements
 * - Latency vs quality tradeoffs
 */
object PipelineConfig {
  const val DEFAULT_TARGET_LANGUAGE = "Spanish"

  // Thread counts - should ideally be derived from Runtime.getRuntime().availableProcessors()
  const val STT_THREADS = 2
  const val TTS_THREADS = 2
  const val TRANSLATION_THREADS = 4
  const val VAD_THREADS = 1

  // Audio sample rates (Hz). STT capture (Whisper / VAD / recording / enrollment) must all match;
  // TTS playback runs at its own rate. Single source of truth — do not hardcode these elsewhere.
  const val STT_SAMPLE_RATE = 16000
  const val TTS_SAMPLE_RATE = 24000

  // Translation engine
  const val MAX_TOKENS = 512
  const val MAX_INPUT_LENGTH = 2000

  // Translation sampler (temperature/topK/topP control generation quality)
  const val SAMPLER_TEMPERATURE = 0.3
  const val SAMPLER_TOP_K = 40
  const val SAMPLER_TOP_P = 0.9

  // Whisper STT
  const val WHISPER_TAIL_PADDINGS = 1000

  // Voice cloning (PocketTTS)
  const val VOICE_EMBEDDING_CACHE_CAPACITY = 10
  const val VOICE_CLONE_NUM_STEPS = 2
  const val VOICE_CLONE_SILENCE_SCALE = 1.0f
  const val VOICE_CLONE_SID = 0

}
