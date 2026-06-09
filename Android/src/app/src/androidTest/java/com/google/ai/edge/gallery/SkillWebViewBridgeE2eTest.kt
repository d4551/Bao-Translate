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

package com.google.ai.edge.gallery

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.SkillStorageBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the skill-hardening changes actually work in a real WebView served by the
 * app's [BaseGalleryWebViewClient] asset loader — no LLM model required.
 *
 * Asserts, against the real shipped `mood-tracker` skill:
 *  1. The `<script type="module">` entrypoint executed (`window.ai_edge_gallery_get_result` is
 *     defined). This can only happen if the cross-directory `import` of the `_shared` ES modules
 *     resolved AND they were served with a module-compatible MIME type — i.e. the
 *     `correctAssetMimeType` fix is doing its job. A wrong MIME aborts the whole module.
 *  2. `window.AndroidBridge` is present with a working `getLocale()`.
 *  3. A `setItem` / `getItem` / `removeItem` round-trip through the native SharedPreferences bridge
 *     succeeds (durable, host-visible storage).
 */
@RunWith(AndroidJUnit4::class)
class SkillWebViewBridgeE2eTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private lateinit var webView: WebView

  @Test
  fun moodTrackerSkill_loadsSharedModules_andBridgeRoundTrips() {
    val pageFinished = CountDownLatch(1)
    instrumentation.runOnMainSync {
      webView =
        WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          addJavascriptInterface(SkillStorageBridge(context), SkillStorageBridge.INTERFACE_NAME)
          webViewClient =
            object : BaseGalleryWebViewClient(context) {
              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished.countDown()
              }
            }
        }
      webView.loadUrl("$LOCAL_URL_BASE/assets/skills/mood-tracker/scripts/index.html")
    }

    assertTrue("Skill page did not finish loading in time", pageFinished.await(20, TimeUnit.SECONDS))

    // ES modules execute asynchronously after onPageFinished; poll until the entrypoint appears.
    val probe =
      pollJs(
        script =
          """
          (function () {
            try {
              var entry = (typeof window.ai_edge_gallery_get_result === 'function');
              var bridge = (typeof window.AndroidBridge !== 'undefined' &&
                            typeof window.AndroidBridge.getLocale === 'function');
              var locale = bridge ? window.AndroidBridge.getLocale() : null;
              var roundtrip = false;
              if (bridge) {
                window.AndroidBridge.setItem('__probe_key__', 'probe_value');
                roundtrip = (window.AndroidBridge.getItem('__probe_key__') === 'probe_value');
                window.AndroidBridge.removeItem('__probe_key__');
              }
              return JSON.stringify({ entry: entry, bridge: bridge, locale: locale, roundtrip: roundtrip });
            } catch (e) {
              return JSON.stringify({ error: String(e) });
            }
          })()
          """
            .trimIndent(),
        predicate = { it.contains("\"entry\":true") },
        timeoutMs = 10_000,
      )

    assertTrue(
      "Shared ES modules did not load (module entrypoint missing) — probe=$probe",
      probe.contains("\"entry\":true"),
    )
    assertTrue("window.AndroidBridge not exposed — probe=$probe", probe.contains("\"bridge\":true"))
    assertTrue(
      "AndroidBridge storage round-trip failed — probe=$probe",
      probe.contains("\"roundtrip\":true"),
    )
  }

  @Test
  fun moodTrackerDashboard_loadsVendoredOutfitFont_offline() {
    val pageFinished = CountDownLatch(1)
    instrumentation.runOnMainSync {
      webView =
        WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          addJavascriptInterface(SkillStorageBridge(context), SkillStorageBridge.INTERFACE_NAME)
          webViewClient =
            object : BaseGalleryWebViewClient(context) {
              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished.countDown()
              }
            }
        }
      webView.loadUrl("$LOCAL_URL_BASE/assets/skills/mood-tracker/assets/dashboard.html")
    }
    assertTrue("Dashboard did not finish loading", pageFinished.await(20, TimeUnit.SECONDS))

    // Force-load the vendored @font-face, then confirm it is actually available (proves the
    // local woff2 is served by the asset loader — no CDN, works offline).
    val probe =
      pollJs(
        script =
          """
          (function () {
            // evaluateJavascript cannot await a Promise, so kick the async font load off once and
            // stash its result on a window global that subsequent polls read synchronously.
            if (window.__fontProbe) return JSON.stringify(window.__fontProbe);
            if (!window.__fontProbeStarted) {
              window.__fontProbeStarted = true;
              if (document.fonts && document.fonts.load) {
                document.fonts.load('600 16px "Outfit"').then(function (faces) {
                  window.__fontProbe = { loaded: faces.length > 0,
                                         check: document.fonts.check('600 16px "Outfit"') };
                }).catch(function (e) { window.__fontProbe = { error: String(e) }; });
              } else {
                window.__fontProbe = { supported: false };
              }
            }
            return JSON.stringify(window.__fontProbe || { pending: true });
          })()
          """
            .trimIndent(),
        predicate = { it.contains("\"loaded\":true") || it.contains("\"check\":true") },
        timeoutMs = 10_000,
      )
    assertTrue(
      "Vendored Outfit font failed to load from local assets — probe=$probe",
      probe.contains("\"loaded\":true") || probe.contains("\"check\":true"),
    )
  }

  /** Evaluates [script] on the main thread, polling until [predicate] holds or [timeoutMs] elapses. */
  private fun pollJs(script: String, predicate: (String) -> Boolean, timeoutMs: Long): String {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var last = ""
    while (SystemClock.uptimeMillis() < deadline) {
      val latch = CountDownLatch(1)
      val ref = AtomicReference("")
      instrumentation.runOnMainSync {
        webView.evaluateJavascript(script) { value ->
          ref.set(value ?: "")
          latch.countDown()
        }
      }
      latch.await(3, TimeUnit.SECONDS)
      // evaluateJavascript returns a JSON-encoded value; unwrap the outer string literal.
      last = ref.get().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
      if (predicate(last)) return last
      SystemClock.sleep(300)
    }
    return last
  }
}
