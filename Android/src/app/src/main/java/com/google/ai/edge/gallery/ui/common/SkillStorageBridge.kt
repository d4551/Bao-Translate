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

package com.google.ai.edge.gallery.ui.common

import android.content.Context
import android.webkit.JavascriptInterface
import java.util.Locale

/**
 * Synchronous key/value storage bridge exposed to skill WebViews as `window.AndroidBridge`.
 *
 * Backed by app-private [android.content.SharedPreferences] rather than the WebView's DOM
 * `localStorage`, so skill data:
 *  - survives a WebView data/cache clear,
 *  - is visible to (and exportable by) the host app, and
 *  - can be observed for realtime change broadcasts.
 *
 * The `@JavascriptInterface` methods are invoked on a WebView binder thread, not the main thread.
 * `SharedPreferences` reads/writes are synchronous, which matches the synchronous JS contract in
 * `_shared/storage.js` (`getItem` returns a value directly).
 *
 * [onChange] is invoked after every mutation so the host can broadcast a realtime change event
 * back into open WebViews. It runs on the calling binder thread; the host is responsible for
 * marshaling to the main thread before touching the WebView.
 */
class SkillStorageBridge(
  context: Context,
  private val onChange: ((key: String, value: String?) -> Unit)? = null,
) {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  @JavascriptInterface fun getItem(key: String): String? = prefs.getString(key, null)

  @JavascriptInterface
  fun setItem(key: String, value: String) {
    prefs.edit().putString(key, value).apply()
    onChange?.invoke(key, value)
  }

  @JavascriptInterface
  fun removeItem(key: String) {
    prefs.edit().remove(key).apply()
    onChange?.invoke(key, null)
  }

  /** The host app locale tag (e.g. `en`, `es`), used by `_shared/i18n.js` to pick a string table. */
  @JavascriptInterface fun getLocale(): String = Locale.getDefault().language

  companion object {
    private const val PREFS_NAME = "skill_storage"
    const val INTERFACE_NAME = "AndroidBridge"
  }
}
