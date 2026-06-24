/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import com.google.ai.edge.gallery.common.BaoLog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.focus.FocusManager
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMASender"

fun sendPrompt(
  text: String,
  task: Task,
  model: Model,
  tools: List<ToolProvider>,
  viewModel: MobileActionsViewModel,
  curActions: SnapshotStateList<Action>,
  scope: CoroutineScope,
  snackbarHostState: SnackbarHostState,
  focusManager: FocusManager,
  context: Context,
  resources: Resources,
  noFunctionCallSnackbarMessage: String,
  onSelectedTabReset: () -> Unit,
  onClearInputTrigger: () -> Unit,
  onProcessingStarted: () -> Unit,
  onDoneGenerating: () -> Unit,
  onError: (String) -> Unit,
) {
  scope.launch(Dispatchers.Main) {
    onSelectedTabReset()
    onClearInputTrigger()
    focusManager.clearFocus()
  }

  onProcessingStarted()

  viewModel.processUserPrompt(
    model = model,
    userPrompt = text,
    tools = tools,
    onProcessDone = {
      onDoneGenerating()
      BaoLog.d(TAG, "Actions count: ${curActions.size}")

      if (curActions.isNotEmpty()) {
        val errors = mutableListOf<String>()
        for (action in curActions) {
          val curError = viewModel.performAction(action = action, context = context)
          if (curError.isEmpty()) {
            viewModel.addFunctionCallDetails(
              details = genFormattedFunctionCall(action = action, resources = resources)
            )
          } else {
            errors.add(curError)
          }
        }
        if (errors.isNotEmpty()) {
          scope.launch {
            snackbarHostState.showSnackbar(
              errors.joinToString(separator = "; "),
              withDismissAction = true,
              duration = SnackbarDuration.Long,
            )
          }
        }
      } else {
        viewModel.setNoFunctionRecognized(value = true)
        scope.launch {
          snackbarHostState.showSnackbar(
            noFunctionCallSnackbarMessage,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
          )
        }
      }
    },
    onError = onError,
  )

  firebaseAnalytics?.logEvent(
    GalleryEvent.GENERATE_ACTION.id,
    Bundle().apply {
      putString("capability_name", task.id)
      putString("model_id", model.name)
    },
  )
}

fun genFormattedFunctionCall(action: Action, resources: Resources): String {
  val strFunctionName = action.functionCallDetails.functionName
  val functionNameLabel = resources.getString(R.string.function_name)
  var content = "**$functionNameLabel**:\n- $strFunctionName"
  if (action.functionCallDetails.parameters.isNotEmpty()) {
    val parametersLabel =
      resources.getQuantityString(R.plurals.parameter, action.functionCallDetails.parameters.size)
    val strParameters =
      action.functionCallDetails.parameters.joinToString("\n") { "- ${it.first}: \"${it.second}\"" }
    content += "\n\n**$parametersLabel**:\n$strParameters"
  }
  return content
}
