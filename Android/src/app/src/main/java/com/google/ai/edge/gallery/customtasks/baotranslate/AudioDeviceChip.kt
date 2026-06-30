package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioInputOption
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.isReducedMotion

@Composable
fun AudioDeviceChip(
  currentDevice: AudioDevice,
  availableOutputs: List<AudioDevice>,
  availableInputs: List<AudioInputOption>,
  preferredInput: AudioDevice.BluetoothHeadset?,
  routingStatus: RoutingStatus,
  onOpenPicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val (label, icon, isError) = describeDevice(currentDevice)
  val outputLabel = audioRouteLabel(currentDevice)
  val inputLabel = preferredInput?.let { audioRouteLabel(it, useShortTransport = false) }
    ?: stringResource(R.string.bao_translate_audio_input_default)
  val visibleInputLabel = preferredInput?.let { audioRouteLabel(it) }
    ?: stringResource(R.string.bao_translate_audio_input_default_short)
  val deviceCount = availableOutputs.size
  val availableOutputCount = deviceCount.coerceAtLeast(1)
  val chipDesc = pluralStringResource(
    R.plurals.bao_translate_audio_chip_cd_format,
    availableOutputCount,
    outputLabel,
    inputLabel,
    availableOutputCount,
  )
  val statusDesc = stringResource(
    R.string.bao_translate_audio_status_format,
    when (routingStatus) {
      RoutingStatus.IDLE -> stringResource(R.string.bao_translate_audio_status_idle)
      RoutingStatus.ROUTING -> stringResource(R.string.bao_translate_audio_status_routing)
      RoutingStatus.CONNECTED -> stringResource(R.string.bao_translate_audio_status_connected)
      RoutingStatus.FAILED -> stringResource(R.string.bao_translate_audio_status_failed)
      RoutingStatus.NO_BLUETOOTH_OUTPUT -> stringResource(R.string.bao_translate_audio_status_no_bt)
    },
  )
  val reduceMotion = isReducedMotion
  val animatedContainer by animateColorAsState(
    targetValue = when (routingStatus) {
      RoutingStatus.FAILED, RoutingStatus.NO_BLUETOOTH_OUTPUT -> MaterialTheme.colorScheme.errorContainer
      RoutingStatus.ROUTING -> MaterialTheme.colorScheme.secondaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    animationSpec = if (reduceMotion) {
      androidx.compose.animation.core.tween(durationMillis = 0)
    } else {
      androidx.compose.animation.core.tween(durationMillis = 220)
    },
    label = "audioChipContainer",
  )
  Card(
    onClick = onOpenPicker,
    modifier = modifier
      .widthIn(max = Dimensions.Component.chipMaxWidth)
      .semantics(mergeDescendants = true) {
        contentDescription = "$chipDesc. $statusDesc"
        liveRegion = LiveRegionMode.Polite
        role = Role.Button
      },
    colors = CardDefaults.cardColors(containerColor = animatedContainer),
    shape = RoundedCornerShape(Dimensions.Component.chipCornerRadius),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = Dimensions.Spacing.md, vertical = Dimensions.Spacing.sm),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.sm),
    ) {
      AudioRouteChipSegment(
        label = stringResource(R.string.bao_translate_audio_output_label),
        value = outputLabel,
        icon = icon,
        isError = isError,
        routingStatus = routingStatus,
        reduceMotion = reduceMotion,
        modifier = Modifier.weight(1f),
      )
      AudioRouteChipSegment(
        label = stringResource(R.string.bao_translate_audio_input_short_label),
        value = visibleInputLabel,
        icon = Icons.Default.Mic,
        isError = false,
        routingStatus = RoutingStatus.IDLE,
        reduceMotion = reduceMotion,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun AudioRouteChipSegment(
  label: String,
  value: String,
  icon: ImageVector,
  isError: Boolean,
  routingStatus: RoutingStatus,
  reduceMotion: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
  ) {
    AnimatedContent(
      targetState = routingStatus,
      transitionSpec = {
        if (reduceMotion) {
          (fadeIn(animationSpec = androidx.compose.animation.core.tween(0)) togetherWith
            fadeOut(animationSpec = androidx.compose.animation.core.tween(0)))
        } else {
          (fadeIn() togetherWith fadeOut())
        }
      },
      label = "audioChipIcon",
    ) { status ->
      when (status) {
        RoutingStatus.ROUTING -> CircularProgressIndicator(
          modifier = Modifier.size(Dimensions.Icon.small),
          strokeWidth = Dimensions.Component.strokeWidth,
        )
        else -> Icon(
          imageVector = if (isError) Icons.Default.Warning else icon,
          contentDescription = null,
          modifier = Modifier.size(Dimensions.Icon.small),
          tint = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
      }
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDevicePickerSheet(
  currentDevice: AudioDevice,
  availableOutputs: List<AudioDevice>,
  availableInputs: List<AudioInputOption>,
  preferredInput: AudioDevice.BluetoothHeadset?,
  onSelectOutput: (AudioDevice) -> Unit,
  onSelectInput: (AudioDevice.BluetoothHeadset?) -> Unit,
  onDismiss: () -> Unit,
  onOpenBluetoothSettings: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  
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
        .padding(horizontal = Dimensions.Spacing.large, vertical = Dimensions.Spacing.small),
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
    ) {
      Text(
        text = stringResource(R.string.bao_translate_audio_picker_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.bao_translate_audio_picker_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      PickerSectionHeader(stringResource(R.string.bao_translate_audio_output_label))
      if (availableOutputs.isEmpty()) {
        PickerGuidance(
          message = stringResource(R.string.bao_translate_audio_no_outputs),
          hint = stringResource(R.string.bao_translate_audio_pair_output_hint),
          onOpenSettings = onOpenBluetoothSettings,
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
          availableOutputs.forEach { device ->
            AudioOutputRow(
              device = device,
              isSelected = device == currentDevice,
              onSelect = { onSelectOutput(device) },
            )
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = Dimensions.Spacing.small))

      PickerSectionHeader(stringResource(R.string.bao_translate_audio_input_label))
      Text(
        text = stringResource(R.string.bao_translate_audio_input_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      AudioInputRow(
        label = stringResource(R.string.bao_translate_audio_input_default),
        isSelected = preferredInput == null,
        onSelect = { onSelectInput(null) },
      )
      if (availableInputs.isEmpty()) {
        PickerGuidance(
          message = stringResource(R.string.bao_translate_audio_no_bt_inputs),
          hint = stringResource(R.string.bao_translate_audio_pair_input_hint),
          onOpenSettings = onOpenBluetoothSettings,
        )
      } else {
        availableInputs.forEach { option ->
          AudioInputRow(
            label = option.device.name,
            transportLabel = transportLabel(option.device.transport),
            isSelected = preferredInput == option.device,
            onSelect = { onSelectInput(option.device) },
          )
        }
      }

      Spacer(modifier = Modifier.size(Dimensions.Spacing.small))
    }
  }
}

@Composable
private fun PickerSectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun PickerGuidance(
  message: String,
  hint: String,
  onOpenSettings: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.small),
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Icon(
        imageVector = Icons.Default.Bluetooth,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(Dimensions.Icon.medium),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = hint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    OutlinedButton(
      onClick = onOpenSettings,
      modifier = Modifier.fillMaxWidth(),
      contentPadding = ButtonDefaults.ContentPadding,
    ) {
      Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = null,
        modifier = Modifier.size(Dimensions.Component.iconSmall),
      )
      Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
      Text(stringResource(R.string.bao_translate_audio_open_bt_settings))
    }
  }
}

@Composable
private fun AudioOutputRow(
  device: AudioDevice,
  isSelected: Boolean,
  onSelect: () -> Unit,
) {
  val (label, icon) = describeDevice(device)
  val isBt = device is AudioDevice.BluetoothHeadset
  val transportText = (device as? AudioDevice.BluetoothHeadset)?.let { bt ->
    transportLabel(bt.transport) + if (!bt.supportsInput) " ${stringResource(R.string.bao_translate_audio_output_only_suffix)}" else ""
  }
  val rowContentDescription = if (transportText == null) {
    stringResource(R.string.bao_translate_audio_output_row_cd_format, label)
  } else {
    stringResource(R.string.bao_translate_audio_output_row_cd_with_detail_format, label, transportText)
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Dimensions.Component.rowCornerRadius))
      .background(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
      )
      .clickable(onClick = onSelect)
      .padding(horizontal = Dimensions.Spacing.md, vertical = Dimensions.Spacing.smd)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton
        contentDescription = rowContentDescription
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
  ) {
    RadioButton(selected = isSelected, onClick = null)
    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Column(modifier = Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      if (isBt) {
        Text(
          text = transportText.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun AudioInputRow(
  label: String,
  isSelected: Boolean,
  onSelect: () -> Unit,
  transportLabel: String? = null,
) {
  val rowContentDescription = if (transportLabel == null) {
    stringResource(R.string.bao_translate_audio_input_row_cd_format, label)
  } else {
    stringResource(R.string.bao_translate_audio_input_row_cd_with_detail_format, label, transportLabel)
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(Dimensions.Component.rowCornerRadius))
      .background(
        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
      )
      .clickable(onClick = onSelect)
      .padding(horizontal = Dimensions.Spacing.md, vertical = Dimensions.Spacing.smd)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton
        contentDescription = rowContentDescription
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.md),
  ) {
    RadioButton(selected = isSelected, onClick = null)
    Icon(
      imageVector = Icons.Default.GraphicEq,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.secondary,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      if (transportLabel != null) {
        Text(
          text = transportLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun transportLabel(transport: BluetoothTransport): String = when (transport) {
  BluetoothTransport.BLE_AUDIO -> stringResource(R.string.bao_translate_audio_transport_ble)
  BluetoothTransport.A2DP -> stringResource(R.string.bao_translate_audio_transport_a2dp)
  BluetoothTransport.SCO -> stringResource(R.string.bao_translate_audio_transport_sco)
}

@Composable
private fun transportShortLabel(transport: BluetoothTransport): String = when (transport) {
  BluetoothTransport.BLE_AUDIO -> stringResource(R.string.bao_translate_audio_transport_ble_short)
  BluetoothTransport.A2DP -> stringResource(R.string.bao_translate_audio_transport_a2dp_short)
  BluetoothTransport.SCO -> stringResource(R.string.bao_translate_audio_transport_sco_short)
}

@Composable
private fun audioRouteLabel(
  device: AudioDevice,
  useShortTransport: Boolean = true,
): String = when (device) {
  is AudioDevice.BluetoothHeadset -> {
    val transport = if (useShortTransport) transportShortLabel(device.transport) else transportLabel(device.transport)
    if (useShortTransport) "${device.name} $transport" else "${device.name} ($transport)"
  }
  is AudioDevice.WiredHeadset -> device.name
  AudioDevice.Speaker -> stringResource(R.string.bao_translate_phone_speaker)
}

@Composable
internal fun describeDevice(device: AudioDevice): Triple<String, ImageVector, Boolean> = when (device) {
  AudioDevice.Speaker -> Triple(
    stringResource(R.string.bao_translate_phone_speaker),
    Icons.Default.Speaker,
    false,
  )
  is AudioDevice.WiredHeadset -> Triple(
    device.name,
    Icons.Default.Headphones,
    false,
  )
  is AudioDevice.BluetoothHeadset -> {
    val icon = when (device.transport) {
      BluetoothTransport.BLE_AUDIO -> Icons.Default.BluetoothConnected
      BluetoothTransport.A2DP -> Icons.Default.BluetoothAudio
      BluetoothTransport.SCO -> Icons.Default.Bluetooth
    }
    Triple(device.name, icon, false)
  }
}
