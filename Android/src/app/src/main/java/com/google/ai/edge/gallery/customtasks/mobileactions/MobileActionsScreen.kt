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

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import com.google.ai.edge.gallery.common.BaoLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyLoading
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMAScreen"

data class PromptTemplate(@StringRes val labelResId: Int, @StringRes val promptResId: Int)

internal val PROMPT_TEMPLATES =
  listOf(
    PromptTemplate(
      labelResId = R.string.prompt_template_label_flash_on,
      promptResId = R.string.prompt_template_text_flash_on,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_flash_off,
      promptResId = R.string.prompt_template_text_flash_off,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_create_contact,
      promptResId = R.string.prompt_template_text_create_contact,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_send_email,
      promptResId = R.string.prompt_template_text_send_email,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_create_calendar_event,
      promptResId = R.string.prompt_template_text_create_calendar_event,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_show_location_on_map,
      promptResId = R.string.prompt_template_text_show_location_on_map,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_open_wifi_settings,
      promptResId = R.string.prompt_template_text_open_wifi_settings,
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_capture_photo,
      promptResId = R.string.prompt_template_text_capture_photo,
    ),
  )

internal data class SampleActionItem(@StringRes val labelResId: Int, val icon: ImageVector)

internal val SAMPLE_ACTION_ITEMS =
  listOf(
    SampleActionItem(
      labelResId = R.string.prompt_template_label_flash_on_off,
      icon = Icons.Outlined.FlashlightOn,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_create_contact,
      icon = Icons.Outlined.PersonAdd,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_send_email,
      icon = Icons.Outlined.Email,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_create_calendar_event,
      icon = Icons.Outlined.CalendarMonth,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_show_location_on_map,
      icon = Icons.Outlined.Map,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_open_wifi_settings,
      icon = Icons.Outlined.Wifi,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_capture_photo,
      icon = Icons.Outlined.PhotoCamera,
    ),
  )

internal data class MobileActionsTab(@StringRes val labelResId: Int, val icon: ImageVector)

internal val TABS =
  listOf(
    MobileActionsTab(
      labelResId = R.string.mobile_actions_tab_model_response,
      icon = Icons.AutoMirrored.Rounded.Article,
    ),
    MobileActionsTab(labelResId = R.string.mobile_actions_tab_function_called, icon = Icons.Rounded.Functions),
  )

/**
 * A Composable function that displays the MobileActions screen.
 *
 * This screen allows users to interact with an AI model using voice or text input to perform
 * various actions on their device.
 */
@Composable
fun MobileActionsScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  mobileActionsViewModel: MobileActionsViewModel = hiltViewModel(),
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  tools: List<ToolProvider>,
  onProcessingStarted: () -> Unit,
) {
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current

  CaptureLauncherEffect(coordinator = mobileActionsViewModel.captureCoordinator)

  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        recordAudioPermissionGranted = true
      }
    }

  LaunchedEffect(Unit) {
    when (PackageManager.PERMISSION_GRANTED) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        recordAudioPermissionGranted = true
      }

      else -> {
        recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  if (recordAudioPermissionGranted) {
    Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()
    ) {
      MainUi(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        tools = tools,
        bottomPadding = bottomPadding,
        viewModel = mobileActionsViewModel,
        curActions = curActions,
        setAppBarControlsDisabled = setAppBarControlsDisabled,
        onProcessingStarted = onProcessingStarted,
      )
    }
  } else {
    PermissionDeniedUi(
      onRetry = { recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  viewModel: MobileActionsViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  onProcessingStarted: () -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initialModelConfigValues = remember { model.configValues }
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  var curAmplitude by remember { mutableIntStateOf(0) }
  var clearInputTextTrigger by remember { mutableLongStateOf(0L) }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var doneGeneratingResponse by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val focusManager = LocalFocusManager.current
  val resources = LocalResources.current
  val taskColor = getTaskBgGradientColors(task = task)[1]

  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

  LaunchedEffect(model.configValues) {
    if (model.configValues != initialModelConfigValues) {
      BaoLog.d(TAG, "model config values changed.")
      modelManagerViewModel.setInitializationStatus(
        model = model,
        status = ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED),
      )
      viewModel.reset()
    }
  }

  DisposableEffect(Unit) { onDispose { viewModel.cleanUp() } }

  val initStatus = modelManagerUiState.modelInitializationStatus[model.name]
  val initError = initStatus?.status == ModelInitializationStatusType.ERROR

  if (initError) {
    ModelInitErrorUi(
      context = context,
      task = task,
      model = model,
      initStatus = initStatus,
      modelManagerViewModel = modelManagerViewModel,
    )
  } else if (!modelManagerUiState.isModelInitialized(model = model)) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .semantics { liveRegion = LiveRegionMode.Polite },
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = Dimensions.Stroke.medium,
        modifier = Modifier.size(Dimensions.Icon.medium),
      )
      Spacer(modifier = Modifier.size(Dimensions.Spacing.medium))
      Text(
        text = stringResource(R.string.mobile_actions_loading_model),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  else {
    val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

    val send: (String) -> Unit = { text ->
      sendPrompt(
        text = text,
        task = task,
        model = model,
        tools = tools,
        viewModel = viewModel,
        curActions = curActions,
        scope = scope,
        snackbarHostState = snackbarHostState,
        focusManager = focusManager,
        context = context,
        resources = resources,
        noFunctionCallSnackbarMessage = noFunctionCallSnackbarMessage,
        onSelectedTabReset = { selectedTabIndex = 0 },
        onClearInputTrigger = { clearInputTextTrigger = System.currentTimeMillis() },
        onProcessingStarted = onProcessingStarted,
        onDoneGenerating = { doneGeneratingResponse = true },
        onError = { error ->
          doneGeneratingResponse = true
          errorDialogContent = error
          showErrorDialog = true
        },
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(
              bottom =
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else Dimensions.Spacing.small
            )
            .imePadding()
      ) {
        if (uiState.showWelcomeMessage) {
          WelcomeSection(task = task)
        }
        else {
          Box(
            modifier =
              Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.CenterStart,
          ) {
            Text(
              uiState.userPrompt,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.medium),
            )
          }

          if (uiState.processing) {
            Box(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(Dimensions.Spacing.medium)
                .semantics { liveRegion = LiveRegionMode.Assertive },
              contentAlignment = Alignment.TopStart,
            ) {
              MessageBodyLoading()
            }
          }
          else {
            ResponseTabs(
              task = task,
              uiState = uiState,
              doneGeneratingResponse = doneGeneratingResponse,
            )
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(top = Dimensions.Spacing.small),
          verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
        ) {
          PromptTemplateBar(processing = uiState.processing, onSend = send)

          Row(
            modifier = Modifier.padding(horizontal = Dimensions.Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
          ) {
            TextAndVoiceInput(
              task = task,
              processing = uiState.processing,
              holdToDictateViewModel = holdToDictateViewModel,
              onDone = { text -> send(text) },
              onAmplitudeChanged = { curAmplitude = it },
              clearTextTrigger = clearInputTextTrigger,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      AnimatedVisibility(
        holdToDictateUiState.recognizing,
        enter = fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
        exit =
          fadeOut(
            animationSpec =
              tween(durationMillis = 100, easing = FastOutSlowInEasing, delayMillis = 300)
          ),
      ) {
        VoiceRecognizerOverlay(
          task = task,
          viewModel = holdToDictateViewModel,
          curAmplitude = curAmplitude,
          bottomPadding = bottomPadding,
        )
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.padding(bottom = bottomPadding + 100.dp).align(Alignment.BottomCenter),
      )
    }
  }

  if (showErrorDialog) {
    ErrorResetDialog(
      task = task,
      errorContent = errorDialogContent,
      context = context,
      viewModel = viewModel,
      model = model,
      tools = tools,
      modelManagerViewModel = modelManagerViewModel,
      onDismiss = {
        showErrorDialog = false
        errorDialogContent = ""
      },
      onError = {
        errorDialogContent = it
        showErrorDialog = true
      },
    )
  }
}
