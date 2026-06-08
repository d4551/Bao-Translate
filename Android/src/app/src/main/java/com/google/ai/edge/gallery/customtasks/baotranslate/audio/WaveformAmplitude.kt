package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import kotlin.math.sqrt

/**
 * Single source of truth for the recording waveform's amplitude pipeline.
 *
 * Both the live translation capture
 * ([com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController]) and voice enrollment
 * ([com.google.ai.edge.gallery.customtasks.baotranslate.VoiceEnrollmentSheet]) compute bars through
 * this so they render the identical [WaveformRenderer] from identically-computed amplitudes — no
 * duplicated/divergent formula.
 */

/** Perceptual gain applied after the sqrt loudness curve so normal speech fills the bars. */
const val WAVEFORM_VISUAL_GAIN = 2.5f

/** Number of recent amplitude samples retained for the scrolling waveform history. */
const val WAVEFORM_HISTORY = 50

/**
 * Normalized 0f..1f waveform bar height from the first [count] PCM16 samples of [buffer]: RMS, then
 * a sqrt perceptual curve, then [WAVEFORM_VISUAL_GAIN], clamped to 0..1.
 */
fun waveformAmplitude(buffer: ShortArray, count: Int): Float {
  if (count <= 0) return 0f
  var sumSquares = 0L
  for (i in 0 until count) {
    val sample = buffer[i].toLong()
    sumSquares += sample * sample
  }
  val rms = sqrt(sumSquares.toDouble() / count) / 32768.0
  return (sqrt(rms).toFloat() * WAVEFORM_VISUAL_GAIN).coerceIn(0f, 1f)
}
