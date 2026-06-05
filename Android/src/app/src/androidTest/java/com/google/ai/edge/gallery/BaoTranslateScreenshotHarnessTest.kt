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
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_audio_input_default))
      .assertIsDisplayed()
    composeRule.assertNoLocalPhoneBluetoothRoute()
    composeRule.captureScreenshot("03_audio_device_picker", screenshotRunDir)
    pressBack()
    composeRule.waitForContentDescription(audioChipPrefix, substring = true)

    val conversationMode = composeRule.stringResource(R.string.bao_translate_conversation_mode)
    if (composeRule.hasContentDescription(conversationMode)) {
      composeRule.onAllNodesWithContentDescription(conversationMode, useUnmergedTree = true)
        .onFirst()
        .performClick()
      composeRule.waitForText(R.string.bao_translate_conversation_mode)
      composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_connect_subtitle))
        .assertIsDisplayed()
      composeRule.captureScreenshot("04_conversation_mode", screenshotRunDir)
      composeRule.onAllNodesWithContentDescription(conversationMode, useUnmergedTree = true)
        .onFirst()
        .performClick()
      composeRule.waitForContentDescription(audioChipPrefix, substring = true)
    }

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

    ensureEveryBaoTranslateModelReady(context)
    pressBack()
    composeRule.waitForHomeReady(taskDescriptionPrefix, timeoutMillis = 30_000)
    composeRule.openScreenshotTaskByDescription(taskDescriptionPrefix)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForBaoTranslateReadyForRecording(timeoutMillis = 180_000)
    composeRule.captureScreenshot("07_bao_translate_ready_state", screenshotRunDir)

    composeRule.captureRecordingStateScreenshot(context, screenshotRunDir)

    composeRule.openBaoTranslateSettings()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings_models))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.captureScreenshot("09_settings_after_all_models_ready", screenshotRunDir)

    Log.i(TAG, "BaoTranslate screenshot run saved to $screenshotRunDir")
  }

  private fun prepareDeviceForScreenshots() {
    runScreenshotShell("mkdir -p $screenshotRunDir")
    runScreenshotShell("input keyevent KEYCODE_WAKEUP")
    runScreenshotShell("wm dismiss-keyguard")
    runScreenshotShell("svc power stayon true")
    runScreenshotShell("cmd statusbar collapse")
  }

  private fun ensureEveryBaoTranslateModelReady(context: Context) {
    BaoTranslateModelManager.ALL_MODELS.forEach { model ->
      if (BaoTranslateModelManager.checkModelStatus(context, model.id) != ModelStatus.Ready) {
        val result = runBlocking {
          BaoTranslateModelManager.downloadModel(context, model.id, wifiOnly = false)
        }
        assertTrue(
          "Failed to download ${model.id}: ${result.exceptionOrNull()?.message}",
          result.isSuccess,
        )
      }

      val status = BaoTranslateModelManager.checkModelStatus(context, model.id)
      assertTrue(
        "${model.id} is not ready after visual harness provisioning; statuses=${statusSummary(context)}",
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
  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
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
  }
}

private typealias MainActivityScreenshotRule =
  AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private fun MainActivityScreenshotRule.stringResource(@StringRes resId: Int): String =
  activity.getString(resId)

private fun MainActivityScreenshotRule.stringResource(@StringRes resId: Int, vararg formatArgs: Any): String =
  activity.getString(resId, *formatArgs)

private fun MainActivityScreenshotRule.audioChipContentDescriptionPrefix(): String {
  val outputMarker = "__OUTPUT_DEVICE__"
  return stringResource(
    R.string.bao_translate_audio_chip_cd_format,
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

private fun MainActivityScreenshotRule.waitForHomeReady(
  taskDescriptionPrefix: String,
  timeoutMillis: Long = 10_000,
) {
  waitUntil(timeoutMillis) { isHomeReady(taskDescriptionPrefix) }
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

  waitUntil(timeoutMillis) {
    hasText(setupAction) || hasText(modelsReady) || hasContentDescription(readyAction)
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

  Thread.sleep(1_500)
  val audioMonitorBlock = currentAudioMonitorBlock()
  assertTrue(
    "Bao Translate did not expose an active recorder while the screenshot harness was recording: $audioMonitorBlock",
    !audioMonitorBlock.contains("Recorder port map: {}"),
  )
  assertTrue(
    "Bao Translate active recorder did not belong to app uid ${context.applicationInfo.uid}: $audioMonitorBlock",
    audioMonitorBlock.contains(context.applicationInfo.uid.toString()),
  )

  captureScreenshot("08_recording_default_mic_state", screenshotRunDir)

  onNodeWithContentDescription(stringResource(R.string.cd_bao_translate_stop))
    .assertIsDisplayed()
    .performClick()
  waitForContentDescription(
    stringResource(R.string.cd_bao_translate_start),
    timeoutMillis = 20_000,
  )
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
  waitUntil(timeoutMillis) {
    runCatching {
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
      }
      .getOrDefault(false)
  }
}

private fun MainActivityScreenshotRule.waitForContentDescription(
  contentDescription: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) {
    runCatching {
        onAllNodesWithContentDescription(contentDescription, substring, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty()
      }
      .getOrDefault(false)
  }
}

private fun MainActivityScreenshotRule.clickScreenshotTextIfPresent(
  @StringRes resId: Int,
  timeoutMillis: Long = 3_000,
): Boolean {
  val text = stringResource(resId)
  val appeared =
    runCatching {
        waitUntil(timeoutMillis) {
          runCatching {
              onAllNodesWithText(text, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()
            }
            .getOrDefault(false)
        }
      }
      .isSuccess
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
) {
  waitForIdle()
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

private fun parseRemoteFileSize(listing: String): Long {
  val firstLine = listing.lineSequence().firstOrNull { it.isNotBlank() } ?: return 0L
  val columns = firstLine.trim().split(Regex("\\s+"))
  return columns.getOrNull(4)?.toLongOrNull() ?: 0L
}

private fun currentAudioMonitorBlock(): String {
  val audioDump = runScreenshotShell("dumpsys audio")
  val start = audioDump.indexOf("AudioMonitor status:")
  if (start < 0) return audioDump
  val end = audioDump.indexOf("Events log: ZAudio service playbck & record monitor", start)
  return if (end > start) audioDump.substring(start, end) else audioDump.substring(start)
}

private fun runScreenshotShell(command: String): String {
  val descriptor =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    .bufferedReader()
    .use { it.readText() }
}
