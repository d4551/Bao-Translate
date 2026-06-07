package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfile
import com.google.ai.edge.gallery.ui.theme.Dimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaoTranslateSettingsSheet(
  onDismiss: () -> Unit,
  voiceProfile: VoiceProfile?,
  currentAudioDevice: AudioDevice,
  preferredInputDevice: AudioDevice.BluetoothHeadset?,
  sttModel: String,
  translationModel: String,
  wifiOnlyDownloads: Boolean,
  storageBreakdown: Map<String, Long>,
  modelStatuses: Map<String, ModelStatus>,
  onSttModelChange: (String) -> Unit,
  onTranslationModelChange: (String) -> Unit,
  onWifiOnlyChange: (Boolean) -> Unit,
  onReRecordVoice: () -> Unit,
  onDeleteVoiceProfile: () -> Unit,
  onDeleteModels: () -> Unit,
  onDownloadModel: (String) -> Unit,
  onDeleteModel: (String) -> Unit,
  onOpenAudioPicker: () -> Unit,
  isTablet: Boolean = false,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  val totalStorageMb = storageBreakdown.values.sum().toFloat() / (1024f * 1024f)
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = Modifier.focusRequester(focusRequester),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = maxWidth)
        .padding(Dimensions.Spacing.large)
        .verticalScroll(rememberScrollState()),
    ) {
      Text(
        text = stringResource(R.string.bao_translate_settings),
        style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.large))

      SectionHeader(stringResource(R.string.bao_translate_settings_downloads))

      ModelDownloadSection(
        modelStatuses = modelStatuses,
        storageBreakdown = storageBreakdown,
        onDownloadModel = onDownloadModel,
        onDeleteModel = onDeleteModel,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.bao_translate_wifi_only),
              style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
              checked = wifiOnlyDownloads,
              onCheckedChange = onWifiOnlyChange,
            )
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = Dimensions.Spacing.small))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.bao_translate_storage_format, totalStorageMb),
              style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDeleteModels) {
              Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.cd_delete_icon),
                modifier = Modifier.height(Dimensions.Spacing.medium),
              )
              Spacer(Modifier.width(Dimensions.Spacing.xs))
              Text(stringResource(R.string.bao_translate_delete_all_models))
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_models))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
          SettingsRadioGroup(
            label = stringResource(R.string.bao_translate_stt_model),
            options = listOf(
              stringResource(R.string.bao_translate_stt_model_option_whisper) to "whisper_base",
            ),
            selectedOption = sttModel,
            onOptionSelected = onSttModelChange,
          )

          HorizontalDivider(modifier = Modifier.padding(vertical = Dimensions.Spacing.small))

          SettingsRadioGroup(
            label = stringResource(R.string.bao_translate_translation_model),
            options = listOf(
              stringResource(R.string.bao_translate_translation_model_option_qwen25) to "qwen25_1b",
              stringResource(R.string.bao_translate_translation_model_option_gemma4_e2b) to "gemma4_e2b",
            ),
            selectedOption = translationModel,
            onOptionSelected = onTranslationModelChange,
            enabledOptions = buildSet {
              add("qwen25_1b")
              if (modelStatuses["gemma4_e2b"] == ModelStatus.Ready) add("gemma4_e2b")
            },
            optionDescriptions = if (modelStatuses["gemma4_e2b"] == ModelStatus.Ready) {
              emptyMap()
            } else {
              mapOf("gemma4_e2b" to stringResource(R.string.bao_translate_model_download_first))
            },
          )
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_voice))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(
          modifier = Modifier.padding(Dimensions.Spacing.medium),
          verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
          ) {
            val settingsEnrollVoiceContentDescription =
              stringResource(R.string.cd_bao_translate_settings_enroll_voice)
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xxs)) {
              Text(
                text = stringResource(R.string.bao_translate_your_voice_profile),
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = if (voiceProfile != null) {
                  stringResource(R.string.bao_translate_voice_profile_saved)
                } else {
                  stringResource(R.string.bao_translate_voice_profile_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End,
            ) {
              if (voiceProfile != null) {
                TextButton(onClick = onDeleteVoiceProfile) {
                  Text(stringResource(R.string.bao_translate_unenroll_voice))
                }
              }
              TextButton(
                onClick = onReRecordVoice,
                modifier = Modifier.semantics {
                  if (voiceProfile == null) {
                    contentDescription = settingsEnrollVoiceContentDescription
                  }
                },
              ) {
                Text(
                  if (voiceProfile != null) {
                    stringResource(R.string.bao_translate_re_record_voice)
                  } else {
                    stringResource(R.string.bao_translate_enroll_voice)
                  },
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_audio))

      AudioDeviceSection(
        currentDevice = currentAudioDevice,
        preferredInputDevice = preferredInputDevice,
        onOpenAudioPicker = onOpenAudioPicker,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.xl))
    }
  }
}

@Composable
private fun ModelDownloadSection(
  modelStatuses: Map<String, ModelStatus>,
  storageBreakdown: Map<String, Long>,
  onDownloadModel: (String) -> Unit,
  onDeleteModel: (String) -> Unit,
) {
	  val allModels = BaoTranslateModelManager.ALL_MODELS
	  val requiredModels = BaoTranslateModelManager.REQUIRED_MODEL_IDS.mapNotNull { id ->
	    allModels.firstOrNull { it.id == id }
	  }
	  val optionalModels = allModels.filter { it.id !in BaoTranslateModelManager.REQUIRED_MODEL_IDS }
	  val anyBusy = modelStatuses.values.any { it is ModelStatus.Downloading || it is ModelStatus.Extracting }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Column(
      modifier = Modifier.padding(Dimensions.Spacing.medium),
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Text(
        text = stringResource(R.string.bao_translate_required_models),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      requiredModels.forEach { model ->
        val status = modelStatuses[model.id] ?: ModelStatus.NotDownloaded
        val usedBytes = storageBreakdown[model.id] ?: 0L
        val usedMb = usedBytes.toFloat() / (1024f * 1024f)

	        ModelDownloadCard(
	          modelInfo = model,
	          status = status,
	          usedMb = usedMb,
	          onDownload = { onDownloadModel(model.id) },
	          onDelete = { onDeleteModel(model.id) },
	          downloadDisabled = anyBusy,
	        )
      }

      if (optionalModels.isNotEmpty()) {
        HorizontalDivider()
        Text(
          text = stringResource(R.string.bao_translate_optional_models),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      optionalModels.forEach { model ->
        val status = modelStatuses[model.id] ?: ModelStatus.NotDownloaded
        val usedBytes = storageBreakdown[model.id] ?: 0L
        val usedMb = usedBytes.toFloat() / (1024f * 1024f)

	        ModelDownloadCard(
	          modelInfo = model,
	          status = status,
	          usedMb = usedMb,
	          onDownload = { onDownloadModel(model.id) },
	          onDelete = { onDeleteModel(model.id) },
	          downloadDisabled = anyBusy,
	        )
      }
    }
  }
}

@Composable
private fun ModelDownloadCard(
  modelInfo: ModelInfo,
  status: ModelStatus,
	  usedMb: Float,
	  onDownload: () -> Unit,
	  onDelete: () -> Unit,
	  downloadDisabled: Boolean,
	) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(modelInfo.displayNameRes),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
      )

      when (status) {
        is ModelStatus.Downloading -> {
          Spacer(modifier = Modifier.height(Dimensions.Spacing.xs))
          LinearProgressIndicator(
            progress = { status.progress },
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            text = stringResource(
              R.string.bao_translate_download_progress_format,
              status.bytesReceived.toFloat() / (1024 * 1024),
              status.totalBytes.toFloat() / (1024 * 1024),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is ModelStatus.Extracting -> {
          Spacer(modifier = Modifier.height(Dimensions.Spacing.xs))
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(Dimensions.Spacing.small), strokeWidth = Dimensions.Stroke.thin)
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
            Text(
              text = stringResource(R.string.bao_translate_extracting),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is ModelStatus.Error -> {
          Spacer(modifier = Modifier.height(Dimensions.Spacing.xs))
          Text(
            text = status.reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
          )
        }
        else -> {
          Text(
            text = if (status == ModelStatus.Ready && usedMb > 0f && usedMb < 1f) {
              stringResource(R.string.bao_translate_model_size_under_one_mb)
            } else {
              stringResource(
                R.string.bao_translate_model_size_format,
                if (status == ModelStatus.Ready) usedMb.toLong() else modelInfo.estimatedSizeMb,
              )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (downloadDisabled && (status is ModelStatus.NotDownloaded || status is ModelStatus.Error)) {
        Text(
          text = stringResource(R.string.bao_translate_download_busy),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    when (status) {
	      is ModelStatus.Ready -> TextButton(onClick = onDelete) {
	        Text(stringResource(R.string.bao_translate_delete))
	      }
	      is ModelStatus.NotDownloaded -> {
	        TextButton(onClick = onDownload, enabled = !downloadDisabled) {
	          Text(stringResource(R.string.bao_translate_download))
	        }
	      }
	      is ModelStatus.Error -> {
	        TextButton(onClick = onDownload, enabled = !downloadDisabled) {
	          Text(stringResource(R.string.bao_translate_retry))
	        }
	      }
      is ModelStatus.Downloading, is ModelStatus.Extracting -> {
        CircularProgressIndicator(
          modifier = Modifier.size(Dimensions.Icon.medium),
          strokeWidth = Dimensions.Stroke.thin,
        )
      }
    }
  }
}

@Composable
private fun AudioDeviceSection(
  currentDevice: AudioDevice,
  preferredInputDevice: AudioDevice.BluetoothHeadset?,
  onOpenAudioPicker: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
      Text(
        text = stringResource(R.string.bao_translate_settings_audio),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

      Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
        AudioRouteSummaryRow(
          label = stringResource(R.string.bao_translate_audio_output_label),
          value = audioDeviceName(currentDevice),
        )
        AudioRouteSummaryRow(
          label = stringResource(R.string.bao_translate_audio_input_label),
          value = preferredInputDevice?.let { audioDeviceName(it) }
            ?: stringResource(R.string.bao_translate_audio_input_default_short),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onOpenAudioPicker) {
            Text(stringResource(R.string.bao_translate_change_audio_devices))
          }
        }
      }
    }
  }
}

@Composable
private fun AudioRouteSummaryRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun audioDeviceName(device: AudioDevice): String = when (device) {
  is AudioDevice.BluetoothHeadset -> "${device.name} ${transportShortLabel(device.transport)}"
  is AudioDevice.WiredHeadset -> device.name
  AudioDevice.Speaker -> stringResource(R.string.bao_translate_phone_speaker)
}

@Composable
private fun transportShortLabel(transport: BluetoothTransport): String = when (transport) {
  BluetoothTransport.BLE_AUDIO -> stringResource(R.string.bao_translate_audio_transport_ble_short)
  BluetoothTransport.A2DP -> stringResource(R.string.bao_translate_audio_transport_a2dp_short)
  BluetoothTransport.SCO -> stringResource(R.string.bao_translate_audio_transport_sco_short)
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = Dimensions.Spacing.small),
  )
}

@Composable
private fun SettingsRadioGroup(
  label: String,
  options: List<Pair<String, String>>,
  selectedOption: String,
  onOptionSelected: (String) -> Unit,
  enabledOptions: Set<String> = options.map { it.second }.toSet(),
  optionDescriptions: Map<String, String> = emptyMap(),
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Medium,
    )

    Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

    Column(modifier = Modifier.selectableGroup()) {
      options.forEach { (displayName, value) ->
        val enabled = value in enabledOptions
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .selectable(
              selected = selectedOption == value,
              onClick = { onOptionSelected(value) },
              enabled = enabled,
              role = Role.RadioButton,
            )
            .padding(vertical = Dimensions.Spacing.xs),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selectedOption == value,
            onClick = null,
            enabled = enabled,
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Column {
            Text(
              text = displayName,
              style = MaterialTheme.typography.bodyMedium,
              color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            optionDescriptions[value]?.let { description ->
              Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
