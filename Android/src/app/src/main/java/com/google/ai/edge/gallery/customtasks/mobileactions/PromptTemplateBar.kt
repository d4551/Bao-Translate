/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun PromptTemplateBar(processing: Boolean, onSend: (String) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).graphicsLayer {
        alpha = if (processing) 0.5f else 1f
      },
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Spacer(modifier = Modifier.width(12.dp))
    for (item in PROMPT_TEMPLATES) {
      Text(
        stringResource(item.labelResId),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        modifier =
          Modifier.clip(RoundedCornerShape(12.dp))
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = !processing) { onSend(item.prompt) }
            .background(color = MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
              width = 1.dp,
              color = MaterialTheme.colorScheme.outlineVariant,
              shape = RoundedCornerShape(12.dp),
            )
            .padding(all = 12.dp),
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
  }
}
