package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.DiscoveredPeer
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationModeScreen(
  localParticipant: Participant?,
  remoteParticipants: List<Participant>,
  discoveredPeers: List<DiscoveredPeer>,
  isScanning: Boolean,
  connectionState: ConnectionState,
  connectingPeers: Set<String>,
  currentAudioDevice: AudioDevice,
  onScanDevices: () -> Unit,
  onStopScan: () -> Unit,
  onConnectDevice: (String) -> Unit,
  onStartConversation: () -> Unit,
  modifier: Modifier = Modifier,
  isTablet: Boolean = false,
) {
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth

  Column(
    modifier = modifier
      .fillMaxSize()
      .widthIn(max = maxWidth)
      .verticalScroll(rememberScrollState())
      .padding(Dimensions.Spacing.medium),
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
  ) {
    Text(
      text = stringResource(R.string.bao_translate_conversation_mode),
      style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )

    Text(
      text = stringResource(R.string.bao_translate_connect_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ConnectionStateRow(connectionState = connectionState, isScanning = isScanning)

    localParticipant?.let { participant ->
      ParticipantCard(
        participant = participant,
        isLocal = true,
        audioDevice = currentAudioDevice,
      )
    }

    if (remoteParticipants.isNotEmpty()) {
      Text(
        text = stringResource(R.string.bao_translate_connected),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      remoteParticipants.forEach { participant ->
        ParticipantCard(
          participant = participant,
          isLocal = false,
        )
      }
    }

    // Peers that connected are shown in the Connected section; exclude them here so they don't
    // appear twice. (Filtering at render avoids a reconnection trap from mutating manager state,
    // since the library may not re-emit onPeerFound after a disconnect.)
    val unconnectedPeers = discoveredPeers.filter { peer -> remoteParticipants.none { it.id == peer.id } }
    if (unconnectedPeers.isNotEmpty()) {
      Text(
        text = stringResource(R.string.bao_translate_discovered_devices_section),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small)) {
        unconnectedPeers.forEach { peer ->
          val isConnecting = connectingPeers.contains(peer.id)
          DiscoveredPeerCard(
            peer = peer,
            isConnected = false,
            isConnecting = isConnecting,
            onConnect = { onConnectDevice(peer.deviceAddress) },
          )
        }
      }
    }

    ScanSection(
      isScanning = isScanning,
      connectionState = connectionState,
      discoveredCount = discoveredPeers.size,
      onScanDevices = onScanDevices,
      onStopScan = onStopScan,
    )

    if (!isScanning && discoveredPeers.isEmpty() && remoteParticipants.isEmpty()) {
      NoDevicesState()
    }

    val connectedCount = remoteParticipants.count { it.isConnected }
    Button(
      onClick = onStartConversation,
      modifier = Modifier.fillMaxWidth(),
      enabled = connectedCount > 0,
    ) {
      Icon(
        imageVector = Icons.Default.Bluetooth,
        contentDescription = null,
      )
      Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
      Text(
        text = if (connectedCount > 0) {
          stringResource(R.string.bao_translate_group_conversation_format, connectedCount)
        } else {
          stringResource(R.string.bao_translate_connect_devices_first)
        }
      )
    }
  }
}

@Composable
private fun ConnectionStateRow(
  connectionState: ConnectionState,
  isScanning: Boolean,
) {
  val label = when {
    isScanning -> stringResource(R.string.bao_translate_scanning)
    connectionState == ConnectionState.ADVERTISING -> stringResource(R.string.bao_translate_advertising)
    connectionState == ConnectionState.CONNECTING -> stringResource(R.string.bao_translate_connecting)
    connectionState == ConnectionState.CONNECTED -> stringResource(R.string.bao_translate_connected)
    else -> stringResource(R.string.bao_translate_ready_to_pair)
  }
  val showProgress = isScanning ||
    connectionState == ConnectionState.ADVERTISING ||
    connectionState == ConnectionState.CONNECTING

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.small),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      if (showProgress) {
        CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.medium), strokeWidth = Dimensions.Component.strokeWidth)
      } else {
        Box(
          modifier = Modifier
            .size(Dimensions.Indicator.medium)
            .clip(CircleShape)
            .background(
              if (connectionState == ConnectionState.CONNECTED) {
                MaterialTheme.customColors.successColor
              } else {
                MaterialTheme.colorScheme.outline
              }
            )
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun DiscoveredPeerCard(
  peer: DiscoveredPeer,
  isConnected: Boolean,
  isConnecting: Boolean,
  onConnect: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Dimensions.Spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Box(
        modifier = Modifier
          .size(Dimensions.Icon.xl)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = peer.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = peer.deviceAddress,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (isConnected) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = stringResource(R.string.cd_bao_translate_connected_icon),
          tint = MaterialTheme.customColors.successColor,
          modifier = Modifier.size(Dimensions.Icon.medium),
        )
      } else {
        OutlinedButton(onClick = onConnect, enabled = !isConnecting) {
          if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.small), strokeWidth = Dimensions.Component.strokeWidth)
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          }
          Text(
            if (isConnecting) {
              stringResource(R.string.bao_translate_connecting)
            } else {
              stringResource(R.string.bao_translate_connect_action)
            }
          )
        }
      }
    }
  }
}

@Composable
private fun ParticipantCard(
  participant: Participant,
  isLocal: Boolean,
  audioDevice: AudioDevice? = null,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isLocal) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    ),
  ) {
    Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(Dimensions.Icon.xl)
            .clip(CircleShape)
            .background(
              if (isLocal) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.surfaceVariant
              }
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = if (isLocal) {
              MaterialTheme.colorScheme.onPrimary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }

        Spacer(modifier = Modifier.width(Dimensions.Spacing.small))

        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = if (isLocal) {
                stringResource(R.string.bao_translate_you_suffix, participant.name)
              } else {
                participant.name
              },
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
            if (isLocal) {
              Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
              Box(
                modifier = Modifier
                  .size(Dimensions.Spacing.small)
                  .clip(CircleShape)
                  .background(MaterialTheme.customColors.successColor)
              )
            } else if (participant.isConnected) {
              Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_bao_translate_connected_icon),
                tint = MaterialTheme.customColors.successColor,
                modifier = Modifier.size(Dimensions.Icon.small),
              )
            }
          }

          Text(
            text = stringResource(
              R.string.bao_translate_lang_pair_format,
              participantLanguageDisplayName(participant.sourceLanguage),
              participantLanguageDisplayName(participant.targetLanguage),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (isLocal && audioDevice != null) {
          val deviceName = when (audioDevice) {
            is AudioDevice.BluetoothHeadset -> audioDevice.name
            is AudioDevice.WiredHeadset -> audioDevice.name
            AudioDevice.Speaker -> stringResource(R.string.bao_translate_phone_speaker)
          }
          val routeIcon = when (audioDevice) {
            is AudioDevice.BluetoothHeadset -> Icons.Default.BluetoothConnected
            is AudioDevice.WiredHeadset -> Icons.AutoMirrored.Filled.VolumeUp
            AudioDevice.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = routeIcon,
              contentDescription = null,
              modifier = Modifier.size(Dimensions.Icon.small),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
            Text(
              text = deviceName,
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }

        if (participant.hasVoiceProfile) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              modifier = Modifier.size(Dimensions.Icon.small),
              tint = MaterialTheme.customColors.successColor,
            )
            Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
            Text(
              text = stringResource(R.string.bao_translate_voice_enrolled),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.customColors.successColor,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NoDevicesState() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Dimensions.Spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Icon(
        imageVector = Icons.Default.Search,
        contentDescription = null,
        modifier = Modifier.size(Dimensions.Icon.large),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.bao_translate_no_devices_found),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stringResource(R.string.bao_translate_devices_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun participantLanguageDisplayName(language: String): String {
  val key = SupportedLanguages.keyForCode(language) ?: language
  val supportedLanguage = SupportedLanguages.ALL.firstOrNull { it.key == key }
  return supportedLanguage?.let { stringResource(it.displayNameRes) } ?: language.uppercase()
}

@Composable
private fun ScanSection(
  isScanning: Boolean,
  connectionState: ConnectionState,
  discoveredCount: Int,
  onScanDevices: () -> Unit,
  onStopScan: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    Column(
      modifier = Modifier.padding(Dimensions.Spacing.medium),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (isScanning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.medium))
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Text(
            text = stringResource(R.string.bao_translate_scanning),
            style = MaterialTheme.typography.bodyMedium,
          )
          if (discoveredCount > 0) {
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
            Text(
              text = stringResource(R.string.bao_translate_devices_found_format, discoveredCount),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

        OutlinedButton(onClick = onStopScan) {
          Text(stringResource(R.string.bao_translate_stop_scanning))
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.bao_translate_find_devices),
            tint = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Text(
            text = if (discoveredCount > 0) {
              stringResource(R.string.bao_translate_device_count_format, discoveredCount, if (discoveredCount == 1) "" else "s")
            } else {
              stringResource(R.string.bao_translate_find_devices)
            },
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

        OutlinedButton(onClick = onScanDevices) {
          Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = stringResource(R.string.bao_translate_scan_devices),
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Text(stringResource(R.string.bao_translate_scan_devices))
        }
      }
    }
  }
}
