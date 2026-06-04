package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.ErrorCard
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
internal fun RequiredModelsPanel(
  uiState: BaoTranslateUiState,
  onDownloadModel: (String) -> Unit,
  onDownloadAll: () -> Unit,
  onInitializeModels: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val requiredModels = BaoTranslateModelManager.REQUIRED_MODEL_IDS.mapNotNull { id ->
    BaoTranslateModelManager.ALL_MODELS.firstOrNull { it.id == id }
  }
  val anyBusy = requiredModels.any { model ->
    val status = uiState.modelStatuses[model.id]
    status is ModelStatus.Downloading || status is ModelStatus.Extracting
  }
  val allReady = requiredModels.all { model -> uiState.modelStatuses[model.id] == ModelStatus.Ready }
  val runtimeNeedsRetry = allReady && !uiState.modelsReady
  val actionBusy = anyBusy || uiState.isInitializing

  Column(
    modifier = modifier.padding(vertical = Dimensions.Spacing.medium),
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
  ) {
    Text(
      text = stringResource(
        when {
          uiState.isInitializing -> R.string.bao_translate_initializing
          runtimeNeedsRetry -> R.string.bao_translate_initialize_models
          else -> R.string.bao_translate_download_required
        }
      ),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = if (runtimeNeedsRetry) {
        stringResource(R.string.bao_translate_runtime_needed)
      } else {
        stringResource(R.string.bao_translate_models_needed)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    requiredModels.forEach { model ->
      RequiredModelRow(
        model = model,
        status = uiState.modelStatuses[model.id] ?: ModelStatus.NotDownloaded,
        onDownload = { onDownloadModel(model.id) },
        downloadDisabled = anyBusy,
      )
    }

    Button(
      onClick = if (runtimeNeedsRetry) onInitializeModels else onDownloadAll,
      enabled = !actionBusy,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        when {
          uiState.isInitializing -> stringResource(R.string.bao_translate_initializing)
          runtimeNeedsRetry -> stringResource(R.string.bao_translate_initialize_models)
          allReady -> stringResource(R.string.bao_translate_model_ready)
          anyBusy -> stringResource(R.string.bao_translate_downloading_models)
          else -> stringResource(R.string.bao_translate_download_all)
        }
      )
    }
  }
}

@Composable
internal fun RequiredModelRow(
  model: ModelInfo,
  status: ModelStatus,
  onDownload: () -> Unit,
  downloadDisabled: Boolean,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
        Text(stringResource(model.displayNameRes), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
          stringResource(R.string.bao_translate_model_size_format, model.estimatedSizeMb),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
          is ModelStatus.Downloading -> {
            LinearProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
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
          is ModelStatus.Extracting -> Text(
            text = stringResource(R.string.bao_translate_extracting),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          is ModelStatus.Error -> ErrorCard(
            message = status.reason,
            onDismiss = onDownload,
            dismissLabel = stringResource(R.string.bao_translate_retry),
          )
          else -> Unit
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
        is ModelStatus.Ready -> Text(
          text = stringResource(R.string.bao_translate_model_ready),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.customColors.successColor,
        )
        is ModelStatus.Downloading, is ModelStatus.Extracting -> CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.medium), strokeWidth = Dimensions.Stroke.thin)
        is ModelStatus.Error -> TextButton(onClick = onDownload, enabled = !downloadDisabled) {
          Text(stringResource(R.string.bao_translate_retry))
        }
        is ModelStatus.NotDownloaded -> TextButton(onClick = onDownload, enabled = !downloadDisabled) {
          Text(stringResource(R.string.bao_translate_download))
        }
      }
    }
  }
}
