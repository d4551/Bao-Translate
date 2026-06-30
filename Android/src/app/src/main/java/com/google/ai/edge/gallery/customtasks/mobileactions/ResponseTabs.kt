/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyWarning
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.theme.Dimensions

@Composable
fun ColumnScope.ResponseTabs(
  task: Task,
  uiState: MobileActionsUiState,
  doneGeneratingResponse: Boolean,
) {
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  val taskColor = getTaskBgGradientColors(task = task)[1]

  Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
    Row(modifier = Modifier.fillMaxWidth()) {
      PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = Color.Transparent,
        indicator = {
          TabRowDefaults.PrimaryIndicator(
            modifier =
              Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
            color = taskColor,
            width = androidx.compose.ui.unit.Dp.Unspecified,
          )
        },
      ) {
        for ((index, tab) in TABS.withIndex()) {
          val enabled = index == 0 || (index == 1 && !uiState.noFunctionRecognized)
          Tab(
            selected = selectedTabIndex == index,
            enabled = enabled,
            onClick = { selectedTabIndex = index },
            modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.3f },
            text = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
              ) {
                val titleColor =
                  if (selectedTabIndex == index) taskColor
                  else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(
                  tab.icon,
                  contentDescription = null,
                  modifier = Modifier.size(Dimensions.Icon.small).alpha(0.7f),
                  tint = titleColor,
                )
                BasicText(
                  text = stringResource(tab.labelResId),
                  maxLines = 1,
                  color = { titleColor },
                  style =
                    MaterialTheme.typography.bodyMedium.copy(
                      fontWeight = FontWeight.Medium
                    ),
                  autoSize =
                    TextAutoSize.StepBased(
                      minFontSize = 9.sp,
                      maxFontSize = 14.sp,
                      stepSize = 1.sp,
                    ),
                )
              }
            },
          )
        }
      }
    }

    AnimatedContent(
      selectedTabIndex,
      transitionSpec = {
        if (targetState > initialState) {
          slideInHorizontally { 40 } + fadeIn() togetherWith
            slideOutHorizontally { -40 } + fadeOut(animationSpec = tween(50))
        } else {
          slideInHorizontally { -40 } + fadeIn() togetherWith
            slideOutHorizontally { 40 } + fadeOut(animationSpec = tween(50))
        }
      },
      modifier = Modifier.weight(1f),
    ) { selectedTabIndex ->
      if (selectedTabIndex == 0) {
        Column(modifier = Modifier.fillMaxWidth()) {
          val cdResponse = stringResource(R.string.cd_model_response_text)
          MarkdownText(
            text = uiState.modelResponse,
            modifier =
              Modifier.semantics(mergeDescendants = true) {
                  contentDescription = cdResponse
                  if (doneGeneratingResponse) {
                    liveRegion = LiveRegionMode.Polite
                  }
                }
                .padding(Dimensions.Spacing.medium),
          )
          if (uiState.noFunctionRecognized) {
            MessageBodyWarning(
              ChatMessageWarning(
                content = stringResource(R.string.warning_no_function_call)
              )
            )
          }
        }
      } else if (selectedTabIndex == 1) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
        ) {
          for ((index, details) in uiState.functionCallDetails.withIndex()) {
            MarkdownText(text = details, modifier = Modifier.padding(Dimensions.Spacing.medium))
            if (index != uiState.functionCallDetails.size - 1) {
              HorizontalDivider(modifier = Modifier.padding(horizontal = Dimensions.Spacing.medium))
            }
          }
        }
      }
    }
  }
}
