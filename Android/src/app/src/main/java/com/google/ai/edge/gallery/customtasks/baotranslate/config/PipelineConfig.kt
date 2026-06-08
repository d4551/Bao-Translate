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
  // SSOT: the default BLE-peer target language. References SupportedLanguages so a rename there
  // (or a removal of "Spanish" from the selectable set) surfaces here as a build error, not a
  // silent "no language selected" UI. Also pins the first-run user-facing default.
  const val DEFAULT_TARGET_LANGUAGE: String = com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages.DEFAULT_TARGET_KEY
  const val DEFAULT_TARGET_CODE: String = com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages.DEFAULT_TARGET_CODE

  // Thread counts - should ideally be derived from Runtime.getRuntime().availableProcessors()
  const val STT_THREADS = 2
  const val TTS_THREADS = 2
  const val TRANSLATION_THREADS = 4
  const val VAD_THREADS = 1

  // Audio sample rates (Hz). STT capture (Whisper / VAD / recording / enrollment) must all match;
  // TTS playback runs at its own rate. Single source of truth — do not hardcode these elsewhere.
  const val STT_SAMPLE_RATE = 16000
  const val TTS_SAMPLE_RATE = 24000

  // Translation engine. maxNumTokens is the litertlm kv-cache size (sum of input+output tokens).
  // gemma-4-E2B-it is published/benchmarked at a 2048-token context; a 512 cache made its compiled
  // graph fail to invoke ("Failed to invoke the compiled model") while qwen tolerated it. 2048 is
  // the model's documented context and is comfortably small for a ~2B model's kv-cache; the actual
  // translation workload (short prompt + short output) is far below it, so latency is unaffected.
  const val MAX_TOKENS = 2048
  const val MAX_INPUT_LENGTH = 2000

  // Translation sampler (temperature/topK/topP control generation quality)
  const val SAMPLER_TEMPERATURE = 0.3
  const val SAMPLER_TOP_K = 40
  const val SAMPLER_TOP_P = 0.9

  // Whisper STT
  const val WHISPER_TAIL_PADDINGS = 1000

}
