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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions

@Composable
fun ChatHistorySideSheetContent(
  history: List<com.google.ai.edge.gallery.proto.ChatSessionProto>,
  onHistoryItemClicked: (String) -> Unit,
  onHistoryItemDeleted: (String) -> Unit,
  onHistoryItemsDeleteAll: () -> Unit,
  onNewChatClicked: () -> Unit,
  onDismissed: () -> Unit,
) {
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }
  var itemToDelete by remember { mutableStateOf<String?>(null) }

  Column(modifier = Modifier.fillMaxSize().padding(Dimensions.Spacing.large)) {
    // Top Row: Title and Close button
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = Dimensions.Spacing.md),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(R.string.chat_history_title), style = MaterialTheme.typography.titleLarge)
      IconButton(onClick = onDismissed) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
      }
    }

    // Actions Row: "+ New chat" pill
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = Dimensions.Spacing.medium),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(
        onClick = onNewChatClicked,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      ) {
        Icon(Icons.Rounded.AddComment, contentDescription = null, modifier = Modifier.size(Dimensions.Component.iconSmall))
        Spacer(modifier = Modifier.size(Dimensions.Spacing.small))
        Text(stringResource(R.string.new_chat))
      }
    }

    // Subheading Row: Chat history and Clear all button
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.small),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        stringResource(R.string.chat_history_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = { showConfirmDeleteDialog = true }) {
        Text(stringResource(R.string.clear_all))
      }
    }

    // History list
    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small)) {
      items(history) { session ->
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(Dimensions.Component.rowCornerRadius))
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
              .clickable { onHistoryItemClicked(session.sessionId) }
              .padding(vertical = Dimensions.Spacing.md, horizontal = Dimensions.Spacing.medium),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              session.title,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(onClick = { itemToDelete = session.sessionId }) {
            Icon(
              Icons.Rounded.Delete,
              contentDescription = stringResource(R.string.cd_delete_input_history_entry_icon),
            )
          }
        }
      }
    }

    // Persistent/Info notice footer
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
      )
      Text(
        stringResource(R.string.chat_history_demo_notice),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  if (showConfirmDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showConfirmDeleteDialog = false },
      title = { Text(stringResource(R.string.clear_history_dialog_title)) },
      text = { Text(stringResource(R.string.clear_history_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            showConfirmDeleteDialog = false
            onHistoryItemsDeleteAll()
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { showConfirmDeleteDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  if (itemToDelete != null) {
    AlertDialog(
      onDismissRequest = { itemToDelete = null },
      title = { Text(stringResource(R.string.clear_history_dialog_title)) },
      text = { Text(stringResource(R.string.clear_history_dialog_content)) },
      confirmButton = {
        Button(
          onClick = {
            val toDel = itemToDelete
            itemToDelete = null
            if (toDel != null) {
              onHistoryItemDeleted(toDel)
            }
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { itemToDelete = null }) { Text(stringResource(R.string.cancel)) }
      },
    )
  }
}
