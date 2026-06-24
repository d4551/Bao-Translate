/*
 * Copyright 2025 Google LLC
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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.common.BaoLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CaptureCoordinator"

/**
 * Subdirectory under [Context.cacheDir] where stock-camera captures are written by way of the
 * FileProvider. The provider element in AndroidManifest must declare this path. Files here are
 * reclaimed by the platform under storage pressure and cleared on [reset].
 */
internal const val CAPTURE_SUBDIR: String = "mobile_actions_captures"

/**
 * A capture produced by [CapturePhotoAction] and awaiting consumption (preview or discard).
 * [uri] is a content:// Uri backed by a FileProvider-exposed file in the app's cache; it
 * carries a read grant to this process only, so downstream consumers must copy the bytes
 * before sharing.
 */
data class PendingCapture(
  val uri: Uri,
  val displayName: String,
  val resolutionHint: String,
  val mode: String,
  val capturedAtMs: Long,
)

/**
 * Owns the stock-camera capture lifecycle for the Mobile Actions task.
 *
 * The coordinator bridges the synchronous [MobileActionsViewModel.performAction] contract
 * (returns an error string) with the async [androidx.activity.result.ActivityResultLauncher]
 * that the Screen owns. The flow is:
 *
 * 1. `performAction` sees a [CapturePhotoAction] → calls [arm].
 * 2. [arm] provisions a FileProvider Uri, stores it + the action, returns "".
 * 3. The Screen observes [captureRequest] + [outputUri]; when both are non-null it fires
 *    `TakePicture(uri)`.
 * 4. The camera returns → Screen calls [onResult].
 * 5. On success the coordinator appends a [PendingCapture] to [captures].
 *
 * Single armed request at a time: a second [CapturePhotoAction] arriving while
 * the first is pending replaces the request. Completed captures accumulate in [captures]
 * until consumed or cleared by [reset].
 *
 * @param uriProvider Injectable seam for URI provisioning. Defaults to FileProvider; tests
 *   inject a fake to avoid Android framework dependency.
 */
class CaptureCoordinator(
  private val appContext: Context,
  private val uriProvider: (Context, String, java.io.File) -> Uri = { ctx, authority, file ->
    FileProvider.getUriForFile(ctx, authority, file)
  },
) {

  private val _captureRequest = MutableStateFlow<CapturePhotoAction?>(null)
  val captureRequest = _captureRequest.asStateFlow()

  private val _outputUri = MutableStateFlow<Uri?>(null)
  val outputUri = _outputUri.asStateFlow()

  private val _captures = MutableStateFlow<List<PendingCapture>>(emptyList())
  val captures = _captures.asStateFlow()

  /**
   * Provision a FileProvider Uri for [action] and arm the capture request. Returns "" on
   * success. FileProvider misconfiguration throws IllegalArgumentException — a programming
   * error, not a runtime boundary, so it propagates rather than being swallowed.
   */
  fun arm(action: CapturePhotoAction): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val displaySuffix = sanitizeFileToken(action.resolutionHint)
    val fileName = "capture-$timestamp$displaySuffix.jpg"
    val dir = java.io.File(appContext.cacheDir, CAPTURE_SUBDIR)
    if (!dir.exists()) dir.mkdirs()
    val file = java.io.File(dir, fileName)
    val uri = uriProvider(appContext, "${appContext.packageName}.provider", file)
    _outputUri.value = uri
    _captureRequest.value = action
    return ""
  }

  /**
   * Called by the Screen when ACTION_IMAGE_CAPTURE returns. On success, appends a
   * [PendingCapture]. Clears the armed state either way so the next capture can arm.
   */
  fun onResult(success: Boolean) {
    val action = _captureRequest.value
    val uri = _outputUri.value
    _captureRequest.value = null
    _outputUri.value = null
    if (!success || action == null || uri == null) {
      if (action != null && !success) {
        BaoLog.w(TAG, "Stock camera capture cancelled by user")
      }
      return
    }
    val displayName = uri.lastPathSegment ?: "capture-${System.currentTimeMillis()}.jpg"
    val capture =
      PendingCapture(
        uri = uri,
        displayName = displayName,
        resolutionHint = action.resolutionHint,
        mode = action.mode,
        capturedAtMs = System.currentTimeMillis(),
      )
    _captures.value = _captures.value + capture
  }

  /** Remove a single pending capture (e.g. after LibreDrop consumes it). */
  fun consume(uri: Uri) {
    _captures.value = _captures.value.filterNot { it.uri == uri }
  }

  /** Clear all state — armed request, output Uri, and completed captures. */
  fun reset() {
    _captureRequest.value = null
    _outputUri.value = null
    _captures.value = emptyList()
  }

  private fun sanitizeFileToken(token: String): String {
    val cleaned = token.lowercase(Locale.US).filter { it.isLetterOrDigit() }
    return if (cleaned.isEmpty()) "" else "-$cleaned"
  }
}
