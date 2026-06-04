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
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkRouteSmokeTest {
  private val composeRule = createEmptyComposeRule()

  @get:Rule
  val ruleChain: TestRule =
    RuleChain.outerRule(DeepLinkPermissionRule())
      .around(composeRule)

  @Test
  fun taskDeepLinkOpensModelDetailRoute() {
    launchDeepLink("com.google.ai.edge.gallery://llm_chat?query=smoke") { context ->
      composeRule.dismissStartupDialogs(context)
      composeRule.waitForText(context, R.string.task_label_ai_chat, timeoutMillis = 60_000)
      composeRule.onNodeWithText(
          context.getString(R.string.task_label_ai_chat),
          useUnmergedTree = true,
        )
        .assertIsDisplayed()
      composeRule.waitForContentDescription(context, R.string.cd_navigate_back_icon)
      composeRule.waitForModelDetailSurface(context)
    }
  }

  @Test
  fun benchmarkDeepLinkOpensBenchmarkRoute() {
    launchDeepLink("com.google.ai.edge.gallery://benchmark") { context ->
      composeRule.dismissStartupDialogs(context)
      composeRule.waitForText(context, R.string.benchmark_model, timeoutMillis = 60_000)
      composeRule.onNodeWithText(
          context.getString(R.string.benchmark_model),
          useUnmergedTree = true,
        )
        .assertIsDisplayed()
      composeRule.waitForContentDescription(context, R.string.cd_navigate_back_icon)
      composeRule.waitForText(context, R.string.view_results)
      composeRule.waitForText(context, R.string.benchmark)
    }
  }

  private fun launchDeepLink(uri: String, assertions: (Context) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent =
      Intent(Intent.ACTION_VIEW, uri.toUri(), context, MainActivity::class.java).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
      }

    ActivityScenario.launch<MainActivity>(intent).use {
      assertions(context)
    }
  }
}

private class DeepLinkPermissionRule : ExternalResource() {
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
    instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
  }
}

private fun ComposeTestRule.dismissStartupDialogs(context: Context) {
  repeat(6) {
    clickTextIfPresent(
      context.getString(R.string.tos_dialog_accept_and_continue_button_label),
      1_500,
    )
    clickTextIfPresent(context.getString(R.string.dismiss), 1_000)
  }
}

private fun ComposeTestRule.waitForModelDetailSurface(context: Context) {
  val downloadAction = context.getString(R.string.download)
  val downloadAndTryAction = context.getString(R.string.download_and_try_it)
  val notDownloaded = context.getString(R.string.model_not_downloaded_msg)
  val promptInput = context.getString(R.string.cd_prompt_input_text_field)
  val chatPanel = context.getString(R.string.cd_chat_panel)

  waitUntil(timeoutMillis = 45_000) {
    hasText(downloadAction) ||
      hasText(downloadAndTryAction, substring = true) ||
      hasText(notDownloaded, substring = true) ||
      hasContentDescription(promptInput) ||
      hasContentDescription(chatPanel)
  }
}

private fun ComposeTestRule.waitForText(
  context: Context,
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForText(context.getString(resId), timeoutMillis, substring)
}

private fun ComposeTestRule.waitForText(
  text: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) {
    hasText(text = text, substring = substring)
  }
}

private fun ComposeTestRule.waitForContentDescription(
  context: Context,
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForContentDescription(context.getString(resId), timeoutMillis, substring)
}

private fun ComposeTestRule.waitForContentDescription(
  contentDescription: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) {
    hasContentDescription(contentDescription = contentDescription, substring = substring)
  }
}

private fun ComposeTestRule.clickTextIfPresent(
  text: String,
  timeoutMillis: Long = 3_000,
): Boolean {
  val appeared = runCatching { waitForText(text, timeoutMillis) }.isSuccess
  if (appeared) {
    onNodeWithText(text).performClick()
    waitForIdle()
  }
  return appeared
}

private fun ComposeTestRule.hasText(text: String, substring: Boolean = false): Boolean =
  runCatching {
      onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun ComposeTestRule.hasContentDescription(
  contentDescription: String,
  substring: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithContentDescription(contentDescription, substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)
