package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.google.ai.edge.gallery.ui.theme.Dimensions

/**
 * Renders a live recording waveform driven by real microphone amplitude.
 *
 * [amplitudes] contains recent normalized RMS values (0f..1f) computed by
 * [com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController]; each bar maps to a real
 * captured audio sample rather than a synthetic animation.
 */
@Composable
fun WaveformRenderer(
  modifier: Modifier = Modifier,
  amplitudes: List<Float> = emptyList(),
  amplitudeProvider: () -> Float = { 0f },
  isActive: Boolean = true,
) {
  val barColor = MaterialTheme.colorScheme.primary

  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .clearAndSetSemantics {},
  ) {
    val barWidth = Dimensions.Waveform.barWidth.toPx()
    val spacing = Dimensions.Waveform.barSpacing.toPx()
    val barCount = (size.width / (barWidth + spacing)).toInt().coerceAtLeast(1)
    val centerY = size.height / 2
    val recentAmplitudes =
      when {
        amplitudes.size >= barCount -> amplitudes.takeLast(barCount)
        amplitudes.isNotEmpty() -> List(barCount - amplitudes.size) { 0f } + amplitudes
        else -> List(barCount) { amplitudeProvider() }
      }
    val peakAmplitude = recentAmplitudes.maxOrNull()?.coerceAtLeast(0f) ?: 0f
    val displayAmplitudes =
      if (peakAmplitude > 0f) {
        recentAmplitudes.map { it / peakAmplitude }
      } else {
        recentAmplitudes
      }

    for (i in 0 until barCount) {
      val x = i * (barWidth + spacing)
      val amplitude = displayAmplitudes[i].coerceIn(0f, 1f)
      val barHeight = (centerY * amplitude * if (isActive) 1f else 0.3f)
        .coerceAtLeast(Dimensions.Waveform.minBarHeight.toPx())

      drawLine(
        color = barColor,
        start = Offset(x, centerY - barHeight),
        end = Offset(x, centerY + barHeight),
        strokeWidth = barWidth,
      )
    }
  }
}
