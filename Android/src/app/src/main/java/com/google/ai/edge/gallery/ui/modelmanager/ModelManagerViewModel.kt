/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.LenientJson
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.isAICoreSupported
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelHelper
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.collections.sortedWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService

private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

private const val TEST_MODEL_ALLOW_LIST = ""

data class ModelInitializationStatus(
  val status: ModelInitializationStatusType,
  var error: String = "",
  var initializedBackends: Set<String> = setOf(),
) {
  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return !initializedBackends.contains(backend)
  }
}

enum class ModelInitializationStatusType {
  NOT_INITIALIZED,
  INITIALIZING,
  INITIALIZED,
  ERROR,
}

data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** Tasks grouped by category. */
  val tasksByCategory: Map<String, List<Task>>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** A map that tracks the initialization status of each model, indexed by model name. */
  val modelInitializationStatus: Map<String, ModelInitializationStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
  // Updated when model is imported of an imported model is deleted.
  val modelImportingUpdateTrigger: Long = 0L,
) {
  fun isModelInitialized(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZED
  }

  fun isModelInitializing(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZING
  }
}

private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

private val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.BAO_TRANSLATE,
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

/**
 * ViewModel responsible for managing models, their download status, and initialization.
 *
 * This ViewModel handles model-related operations such as downloading, deleting, initializing, and
 * cleaning up models. It also manages the UI state for model management, including the list of
 * tasks, models, download statuses, and initialization statuses.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  private val lifecycleProvider: AppLifecycleProvider,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  private val systemPromptRepository: SystemPromptRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val externalFilesDir = context.getExternalFilesDir(null)
  protected val _uiState = MutableStateFlow(createInitialUiState())
  open val uiState = _uiState.asStateFlow()

  private var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  val authService = AuthorizationService(context)
  var curAccessToken: String = ""

  override fun onCleared() {
    authService.dispose()
  }

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  fun getActiveCustomTasks(): List<CustomTask> {
    return customTasks.toList()
  }

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return null
  }

  fun getAllModels(): List<Model> {
    val allModels = mutableSetOf<Model>()
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
  }

  fun processTasks() {
    val curTasks = getActiveCustomTasks().map { it.task }
    for (task in curTasks) {
      for (model in task.models) {
        model.preProcess()
      }
      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { it.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { it.copy(selectedModel = model) }
    }
  }

  open fun downloadModel(task: Task?, model: Model) {
    // Update status.
    setDownloadStatus(
      curModel = model,
      status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
    )

    // Tracked in b/494029782: LiteRT-LM and AICore downloads still use separate storage paths.
    if (model.runtimeType == RuntimeType.AICORE) {
      AICoreModelHelper.downloadModel(
        context = context,
        coroutineScope = viewModelScope,
        model = model,
        onProgress = { downloaded: Long, total: Long ->
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(
                status = ModelDownloadStatusType.IN_PROGRESS,
                receivedBytes = downloaded,
                totalBytes = total,
              ),
          )
        },
        onDone = {
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(
                status = ModelDownloadStatusType.SUCCEEDED,
                receivedBytes = model.sizeInBytes,
                totalBytes = model.sizeInBytes,
              ),
          )
        },
        onError = { error: String ->
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(status = ModelDownloadStatusType.FAILED, errorMessage = error),
          )
        },
      )
      return
    }

    // Delete the model files first.
    deleteModel(model = model)

    // Start to send download request.
    downloadRepository.downloadModel(
      task = task,
      model = model,
      onStatusUpdated = this::setDownloadStatus,
    )
  }

  fun cancelDownloadModel(model: Model) {
    // Tracked in b/494029782: AICore model deletion is controlled outside the local repository.
    // AICore models cannot be deleted from the download repository within the app.
    if (model.runtimeType == RuntimeType.AICORE) {
      return
    }
    downloadRepository.cancelDownloadModel(model)
    deleteModel(model = model)
  }

  fun deleteModel(model: Model) {
    // If the currently downloaded model is an updatable version, reset the model to its latest
    // version and mark it as not updatable upon deletion.
    if (model.updatable) {
      model.updatable = false
      model.latestModelFile?.let {
        model.version = it.commitHash
        model.downloadFileName = it.fileName
      }
    }

    if (model.imported) {
      deleteFilesFromImportDir(model.downloadFileName)
    } else {
      deleteDirFromExternalFilesDir(model.normalizedName)
    }

    // Update model download status to NotDownloaded.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[model.name] =
      ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

    // Delete model from the list if model is imported as a local model.
    if (model.imported) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }
      curModelDownloadStatus.remove(model.name)

      viewModelScope.launch(Dispatchers.IO) {
        val importedModels = dataStoreRepository.readImportedModels().toMutableList()
        val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
        if (importedModelIndex >= 0) {
          importedModels.removeAt(importedModelIndex)
        }
        dataStoreRepository.saveImportedModels(importedModels = importedModels)
      }
    }
    _uiState.update {
      it.copy(
        modelDownloadStatus = curModelDownloadStatus,
        tasks = it.tasks.toList(),
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }
  }

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Skip if initialized already.
      if (
        !force &&
          uiState.value.modelInitializationStatus[model.name]?.status ==
            ModelInitializationStatusType.INITIALIZED
      ) {
        BaoLog.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        return@launch
      }

      // Skip if initialization is in progress.
      if (model.initializing) {
        model.cleanUpAfterInit = false
        BaoLog.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      // Clean up.
      cleanupModel(context = context, task = task, model = model)

      // Start initialization.
      BaoLog.d(TAG, "Initializing model '${model.name}'...")
      model.initializing = true
      updateModelInitializationStatus(
        model = model,
        status = ModelInitializationStatusType.INITIALIZING,
      )

      val onDoneFn: (error: String) -> Unit = { error ->
        model.initializing = false
        if (model.instance != null) {
          BaoLog.d(TAG, "Model '${model.name}' initialized successfully")
          updateModelInitializationStatus(
            model = model,
            status = ModelInitializationStatusType.INITIALIZED,
          )
          if (model.cleanUpAfterInit) {
            BaoLog.d(TAG, "Model '${model.name}' needs cleaning up after init.")
            cleanupModel(context = context, task = task, model = model)
          }
          onDone()
        } else if (error.isNotEmpty()) {
          BaoLog.d(TAG, "Model '${model.name}' failed to initialize")
          updateModelInitializationStatus(
            model = model,
            status = ModelInitializationStatusType.ERROR,
            error = error,
          )
          onError(error)
        }
      }

      // Call the model initialization function.
      val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      getCustomTaskByTaskId(id = task.id)
        ?.initializeModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          systemInstruction = Contents.of(systemPrompt),
          onDone = onDoneFn,
        )
    }
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      BaoLog.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      BaoLog.d(TAG, "Cleaning up model '${model.name}'...")
      val onDoneFn: () -> Unit = {
        model.instance = null
        model.initializing = false
        updateModelInitializationStatus(
          model = model,
          status = ModelInitializationStatusType.NOT_INITIALIZED,
        )
        BaoLog.d(TAG, "Clean up model '${model.name}' done")
        onDone()
      }
      getCustomTaskByTaskId(id = task.id)
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDoneFn,
        )
    } else {
      // When model is being initialized and we are trying to clean it up at same time, we mark it
      // to clean up and it will be cleaned up after initialization is done.
      if (model.initializing) {
        BaoLog.d(
          TAG,
          "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
        )
        model.cleanUpAfterInit = true
      }
    }
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    // Update model download progress.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[curModel.name] = status
    // Delete downloaded file if status is failed or not_downloaded.
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromExternalFilesDir(curModel.downloadFileName)
    }

    _uiState.update { it.copy(modelDownloadStatus = curModelDownloadStatus) }
  }

  fun setInitializationStatus(model: Model, status: ModelInitializationStatus) {
    val curStatus = uiState.value.modelInitializationStatus.toMutableMap()
    if (curStatus.containsKey(model.name)) {
      val initializedBackends = curStatus[model.name]?.initializedBackends ?: setOf()
      val backend =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      val newInitializedBackends =
        if (status.status == ModelInitializationStatusType.INITIALIZED) {
          initializedBackends + backend
        } else {
          initializedBackends
        }
      curStatus[model.name] = status.copy(initializedBackends = newInitializedBackends)
      _uiState.update { it.copy(modelInitializationStatus = curStatus) }
    }
  }

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { it.copy(textInputHistory = newHistory) }
      viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { it.copy(textInputHistory = mutableListOf()) }
    viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    viewModelScope.launch { dataStoreRepository.saveTheme(theme = theme) }
  }

  fun getModelUrlResponse(model: Model, accessToken: String? = null): Int {
    return runCatching {
      val url = URL(model.url)
      val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnection(
        url = url,
        accessToken = accessToken,
      )
      connection.connect()

      // Report the result.
      connection.responseCode
    }.getOrElse { e ->
      BaoLog.e(TAG, "$e")
      -1
    }
  }

  fun addImportedLlmModel(info: ImportedModel) {
    BaoLog.d(TAG, "adding imported llm model: $info")

    // Create model.
    val model = createModelFromImportedModelInfo(info = info)

    val setOfTasks =
      mutableSetOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
        BuiltInTaskId.LLM_PROMPT_LAB,
        BuiltInTaskId.LLM_TINY_GARDEN,
        BuiltInTaskId.LLM_MOBILE_ACTIONS,
        BuiltInTaskId.LLM_AGENT_CHAT,
      )
    for (task in getTasksByIds(ids = setOfTasks)) {
      // Remove duplicated imported model if existed.
      val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
      if (modelIndex >= 0) {
        BaoLog.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(modelIndex)
      }
      if (
        (task.id == BuiltInTaskId.LLM_ASK_IMAGE && model.llmSupportImage) ||
          (task.id == BuiltInTaskId.LLM_ASK_AUDIO && model.llmSupportAudio) ||
          (task.id == BuiltInTaskId.LLM_TINY_GARDEN && model.llmSupportTinyGarden) ||
          (task.id == BuiltInTaskId.LLM_MOBILE_ACTIONS && model.llmSupportMobileActions) ||
          (task.id != BuiltInTaskId.LLM_ASK_IMAGE &&
            task.id != BuiltInTaskId.LLM_ASK_AUDIO &&
            task.id != BuiltInTaskId.LLM_TINY_GARDEN &&
            task.id != BuiltInTaskId.LLM_MOBILE_ACTIONS)
      ) {
        task.models.add(model)
        if (task.id == BuiltInTaskId.LLM_TINY_GARDEN) {
          val newConfigs = model.configs.toMutableList()
          newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
          model.configs = newConfigs
          model.preProcess()
        }
      }
      task.updateTrigger.value = System.currentTimeMillis()
    }

    // Add initial status and states.
    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    val modelInstances = uiState.value.modelInitializationStatus.toMutableMap()
    modelDownloadStatus[model.name] =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = info.fileSize,
        totalBytes = info.fileSize,
      )
    modelInstances[model.name] =
      ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

    // Update ui state.
    _uiState.update {
      it.copy(
        tasks = it.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelInitializationStatus = modelInstances,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    viewModelScope.launch(Dispatchers.IO) {
      val importedModels = dataStoreRepository.readImportedModels().toMutableList()
      val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
      if (importedModelIndex >= 0) {
        BaoLog.d(TAG, "duplicated imported model found in data store. Removing it first")
        importedModels.removeAt(importedModelIndex)
      }
      importedModels.add(info)
      dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }
  }

  fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
    viewModelScope.launch {
      dataStoreRepository.saveAccessTokenData(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
      )
    }
  }

  fun clearAccessToken() {
    viewModelScope.launch { dataStoreRepository.clearAccessTokenData() }
  }

  /** Persists that a promo has been viewed (fire-and-forget). */
  fun markPromoViewed(promoId: String) {
    viewModelScope.launch { dataStoreRepository.addViewedPromoId(promoId = promoId) }
  }

  // Tracked in b/494029782: startup status probing remains runtime-specific until model storage is
  // unified behind one repository.
  private fun checkAICoreModelStatuses() {
    viewModelScope.launch(Dispatchers.Main) {
      val aicoreModels =
        uiState.value.tasks
          .flatMap { it.models }
          .filter { it.runtimeType == RuntimeType.AICORE }
          .distinctBy { it.name }

      // Proactively attempt AICore model download upon app startup.
      for (model in aicoreModels) {
        downloadModel(task = null, model = model)
      }
    }
  }

  private fun processPendingDownloads() {
    // Cancel all pending downloads for the retrieved models.
    downloadRepository.cancelAll {
      BaoLog.d(TAG, "All workers are cancelled.")

      viewModelScope.launch(Dispatchers.Main) {
        val checkedModelNames = mutableSetOf<String>()
        val tokenStatusAndData = getTokenStatusAndData()
        for (task in uiState.value.tasks) {
          for (model in task.models) {
            if (checkedModelNames.contains(model.name)) {
              continue
            }

            // Start download for partially downloaded models.
            val downloadStatus = uiState.value.modelDownloadStatus[model.name]?.status
            if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
              if (
                tokenStatusAndData.status == TokenStatus.NOT_EXPIRED &&
                  tokenStatusAndData.data != null
              ) {
                model.accessToken = tokenStatusAndData.data.accessToken
              }
              BaoLog.d(TAG, "Sending a new download request for '${model.name}'")
              downloadRepository.downloadModel(
                task = task,
                model = model,
                onStatusUpdated = this@ModelManagerViewModel::setDownloadStatus,
              )
            }

            checkedModelNames.add(model.name)
          }
        }
      }
    }
  }

  fun loadModelAllowlist() {
    _uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }

    viewModelScope.launch(Dispatchers.IO) {
      runCatching {

        // Clear existing allowlist models.
        _allowlistModels.clear()

        // Load model allowlist json.
        var modelAllowlist: ModelAllowlist? = null

        // Try to read the test allowlist first.
        BaoLog.d(TAG, "Loading test model allowlist.")
        modelAllowlist = readModelAllowlistFromDisk(fileName = MODEL_ALLOWLIST_TEST_FILENAME)

        // Local test only.
        if (TEST_MODEL_ALLOW_LIST.isNotEmpty()) {
          BaoLog.d(TAG, "Loading local model allowlist for testing.")
          runCatching {
              modelAllowlist = LenientJson.decodeFromString<ModelAllowlist>(TEST_MODEL_ALLOW_LIST)
            }
            .onFailure { e -> BaoLog.e(TAG, "Failed to parse local test json", e) }
        }

        if (modelAllowlist == null) {
          // Load from github.
          var version = ProjectConfig.versionName.replace(".", "_")
          val url = getAllowlistUrl(version)

          BaoLog.d(TAG, "Loading model allowlist from internet. Url: $url")
          val data = getJsonResponse<ModelAllowlist>(url = url)
          modelAllowlist = data?.jsonObj

          if (modelAllowlist == null) {
            BaoLog.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
            modelAllowlist = readModelAllowlistFromDisk()
          } else {
            BaoLog.d(TAG, "Done: loading model allowlist from internet")
            saveModelAllowlistToDisk(modelAllowlistContent = data.textContent)
          }
        }

        if (modelAllowlist == null) {
          val curTasks = getActiveCustomTasks().map { it.task }
          processTasks()
          _uiState.update {
            createUiState()
              .copy(
                loadingModelAllowlist = false,
                loadingModelAllowlistError = "Failed to load model list",
                tasks = curTasks,
                tasksByCategory = groupTasksByCategory(),
              )
          }
          return@launch
        }

        BaoLog.d(TAG, "Allowlist: $modelAllowlist")

        val isAICoreAvailable by lazy {
          // Build a fast-lookup set of all supported device models.
          // This extracts the models from all allowed groups, flattens them into a single stream,
          // lowercases them for case-insensitive matching, and stores them in a Set.
          val allowedDeviceModelsSet =
            modelAllowlist.aicoreRequirements
              ?.allowedDeviceGroups
              ?.asSequence()
              ?.flatMap { it.deviceModels }
              ?.map { it.lowercase() }
              ?.toSet()
          isAICoreSupported(allowedDeviceModelsSet)
        }

        // Convert models in the allowlist.
        val curTasks = getActiveCustomTasks().map { it.task }
        val nameToModel = mutableMapOf<String, Model>()
        for (allowedModel in modelAllowlist.models) {
          if (allowedModel.disabled == true) {
            continue
          }

          if (allowedModel.runtimeType == RuntimeType.AICORE && !isAICoreAvailable) {
            continue
          }

          // Ignore the allowedModel if its accelerator is only npu and this device's soc is not in
          // its socToModelFiles.
          val accelerators = allowedModel.defaultConfig.accelerators ?: ""
          val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
          if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
            val socToModelFiles = allowedModel.socToModelFiles
            if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
              BaoLog.d(
                TAG,
                "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
              )
              continue
            }
          }

          val model = allowedModel.toModel()
          _allowlistModels.add(model)
          nameToModel.put(model.name, model)
          for (taskType in allowedModel.taskTypes) {
            val task = curTasks.find { it.id == taskType }
            task?.models?.add(model)

            if (task?.id == BuiltInTaskId.LLM_TINY_GARDEN) {
              val newConfigs = model.configs.toMutableList()
              newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
              model.configs = newConfigs
            }
          }
        }

        // Find models from allowlist if a task's `modelNames` field is not empty.
        for (task in curTasks) {
          if (task.modelNames.isNotEmpty()) {
            for (modelName in task.modelNames) {
              val model = nameToModel[modelName]
              if (model == null) {
                BaoLog.w(TAG, "Model '${modelName}' in task '${task.label}' not found in allowlist.")
                continue
              }
              task.models.add(model)
            }
          }
        }

        // Process all tasks.
        processTasks()

        // Update UI state.
        _uiState.update {
          createUiState()
            .copy(
              loadingModelAllowlist = false,
              tasks = curTasks,
              tasksByCategory = groupTasksByCategory(),
            )
        }

        processPendingDownloads()

        checkAICoreModelStatuses()
        }
        .onFailure { e ->
          BaoLog.e(TAG, "Failed to load model allowlist", e)
          _uiState.update {
            it.copy(
              loadingModelAllowlist = false,
              loadingModelAllowlistError = "Failed to load model list: ${e.message}",
            )
          }
        }
    }
  }

  fun clearLoadModelAllowlistError() {
    viewModelScope.launch(Dispatchers.IO) {
      val curTasks = getActiveCustomTasks().map { it.task }
      processTasks()
      val newState =
        createUiState()
          .copy(
            loadingModelAllowlist = false,
            tasks = curTasks,
            loadingModelAllowlistError = "",
            tasksByCategory = groupTasksByCategory(),
          )
      _uiState.update {
        newState
      }
    }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  private fun saveModelAllowlistToDisk(modelAllowlistContent: String) {
    runCatching {
        BaoLog.d(TAG, "Saving model allowlist to disk...")
        val file = File(externalFilesDir, MODEL_ALLOWLIST_FILENAME)
        file.writeText(modelAllowlistContent)
        BaoLog.d(TAG, "Done: saving model allowlist to disk.")
      }
      .onFailure { e -> BaoLog.e(TAG, "failed to write model allowlist to disk", e) }
  }

  private fun readModelAllowlistFromDisk(
    fileName: String = MODEL_ALLOWLIST_FILENAME
  ): ModelAllowlist? {
    runCatching {
        BaoLog.d(TAG, "Reading model allowlist from disk: $fileName")
        val baseDir =
          if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) {
            File("/data/local/tmp")
          } else {
            externalFilesDir
          }
        val file = File(baseDir, fileName)
        if (file.exists()) {
          val content = file.readText()
          BaoLog.d(TAG, "Model allowlist content from local file: $content")

          return LenientJson.decodeFromString<ModelAllowlist>(content)
        }
      }
      .onFailure { e ->
        BaoLog.e(TAG, "failed to read model allowlist from disk", e)
        return null
      }

    return null
  }

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    // A model is partially downloaded when the tmp file exists.
    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
    return File(tmpFilePath).exists()
  }

  private fun createEmptyUiState(): ModelManagerUiState {
    return ModelManagerUiState(
      tasks = listOf(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = mapOf(),
      modelInitializationStatus = mapOf(),
    )
  }

  private fun createInitialUiState(): ModelManagerUiState {
    val tasks = getActiveCustomTasks().map { it.task }.toList()
    val tasksByCategory = tasks.groupBy { it.category.id }
    return ModelManagerUiState(
      tasks = tasks,
      tasksByCategory = tasksByCategory,
      modelDownloadStatus = mapOf(),
      modelInitializationStatus = mapOf(),
      loadingModelAllowlist = false,
    )
  }

  private suspend fun createUiState(): ModelManagerUiState {
    val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
    val modelInstances: MutableMap<String, ModelInitializationStatus> = mutableMapOf()
    val tasks: MutableMap<String, Task> = mutableMapOf()
    val checkedModelNames = mutableSetOf<String>()
    for (customTask in getActiveCustomTasks()) {
      val task = customTask.task
      tasks.put(key = task.id, value = task)
      for (model in task.models) {
        if (checkedModelNames.contains(model.name)) {
          continue
        }
        modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
        modelInstances[model.name] =
          ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
        checkedModelNames.add(model.name)
      }
    }

    // Load imported models.
    for (importedModel in dataStoreRepository.readImportedModels()) {
      BaoLog.d(TAG, "stored imported model: $importedModel")

      // Create model.
      val model = createModelFromImportedModelInfo(info = importedModel)

      // Add to task.
      tasks.get(key = BuiltInTaskId.LLM_CHAT)?.models?.add(model)
      tasks.get(key = BuiltInTaskId.LLM_PROMPT_LAB)?.models?.add(model)
      tasks.get(key = BuiltInTaskId.LLM_AGENT_CHAT)?.models?.add(model)
      if (model.llmSupportImage) {
        tasks.get(key = BuiltInTaskId.LLM_ASK_IMAGE)?.models?.add(model)
      }
      if (model.llmSupportAudio) {
        tasks.get(key = BuiltInTaskId.LLM_ASK_AUDIO)?.models?.add(model)
      }
      if (model.llmSupportTinyGarden) {
        tasks.get(key = BuiltInTaskId.LLM_TINY_GARDEN)?.models?.add(model)
        val newConfigs = model.configs.toMutableList()
        newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
        model.configs = newConfigs
        model.preProcess()
      }
      if (model.llmSupportMobileActions) {
        tasks.get(key = BuiltInTaskId.LLM_MOBILE_ACTIONS)?.models?.add(model)
      }

      // Update status.
      modelDownloadStatus[model.name] =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = importedModel.fileSize,
          totalBytes = importedModel.fileSize,
        )
    }

    val textInputHistory = dataStoreRepository.readTextInputHistory()
    BaoLog.d(TAG, "text input history: $textInputHistory")

    BaoLog.d(TAG, "model download status: $modelDownloadStatus")
    return ModelManagerUiState(
      tasks = getActiveCustomTasks().map { it.task }.toList(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = modelDownloadStatus,
      modelInitializationStatus = modelInstances,
      textInputHistory = textInputHistory,
    )
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: MutableList<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { acceleratorLabel ->
          when (acceleratorLabel.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU
            else -> null // Ignore unknown accelerator labels
          }
        }
        .toMutableList()
    val llmMaxToken = info.llmConfig.defaultMaxTokens
    val llmSupportImage = info.llmConfig.supportImage
    val llmSupportAudio = info.llmConfig.supportAudio
    val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
    val llmSupportMobileActions = info.llmConfig.supportMobileActions
    val llmSupportThinking = info.llmConfig.supportThinking
    val llmSupportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = llmMaxToken,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
          supportThinking = llmSupportThinking,
          supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
        )
        .toMutableList()
    val capabilities: MutableList<ModelCapability> = mutableListOf()
    val capabilityToTaskTypes: MutableMap<ModelCapability, List<String>> = mutableMapOf()
    if (llmSupportThinking) {
      capabilities.add(ModelCapability.LLM_THINKING)
      capabilityToTaskTypes[ModelCapability.LLM_THINKING] =
        listOf(
          BuiltInTaskId.LLM_CHAT,
          BuiltInTaskId.LLM_ASK_IMAGE,
          BuiltInTaskId.LLM_ASK_AUDIO,
        )
    }
    if (llmSupportSpeculativeDecoding) {
      capabilities.add(ModelCapability.SPECULATIVE_DECODING)
      capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] =
        listOf(
          BuiltInTaskId.LLM_CHAT,
          BuiltInTaskId.LLM_ASK_IMAGE,
          BuiltInTaskId.LLM_ASK_AUDIO,
          BuiltInTaskId.LLM_PROMPT_LAB,
          BuiltInTaskId.LLM_AGENT_CHAT,
        )
    }
    val model =
      Model(
        name = info.fileName,
        url = "",
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = "$IMPORTS_DIR/${info.fileName}",
        showBenchmarkButton = false,
        showRunAgainButton = false,
        imported = true,
        llmSupportImage = llmSupportImage,
        llmSupportAudio = llmSupportAudio,
        llmSupportTinyGarden = llmSupportTinyGarden,
        llmSupportMobileActions = llmSupportMobileActions,
        capabilities = capabilities.toList(),
        capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
        llmMaxToken = llmMaxToken,
        accelerators = accelerators,
        // We assume all imported models are LLM for now.
        isLlm = true,
        runtimeType = RuntimeType.LITERT_LM,
      )
    model.preProcess()

    return model
  }

  private fun groupTasksByCategory(): Map<String, List<Task>> {
    val tasks = getActiveCustomTasks().map { it.task }

    val categoryMap: Map<String, CategoryInfo> =
      tasks.associateBy { it.category.id }.mapValues { it.value.category }

    val groupedTasks = tasks.groupBy { it.category.id }
    val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
    // Sort the tasks in categories by pre-defined order. Sort other tasks by label.
    for (categoryId in groupedTasks.keys) {
      val sortedTasks =
        (groupedTasks[categoryId] ?: emptyList()).sortedWith { a, b ->
          if (categoryId == Category.LLM.id) {
            val order: List<String> =
              when (categoryId) {
                Category.LLM.id -> PREDEFINED_LLM_TASK_ORDER
                else -> listOf()
              }
            val indexA = order.indexOf(a.id)
            val indexB = order.indexOf(b.id)
            if (indexA != -1 && indexB != -1) {
              indexA.compareTo(indexB)
            } else if (indexA != -1) {
              -1
            } else if (indexB != -1) {
              1
            } else {
              val ca = categoryMap[a.id] ?: return@sortedWith 0
              val cb = categoryMap[b.id] ?: return@sortedWith 0
              val caLabel = getCategoryLabel(context = context, category = ca)
              val cbLabel = getCategoryLabel(context = context, category = cb)
              caLabel.compareTo(cbLabel)
            }
          } else {
            a.label.compareTo(b.label)
          }
        }
      for ((index, task) in sortedTasks.withIndex()) {
        task.index = index
      }
      groupedSortedTasks[categoryId] = sortedTasks
    }

    return groupedSortedTasks
  }

  private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
    val stringRes = category.labelStringRes
    val label = category.label
    if (stringRes != null) {
      return context.getString(stringRes)
    } else if (label != null) {
      return label
    }
    return context.getString(R.string.category_unlabeled)
  }

  /**
   * Retrieves the download status of a model.
   *
   * This function determines the download status of a given model by checking if it's fully
   * downloaded, partially downloaded, or not downloaded at all. It also retrieves the received and
   * total bytes for partially downloaded models.
   */
  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus {
    BaoLog.d(TAG, "Checking model ${model.name} download status...")

    if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
      BaoLog.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    var status = ModelDownloadStatusType.NOT_DOWNLOADED
    var receivedBytes = 0L
    var totalBytes = 0L

    // Partially downloaded.
    if (isModelPartiallyDownloaded(model = model)) {
      status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
      val tmpFilePath =
        model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
      val tmpFile = File(tmpFilePath)
      receivedBytes = tmpFile.length()
      totalBytes = model.totalBytes
      BaoLog.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
    }
    // Fully downloaded.
    else if (isModelDownloaded(model = model)) {
      status = ModelDownloadStatusType.SUCCEEDED
      BaoLog.d(TAG, "${model.name} has been downloaded.")
    }
    // Not downloaded.
    else {
      BaoLog.d(TAG, "${model.name} has not been downloaded.")
    }

    return ModelDownloadStatus(
      status = status,
      receivedBytes = receivedBytes,
      totalBytes = totalBytes,
    )
  }

  private fun isFileInExternalFilesDir(fileName: String): Boolean {
    if (externalFilesDir != null) {
      val file = File(externalFilesDir, fileName)
      return file.exists()
    } else {
      return false
    }
  }

  private fun isFileInDataLocalTmpDir(fileName: String): Boolean {
    val file = File("/data/local/tmp", fileName)
    return file.exists()
  }

  private fun deleteFileFromExternalFilesDir(fileName: String) {
    if (isFileInExternalFilesDir(fileName)) {
      val file = File(externalFilesDir, fileName)
      file.delete()
    }
  }

  /**
   * Deletes files from the the model imports directory whose absolute paths start with a given
   * prefix.
   */
  private fun deleteFilesFromImportDir(fileName: String) {
    val dir = context.getExternalFilesDir(null) ?: return

    val prefixAbsolutePath = "${context.getExternalFilesDir(null)}${File.separator}$fileName"
    val filesToDelete =
      File(dir, IMPORTS_DIR).listFiles { dirFile, name ->
        File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
      } ?: arrayOf()
    for (file in filesToDelete) {
      BaoLog.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  private fun deleteDirFromExternalFilesDir(dir: String) {
    if (isFileInExternalFilesDir(dir)) {
      val file = File(externalFilesDir, dir)
      file.deleteRecursively()
    }
  }

  private fun updateModelInitializationStatus(
    model: Model,
    status: ModelInitializationStatusType,
    error: String = "",
  ) {
    val curModelInstance = uiState.value.modelInitializationStatus.toMutableMap()
    val initializedBackends = curModelInstance[model.name]?.initializedBackends ?: setOf()
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val newInitializedBackends =
      if (status == ModelInitializationStatusType.INITIALIZED) {
        initializedBackends + backend
      } else {
        initializedBackends
      }
    curModelInstance[model.name] =
      ModelInitializationStatus(
        status = status,
        error = error,
        initializedBackends = newInitializedBackends,
      )
    _uiState.update { it.copy(modelInitializationStatus = curModelInstance) }
  }

  @androidx.annotation.VisibleForTesting
  fun isModelDownloaded(model: Model): Boolean {
    model.updatable = false
    // First, check if the model with the current (latest) version has been downloaded.
    if (checkIfModelDownloaded(model, model.version)) return true

    // If not, check if any updatable model file (previous version) has been downloaded.
    for (updatableFile in model.updatableModelFiles) {
      if (updatableFile.commitHash.isEmpty()) continue
      if (checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)) {
        // If an updatable version is found on the device, update the model's version and file name
        // to match the downloaded one, and mark it as updatable.
        model.version = updatableFile.commitHash
        model.downloadFileName = updatableFile.fileName
        model.updatable = true
        return true
      }
    }

    return false
  }

  private fun checkIfModelDownloaded(
    model: Model,
    version: String,
    fileName: String = model.downloadFileName,
  ): Boolean {
    val modelRelativePath =
      listOf(model.normalizedName, version, fileName).joinToString(File.separator)
    val downloadedFileExists =
      fileName.isNotEmpty() &&
        ((model.localModelFilePathOverride.isEmpty() &&
          isFileInExternalFilesDir(modelRelativePath)) ||
          (model.localModelFilePathOverride.isNotEmpty() &&
            File(model.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.isZip &&
        model.unzipDir.isNotEmpty() &&
        isFileInExternalFilesDir(
          listOf(model.normalizedName, version, model.unzipDir).joinToString(File.separator)
        )

    return downloadedFileExists || unzippedDirectoryExists
  }
}

private fun getAllowlistUrl(version: String): String {
  return "$ALLOWLIST_BASE_URL/${version}.json"
}
