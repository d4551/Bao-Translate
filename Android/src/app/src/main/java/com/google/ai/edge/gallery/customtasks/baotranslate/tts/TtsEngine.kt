package com.google.ai.edge.gallery.customtasks.baotranslate.tts

import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig

data class SynthesizedAudio(
  val samples: FloatArray,
  val sampleRate: Int,
)

interface TtsEngine {
  fun initialize(modelDir: String): Boolean
  fun synthesize(text: String, voiceId: String? = null, speed: Float = 1.0f): FloatArray?
  fun synthesizeAudio(text: String, voiceId: String? = null, speed: Float = 1.0f): SynthesizedAudio? =
    synthesize(text, voiceId, speed)?.let { samples ->
      SynthesizedAudio(samples = samples, sampleRate = PipelineConfig.TTS_SAMPLE_RATE)
    }
  fun play(samples: FloatArray, sampleRate: Int = PipelineConfig.TTS_SAMPLE_RATE)
  fun cleanup()
}
