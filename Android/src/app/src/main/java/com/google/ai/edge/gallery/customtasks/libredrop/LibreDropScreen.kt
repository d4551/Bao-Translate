package com.google.ai.edge.gallery.customtasks.libredrop

import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData

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
)

enum class TransferState {
  CONNECTING, TRANSFERRING, COMPLETE, FAILED
}

@Composable
fun LibreDropScreen(data: CustomTaskData) {
  val peers = remember { mutableStateListOf<DiscoveredPeer>() }
  val selectedFiles = remember { mutableStateListOf<SelectedFile>() }
  val transfers = remember { mutableStateListOf<TransferStatus>() }
  var isDiscovering by remember { mutableStateOf(false) }
  var isSending by remember { mutableStateOf(false) }

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
        onFileSelected = { file -> selectedFiles.add(file) },
        onFileRemoved = { file -> selectedFiles.remove(file) },
      )

      PeerDiscoverySection(
        peers = peers,
        isDiscovering = isDiscovering,
        onStartDiscovery = { isDiscovering = true },
        onStopDiscovery = { isDiscovering = false },
      )

      if (selectedFiles.isNotEmpty() && peers.isNotEmpty()) {
        Button(
          onClick = { isSending = true },
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
          Text(stringResource(R.string.libre_drop_send_to, peers.first().name))
        }
      }

      if (transfers.isNotEmpty()) {
        TransferStatusSection(transfers = transfers)
      }
    }
  }
}

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
            PeerListItem(peer = peer)
          }
        }
      }
    }
  }
}

@Composable
private fun PeerListItem(peer: DiscoveredPeer) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.Devices,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.width(12.dp))
    Column {
      Text(
        text = peer.name,
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = peer.endpointId,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = stringResource(R.string.libre_drop_transfers),
        style = MaterialTheme.typography.titleSmall,
      )
      Spacer(modifier = Modifier.height(8.dp))
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
            TransferState.FAILED -> Text(
              text = stringResource(R.string.libre_drop_failed),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
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
