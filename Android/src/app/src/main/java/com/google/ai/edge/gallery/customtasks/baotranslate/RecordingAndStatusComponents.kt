package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WaveformRenderer
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.isReducedMotion
import com.google.ai.edge.gallery.ui.theme.rememberPulseFloat

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
  liveTranslationPreview: String?,
  liveSourcePreview: String?,
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

    Text(
      text = formatDuration(elapsedSeconds.coerceAtLeast(0f)),
      style = if (isTablet) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )

    WaveformRenderer(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = if (isTablet) Dimensions.Spacing.xxl else Dimensions.Spacing.large)
        .height(if (isTablet) Dimensions.Component.waveformHeightTablet else Dimensions.Component.waveformHeight),
      amplitudes = amplitudes,
      isActive = true,
    )

    // Recognized source caption — shown the instant STT completes (before the slower translation),
    // then replaced by the translated preview below. Lighter/italic so it reads as interim.
    if (!liveSourcePreview.isNullOrBlank() && liveTranslationPreview.isNullOrBlank()) {
      Text(
        text = liveSourcePreview,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = if (isTablet) Dimensions.Spacing.xxl else Dimensions.Spacing.large),
        style = MaterialTheme.typography.bodyLarge,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!liveTranslationPreview.isNullOrBlank()) {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = if (isTablet) Dimensions.Spacing.xxl else Dimensions.Spacing.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
      ) {
        Text(
          text = liveTranslationPreview,
          modifier = Modifier.padding(Dimensions.Spacing.medium),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Text(
      text = stringResource(R.string.bao_translate_tap_to_stop),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingOverlay(
  amplitudes: List<Float>,
  elapsedSeconds: Float,
  liveTranslationPreview: String?,
  liveSourcePreview: String?,
  isTablet: Boolean,
  modifier: Modifier = Modifier,
  onCancel: (() -> Unit)? = null,
) {
  val pulseAlpha by rememberPulseFloat(
    initialValue = 0.3f, targetValue = 1f, durationMillis = 1000, restValue = 1f, label = "recording_pulse_alpha")
  val pulseScale by rememberPulseFloat(
    initialValue = 0.85f, targetValue = 1.15f, durationMillis = 800, restValue = 1f, label = "recording_pulse_scale")

  val listeningDescription = stringResource(R.string.bao_translate_listening)
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

  // Handle back gesture / tap-outside to dismiss overlay when cancel is available.
  BackHandler(enabled = onCancel != null) { onCancel?.invoke() }

  Box(
    modifier = modifier
      .background(MaterialTheme.colorScheme.background)
      .pointerInput(onCancel) {
        if (onCancel != null) {
          detectTapGestures(onTap = { onCancel() })
        }
      }
      .semantics { liveRegion = LiveRegionMode.Polite; stateDescription = listeningDescription },
  ) {
    // Cancel button in top-right corner
    if (onCancel != null) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = Dimensions.Spacing.medium, end = Dimensions.Spacing.medium)
          .align(Alignment.TopEnd),
      ) {
        TooltipBox(
          positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
          tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_dismiss)) } },
          state = rememberTooltipState(),
        ) {
          IconButton(
            onClick = onCancel,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.bao_translate_dismiss),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      if (isLandscape) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.large),
        ) {
          RecordingPulseCircle(pulseScale = pulseScale, pulseAlpha = pulseAlpha, isTablet = isTablet)
          RecordingInfoColumn(
            elapsedSeconds = elapsedSeconds,
            isTablet = isTablet,
            amplitudes = amplitudes,
            liveTranslationPreview = liveTranslationPreview,
            liveSourcePreview = liveSourcePreview,
          )
        }
      } else {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.large),
        ) {
          RecordingPulseCircle(pulseScale = pulseScale, pulseAlpha = pulseAlpha, isTablet = isTablet)
          RecordingInfoColumn(
            elapsedSeconds = elapsedSeconds,
            isTablet = isTablet,
            amplitudes = amplitudes,
            liveTranslationPreview = liveTranslationPreview,
            liveSourcePreview = liveSourcePreview,
          )
        }
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
