/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

@Composable
internal fun TransferStatusSection(transfers: List<TransferStatus>) {
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
