/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ToolProvider

@Composable
fun ErrorResetDialog(
  task: Task,
  errorContent: String,
  context: Context,
  viewModel: MobileActionsViewModel,
  model: com.google.ai.edge.gallery.data.Model,
  tools: List<ToolProvider>,
  modelManagerViewModel: ModelManagerViewModel,
  onDismiss: () -> Unit,
  onError: (String) -> Unit,
) {
  val taskColor = getTaskBgGradientColors(task = task)[1]

  AlertDialog(
    title = { Text(stringResource(R.string.error)) },
    text = { Text(errorContent, style = MaterialTheme.typography.bodyMedium) },
    onDismissRequest = onDismiss,
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onDismiss()
          viewModel.resetEngine(
            context = context,
            model = model,
            tools = tools,
            modelManagerViewModel = modelManagerViewModel,
            onError = onError,
          )
        },
        colors = ButtonDefaults.buttonColors(containerColor = taskColor),
      ) {
        Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.onPrimary)
      }
    },
  )
}
