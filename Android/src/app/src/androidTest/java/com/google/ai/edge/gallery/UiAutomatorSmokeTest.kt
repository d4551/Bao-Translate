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

import android.content.Context
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end launch + navigation smoke test driven by UiAutomator.
 *
 * UiAutomator polls the on-screen accessibility tree and never calls Compose `waitForIdle()`, so —
 * unlike a `createAndroidComposeRule` test — it does not hang on this app, whose composition never
 * registers as idle under the Compose test framework. This is the working replacement for the old
 * Compose-rule `SmokeE2eTest`, which hung at launch and was historically masked green by the
 * `am instrument` exit-0 bug (now fixed; see `smokeE2e` in build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class UiAutomatorSmokeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  private fun s(@StringRes id: Int): String = context.getString(id)

  private fun waitForText(text: String, timeoutMs: Long = TIMEOUT): Boolean =
    device.wait(Until.hasObject(By.text(text)), timeoutMs) != null

  private fun waitForDesc(desc: String, timeoutMs: Long = TIMEOUT): Boolean =
    device.wait(Until.hasObject(By.desc(desc)), timeoutMs) != null

  private fun waitForHome() {
    // Launch via `am start` shell command: unlike context.startActivity it is not subject to
    // background-activity-launch restrictions (so the app reliably foregrounds), and unlike
    // ActivityScenario it does NOT call Instrumentation.waitForIdleSync() — which would hang on
    // this app, whose main looper never idles under the Compose runtime. (No force-stop: the test
    // runs INSIDE the target com.bao.translate process, so stopping it would kill the test.)
    // FLAG_ACTIVITY_CLEAR_TOP (-f 0x4000000) gives a clean Home each test if already running.
    device.executeShellCommand(
      "am start -f 0x04000000 -n ${context.packageName}/com.google.ai.edge.gallery.MainActivity"
    )
    // First-run consent, if shown, must be accepted before the home surfaces.
    device.wait(Until.findObject(By.text(s(R.string.tos_dialog_accept_and_continue_button_label))), 3_000)
      ?.click()
    // Wait for an APP-SPECIFIC home element — the drawer menu, scoped to our package.
    assertTrue(
      "App home (drawer menu) did not render",
      device.wait(Until.hasObject(By.pkg(context.packageName).desc(s(R.string.cd_menu))), TIMEOUT) !=
        null,
    )
  }

  private fun openHomeDrawer() {
    val menu =
      requireNotNull(device.wait(Until.findObject(By.desc(s(R.string.cd_menu))), TIMEOUT)) {
        "Home drawer menu button never appeared"
      }
    menu.click()
    assertTrue("Drawer did not open", waitForText(s(R.string.drawer_models_label)))
  }

  @Test
  fun launchesToHomeWithCapabilityCards() {
    waitForHome()
    assertTrue("Bao Translate task card missing", waitForText(s(R.string.bao_translate)))
    assertTrue("AI Chat task card missing", waitForText(s(R.string.task_label_ai_chat)))
  }

  @Test
  fun homeDrawerExposesModelsAndSettingsAndNotifications() {
    waitForHome()
    openHomeDrawer()
    assertTrue("Models drawer item missing", waitForText(s(R.string.drawer_models_label)))
    assertTrue("Settings drawer item missing", waitForText(s(R.string.drawer_settings_label)))
    assertTrue(
      "Notifications drawer item missing",
      waitForText(s(R.string.drawer_notifications_label)),
    )
  }

  @Test
  fun navigatesToModelsRouteAndBack() {
    waitForHome()
    openHomeDrawer()
    requireNotNull(device.findObject(By.text(s(R.string.drawer_models_label)))) {
        "Models drawer item not clickable"
      }
      .click()
    // The Models screen exposes the import-model affordance; its presence proves we navigated.
    assertTrue(
      "Models screen (import affordance) did not render",
      waitForDesc(s(R.string.cd_import_model_button)),
    )
    device.pressBack()
    assertTrue("Did not return to home after Models", waitForDesc(s(R.string.cd_menu)))
  }

  @Test
  fun navigatesToSettingsRouteAndBack() {
    waitForHome()
    openHomeDrawer()
    requireNotNull(device.findObject(By.text(s(R.string.drawer_settings_label)))) {
        "Settings drawer item not clickable"
      }
      .click()
    // Assert on a settings-screen-SPECIFIC control ("Theme"), not the word "Settings", which also
    // labels the drawer item we just clicked — so this proves the screen actually rendered.
    assertTrue("Settings screen did not render", waitForText(s(R.string.settings_theme)))
    device.pressBack()
    assertTrue("Did not return to home after Settings", waitForDesc(s(R.string.cd_menu)))
  }

  companion object {
    private const val TIMEOUT = 15_000L
  }
}
