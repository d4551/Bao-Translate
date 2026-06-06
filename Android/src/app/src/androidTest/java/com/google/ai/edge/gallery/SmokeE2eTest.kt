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
import android.os.Build
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.hasContentDescription as hasContentDescriptionMatcher

@RunWith(AndroidJUnit4::class)
class SmokeE2eTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val ruleChain: TestRule =
    RuleChain.outerRule(NotificationPermissionRule())
      .around(composeRule)

  @Test
  fun launchesHomeAndOpensBaoTranslateSetupSurfaces() {
    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)

    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.onAllNodesWithText(
        composeRule.stringResource(R.string.bao_translate_title),
        useUnmergedTree = true,
      )
      .onFirst()
      .assertIsDisplayed()
    composeRule.waitForBaoTranslateReadyOrSetup()
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
    composeRule.waitForContentDescription(phoneSpeakerOutput, substring = true)
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_audio_input_default))
      .assertIsDisplayed()
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
      composeRule.onAllNodesWithContentDescription(conversationMode, useUnmergedTree = true)
        .onFirst()
        .performClick()
    }

    composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.settings_title))
      .performClick()
    composeRule.waitForText(R.string.bao_translate_settings)
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings))
      .assertIsDisplayed()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings_models))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_settings_voice))
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.waitForText(R.string.bao_translate_settings_audio)
    val settingsEnrollVoice =
      composeRule.stringResource(R.string.cd_bao_translate_settings_enroll_voice)
    if (composeRule.hasContentDescription(settingsEnrollVoice)) {
      composeRule.onNodeWithContentDescription(settingsEnrollVoice).performScrollTo().performClick()
      composeRule.waitForText(R.string.bao_translate_record_voice_title)
      composeRule.onNodeWithText(composeRule.stringResource(R.string.bao_translate_record_prompt))
        .assertIsDisplayed()
      composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start_recording))
        .assertIsDisplayed()
      pressBack()
    } else {
      pressBack()
    }

    pressBack()
    composeRule.waitForContentDescription(R.string.cd_menu, timeoutMillis = 15_000)
    exerciseHomeDrawerRoutes()
  }

  @Test
  fun renderedCapabilityTaskCardsOpenModelLists() {
    val homeTaskDescriptionPrefix = composeRule.prepareHome()
    val capabilityLabels =
      listOf(
        composeRule.stringResource(R.string.task_label_ai_chat),
        composeRule.stringResource(R.string.task_label_prompt_lab),
        composeRule.stringResource(R.string.task_label_ask_image),
        composeRule.stringResource(R.string.task_label_audio_scribe),
        "Tiny Garden",
        "Mobile Actions",
        "Agent Chat",
      )
    val exercisedLabels = mutableListOf<String>()

    capabilityLabels.forEach { label ->
      if (composeRule.openTaskCardIfPresent(label)) {
        composeRule.waitForText(label, timeoutMillis = 15_000)
        exercisedLabels.add(label)
        pressBack()
        composeRule.waitForHomeReady(homeTaskDescriptionPrefix, timeoutMillis = 15_000)
      }
    }

    assertTrue(
      "No rendered non-Bao capability task cards were available to smoke",
      exercisedLabels.isNotEmpty(),
    )
  }

  private fun exerciseHomeDrawerRoutes() {
    composeRule.openHomeDrawer()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.drawer_settings_label))
      .performClick()
    composeRule.waitForText(R.string.settings_title)
    composeRule.onNodeWithText(composeRule.stringResource(R.string.settings_theme))
      .assertIsDisplayed()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.settings_close))
      .performClick()

    composeRule.openHomeDrawer()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.drawer_models_label))
      .performClick()
    composeRule.waitForContentDescription(R.string.cd_import_model_button)
    composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_import_model_button))
      .assertIsDisplayed()
      .performClick()
    composeRule.waitForText(R.string.cd_import_model_button)
    composeRule.onNodeWithContentDescription(
        composeRule.stringResource(R.string.cd_import_model_from_local_file_button)
      )
      .assertIsDisplayed()
    pressBack()
    composeRule.waitForContentDescription(R.string.cd_import_model_button)
    composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_close_icon))
      .performClick()

    composeRule.openHomeDrawer()
    composeRule.onNodeWithText(composeRule.stringResource(R.string.drawer_notifications_label))
      .performClick()
    composeRule.waitForText(R.string.notifications_title, substring = true)
    composeRule.onNodeWithText(composeRule.stringResource(R.string.notifications_empty_state))
      .assertIsDisplayed()
    composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_close_icon))
      .performClick()
  }
}

private fun collapseSystemShade() {
  InstrumentationRegistry.getInstrumentation()
    .uiAutomation
    .executeShellCommand("cmd statusbar collapse")
    .close()
}

private class NotificationPermissionRule : ExternalResource() {
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

private typealias MainActivityComposeRule =
  AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private fun MainActivityComposeRule.stringResource(@StringRes resId: Int): String =
  activity.getString(resId)

private fun MainActivityComposeRule.stringResource(@StringRes resId: Int, vararg formatArgs: Any): String =
  activity.getString(resId, *formatArgs)

private fun MainActivityComposeRule.audioChipContentDescriptionPrefix(): String {
  val outputMarker = "__OUTPUT_DEVICE__"
  return stringResource(
    R.string.bao_translate_audio_chip_cd_format,
    outputMarker,
    "__INPUT_DEVICE__",
    0,
  ).substringBefore(outputMarker)
}

private fun MainActivityComposeRule.prepareHome(): String {
  collapseSystemShade()

  val taskDescriptionPrefix = "${stringResource(R.string.bao_translate)} task"
  repeat(8) {
    if (clickTextIfPresent(R.string.tos_dialog_accept_and_continue_button_label, 2_000)) {
      return@repeat
    }
    if (clickTextIfPresent(R.string.dismiss, timeoutMillis = 1_000)) {
      return@repeat
    }
    if (isHomeReady(taskDescriptionPrefix)) {
      return taskDescriptionPrefix
    }
  }
  waitForHomeReady(taskDescriptionPrefix, timeoutMillis = 30_000)
  return taskDescriptionPrefix
}

private fun MainActivityComposeRule.isHomeReady(taskDescriptionPrefix: String): Boolean {
  val menuDescription = stringResource(R.string.cd_menu)
  return hasContentDescription(menuDescription) ||
    hasContentDescription(taskDescriptionPrefix, substring = true)
}

private fun MainActivityComposeRule.waitForHomeReady(
  taskDescriptionPrefix: String,
  timeoutMillis: Long = 10_000,
) {
  waitUntil(timeoutMillis) { isHomeReady(taskDescriptionPrefix) }
}

private fun MainActivityComposeRule.openHomeDrawer() {
  waitForContentDescription(R.string.cd_menu)
  onNodeWithContentDescription(stringResource(R.string.cd_menu)).performClick()
  waitForText(R.string.drawer_models_label)
}

private fun MainActivityComposeRule.openTaskByDescription(taskDescriptionPrefix: String) {
  waitForContentDescription(taskDescriptionPrefix, substring = true)
  onNode(
      hasContentDescriptionMatcher(taskDescriptionPrefix, substring = true),
      useUnmergedTree = true,
    )
    .performClick()
}

private fun MainActivityComposeRule.openTaskCardIfPresent(
  label: String,
  timeoutMillis: Long = 4_000,
): Boolean {
  val taskDescription = findTaskCardDescriptionWithModels(label, timeoutMillis) ?: return false
  repeat(3) {
    onNode(
        hasContentDescriptionMatcher(taskDescription, substring = false),
        useUnmergedTree = false,
      )
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    waitForIdle()
    if (waitForContentDescriptionIfPresent(label, timeoutMillis = 5_000)) {
      return true
    }
    if (!hasContentDescription(taskDescription)) {
      return false
    }
    Thread.sleep(500)
  }
  return false
}

private fun MainActivityComposeRule.findTaskCardDescriptionWithModels(
  label: String,
  timeoutMillis: Long,
): String? {
  val positiveModelDescriptions =
    (1..100).map { modelCount ->
      activity.getString(R.string.cd_task_card, label, modelCount)
    }
  fun positiveModelDescription(): String? =
    positiveModelDescriptions.firstOrNull { description ->
      hasDisplayedContentDescription(description)
    }

  val appeared =
    runCatching {
        waitUntil(timeoutMillis = timeoutMillis) {
          positiveModelDescription() != null
        }
      }
      .isSuccess
  if (!appeared) {
    return null
  }
  return positiveModelDescription()
}

private fun MainActivityComposeRule.waitForText(
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForText(stringResource(resId), timeoutMillis, substring)
}

private fun MainActivityComposeRule.waitForText(
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

private fun MainActivityComposeRule.waitForContentDescription(
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForContentDescription(stringResource(resId), timeoutMillis, substring)
}

private fun MainActivityComposeRule.waitForContentDescription(
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

private fun MainActivityComposeRule.waitForContentDescriptionIfPresent(
  contentDescription: String,
  timeoutMillis: Long = 3_000,
  substring: Boolean = false,
): Boolean =
  runCatching { waitForContentDescription(contentDescription, timeoutMillis, substring) }
    .isSuccess

private fun MainActivityComposeRule.clickTextIfPresent(
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

private fun MainActivityComposeRule.waitForBaoTranslateReadyOrSetup(timeoutMillis: Long = 10_000) {
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

private fun MainActivityComposeRule.hasText(text: String, substring: Boolean = false): Boolean =
  runCatching {
      onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun MainActivityComposeRule.hasContentDescription(
  contentDescription: String,
  substring: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithContentDescription(contentDescription, substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun MainActivityComposeRule.hasDisplayedContentDescription(
  contentDescription: String,
  substring: Boolean = false,
): Boolean =
  runCatching {
      onNode(
          hasContentDescriptionMatcher(contentDescription, substring = substring),
          useUnmergedTree = false,
        )
        .assertIsDisplayed()
      true
    }
    .getOrDefault(false)
