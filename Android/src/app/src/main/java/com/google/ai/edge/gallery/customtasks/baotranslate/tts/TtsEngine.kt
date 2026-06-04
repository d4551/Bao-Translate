package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig

interface TtsEngine {
  fun initialize(modelDir: String): Boolean
  fun synthesize(text: String, voiceId: String? = null, speed: Float = 1.0f): FloatArray?
  fun play(samples: FloatArray, sampleRate: Int = PipelineConfig.TTS_SAMPLE_RATE)
  fun cleanup()
}
