package com.google.ai.edge.gallery.customtasks.baotranslate

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WaveformRenderer
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfile
import com.google.ai.edge.gallery.ui.common.EmptyState
import com.google.ai.edge.gallery.ui.common.ErrorCard
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.isReducedMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaoTranslateScreen(
  viewModel: BaoTranslateViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val windowInfo = LocalWindowInfo.current
  val density = androidx.compose.ui.platform.LocalDensity.current
  val isTablet = with(density) { windowInfo.containerSize.width.toDp() > Dimensions.Breakpoint.tablet }
  val context = LocalContext.current
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth
  val listState = rememberLazyListState()

  var showSettings by remember { mutableStateOf(false) }
  var showEnrollment by remember { mutableStateOf(false) }
  var showConversationMode by remember { mutableStateOf(false) }
  var showFaceToFace by remember { mutableStateOf(false) }
  var showAudioPicker by remember { mutableStateOf(false) }
  var showWelcome by remember { mutableStateOf(false) }
  var enrollmentState by remember { mutableStateOf(EnrollmentState.READY) }

  LaunchedEffect(uiState.welcomeDismissed) {
    showWelcome = !uiState.welcomeDismissed
  }

  val participants by viewModel.bleManager.participants.collectAsState()
  val discoveredPeers by viewModel.bleManager.discoveredPeers.collectAsState()
  val isScanning by viewModel.bleManager.isScanning.collectAsState()
  val connectionState by viewModel.bleManager.connectionState.collectAsState()

  val defaultVoiceName = stringResource(R.string.bao_translate_default_voice_name)
  val micPermissionDenied = stringResource(R.string.bao_translate_permission_mic_denied)
  val bluetoothPermissionDenied = stringResource(R.string.bao_translate_permission_nearby_denied)
  val voiceProfile = remember(defaultVoiceName, uiState.voiceProfileEnrolled, uiState.voiceProfilePath) {
    if (uiState.voiceProfileEnrolled && uiState.voiceProfilePath != null) {
      uiState.voiceProfilePath?.let { VoiceProfile(name = defaultVoiceName, wavPath = it) }
    } else null
  }

  LaunchedEffect(Unit) { viewModel.initializeModels() }
  LaunchedEffect(uiState.transcripts.size) {
    if (uiState.transcripts.isNotEmpty()) {
      listState.animateScrollToItem(uiState.transcripts.size - 1)
    }
  }

  val micPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      viewModel.startRecording()
    } else {
      viewModel.setErrorMessage(micPermissionDenied)
    }
  }

  var pendingBluetoothAction by remember { mutableStateOf<(() -> Unit)?>(null) }
  val bluetoothPermissions = remember {
    buildList {
      add(Manifest.permission.BLUETOOTH_SCAN)
      add(Manifest.permission.BLUETOOTH_CONNECT)
      add(Manifest.permission.BLUETOOTH_ADVERTISE)
      // Nearby Connections discovery also needs NEARBY_WIFI_DEVICES on API 33+, or FINE_LOCATION on
      // API 31-32 (the only sub-33 levels this app supports, minSdk = 31).
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
      } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }.toTypedArray()
  }
  val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { grants ->
    if (bluetoothPermissions.all { grants[it] == true }) {
      pendingBluetoothAction?.invoke()
    } else {
      viewModel.setErrorMessage(bluetoothPermissionDenied)
    }
    pendingBluetoothAction = null
  }

  fun startRecordingWithPermission() {
    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    if (hasPermission) {
      viewModel.startRecording()
    } else {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun runWithBluetoothPermissions(action: () -> Unit) {
    val hasPermissions = bluetoothPermissions.all { permission ->
      ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    if (hasPermissions) {
      action()
    } else {
      pendingBluetoothAction = action
      bluetoothPermissionLauncher.launch(bluetoothPermissions)
    }
  }

  if (showSettings) {
    BaoTranslateSettingsSheet(
      onDismiss = { showSettings = false },
      voiceProfile = voiceProfile,
      currentAudioDevice = uiState.currentAudioDevice,
      preferredInputDevice = uiState.preferredInputDevice,
      sttModel = uiState.sttModel,
      translationModel = uiState.translationModel,
      wifiOnlyDownloads = uiState.wifiOnlyDownloads,
      storageBreakdown = uiState.storageBreakdown,
      modelStatuses = uiState.modelStatuses,
      onSttModelChange = viewModel::setSttModel,
      onTranslationModelChange = viewModel::setTranslationModel,
      onWifiOnlyChange = viewModel::setWifiOnly,
      onReRecordVoice = { showSettings = false; showEnrollment = true; enrollmentState = EnrollmentState.READY },
      onDeleteVoiceProfile = viewModel::deleteVoiceProfile,
      onDeleteModels = { viewModel.deleteAllModels(); showSettings = false },
      onDownloadModel = viewModel::downloadModel,
      onDeleteModel = viewModel::deleteModel,
      onOpenAudioPicker = {
        viewModel.refreshAudioDevice()
        showSettings = false
        showAudioPicker = true
      },
      isTablet = isTablet,
    )
  }

  if (showEnrollment) {
    VoiceEnrollmentSheet(
      onDismiss = { showEnrollment = false; enrollmentState = EnrollmentState.READY },
      onEnrollComplete = { samples, sampleRate -> viewModel.startEnrollmentRecording(samples, sampleRate) },
      enrollmentState = enrollmentState,
      onEnrollmentStateChange = { enrollmentState = it },
      sourceLanguage = uiState.sourceLanguage,
      isTablet = isTablet,
    )
  }

  if (showAudioPicker) {
    AudioDevicePickerSheet(
      currentDevice = uiState.currentAudioDevice,
      availableOutputs = uiState.availableAudioDevices,
      availableInputs = uiState.availableInputDevices,
      preferredInput = uiState.preferredInputDevice,
      onSelectOutput = { device ->
        viewModel.selectAudioDevice(device)
      },
      onSelectInput = { device ->
        viewModel.selectInputDevice(device)
      },
      onDismiss = { showAudioPicker = false },
      onOpenBluetoothSettings = {
        val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        context.startActivity(intent)
      },
    )
  }

  if (showWelcome) {
    BaoTranslateWelcomeSheet(
      onDismiss = {
        showWelcome = false
        viewModel.dismissWelcome()
      },
      isTablet = isTablet,
    )
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(R.string.bao_translate_title),
            style = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
          )
        },
        actions = {
          val conversationModeDesc = stringResource(R.string.bao_translate_conversation_mode)
          val settingsDesc = stringResource(R.string.settings_title)
          val clearDesc = stringResource(R.string.cd_bao_translate_clear)
          // Clear the current conversation transcript (local-only). Shown only when there is
          // something to clear and the user is on the translate view, not in conversation pairing.
          if (uiState.modelsReady && !showConversationMode && uiState.transcripts.isNotEmpty()) {
            IconButton(
              onClick = { viewModel.clearTranscripts() },
              modifier = Modifier.semantics { contentDescription = clearDesc },
            ) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
            }
          }
          if (uiState.modelsReady) {
            val conversationTooltipState = rememberTooltipState(isPersistent = true)
            val conversationTooltipScope = rememberCoroutineScope()
            TooltipBox(
              positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
              tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_tooltip_conversation)) } },
              state = conversationTooltipState,
            ) {
              IconButton(
                onClick = { showConversationMode = !showConversationMode },
                modifier = Modifier
                  .semantics { contentDescription = conversationModeDesc }
                  .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                      conversationTooltipScope.launch { conversationTooltipState.show() }
                    }
                  },
              ) {
                Icon(
                  imageVector = Icons.Default.People,
                  contentDescription = null,
                  // Primary tint while the pairing sheet is open OR a peer is actively connected, so
                  // the user can see they are in a live conversation after returning to the main view.
                  tint = if (showConversationMode || connectionState == ConnectionState.CONNECTED) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                )
              }
            }
          }
          if (uiState.modelsReady) {
            val faceToFaceDesc = stringResource(R.string.bao_face_to_face_mode)
            IconButton(
              onClick = {
                if (showFaceToFace) {
                  showFaceToFace = false
                  viewModel.setFaceToFaceMode(false)
                } else {
                  showConversationMode = false
                  showFaceToFace = true
                  viewModel.setFaceToFaceMode(true)
                }
              },
              modifier = Modifier.semantics { contentDescription = faceToFaceDesc },
            ) {
              Icon(
                imageVector = Icons.Default.SwapVert,
                contentDescription = null,
                tint = if (showFaceToFace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
            }
          }
          IconButton(
            onClick = {
              viewModel.refreshAudioDevice()
              showSettings = true
            },
            modifier = Modifier.semantics { contentDescription = settingsDesc },
          ) {
            Icon(imageVector = Icons.Default.Settings, contentDescription = null)
          }
        },
        modifier = Modifier.widthIn(max = maxWidth),
      )
    },
    floatingActionButton = {
      if (!showConversationMode && !showFaceToFace && uiState.modelsReady) {
        val startDesc = stringResource(R.string.cd_bao_translate_start)
        val stopDesc = stringResource(R.string.cd_bao_translate_stop)
        val canUseMic = uiState.modelsReady &&
          !uiState.isInitializing &&
          !uiState.isStartingRecording &&
          !uiState.isProcessing &&
          !uiState.isSpeaking &&
          uiState.pipelineStatus !is PipelineStatus.ModelsNotReady
        val recordingControlActive = uiState.isRecording || uiState.isStartingRecording
        val tooltipState = rememberTooltipState(isPersistent = true)
        val tooltipScope = rememberCoroutineScope()
        TooltipBox(
          positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
          tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_tooltip_record)) } },
          state = tooltipState,
        ) {
          Box(
            modifier = Modifier
              .size(Dimensions.Component.fabSize)
              .clip(CircleShape)
              .background(
                when {
                  recordingControlActive -> MaterialTheme.customColors.recordButtonBgColor
                  canUseMic -> MaterialTheme.colorScheme.primary
                  else -> MaterialTheme.colorScheme.surfaceVariant
                }
              )
              .combinedClickable(
                onClick = {
                  when {
                    recordingControlActive -> viewModel.stopRecording()
                    canUseMic -> startRecordingWithPermission()
                  }
                },
                onLongClick = {
                  tooltipScope.launch { tooltipState.show() }
                }
              )
              .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                  tooltipScope.launch { tooltipState.show() }
                }
              }
              .semantics {
                contentDescription = if (recordingControlActive) stopDesc else startDesc
              },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = if (recordingControlActive) Icons.Default.Stop else Icons.Default.Mic,
              contentDescription = null,
              modifier = Modifier.size(Dimensions.Icon.large),
              tint = when {
                recordingControlActive -> MaterialTheme.colorScheme.onPrimary
                canUseMic -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
            )
          }
        }
      }
    },
  ) { paddingValues ->
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.TopCenter) {
      Column(modifier = Modifier.fillMaxSize().widthIn(max = maxWidth).padding(horizontal = if (isTablet) Dimensions.Spacing.xl else Dimensions.Spacing.medium)) {
        AudioDeviceChip(
          currentDevice = uiState.currentAudioDevice,
          availableOutputs = uiState.availableAudioDevices,
          availableInputs = uiState.availableInputDevices,
          preferredInput = uiState.preferredInputDevice,
          routingStatus = uiState.routingStatus,
          onOpenPicker = {
            viewModel.refreshAudioDevice()
            showAudioPicker = true
          },
          modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.xs),
        )
        uiState.errorMessage?.let { error ->
          ErrorCard(
            message = error,
            onDismiss = viewModel::retryLastAction,
            dismissLabel = stringResource(R.string.bao_translate_error_retry),
          )
        }

        if (uiState.isInitializing) {
          Card(modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.small), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(modifier = Modifier.padding(Dimensions.Spacing.medium), verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small)) {
                CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.small), strokeWidth = Dimensions.Stroke.thin)
                Text(stringResource(R.string.bao_translate_initializing), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
              }
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
          }
        }

        if (!uiState.modelsReady || uiState.pipelineStatus is PipelineStatus.ModelsNotReady) {
          RequiredModelsPanel(
            uiState = uiState,
            onDownloadModel = viewModel::downloadModel,
            onDownloadAll = viewModel::downloadRequiredModels,
            onInitializeModels = viewModel::initializeModels,
            modifier = Modifier.weight(1f).fillMaxWidth(),
          )
        } else if (showConversationMode) {
          ConversationModeScreen(
            localParticipant = uiState.localParticipant,
            remoteParticipants = participants,
            discoveredPeers = discoveredPeers,
            isScanning = isScanning,
            connectionState = connectionState,
            connectingPeers = viewModel.bleManager.connectingPeers.collectAsState().value,
            currentAudioDevice = uiState.currentAudioDevice,
            onScanDevices = { runWithBluetoothPermissions { viewModel.bleManager.startConversationDiscovery() } },
            onStopScan = { viewModel.bleManager.stopConversationDiscovery() },
            onConnectDevice = { address -> runWithBluetoothPermissions { viewModel.bleManager.connectToDevice(address) } },
            onDisconnectDevice = { address -> viewModel.disconnectPeer(address) },
            onStartConversation = { showConversationMode = false },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            isTablet = isTablet,
          )
        } else if (showFaceToFace) {
          FaceToFaceConversationScreen(
            uiState = uiState,
            isRecording = uiState.isRecording || uiState.isStartingRecording,
            onSetLanguageA = viewModel::setSourceLanguage,
            onSetLanguageB = viewModel::setTargetLanguage,
            onToggleRecording = {
              if (uiState.isRecording || uiState.isStartingRecording) viewModel.stopRecording()
              else startRecordingWithPermission()
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            isTablet = isTablet,
          )
        } else {
          if (uiState.pipelineStatus is PipelineStatus.Error) {
            val errorMsg = (uiState.pipelineStatus as PipelineStatus.Error).message
            ErrorCard(
              message = errorMsg,
              onDismiss = viewModel::clearError,
              dismissLabel = stringResource(R.string.bao_translate_dismiss),
            )
          }

          LanguageSelectionBar(
            sourceLanguage = uiState.sourceLanguage,
            targetLanguage = uiState.targetLanguage,
            onSourceLanguageChange = viewModel::setSourceLanguage,
            onTargetLanguageChange = viewModel::setTargetLanguage,
            onSwapLanguages = viewModel::swapLanguages,
            detectedLanguage = uiState.detectedLanguage,
            voiceProfileEnrolled = uiState.voiceProfileEnrolled,
            onEnrollVoice = { showEnrollment = true; enrollmentState = EnrollmentState.READY },
            isTablet = isTablet,
          )

          TranscriptList(transcripts = uiState.transcripts, modifier = Modifier.weight(1f).fillMaxWidth(), listState = listState, isTablet = isTablet)
          StatusBar(isProcessing = uiState.isProcessing, isSpeaking = uiState.isSpeaking, isTablet = isTablet)
        }
      }

      if (uiState.isRecording) {
        RecordingOverlay(
          amplitudes = uiState.amplitudes,
          elapsedSeconds = uiState.elapsedSeconds,
          liveTranslationPreview = uiState.liveTranslationPreview,
          isTablet = isTablet,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}
