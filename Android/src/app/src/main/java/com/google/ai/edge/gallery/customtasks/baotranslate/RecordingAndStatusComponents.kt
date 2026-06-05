package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WaveformRenderer
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.isReducedMotion

@Composable
internal fun RecordingPulseCircle(
  pulseScale: Float,
  pulseAlpha: Float,
  isTablet: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .size(if (isTablet) Dimensions.Component.pulseSizeTablet else Dimensions.Component.pulseSize)
      .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
      .clip(CircleShape)
      .background(MaterialTheme.customColors.recordButtonBgColor.copy(alpha = pulseAlpha)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Default.Mic,
      contentDescription = null,
      modifier = Modifier.size(if (isTablet) Dimensions.Component.pulseIconSizeTablet else Dimensions.Component.pulseIconSize),
      tint = MaterialTheme.colorScheme.onPrimary,
    )
  }
}

@Composable
internal fun RecordingInfoColumn(
  elapsedSeconds: Float,
  isTablet: Boolean,
  amplitudes: List<Float>,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
  ) {
    Text(
      text = stringResource(R.string.bao_translate_listening),
      style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.customColors.recordButtonBgColor,
    )

    if (elapsedSeconds > 0f) {
      Text(
        text = formatDuration(elapsedSeconds),
        style = if (isTablet) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    WaveformRenderer(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = if (isTablet) Dimensions.Spacing.xxl else Dimensions.Spacing.large)
        .height(if (isTablet) Dimensions.Component.waveformHeightTablet else Dimensions.Component.waveformHeight),
      amplitudeProvider = { amplitudes.lastOrNull() ?: 0f },
      isActive = true,
    )

    Text(
      text = stringResource(R.string.bao_translate_tap_to_stop),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun RecordingOverlay(
  amplitudes: List<Float>,
  elapsedSeconds: Float,
  isTablet: Boolean,
  modifier: Modifier = Modifier,
) {
  val reduceMotion = isReducedMotion
  val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
  val pulseAlpha by if (reduceMotion) {
    remember { mutableStateOf(1f) }
  } else {
    infiniteTransition.animateFloat(
      initialValue = 0.3f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
      label = "pulseAlpha",
    )
  }
  val pulseScale by if (reduceMotion) {
    remember { mutableStateOf(1f) }
  } else {
    infiniteTransition.animateFloat(
      initialValue = 0.85f,
      targetValue = 1.15f,
      animationSpec = infiniteRepeatable(animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
      label = "pulseScale",
    )
  }

  val listeningDescription = stringResource(R.string.bao_translate_listening)
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

  Box(
    modifier = modifier
      .background(MaterialTheme.colorScheme.background)
      .semantics { liveRegion = LiveRegionMode.Polite; stateDescription = listeningDescription },
    contentAlignment = Alignment.Center,
  ) {
    if (isLandscape) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.large),
      ) {
        RecordingPulseCircle(pulseScale = pulseScale, pulseAlpha = pulseAlpha, isTablet = isTablet)
        RecordingInfoColumn(elapsedSeconds = elapsedSeconds, isTablet = isTablet, amplitudes = amplitudes)
      }
    } else {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.large),
      ) {
        RecordingPulseCircle(pulseScale = pulseScale, pulseAlpha = pulseAlpha, isTablet = isTablet)
        RecordingInfoColumn(elapsedSeconds = elapsedSeconds, isTablet = isTablet, amplitudes = amplitudes)
      }
    }
  }
}

@Composable
internal fun StatusBar(isProcessing: Boolean, isSpeaking: Boolean, isTablet: Boolean = false) {
  val reduceMotion = isReducedMotion
  val statusBarDescription = when {
    isProcessing -> stringResource(R.string.bao_translate_translating)
    isSpeaking -> stringResource(R.string.bao_translate_speaking)
    else -> ""
  }
  AnimatedVisibility(
    visible = isProcessing || isSpeaking,
    enter = if (reduceMotion) fadeIn(tween(0)) + slideInVertically(tween(0)) { it } else fadeIn() + slideInVertically { it },
  ) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.small).semantics { liveRegion = LiveRegionMode.Polite; stateDescription = statusBarDescription },
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(if (isTablet) Dimensions.Spacing.large else Dimensions.Spacing.medium)) {
        AnimatedContent(
          targetState = when { isProcessing -> "processing"; isSpeaking -> "speaking"; else -> "idle" },
          transitionSpec = {
            if (reduceMotion) {
              fadeIn(tween(0)) + slideInVertically(tween(0)) { -it } togetherWith fadeOut(tween(0)) + slideOutVertically(tween(0)) { it }
            } else {
              fadeIn() + slideInVertically { -it } togetherWith fadeOut() + slideOutVertically { it }
            }
          },
          label = "statusTransition",
        ) { targetState ->
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            when (targetState) {
              "processing" -> {
                CircularProgressIndicator(modifier = Modifier.size(if (isTablet) Dimensions.Icon.medium else Dimensions.Icon.small), strokeWidth = Dimensions.Stroke.thin)
                Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
                Text(stringResource(R.string.bao_translate_translating), style = if (isTablet) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
              }
              "speaking" -> {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.cd_bao_translate_playback), modifier = Modifier.size(if (isTablet) Dimensions.Icon.medium else Dimensions.Icon.small), tint = MaterialTheme.customColors.recordButtonBgColor)
                Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
                Text(stringResource(R.string.bao_translate_speaking), style = if (isTablet) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
              }
            }
          }
        }
        if (isProcessing) {
          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

internal fun formatDuration(seconds: Float): String {
  val totalSeconds = seconds.toInt()
  val minutes = totalSeconds / 60
  val secs = totalSeconds % 60
  return if (minutes > 0) "$minutes:${secs.toString().padStart(2, '0')}" else "${secs}s"
}
