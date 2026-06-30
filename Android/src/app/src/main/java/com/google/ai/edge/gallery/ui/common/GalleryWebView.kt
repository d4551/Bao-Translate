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

package com.google.ai.edge.gallery.ui.common

import android.Manifest
import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.text.htmlEncode
import androidx.webkit.WebViewAssetLoader
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import java.io.File
import org.json.JSONObject

private const val TAG = "AGGalleryWebView"
private val trustedLocalOrigin = LOCAL_URL_BASE.toUri().webViewOrigin() ?: LOCAL_URL_BASE
private val iframeWrapper =
  """
  <html>
    <body style="margin:0;padding:0;">
      <iframe
          width="100%"
          height="100%"
          src="___"
          title="%%TITLE%%"
          frameborder="0"
          style="border:0;">
      </iframe>
    </body>
  </html>
  """
    .trimIndent()

/** JavaScript execution policy for [GalleryWebView]. */
enum class GalleryWebViewJavaScriptMode {
  Disabled,
  TrustedLocalContent,
}

/** Returns true when [url] is served from the app-owned WebView asset origin. */
fun isTrustedLocalWebViewUrl(url: String): Boolean {
  return url.toUri().webViewOrigin() == trustedLocalOrigin
}

/** Stable origin string used for WebView main-frame navigation allowlists. */
private fun Uri.webViewOrigin(): String? {
  val scheme = scheme?.lowercase() ?: return null
  val host = host?.lowercase() ?: return null
  if (scheme != "http" && scheme != "https") return null
  val portSuffix = if (port >= 0) ":$port" else ""
  return "$scheme://$host$portSuffix"
}

/** Main-frame origins allowed by default for one WebView instance. */
private fun allowedOriginsFor(initialUrl: String?): Set<String> {
  val initialOrigin = initialUrl?.let { it.toUri().webViewOrigin() }
  return listOfNotNull(trustedLocalOrigin, initialOrigin).toSet()
}

/** Builds the generated iframe host document without allowing attribute injection. */
private fun iframeHtml(url: String, title: String): String {
  return iframeWrapper
    .replace("___", url.htmlEncode())
    .replace("%%TITLE%%", title.htmlEncode())
}

/**
 * A base [WebViewClient] for [GalleryWebView] that handles local asset loading and logs page
 * finishing.
 */
open class BaseGalleryWebViewClient(
  private val context: Context,
  private val allowedMainFrameOrigins: Set<String> = setOf(trustedLocalOrigin),
) : WebViewClient() {
  private val localFileAssetsLoader =
    WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
      .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir))
      .build()

  override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
    val requestUrl = request?.url ?: return true
    if (!request.isForMainFrame) return false
    val origin = requestUrl.webViewOrigin()
    val allowed = origin != null && origin in allowedMainFrameOrigins
    if (!allowed) {
      BaoLog.w(TAG, "Blocked WebView navigation to origin=${origin ?: requestUrl.scheme}")
    }
    return !allowed
  }

  override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?,
  ): WebResourceResponse? {
    if (request?.url != null && request.url.toString().startsWith(LOCAL_URL_BASE)) {
      // Returns 404 if file not exist for imported skills.
      if (!request.url.toString().startsWith("$LOCAL_URL_BASE/assets/")) {
        val path = request.url.path ?: ""
        val localFile = File(context.filesDir, path)
        if (!localFile.exists() || localFile.isDirectory) {
          return WebResourceResponse("text/plain", "UTF-8", null)
        }
      }
      return correctAssetMimeType(
        localFileAssetsLoader.shouldInterceptRequest(request.url),
        request.url.path ?: "",
      )
    }
    return super.shouldInterceptRequest(view, request)
  }

  /**
   * [WebViewAssetLoader] derives a response MIME type from the file extension via
   * `URLConnection.guessContentTypeFromName`, which returns `text/plain` for `.mjs` (and `.js` on
   * some Android builds). Strict-MIME WebViews refuse to execute such a response as an ES module
   * (`<script type="module">`) and refuse to stream-compile WASM. Correcting the MIME here — the
   * single point every local asset flows through — lets skills use standard ES module imports and
   * `WebAssembly.instantiateStreaming` deterministically across devices.
   */
  private fun correctAssetMimeType(
    response: WebResourceResponse?,
    path: String,
  ): WebResourceResponse? {
    if (response == null) return null
    val correctedMime =
      when {
        path.endsWith(".mjs") || path.endsWith(".js") -> "text/javascript"
        path.endsWith(".wasm") -> "application/wasm"
        else -> return response
      }
    response.mimeType = correctedMime
    if (correctedMime == "text/javascript" && response.encoding.isNullOrEmpty()) {
      response.encoding = "utf-8"
    }
    return response
  }
}

/**
 * A reusable Composable that wraps an Android WebView, providing common configurations and handling
 * for permissions, local asset loading, and JavaScript interfaces.
 */
@Composable
fun GalleryWebView(
  modifier: Modifier = Modifier,
  initialUrl: String? = null,
  useIframeWrapper: Boolean = false,
  javaScriptMode: GalleryWebViewJavaScriptMode = GalleryWebViewJavaScriptMode.Disabled,
  preventParentScrolling: Boolean = false,
  allowRequestPermission: Boolean = false,
  onWebViewCreated: ((WebView) -> Unit)? = null,
  onConsoleMessage: ((ConsoleMessage?) -> Unit)? = null,
  onPermissionRequest: ((PermissionRequest?) -> Unit)? = null,
  customWebViewClient: WebViewClient? = null,
) {
  val context = LocalContext.current

  val allowedMainFrameOrigins = remember(initialUrl) { allowedOriginsFor(initialUrl) }
  val curWebViewClient = remember(customWebViewClient, context, allowedMainFrameOrigins) {
    customWebViewClient ?: BaseGalleryWebViewClient(
      context = context,
      allowedMainFrameOrigins = allowedMainFrameOrigins,
    )
  }
  val trustedLocalJavaScript = javaScriptMode == GalleryWebViewJavaScriptMode.TrustedLocalContent
  var pendingCameraPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
  var pendingAudioPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

  val cameraPermissionLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
      isGranted: Boolean ->
      pendingCameraPermissionRequest?.let { request ->
        if (isGranted) {
          request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
          // If camera is denied, we don't call request.deny() on the whole request,
          // as it might contain other resources. The WebView will handle the denial
          // of the specific camera resource.
        }
        pendingCameraPermissionRequest = null
      }
    }

  val audioPermissionLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
      isGranted: Boolean ->
      pendingAudioPermissionRequest?.let { request ->
        if (isGranted) {
          request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
          // Similar to camera, don't call request.deny() on the whole request.
        }
        pendingAudioPermissionRequest = null
      }
    }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      // Allow Chrome DevTools (chrome://inspect) to attach to skill WebViews in debug builds only.
      if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
      }
      WebView(ctx).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )

        settings.apply {
          javaScriptEnabled = trustedLocalJavaScript
          domStorageEnabled = trustedLocalJavaScript
          allowFileAccess = false
          allowContentAccess = false
          mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
          safeBrowsingEnabled = true
          mediaPlaybackRequiresUserGesture = false
        }

        if (trustedLocalJavaScript) {
          // Expose durable, host-visible storage + locale to app-owned skill content only.
          val bridgeWebView = this
          addJavascriptInterface(
            SkillStorageBridge(ctx) { key, value ->
              bridgeWebView.post {
                val k = JSONObject.quote(key)
                val v = if (value == null) "null" else JSONObject.quote(value)
                bridgeWebView.evaluateJavascript(
                  "window.dispatchEvent(new CustomEvent('skill-storage-change'," +
                    "{detail:{key:$k,value:$v}}));",
                  null,
                )
              }
            },
            SkillStorageBridge.INTERFACE_NAME,
          )
        }

        if (preventParentScrolling) {
          setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP) {
              v.performClick()
            }
            false
          }
        }

        webChromeClient =
          object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
              BaoLog.d(
                TAG,
                "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}",
              )
              onConsoleMessage?.invoke(consoleMessage)
              return super.onConsoleMessage(consoleMessage)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
              if (!allowRequestPermission || !trustedLocalJavaScript) {
                request?.deny()
                return
              }

              if (request == null) return
              onPermissionRequest?.invoke(request)
                ?: run {
                  val resources = request.resources
                  val isCameraRequest = resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                  }
                  val isAudioRequest = resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                  }

                  if (isCameraRequest) {
                    pendingCameraPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                  }

                  if (isAudioRequest) {
                    pendingAudioPermissionRequest = request
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }

                  if (!isCameraRequest && !isAudioRequest) {
                    request.deny()
                  }
                }
            }
          }

        webViewClient = curWebViewClient

        initialUrl?.let { url ->
          if (trustedLocalJavaScript && !isTrustedLocalWebViewUrl(url)) {
            BaoLog.w(TAG, "Blocked JavaScript-enabled WebView load outside local assets")
            return@let
          }
          if (useIframeWrapper) {
            val framedHtml = iframeHtml(url, ctx.getString(R.string.skill_embedded_content))
            loadDataWithBaseURL(LOCAL_URL_BASE, framedHtml, "text/html", "UTF-8", null)
          } else {
            loadUrl(url)
          }
        }
        onWebViewCreated?.invoke(this)
      }
    },
    onRelease = { webView ->
      webView.stopLoading()
      webView.destroy()
    },
  )
}
