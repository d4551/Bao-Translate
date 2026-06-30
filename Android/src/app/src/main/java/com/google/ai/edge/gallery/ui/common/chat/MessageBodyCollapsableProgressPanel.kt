/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions

private const val MAX_DESCRIPTION_LINES = 5

/**
 * A Composable function that displays a rounded rectangle panel with a title and a collapsable
 * section.
 */
@Composable
fun MessageBodyCollapsableProgressPanel(message: ChatMessageCollapsableProgressPanel) {
  var isExpanded by remember { mutableStateOf(false) }
  var showLogsViewer by remember { mutableStateOf(false) }

  Column(
    modifier =
      Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable { isExpanded = !isExpanded }
        .fillMaxWidth()
  ) {
    // Header Row: Contains the title and the expand/collapse button
    Row(
      modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
      ) {
        // Spinner on the most left when loading
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(Dimensions.Icon.medium)) {
          if (message.inProgress) {
            CircularProgressIndicator(
              modifier = Modifier.size(Dimensions.Icon.small),
              strokeWidth = Dimensions.Component.strokeWidth,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Icon(message.doneIcon, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.medium))
          }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
          // Title.
          AnimatedContent(
            targetState = message.title,
            transitionSpec = {
              slideInVertically { it } + fadeIn() togetherWith
                slideOutVertically { -it } + fadeOut()
            },
          ) { curTitle ->
            Text(text = curTitle, style = MaterialTheme.typography.labelLarge)
          }
        }
      }

      // Expand/Collapse Button on the right side
      Icon(
        imageVector =
          if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = if (isExpanded) stringResource(R.string.cd_collapse_panel) else stringResource(R.string.cd_expand_panel),
      )
    }

    // Collapsable Content: Shown only when isExpanded is true
    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Column(
        modifier =
          Modifier.padding(horizontal = Dimensions.Spacing.medium)
            .padding(
              bottom =
                if (message.logMessages.isEmpty()) {
                  Dimensions.Spacing.md
                } else {
                  Dimensions.Spacing.small
                }
            ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
      ) {
        for (item in message.items) {
          Row(
            modifier =
              Modifier.clip(shape = MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(Dimensions.Spacing.md)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
          ) {
            // A colored dot.
            Box(
              modifier =
                Modifier.size(Dimensions.Spacing.md)
                  .clip(shape = CircleShape)
                  .background(MaterialTheme.colorScheme.secondaryContainer)
            )
            Column() {
              // Title.
              Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = Dimensions.Spacing.xxs),
              )

              // Description.
              if (item.description.isNotEmpty()) {
                val density = LocalDensity.current
                val maxHeight =
                  with(density) {
                    (MaterialTheme.typography.labelMedium.lineHeight * MAX_DESCRIPTION_LINES).toDp()
                  }
                Text(
                  item.description,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier =
                    Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                )
              }
            }
          }
        }

        if (message.logMessages.isNotEmpty()) {
          Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            AssistChip(
              onClick = { showLogsViewer = true },
              label = { Text(stringResource(R.string.view_console_logs)) },
              leadingIcon = {
                Icon(
                  Icons.AutoMirrored.Outlined.Article,
                  contentDescription = null,
                  Modifier.size(AssistChipDefaults.IconSize),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              },
            )
          }
        }
      }
    }
  }

  if (showLogsViewer) {
    LogsViewer(logs = message.logMessages, onDismissRequest = { showLogsViewer = false })
  }
}
