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

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import androidx.compose.ui.test.hasContentDescription as hasContentDescriptionMatcher

@RunWith(AndroidJUnit4::class)
class BaoTranslateScreenshotHarnessTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()
  private val screenshotRunDir =
    "$SCREENSHOT_ROOT/${System.currentTimeMillis()}"

  @get:Rule
  val ruleChain: TestRule =
    RuleChain.outerRule(ScreenshotHarnessPermissionRule())
      .around(composeRule)


  @Test
  fun capturesBaoTranslateVisualFlow_afterSelfProvisioningModels() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForScreenshots()

    val taskDescriptionPrefix = composeRule.prepareScreenshotHome()
    composeRule.captureScreenshot("01_home_task_grid", screenshotRunDir)

    composeRule.openScreenshotTaskByDescription(taskDescriptionPrefix)
    composeRule.clickScreenshotTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForBaoTranslateReadyOrSetup()
    composeRule.captureScreenshot("02_bao_translate_initial_state", screenshotRunDir)

    val audioChipPrefix = composeRule.audioChipContentDescriptionPrefix()
    composeRule.waitForContentDescription(audioChipPrefix, substring = true)
    composeRule.onNode(
        hasContentDescriptionMatcher(audioChipPrefix, substring = true),
        useUnmergedTree = true,
      )
      .assertIsDisplayed()
      .performClick()
    composeRule.waitForText(R.string.bao_translate_audio_picker_title)
    val phoneSpeakerOutput =
      composeRule.stringResource(
        R.string.bao_translate_audio_output_row_cd_format,
        composeRule.stringResource(R.string.bao_translate_phone_speaker),
      )
    composeRule.waitForContentDescription(
      phoneSpeakerOutput,
      substring = true,
    )
    composeRule.captureScreenshot("03_audio_device_picker", screenshotRunDir)
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_audio_input_default))
      .assertIsDisplayed()
    composeRule.assertNoLocalPhoneBluetoothRoute()
    pressBack()
    composeRule.waitForContentDescription(audioChipPrefix, substring = true)

    composeRule.openBaoTranslateSettings()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings_models))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.captureScreenshot("05_settings_models", screenshotRunDir)
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings_voice))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_change_audio_devices))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.captureScreenshot("06_settings_voice_audio", screenshotRunDir)
    pressBack()
    composeRule.waitForText(R.string.bao_translate_title)

    ensureRequiredBaoTranslateModelsReady(context)
    pressBack()
    composeRule.waitForHomeReady(taskDescriptionPrefix, timeoutMillis = 30_000)
    composeRule.openScreenshotTaskByDescription(taskDescriptionPrefix)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForBaoTranslateReadyForRecording(timeoutMillis = 180_000)
    composeRule.captureScreenshot("07_bao_translate_ready_state", screenshotRunDir)

    composeRule.captureConversationModeScreenshot(audioChipPrefix, screenshotRunDir)
    composeRule.captureRecordingStateScreenshot(context, screenshotRunDir)

    composeRule.openBaoTranslateSettings()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings_models))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.captureScreenshot("09_settings_after_required_models_ready", screenshotRunDir)

    Log.i(TAG, "BaoTranslate screenshot run saved to $screenshotRunDir")
  }

  private fun prepareDeviceForScreenshots() {
    runScreenshotShell("mkdir -p $screenshotRunDir")
    runScreenshotShell("input keyevent KEYCODE_WAKEUP")
    runScreenshotShell("wm dismiss-keyguard")
    runScreenshotShell("svc power stayon true")
    runScreenshotShell("cmd statusbar collapse")
  }

  private fun ensureRequiredBaoTranslateModelsReady(context: Context) {
    BaoTranslateModelManager.REQUIRED_MODEL_IDS.forEach { modelId ->
      if (BaoTranslateModelManager.checkModelStatus(context, modelId) != ModelStatus.Ready) {
        val result = runBlocking {
          BaoTranslateModelManager.downloadModel(context, modelId, wifiOnly = false)
        }
        assertTrue(
          "Failed to download $modelId: ${result.exceptionOrNull()?.message}",
          result.isSuccess,
        )
      }

      val status = BaoTranslateModelManager.checkModelStatus(context, modelId)
      assertTrue(
        "$modelId is not ready after visual harness provisioning; statuses=${statusSummary(context)}",
        status == ModelStatus.Ready,
      )
    }
  }

  private fun statusSummary(context: Context): String =
    BaoTranslateModelManager.ALL_MODELS.joinToString { model ->
      "${model.id}=${BaoTranslateModelManager.checkModelStatus(context, model.id)}"
    }
}

private const val TAG = "BaoTranslateScreens"
private const val SCREENSHOT_ROOT = "/sdcard/Download/gallery-baotranslate-screenshots"
private const val MIN_SCREENSHOT_BYTES = 10_000L

private class ScreenshotHarnessPermissionRule : ExternalResource() {
  private val animationScaleKeys =
    listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

  private fun setAnimationScales(value: String) {
    val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    animationScaleKeys.forEach { key ->
      ParcelFileDescriptor.AutoCloseInputStream(
        ui.executeShellCommand("settings put global $key $value"),
      ).use { it.readBytes() } // read to completion so the setting is applied before we proceed
    }
  }

  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    // Screenshots must capture SETTLED screens and assertIsDisplayed must not race an in-flight enter
    // animation (the bottom-sheet slide-in). Disable system animations via the production reduced-motion
    // seam (LocalAnimationScale <- ValueAnimator.getDurationScale()) BEFORE MainActivity first composes
    // — GalleryTheme reads the scale once in a keyless remember{}. With the scale at 0 every animation
    // snaps to its end state in the next frame, so the frozen-clock pumpUntil settles the UI instantly.
    setAnimationScales("0")
    Thread.sleep(500) // let ValueAnimator.getDurationScale() observe the new value before the launch
    val permissions =
      buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(Manifest.permission.BLUETOOTH_SCAN)
          add(Manifest.permission.BLUETOOTH_CONNECT)
          add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
    permissions.forEach { permission ->
      instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
    }
    // grantRuntimePermission grants the permission, but the system's ACTIVE-recording registration
    // (what `dumpsys audio` AudioMonitor reports, asserted in captureRecordingStateScreenshot) also
    // needs the RECORD_AUDIO app-op in ALLOWED mode. Under instrumentation it can be left unset
    // ("App op 27 missing, silencing record"), so the app's recorder is silenced and never appears.
    ParcelFileDescriptor.AutoCloseInputStream(
      instrumentation.uiAutomation.executeShellCommand("appops set $packageName RECORD_AUDIO allow"),
    ).use { it.readBytes() }
  }

  override fun after() {
    setAnimationScales("1") // restore device animations for the next test/app session
  }
}

private typealias MainActivityScreenshotRule =
  AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private fun MainActivityScreenshotRule.stringResource(@StringRes resId: Int): String =
  activity.getString(resId)

private fun MainActivityScreenshotRule.stringResource(@StringRes resId: Int, vararg formatArgs: Any): String =
  activity.getString(resId, *formatArgs)

private fun MainActivityScreenshotRule.quantityStringResource(
  @PluralsRes resId: Int,
  quantity: Int,
  vararg formatArgs: Any,
): String = activity.resources.getQuantityString(resId, quantity, *formatArgs)

private fun MainActivityScreenshotRule.audioChipContentDescriptionPrefix(): String {
  val outputMarker = "__OUTPUT_DEVICE__"
  return quantityStringResource(
    R.plurals.bao_translate_audio_chip_cd_format,
    0,
    outputMarker,
    "__INPUT_DEVICE__",
    0,
  ).substringBefore(outputMarker)
}

private fun MainActivityScreenshotRule.prepareScreenshotHome(): String {
  runScreenshotShell("cmd statusbar collapse")

  val taskDescriptionPrefix = "${stringResource(R.string.bao_translate)} task"
  repeat(8) {
    if (clickScreenshotTextIfPresent(R.string.tos_dialog_accept_and_continue_button_label, 2_000)) {
      return@repeat
    }
    if (clickScreenshotTextIfPresent(R.string.dismiss, timeoutMillis = 1_000)) {
      return@repeat
    }
    if (isHomeReady(taskDescriptionPrefix)) {
      return taskDescriptionPrefix
    }
  }
  waitForHomeReady(taskDescriptionPrefix, timeoutMillis = 30_000)
  return taskDescriptionPrefix
}

private fun MainActivityScreenshotRule.isHomeReady(taskDescriptionPrefix: String): Boolean {
  val menuDescription = stringResource(R.string.cd_menu)
  return hasContentDescription(menuDescription) ||
    hasContentDescription(taskDescriptionPrefix, substring = true)
}

// This is a SCREENSHOT harness: animations are disabled at the source via the production reduced-motion
// seam (ScreenshotHarnessPermissionRule sets the system animation scales to 0 BEFORE the activity
// launches), so the clock auto-advances normally (no infinite pulse to hang waitForIdle) and every
// screen is captured fully SETTLED. So waits use the framework's wall-clock waitUntil directly; this
// returns false on timeout (instead of throwing) to preserve the callers' boolean contract.
private fun MainActivityScreenshotRule.pumpUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean =
  runCatching { waitUntil(timeoutMillis) { condition() } }.isSuccess

private fun MainActivityScreenshotRule.waitForHomeReady(
  taskDescriptionPrefix: String,
  timeoutMillis: Long = 10_000,
) {
  if (!pumpUntil(timeoutMillis) { isHomeReady(taskDescriptionPrefix) }) {
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for home screen to be ready")
  }
}

private fun MainActivityScreenshotRule.openScreenshotTaskByDescription(
  taskDescriptionPrefix: String,
) {
  waitForContentDescription(taskDescriptionPrefix, substring = true)
  onNode(
      hasContentDescriptionMatcher(taskDescriptionPrefix, substring = true),
      useUnmergedTree = true,
    )
    .performClick()
}

private fun MainActivityScreenshotRule.openBaoTranslateSettings() {
  onNodeWithContentDescription(stringResource(R.string.settings_title))
    .assertIsDisplayed()
    .performClick()
  waitForText(R.string.bao_translate_settings)
  onNodeWithText(stringResource(R.string.bao_translate_settings))
    .assertIsDisplayed()
}

private fun MainActivityScreenshotRule.waitForBaoTranslateReadyOrSetup(
  timeoutMillis: Long = 10_000,
) {
  val setupTitle = stringResource(R.string.bao_translate_download_required)
  val setupAction = stringResource(R.string.bao_translate_download_all)
  val modelsReady = stringResource(R.string.bao_translate_model_ready)
  val readyAction = stringResource(R.string.cd_bao_translate_start)

  if (!pumpUntil(timeoutMillis) {
      hasText(setupAction) || hasText(modelsReady) || hasContentDescription(readyAction)
    }) {
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for BaoTranslate ready/setup state")
  }

  if (hasContentDescription(readyAction)) {
    onNodeWithContentDescription(readyAction).assertIsDisplayed()
  } else if (hasText(modelsReady)) {
    assertTrue("Expected at least one BaoTranslate ready model label", hasText(modelsReady))
  } else {
    onNodeWithText(setupTitle).assertIsDisplayed()
    onNodeWithText(setupAction).assertIsDisplayed()
  }
}

private fun MainActivityScreenshotRule.waitForBaoTranslateReadyForRecording(
  timeoutMillis: Long,
) {
  waitForContentDescription(
    stringResource(R.string.cd_bao_translate_start),
    timeoutMillis = timeoutMillis,
  )
  onNodeWithContentDescription(stringResource(R.string.cd_bao_translate_start))
    .assertIsDisplayed()
}

private fun MainActivityScreenshotRule.captureRecordingStateScreenshot(
  context: Context,
  screenshotRunDir: String,
) {
  onNodeWithContentDescription(stringResource(R.string.cd_bao_translate_start))
    .assertIsDisplayed()
    .performClick()
  waitForText(R.string.bao_translate_listening, timeoutMillis = 180_000)
  onNodeWithContentDescription(stringResource(R.string.cd_bao_translate_stop))
    .assertIsDisplayed()

  // Verify the app holds a REAL system-level active MICROPHONE recording while "listening" — proves
  // actual mic capture, not just a UI state. Uses the framework API (getActiveRecordingConfigurations),
  // which from the app's own process returns the app's own recordings, instead of parsing vendor
  // `dumpsys audio` text whose "AudioMonitor status:" section is empty/absent on some OEM builds (e.g.
  // HyperOS) even while the AudioRecord is open. Poll: registration can lag the UI's "listening" state.
  val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  val micSources = setOf(
    MediaRecorder.AudioSource.MIC,
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    MediaRecorder.AudioSource.DEFAULT,
  )
  val recordDeadline = System.currentTimeMillis() + 10_000
  var activeSources = emptyList<Int>()
  while (System.currentTimeMillis() < recordDeadline) {
    activeSources = audioManager.activeRecordingConfigurations.map { it.clientAudioSource }
    if (activeSources.any { it in micSources }) break
    Thread.sleep(250)
  }
  assertTrue(
    "Bao Translate did not expose an active microphone recording while listening; activeSources=$activeSources",
    activeSources.any { it in micSources },
  )

  captureScreenshot(
    "08_recording_default_mic_state",
    screenshotRunDir,
    waitForIdleBeforeCapture = false,
  )

  tapBottomEndFab()
  waitForContentDescription(
    stringResource(R.string.cd_bao_translate_start),
    timeoutMillis = 20_000,
  )
}

private fun MainActivityScreenshotRule.captureConversationModeScreenshot(
  audioChipPrefix: String,
  screenshotRunDir: String,
) {
  val conversationMode = stringResource(R.string.bao_translate_conversation_mode)
  waitForContentDescription(conversationMode, timeoutMillis = 30_000)
  onAllNodesWithContentDescription(conversationMode, useUnmergedTree = true)
    .onFirst()
    .assertIsDisplayed()
    .performClick()
  waitForText(R.string.bao_translate_conversation_mode)
  onNodeWithText(stringResource(R.string.bao_translate_connect_subtitle))
    .assertIsDisplayed()
  captureScreenshot("04_conversation_mode", screenshotRunDir)
  onAllNodesWithContentDescription(conversationMode, useUnmergedTree = true)
    .onFirst()
    .assertIsDisplayed()
    .performClick()
  waitForContentDescription(audioChipPrefix, timeoutMillis = 30_000, substring = true)
}

private fun MainActivityScreenshotRule.waitForText(
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForText(stringResource(resId), timeoutMillis, substring)
}

private fun MainActivityScreenshotRule.waitForText(
  text: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  if (!pumpUntil(timeoutMillis) { hasText(text, substring = substring) }) {
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for text: \"$text\" (substring=$substring)")
  }
}

private fun MainActivityScreenshotRule.waitForContentDescription(
  contentDescription: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  if (!pumpUntil(timeoutMillis) { hasContentDescription(contentDescription, substring) }) {
    throw AssertionError(
      "Timed out after ${timeoutMillis}ms waiting for contentDescription: \"$contentDescription\" (substring=$substring)"
    )
  }
}

private fun MainActivityScreenshotRule.clickScreenshotTextIfPresent(
  @StringRes resId: Int,
  timeoutMillis: Long = 3_000,
): Boolean {
  val text = stringResource(resId)
  val appeared = pumpUntil(timeoutMillis) { hasText(text) }
  if (appeared) {
    onNodeWithText(text).performClick()
    waitForIdle()
  }
  return appeared
}

private fun MainActivityScreenshotRule.hasText(text: String, substring: Boolean = false): Boolean =
  runCatching {
      onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun MainActivityScreenshotRule.hasContentDescription(
  contentDescription: String,
  substring: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithContentDescription(contentDescription, substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun MainActivityScreenshotRule.assertNoLocalPhoneBluetoothRoute() {
  val localDeviceLabels =
    listOf(
        Build.MODEL,
        Build.DEVICE,
        Build.MODEL.replace('_', '-'),
        Build.DEVICE.replace('_', '-'),
      )
      .map { it.trim() }
      .filter { it.isNotBlank() && it != "null" }
      .distinct()

  localDeviceLabels.forEach { label ->
    assertTrue(
      "Audio picker exposed local phone model as a Bluetooth route: $label",
      !hasText(label, substring = true),
    )
  }
}

private fun MainActivityScreenshotRule.captureScreenshot(
  name: String,
  screenshotRunDir: String,
  waitForIdleBeforeCapture: Boolean = true,
) {
  if (waitForIdleBeforeCapture) {
    waitForIdle()
  }
  Thread.sleep(1_000)
  val path = "$screenshotRunDir/$name.png"
  runScreenshotShell("screencap -p $path")
  val listing = runScreenshotShell("ls -ln $path")
  val size = parseRemoteFileSize(listing)
  assertTrue("Screenshot $path was not created. ls output: $listing", listing.contains(path))
  assertTrue(
    "Screenshot $path was too small to be useful: $size bytes. ls output: $listing",
    size >= MIN_SCREENSHOT_BYTES,
  )
  Log.i(TAG, "Captured screenshot $path ($size bytes)")
}

private fun tapBottomEndFab() {
  val (width, height) = currentScreenSize()
  runScreenshotShell("input tap ${(width * 0.84f).toInt()} ${(height * 0.92f).toInt()}")
  Thread.sleep(500)
}

private fun parseRemoteFileSize(listing: String): Long {
  val firstLine = listing.lineSequence().firstOrNull { it.isNotBlank() } ?: return 0L
  val columns = firstLine.trim().split(Regex("\\s+"))
  return columns.getOrNull(4)?.toLongOrNull() ?: 0L
}

private fun currentScreenSize(): Pair<Int, Int> {
  val output = runScreenshotShell("wm size")
  val sizeText = output.substringAfter("Physical size:", output).trim().lineSequence().firstOrNull().orEmpty()
  val width = sizeText.substringBefore("x").trim().toIntOrNull() ?: 1080
  val height = sizeText.substringAfter("x", "").trim().toIntOrNull() ?: 2340
  return width to height
}

private fun runScreenshotShell(command: String): String {
  val descriptor =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    .bufferedReader()
    .use { it.readText() }
}
