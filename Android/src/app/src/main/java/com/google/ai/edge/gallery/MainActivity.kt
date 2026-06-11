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

package com.google.ai.edge.gallery

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.ProjectConfig
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private var contentSet: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    // We intentionally pass null to discard the saved instance state bundle.
    // This prevents Jetpack Compose from automatically restoring the previous screen
    // and forces the app to start cleanly on the Home Screen after an OS kill.
    super.onCreate(null)

    // Log only the extra KEYS, not values: deep-link extra values can carry user/PII payloads, and
    // Bundle.get(key) is deprecated. Keys alone are enough to debug intent routing.
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        BaoLog.d(TAG, "onCreate Extra -> Key: $key")
      }
    }

    // Convert FCM Console data extras to intent data for GalleryNavGraph to pick up
    intent.getStringExtra("deeplink")?.let { link ->
      BaoLog.d(TAG, "onCreate: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }

    fun setContent() {
      if (contentSet) {
        return
      }

      setContent {
        GalleryTheme {
          Surface(modifier = Modifier.fillMaxSize()) {
            GalleryApp(modelManagerViewModel = modelManagerViewModel)

            // Fade out a "mask" that has the same color as the background of the splash screen
            // to reveal the actual app content.
            var startMaskFadeout by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { startMaskFadeout = true }
            AnimatedVisibility(
              !startMaskFadeout,
              enter = fadeIn(animationSpec = snap(0)),
              exit =
                fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
            ) {
              Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
              )
            }
          }
        }
      }

      @OptIn(ExperimentalApi::class)
      ExperimentalFlags.enableBenchmark = false

      contentSet = true
    }

    modelManagerViewModel.loadModelAllowlist()

    // Show splash screen.
    val splashScreen = installSplashScreen()

    // Set Compose content synchronously in onCreate (the standard pattern). The in-Compose mask
    // inside setContent handles the splash cross-fade reveal, so deferring content is unnecessary —
    // and a deferred/coroutine setContent left the composition unattached at onCreate, which broke
    // instrumented idle detection (waitForIdle never settled) and risked a blank frame when the
    // system splash was optimized away (e.g. after a force-quit). Setting it now fixes both.
    setContent()

    // Cross-fade transition from the splash screen to the main content.
    //
    // The logic performs the following key actions:
    // 1. Synchronizes Timing: It calculates the remaining duration of the default icon
    //    animation. It then delays its own animations to ensure the custom fade-out begins just
    //    before the original icon animation would have finished.
    // 2. Initiates a cross-fade:
    //    - Fade out the splash screen.
    //    - Fade in the main content.
    // 3. Cleans up: An `onEnd` listener on the fade-out animator calls
    //    `splashScreenView.remove()` to properly remove the splash screen from the view hierarchy
    //    once it's fully transparent.
    splashScreen.setOnExitAnimationListener { splashScreenView ->
      val now = System.currentTimeMillis()
      val iconAnimationStartMs = splashScreenView.iconAnimationStartMillis
      val duration = splashScreenView.iconAnimationDurationMillis
      val fadeOut = ObjectAnimator.ofFloat(splashScreenView.view, View.ALPHA, 1f, 0f)
      fadeOut.interpolator = DecelerateInterpolator()
      fadeOut.duration = 300L
      fadeOut.doOnEnd { splashScreenView.remove() }
      // Begin the splash fade-out just as the icon animation finishes. The Compose content is
      // already live underneath (set synchronously in onCreate), so the fade simply reveals it.
      lifecycleScope.launch {
        val fadeStartDelay = duration - (now - iconAnimationStartMs) - 300
        if (fadeStartDelay > 0) {
          delay(fadeStartDelay)
        }
        fadeOut.start()
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)

    // Keys only — see onCreate: extra values may carry PII and Bundle.get(key) is deprecated.
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        BaoLog.d(TAG, "onNewIntent Extra -> Key: $key")
      }
    }

    intent.getStringExtra("deeplink")?.let { link ->
      BaoLog.d(TAG, "onNewIntent: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }
  }

  override fun onResume() {
    super.onResume()

    firebaseAnalytics?.logEvent(
      FirebaseAnalytics.Event.APP_OPEN,
      Bundle().apply {
        putString("app_version", ProjectConfig.versionName)
        putString("os_version", Build.VERSION.SDK_INT.toString())
        putString("device_model", Build.MODEL)
      },
    )
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
