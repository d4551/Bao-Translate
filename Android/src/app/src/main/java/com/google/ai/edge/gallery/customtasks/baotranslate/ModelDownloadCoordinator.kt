package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ALL_DOWNLOADS = "__all_downloads__"

internal class ModelDownloadCoordinator(
  private val pipelines: PipelineLifecycleManager,
  private val modelManager: BaoTranslateModelManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  private val getApp: () -> Application,
  private val refreshLocalRuntimeState: (Application) -> Unit,
) {
  private val activeDownloads = mutableSetOf<String>()

  private fun beginDownload(id: String): Boolean = synchronized(activeDownloads) {
    if (id == ALL_DOWNLOADS) {
      if (activeDownloads.isNotEmpty()) return@synchronized false
      activeDownloads.add(id)
    } else {
      if (activeDownloads.contains(ALL_DOWNLOADS) || activeDownloads.contains(id)) return@synchronized false
      activeDownloads.add(id)
    }
  }

  private fun endDownload(id: String) {
    synchronized(activeDownloads) {
      activeDownloads.remove(id)
    }
  }

  fun downloadModel(modelId: String) {
    if (!beginDownload(modelId)) return

    viewModelScope.launch(Dispatchers.IO) {
      val app = getApp()
      try {
        val result = modelManager.downloadModel(
          context = app,
          modelId = modelId,
          wifiOnly = uiState.value.wifiOnlyDownloads,
        )

        result.fold(
          onSuccess = {
            uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(app)) }
            initializeIfRequiredDownloadsReady(app)
          },
          onFailure = { error ->
            val message = error.message ?: app.getString(R.string.bao_translate_error_download_unknown)
            modelManager.updateStatusExternal(modelId, ModelStatus.Error(message))
            uiState.update { it.copy(
              modelsReady = false,
              pipelineStatus = PipelineStatus.ModelsNotReady,
              errorMessage = message,
            ) }
          },
        )
      } finally {
        // Always release the active-download guard so the model can be retried even if anything in
        // the success/init path throws; otherwise the id would be stuck and re-download blocked.
        endDownload(modelId)
      }
    }
  }

  fun downloadRequiredModels() {
    if (!beginDownload(ALL_DOWNLOADS)) return

    viewModelScope.launch(Dispatchers.IO) {
      val app = getApp()
      try {
        val result = modelManager.downloadRequiredModels(
          context = app,
          wifiOnly = uiState.value.wifiOnlyDownloads,
        )

        result.fold(
          onSuccess = {
            uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(app)) }
            initializeIfRequiredDownloadsReady(app)
          },
          onFailure = { error ->
            uiState.update { it.copy(
              modelsReady = false,
              pipelineStatus = PipelineStatus.ModelsNotReady,
              errorMessage = error.message ?: app.getString(R.string.bao_translate_error_download_unknown),
            ) }
          },
        )
      } finally {
        endDownload(ALL_DOWNLOADS)
      }
    }
  }

  private suspend fun initializeIfRequiredDownloadsReady(app: Application) {
    if (!modelManager.areRequiredModelsReady(app)) {
      uiState.update { it.copy(
        modelsReady = false,
        pipelineStatus = PipelineStatus.ModelsNotReady,
      ) }
      return
    }

    pipelines.initializePipelines(app, uiState.value.translationModel)
    if (pipelines.requiredPipelinesReady()) {
      refreshLocalRuntimeState(app)
      uiState.update { it.copy(
        modelsReady = true,
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = null,
      ) }
    } else {
      val missing = pipelines.missingPipelineComponents(app)
      pipelines.cleanupPipelines()
      uiState.update { it.copy(
        modelsReady = false,
        pipelineStatus = PipelineStatus.ModelsNotReady,
        errorMessage = app.getString(
          R.string.bao_translate_error_runtime_not_ready,
          missing.joinToString(),
        ),
      ) }
    }
  }
}
