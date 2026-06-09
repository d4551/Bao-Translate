package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ALL_DOWNLOADS = "__all_downloads__"

/** How to refresh runtime pipelines after a model download completes. */
internal enum class PostDownloadInit { TtsOnly, FullPipelines }

internal fun postDownloadInitAction(
  modelId: String,
  requiredModelsReady: Boolean,
  pipelinesReady: Boolean,
): PostDownloadInit =
  if (modelId == "supertonic_tts" && requiredModelsReady && pipelinesReady) {
    PostDownloadInit.TtsOnly
  } else {
    PostDownloadInit.FullPipelines
  }

internal class ModelDownloadCoordinator(
  private val pipelines: PipelineLifecycleManager,
  private val modelManager: BaoTranslateModelManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  private val getApp: () -> Application,
  private val refreshLocalRuntimeState: (Application) -> Unit,
  // Resolves the translation model to one whose files are actually present (falling back + persisting
  // when the selected model was deleted), so a post-download init never targets a missing model.
  private val resolveTranslationModel: (Application) -> String,
  private val reinitializePipeline: (String) -> Unit,
) {
  // Maps an in-flight download id (a model id, or ALL_DOWNLOADS) to its Job. A delete cancels and
  // awaits the relevant job(s) before touching files, so a delete can never race a live writer
  // (no surviving partial file, no model resurrected by the download's completion write).
  private val activeDownloads = mutableMapOf<String, Job>()

  private fun reserveAndLaunch(id: String, work: suspend () -> Unit): Boolean =
    synchronized(activeDownloads) {
      if (id == ALL_DOWNLOADS) {
        if (activeDownloads.isNotEmpty()) return false
      } else if (activeDownloads.containsKey(ALL_DOWNLOADS) || activeDownloads.containsKey(id)) {
        return false
      }
      // LAZY then start() after registration: a job that completes synchronously can't fire its
      // completion handler before it is in the map (which would otherwise leave a stale entry).
      val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { work() }
      activeDownloads[id] = job
      job.invokeOnCompletion {
        synchronized(activeDownloads) { if (activeDownloads[id] === job) activeDownloads.remove(id) }
      }
      job.start()
      true
    }

  /** Cancels and awaits the in-flight download of [modelId] (and any all-models download). */
  suspend fun cancelDownload(modelId: String) {
    val jobs = synchronized(activeDownloads) {
      listOfNotNull(activeDownloads[modelId], activeDownloads[ALL_DOWNLOADS])
    }
    jobs.forEach { it.cancel() }
    jobs.forEach { it.join() }
  }

  /** Cancels and awaits every in-flight download. */
  suspend fun cancelAllDownloads() {
    val jobs = synchronized(activeDownloads) { activeDownloads.values.toList() }
    jobs.forEach { it.cancel() }
    jobs.forEach { it.join() }
  }

  fun downloadModel(modelId: String) {
    reserveAndLaunch(modelId) {
      val app = getApp()
      val result = modelManager.downloadModel(
        context = app,
        modelId = modelId,
        wifiOnly = uiState.value.wifiOnlyDownloads,
      )

      result.fold(
        onSuccess = {
          uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(app)) }
          onDownloadSuccess(app, modelId)
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
    }
  }

  fun downloadRequiredModels() {
    reserveAndLaunch(ALL_DOWNLOADS) {
      val app = getApp()
      val result = modelManager.downloadRequiredModels(
        context = app,
        wifiOnly = uiState.value.wifiOnlyDownloads,
      )

      result.fold(
        onSuccess = {
          uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(app)) }
          onDownloadSuccess(app, ALL_DOWNLOADS)
        },
        onFailure = { error ->
          uiState.update { it.copy(
            modelsReady = false,
            pipelineStatus = PipelineStatus.ModelsNotReady,
            errorMessage = error.message ?: app.getString(R.string.bao_translate_error_download_unknown),
          ) }
        },
      )
    }
  }

  private suspend fun onDownloadSuccess(app: Application, modelId: String) {
    when (
      postDownloadInitAction(
        modelId = modelId,
        requiredModelsReady = modelManager.areRequiredModelsReady(app),
        pipelinesReady = pipelines.requiredPipelinesReady(),
      )
    ) {
      PostDownloadInit.TtsOnly -> {
        reinitializePipeline("tts")
        uiState.update { it.copy(
          modelsReady = true,
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = null,
        ) }
      }
      PostDownloadInit.FullPipelines -> initializeIfRequiredDownloadsReady(app)
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

    pipelines.initializePipelines(app, resolveTranslationModel(app))
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
