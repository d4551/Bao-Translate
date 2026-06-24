/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

/**
 * Wires [CaptureCoordinator] to the system camera via `ACTION_IMAGE_CAPTURE`.
 *
 * Observes [CaptureCoordinator.captureRequest] and [CaptureCoordinator.outputUri];
 * when both are non-null, launches the stock camera app. A `queryIntentActivities`
 * pre-check prevents crashes on devices without a camera app.
 */
@Composable
fun CaptureLauncherEffect(coordinator: CaptureCoordinator) {
  val context = LocalContext.current
  val pendingRequest by coordinator.captureRequest.collectAsState()
  val outputUri by coordinator.outputUri.collectAsState()

  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
      coordinator.onResult(success = success)
    }

  LaunchedEffect(pendingRequest, outputUri) {
    val request = pendingRequest
    val uri = outputUri
    if (request != null && uri != null) {
      val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
      val hasCamera = context.packageManager.queryIntentActivities(captureIntent, 0).isNotEmpty()
      if (hasCamera) {
        launcher.launch(uri)
      } else {
        coordinator.onResult(success = false)
      }
    }
  }
}
