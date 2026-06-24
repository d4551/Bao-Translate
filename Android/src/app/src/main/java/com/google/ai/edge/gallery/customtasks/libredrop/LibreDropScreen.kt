package com.google.ai.edge.gallery.customtasks.libredrop

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.libredrop.discovery.DiscoveredService

data class DiscoveredPeer(
  val name: String,
  val endpointId: String,
)

data class SelectedFile(
  val uri: Uri,
  val name: String,
  val sizeBytes: Long,
)

data class TransferStatus(
  val peerName: String,
  val fileName: String,
  val progress: Float,
  val state: TransferState,
  val id: Long = 0L,
  val failureReason: String? = null,
)

enum class TransferState {
  CONNECTING, TRANSFERRING, COMPLETE, FAILED
}

@Composable
fun LibreDropScreen(data: CustomTaskData) {
  val senderViewModel: LibreDropSenderViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
  val peers by senderViewModel.peers.collectAsState()
  val isDiscovering by senderViewModel.isDiscovering.collectAsState()
  val transfers by senderViewModel.transfers.collectAsState()
  val selectedFiles = remember { mutableStateListOf<SelectedFile>() }
  var selectedPeer by remember { mutableStateOf<DiscoveredService?>(null) }
  val context = androidx.compose.ui.platform.LocalContext.current

  val filePickerLauncher =
    androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
      if (uri != null) {
        val meta = resolveFileMetadata(context, uri)
        selectedFiles.add(
          SelectedFile(uri = uri, name = meta.first, sizeBytes = meta.second)
        )
      }
    }

  val isSending = remember(transfers) {
    transfers.any { it.state == TransferState.CONNECTING || it.state == TransferState.TRANSFERRING }
  }

  Scaffold { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.libre_drop),
        style = MaterialTheme.typography.headlineMedium,
      )

      Text(
        text = stringResource(R.string.libre_drop_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      FilePickerSection(
        selectedFiles = selectedFiles,
        onFileSelected = { filePickerLauncher.launch(arrayOf("*/*")) },
        onFileRemoved = { file -> selectedFiles.remove(file) },
      )

      PeerDiscoverySection(
        peers = peers.map { it.toDisplayPeer() },
        isDiscovering = isDiscovering,
        onStartDiscovery = { senderViewModel.startDiscovery() },
        onStopDiscovery = { senderViewModel.stopDiscovery() },
        onPeerSelected = { displayName ->
          selectedPeer = peers.firstOrNull { it.toDisplayPeer().name == displayName }
        },
        selectedPeerName = selectedPeer?.toDisplayPeer()?.name,
      )

      if (selectedFiles.isNotEmpty() && selectedPeer != null) {
        Button(
          onClick = {
            val target = selectedPeer ?: return@Button
            val fileSources = selectedFiles.mapIndexed { index, sf ->
              com.google.ai.edge.gallery.customtasks.libredrop.service.uploads.UriFileSource(
                context = context,
                uri = sf.uri,
                payloadId = (index + 1).toLong(),
              ).build()
            }
            senderViewModel.send(peer = target, files = fileSources)
          },
          modifier = Modifier.fillMaxWidth(),
          enabled = !isSending,
        ) {
          if (isSending) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.libre_drop_send_to, selectedPeer?.toDisplayPeer()?.name ?: ""))
        }
      }

      TransferStatusSection(transfers = transfers)
    }
  }
}

private fun com.google.ai.edge.gallery.customtasks.libredrop.discovery.DiscoveredService.toDisplayPeer(): DiscoveredPeer =
  DiscoveredPeer(
    name = endpointInfo?.let { ei ->
      ei.deviceName?.takeIf { it.isNotBlank() }
    } ?: instanceName.take(12),
    endpointId = endpointId?.joinToString("") { "%02x".format(it) } ?: "",
  )

@Composable
private fun FilePickerSection(
  selectedFiles: List<SelectedFile>,
  onFileSelected: (SelectedFile) -> Unit,
  onFileRemoved: (SelectedFile) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = stringResource(R.string.libre_drop_files_to_share),
        style = MaterialTheme.typography.titleSmall,
      )
      Spacer(modifier = Modifier.height(8.dp))
      if (selectedFiles.isEmpty()) {
        Text(
          text = stringResource(R.string.libre_drop_no_files_selected),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        selectedFiles.forEach { file ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = file.name,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = formatFileSize(file.sizeBytes),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PeerDiscoverySection(
  peers: List<DiscoveredPeer>,
  isDiscovering: Boolean,
  onStartDiscovery: () -> Unit,
  onStopDiscovery: () -> Unit,
  onPeerSelected: (String) -> Unit = {},
  selectedPeerName: String? = null,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.libre_drop_nearby_devices),
          style = MaterialTheme.typography.titleSmall,
        )
        Button(
          onClick = {
            if (isDiscovering) onStopDiscovery() else onStartDiscovery()
          },
        ) {
          if (isDiscovering) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.libre_drop_scanning))
          } else {
            Icon(Icons.Filled.Devices, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.libre_drop_scan))
          }
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      if (peers.isEmpty()) {
        Text(
          text = if (isDiscovering) stringResource(R.string.libre_drop_searching) else stringResource(R.string.libre_drop_no_devices_found),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        LazyColumn {
          items(peers) { peer ->
            PeerListItem(
              peer = peer,
              isSelected = peer.name == selectedPeerName,
              onClick = { onPeerSelected(peer.name) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PeerListItem(
  peer: DiscoveredPeer,
  isSelected: Boolean = false,
  onClick: () -> Unit = {},
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp)
      .semantics {
        selected = isSelected
        role = Role.Button
      }
      .clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.Devices,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = peer.name,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = peer.endpointId,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (isSelected) {
      Icon(
        Icons.Filled.CheckCircle,
        contentDescription = stringResource(R.string.libre_drop_selected_peer),
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun TransferStatusSection(transfers: List<TransferStatus>) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(
      modifier = Modifier
        .padding(16.dp)
        .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
      Text(
        text = stringResource(R.string.libre_drop_transfers),
        style = MaterialTheme.typography.titleSmall,
      )
      Spacer(modifier = Modifier.height(8.dp))
      if (transfers.isEmpty()) {
        Text(
          text = stringResource(R.string.libre_drop_no_transfers),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      transfers.forEach { transfer ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = transfer.fileName,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = stringResource(R.string.libre_drop_to_peer, transfer.peerName),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          when (transfer.state) {
            TransferState.CONNECTING -> Text(
              text = stringResource(R.string.libre_drop_connecting),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
            TransferState.TRANSFERRING -> Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                progress = { transfer.progress },
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "${(transfer.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
              )
            }
            TransferState.COMPLETE -> Text(
              text = stringResource(R.string.libre_drop_done),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
            TransferState.FAILED -> Column(horizontalAlignment = Alignment.End) {
              Text(
                text = stringResource(R.string.libre_drop_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
              if (transfer.failureReason != null) {
                Text(
                  text = transfer.failureReason,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Text(
                text = stringResource(R.string.libre_drop_retry_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

private fun formatFileSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
  }
}

private fun resolveFileMetadata(
  context: android.content.Context,
  uri: android.net.Uri,
): Pair<String, Long> {
  var name: String? = null
  var size = 0L
  context.contentResolver.query(
    uri,
    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
    null,
    null,
    null,
  )?.use { cursor ->
    if (cursor.moveToFirst()) {
      val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
      val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
      if (nameIdx >= 0) name = cursor.getString(nameIdx)
      if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
    }
  }
  return Pair(name ?: uri.lastPathSegment ?: "unnamed", size)
}
