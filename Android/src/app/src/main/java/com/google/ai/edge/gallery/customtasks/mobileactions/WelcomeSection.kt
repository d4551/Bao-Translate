/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import com.google.ai.edge.gallery.ui.theme.Dimensions

@Composable
fun ColumnScope.WelcomeSection(task: Task) {
  Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        stringResource(com.google.ai.edge.gallery.R.string.mobile_actions_title),
        style = MaterialTheme.typography.headlineLarge,
        color = getTaskIconColor(task = task),
      )
      Text(
        stringResource(com.google.ai.edge.gallery.R.string.mobile_actions_description),
        style = MaterialTheme.typography.bodyMedium,
        color = getTaskIconColor(task = task),
      )
      Column {
        Text(
          stringResource(com.google.ai.edge.gallery.R.string.mobile_actions_supported_actions),
          style = MaterialTheme.typography.labelLarge,
          modifier =
            Modifier.padding(top = 64.dp, bottom = Dimensions.Spacing.small).graphicsLayer { alpha = 0.7f },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (item in SAMPLE_ACTION_ITEMS) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              item.icon,
              contentDescription = null,
              modifier = Modifier.size(Dimensions.Icon.medium).padding(end = Dimensions.Spacing.small),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              stringResource(item.labelResId),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
