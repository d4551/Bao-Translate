/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
internal fun ModelInitErrorUi(
  context: Context,
  task: Task,
  model: Model,
  initStatus: ModelInitializationStatus,
  modelManagerViewModel: ModelManagerViewModel,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp)
      .semantics { liveRegion = LiveRegionMode.Assertive },
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.mobile_actions_model_init_error_title),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
      text = initStatus.error,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.size(24.dp))
    Button(
      onClick = {
        modelManagerViewModel.initializeModel(
          context = context,
          task = task,
          model = model,
          force = true,
        )
      },
    ) {
      Text(stringResource(R.string.mobile_actions_model_init_error_retry))
    }
  }
}
