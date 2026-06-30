package com.google.ai.edge.gallery.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Centralized spacing and sizing tokens for consistent UI layout.
 *
 * Usage:
 *   Modifier.padding(Dimensions.Spacing.small)
 *   Modifier.size(Dimensions.Icon.medium)
 */
object Dimensions {
  object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val small = 8.dp
    val smd = 10.dp
    val md = 12.dp
    val medium = 16.dp
    val lg = 20.dp
    val large = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
  }

  object Icon {
    val small = 16.dp
    val medium = 24.dp
    val large = 32.dp
    val xl = 48.dp
  }

  object Component {
    val fabSize = 72.dp
    val progressBarHeight = 4.dp
    val cardElevation = 2.dp
    val maxContentWidth = 840.dp
    val maxContentWidthTablet = 900.dp
    val bubbleMaxWidth = 680.dp
    val bubbleMaxWidthTablet = 720.dp
    val chipHeight = 32.dp
    val chipMaxWidth = 200.dp
    val thumbnailWidth = 200.dp
    val chipCornerRadius = 20.dp
    val buttonHeight = 48.dp
    val borderWidth = 1.5.dp
    val rowCornerRadius = 12.dp
    val iconSmall = 18.dp
    val iconMedium = 20.dp
    val strokeWidth = 2.dp
    val pulseSize = 80.dp
    val pulseSizeTablet = 120.dp
    val pulseIconSize = 40.dp
    val pulseIconSizeTablet = 60.dp
    val waveformHeight = 60.dp
    val waveformHeightTablet = 80.dp
    val imagePreviewHeight = 80.dp
    val inputMinHeight = 76.dp
    val shutterButtonSize = 64.dp
    val shutterIconSize = 36.dp
    val rowHeight = 40.dp
    val cardCornerRadius = 24.dp
    val badgeHeight = 26.dp
  }

  object Home {
    val heroHorizontalPadding = 40.dp
    val heroVerticalPadding = 48.dp
    val entranceOffset = 16.dp
    val backgroundStarBlur = 35.dp
    val categoryTabHeight = 40.dp
    val featuredHeaderTopPadding = 22.dp
    val taskCardIconWidth = 40.dp
  }

  object Waveform {
    val barWidth = 3.dp
    val barSpacing = 2.dp
    val height = 48.dp
    val minBarHeight = 2.dp
  }

  object Breakpoint {
    val tablet = 600.dp
  }

  object Stroke {
    val hairline = 1.dp
    val thin = 2.dp
    val medium = 3.dp
  }

  object Indicator {
    val small = 8.dp
    val medium = 10.dp
  }
}
