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
 * [amplitudeProvider] supplies the latest normalized RMS amplitude (0f..1f) computed by
 * [com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController]; the bars reflect actual
 * captured audio rather than a synthetic animation.
 */
@Composable
fun WaveformRenderer(
  modifier: Modifier = Modifier,
  amplitudeProvider: () -> Float = { 0.5f },
  isActive: Boolean = true,
) {
  val amplitude = amplitudeProvider()
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

    for (i in 0 until barCount) {
      val x = i * (barWidth + spacing)
      val progress = i.toFloat() / barCount
      val waveFactor = kotlin.math.sin(progress * Math.PI.toFloat() * 4) * 0.5f + 0.5f
      val barHeight = (centerY * amplitude * waveFactor * if (isActive) 1f else 0.3f)
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
