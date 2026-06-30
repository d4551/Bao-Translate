/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions

@Composable
internal fun PermissionDeniedUi(onRetry: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(Dimensions.Spacing.xl),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = Icons.Outlined.Mic,
      contentDescription = null,
      modifier = Modifier.size(64.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.size(Dimensions.Spacing.medium))
    Text(
      text = stringResource(R.string.mobile_actions_permission_denied_title),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.size(Dimensions.Spacing.small))
    Text(
      text = stringResource(R.string.mobile_actions_permission_denied_description),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.size(Dimensions.Spacing.large))
    Button(onClick = onRetry) {
      Text(stringResource(R.string.mobile_actions_retry_permission))
    }
  }
}
