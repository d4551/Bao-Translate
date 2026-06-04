package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "BaoTranslateVM"
private const val ALL_DOWNLOADS = "__all_downloads__"

sealed interface PipelineStatus {
  data object Idle : PipelineStatus
  data object Initializing : PipelineStatus
  data object Recording : PipelineStatus
  data object Processing : PipelineStatus
  data object Speaking : PipelineStatus
  data object ModelsNotReady : PipelineStatus
  data class Error(val message: String) : PipelineStatus
}

data class BaoTranslateUiState(
  val pipelineStatus: PipelineStatus = PipelineStatus.Idle,
  val transcripts: List<TranslationMessage> = emptyList(),
  val sourceLanguage: String = SupportedLanguages.AUTO.key,
  val targetLanguage: String = SupportedLanguages.ALL[2].key,
  val voiceProfileEnrolled: Boolean = false,
  val voiceProfilePath: String? = null,
  val errorMessage: String? = null,
  val modelsReady: Boolean = false,
  val modelStatuses: Map<String, ModelStatus> = emptyMap(),
  val amplitudes: List<Float> = emptyList(),
  val elapsedSeconds: Float = 0f,
  val currentAudioDevice: AudioDevice = AudioDevice.Speaker,
  val availableAudioDevices: List<AudioDevice> = emptyList(),
  val availableInputDevices: List<com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioInputOption> = emptyList(),
  val preferredInputDevice: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice.BluetoothHeadset? = null,
  val routingStatus: com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus = com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus.IDLE,
  val sttModel: String = "whisper_base",
  val translationModel: String = "qwen25_1b",
  val ttsEngine: String = "kokoro",
  val wifiOnlyDownloads: Boolean = true,
  val storageBreakdown: Map<String, Long> = emptyMap(),
  val localParticipant: Participant? = null,
  val detectedLanguage: String? = null,
  val welcomeDismissed: Boolean = false,
) {
  val isRecording: Boolean get() = pipelineStatus == PipelineStatus.Recording
  val isProcessing: Boolean get() = pipelineStatus == PipelineStatus.Processing
  val isSpeaking: Boolean get() = pipelineStatus == PipelineStatus.Speaking
  val isInitializing: Boolean get() = pipelineStatus == PipelineStatus.Initializing
  val totalStorageMb: Float
    get() = storageBreakdown.values.sum().toFloat() / (1024f * 1024f)

  val requiredModelsReady: Boolean
    get() {
      val whisper = modelStatuses["whisper_base"]
      val translation = modelStatuses["qwen25_1b"]
      val vad = modelStatuses["silero_vad"]
      val tts = modelStatuses["kokoro_tts"]
      return whisper == ModelStatus.Ready &&
        translation == ModelStatus.Ready &&
        vad == ModelStatus.Ready &&
        tts == ModelStatus.Ready
    }

  val allModelsReady: Boolean
    get() = modelStatuses.values.all { it == ModelStatus.Ready }
}

@HiltViewModel
class BaoTranslateViewModel @Inject constructor(
  application: Application,
  private val dataStoreRepository: DataStoreRepository,
) : AndroidViewModel(application) {

  private val _uiState = MutableStateFlow(BaoTranslateUiState())
  val uiState: StateFlow<BaoTranslateUiState> = _uiState

  val bleManager = BleConversationManager(application)
  val audioRouter = AudioRouter(application)
  private val voiceProfileManager = VoiceProfileManager(application)

  private val pipelines = PipelineLifecycleManager()
  private val localParticipantId = UUID.randomUUID().toString()
  private val modelManager = BaoTranslateModelManager

  private val participantStateManager = ParticipantStateManager(
    pipelines = pipelines,
    audioRouter = audioRouter,
    voiceProfileManager = voiceProfileManager,
    bleManager = bleManager,
    uiState = _uiState,
    localParticipantId = localParticipantId,
    getApp = { getApplication() },
  )

  private val viewModelJob = SupervisorJob()
  val viewModelScope = CoroutineScope(viewModelJob + Dispatchers.Main.immediate)

  private val voiceLanguageCoordinator = VoiceLanguageCoordinator(
    pipelines = pipelines,
    voiceProfileManager = voiceProfileManager,
    bleManager = bleManager,
    uiState = _uiState,
    viewModelScope = viewModelScope,
    getApp = { getApplication() },
    localParticipantId = localParticipantId,
    reinitializePipeline = ::reinitializePipeline,
  )

  private val recordingController = RecordingController(
    pipelines = pipelines,
    audioRouter = audioRouter,
    bleManager = bleManager,
    uiState = _uiState,
    viewModelScope = viewModelScope,
    getApp = { getApplication() },
  )

  private val downloadCoordinator = ModelDownloadCoordinator(
    pipelines = pipelines,
    modelManager = modelManager,
    uiState = _uiState,
    viewModelScope = viewModelScope,
    getApp = { getApplication() },
    refreshLocalRuntimeState = participantStateManager::refreshLocalRuntimeState,
  )

  init {
    _uiState.update { it.copy(welcomeDismissed = dataStoreRepository.getHasDismissedBaoTranslateWelcome(), transcripts = emptyList()) }

    modelManager.refreshStatuses(application)
    _uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(application)) }

    viewModelScope.launch {
      modelManager.modelStatuses.collect { statuses ->
        val requiredReady =
          statuses["whisper_base"] == ModelStatus.Ready &&
            statuses["qwen25_1b"] == ModelStatus.Ready &&
            statuses["silero_vad"] == ModelStatus.Ready &&
            statuses["kokoro_tts"] == ModelStatus.Ready
        _uiState.update { state ->
          state.copy(
            modelStatuses = statuses,
            modelsReady = requiredReady && pipelines.requiredPipelinesReady(),
          )
        }
      }
    }

    viewModelScope.launch {
      audioRouter.currentDevice.collect { device ->
        _uiState.update { state ->
          val updated = state.copy(currentAudioDevice = device)
          val participant = participantStateManager.updateLocalParticipant(updated)
          updated.copy(localParticipant = participant)
        }
      }
    }

    viewModelScope.launch {
      audioRouter.availableOutputDevices.collect { devices ->
        _uiState.update { it.copy(availableAudioDevices = devices) }
      }
    }

    viewModelScope.launch {
      audioRouter.availableInputDevices.collect { inputs ->
        _uiState.update { it.copy(availableInputDevices = inputs) }
      }
    }

    viewModelScope.launch {
      audioRouter.preferredInputDevice.collect { device ->
        _uiState.update { it.copy(preferredInputDevice = device) }
      }
    }

    viewModelScope.launch {
      audioRouter.routingStatus.collect { status ->
        _uiState.update { it.copy(routingStatus = status) }
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      bleManager.messages.collect { bleMsg ->
        val (translation, targetLang) = pipelines.pipelineMutex.withLock {
          pipelines.translationPipeline to _uiState.value.targetLanguage
        }
        var translationSucceeded = false
        val translatedText = if (translation != null) {
          when (val outcome = translation.translateBlocking(
            sourceText = bleMsg.text,
            sourceLanguage = bleMsg.sourceLanguage,
            targetLanguage = targetLang,
          )) {
            is TranslationOutcome.Success -> {
              translationSucceeded = true
              outcome.result.translatedText
            }
            is TranslationOutcome.Failure -> {
              _uiState.update { it.copy(errorMessage = getApplication<Application>().getString(R.string.bao_translate_error_translation_failed, outcome.reason)) }
              bleMsg.text
            }
          }
        } else {
          bleMsg.text
        }

        val messageId = UUID.randomUUID().toString()
        val message = TranslationMessage(
          id = messageId,
          originalText = bleMsg.text,
          translatedText = translatedText,
          sourceLanguage = bleMsg.sourceLanguage,
          targetLanguage = targetLang,
          timestamp = bleMsg.timestamp,
          isUser = false,
          speakerName = bleMsg.senderName,
        )
        _uiState.update { it.copy(transcripts = it.transcripts + message) }

        // Speak the peer's translated message aloud so a live conversation can be *heard*, not just
        // read — mirroring the local-speech path. Previously received messages were silent
        // (synthesizeSpeech was never called and audioPlayed stayed null).
        if (translationSucceeded) {
          val audioPlayed = recordingController.synthesizeSpeech(translatedText, targetLang)
          _uiState.update { state ->
            state.copy(
              transcripts = state.transcripts.map { existing ->
                if (existing.id == messageId) existing.copy(audioPlayed = audioPlayed) else existing
              },
            )
          }
        }
      }
    }
  }

  fun initializeModels() {
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      _uiState.update { it.copy(pipelineStatus = PipelineStatus.Initializing) }

      modelManager.refreshStatuses(app)
      _uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(app)) }
      participantStateManager.refreshLocalRuntimeState(app)

      if (!modelManager.areRequiredModelsReady(app)) {
        _uiState.update { it.copy(
          pipelineStatus = PipelineStatus.ModelsNotReady,
        ) }
        return@launch
      }

      pipelines.initializePipelines(app, _uiState.value.translationModel)
      if (!pipelines.requiredPipelinesReady()) {
        val missing = pipelines.missingPipelineComponents(app)
        pipelines.cleanupPipelines()
        _uiState.update { it.copy(
          modelsReady = false,
          pipelineStatus = PipelineStatus.ModelsNotReady,
          errorMessage = app.getString(
            R.string.bao_translate_error_runtime_not_ready,
            missing.joinToString(),
          ),
        ) }
        return@launch
      }

      participantStateManager.refreshLocalRuntimeState(app)

      _uiState.update {
        it.copy(
          modelsReady = true,
          pipelineStatus = PipelineStatus.Idle,
        )
      }
    }
  }

  fun downloadModel(modelId: String) = downloadCoordinator.downloadModel(modelId)

  fun downloadRequiredModels() = downloadCoordinator.downloadRequiredModels()

  fun deleteModel(modelId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      when (modelId) {
        "whisper_base" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.whisperPipeline?.cleanup()
            pipelines.whisperPipeline = null
          }
        }
        "silero_vad" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.vadProcessor?.cleanup()
            pipelines.vadProcessor = null
          }
        }
        "qwen25_1b" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.translationPipeline?.cleanup()
            pipelines.translationPipeline = null
          }
        }
        "gemma4_e2b" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.translationPipeline?.cleanup()
            pipelines.translationPipeline = null
          }
          if (_uiState.value.translationModel == "gemma4_e2b") {
            _uiState.update { it.copy(translationModel = "qwen25_1b") }
          }
        }
        "kokoro_tts" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.kokoroTts?.cleanup()
            pipelines.kokoroTts = null
          }
        }
        "pocket_tts" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.voiceCloneTts?.cleanup()
            pipelines.voiceCloneTts = null
          }
          if (_uiState.value.ttsEngine == "pocket_tts") {
            _uiState.update { it.copy(ttsEngine = "kokoro") }
          }
        }
      }
      modelManager.deleteModel(app, modelId)
      val requiredReady = modelManager.areRequiredModelsReady(app)
      _uiState.update { it.copy(
        storageBreakdown = modelManager.getStorageBreakdown(app),
        modelsReady = requiredReady && pipelines.requiredPipelinesReady(),
        pipelineStatus = if (requiredReady) PipelineStatus.Idle else PipelineStatus.ModelsNotReady,
      ) }
    }
  }

  fun deleteAllModels() {
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      pipelines.pipelineMutex.withLock {
        pipelines.cleanupPipelinesLocked()
      }
      modelManager.deleteAllModels(app)
      _uiState.update { it.copy(
        storageBreakdown = emptyMap(),
        modelsReady = false,
        pipelineStatus = PipelineStatus.ModelsNotReady,
        ttsEngine = "kokoro",
      ) }
    }
  }

  fun startRecording() = recordingController.startRecording()

  fun stopRecording() = recordingController.stopRecording()

  fun setSourceLanguage(language: String) = voiceLanguageCoordinator.setSourceLanguage(language)

  fun setTargetLanguage(language: String) = voiceLanguageCoordinator.setTargetLanguage(language)

  fun onLanguageChanged(source: String, target: String) = voiceLanguageCoordinator.onLanguageChanged(source, target)

  fun swapLanguages() = voiceLanguageCoordinator.swapLanguages()

  fun onVoiceEnrolled(audioPath: String) = voiceLanguageCoordinator.onVoiceEnrolled(audioPath)

  fun startEnrollmentRecording(audioPcm: ShortArray, sampleRate: Int) = voiceLanguageCoordinator.startEnrollmentRecording(audioPcm, sampleRate)

  fun deleteVoiceProfile() = voiceLanguageCoordinator.deleteVoiceProfile()

  fun setSttModel(model: String) = voiceLanguageCoordinator.setSttModel(model)

  fun setTranslationModel(model: String) = voiceLanguageCoordinator.setTranslationModel(model)

  fun setTtsEngine(engine: String) = voiceLanguageCoordinator.setTtsEngine(engine)

  fun setWifiOnly(enabled: Boolean) = voiceLanguageCoordinator.setWifiOnly(enabled)

  fun onSettingChanged(key: String) = voiceLanguageCoordinator.onSettingChanged(key)

  private fun reinitializePipeline(component: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      _uiState.update { it.copy(pipelineStatus = PipelineStatus.Initializing) }

      pipelines.reinitializePipeline(app, component, _uiState.value.translationModel)

      if (pipelines.requiredPipelinesReady()) {
        participantStateManager.refreshLocalRuntimeState(app)
        _uiState.update { it.copy(
          modelsReady = true,
          pipelineStatus = PipelineStatus.Idle,
          errorMessage = null,
        ) }
      } else {
        val missing = pipelines.missingPipelineComponents(app)
        _uiState.update { it.copy(
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

  fun refreshAudioDevice() {
    _uiState.update { it.copy(
      currentAudioDevice = audioRouter.detectCurrentDevice(),
      availableAudioDevices = audioRouter.getAvailableOutputDevices(),
    ) }
  }

  fun selectAudioDevice(device: AudioDevice) {
    when (device) {
      is AudioDevice.BluetoothHeadset -> audioRouter.preferBluetooth(target = device)
      is AudioDevice.WiredHeadset -> audioRouter.selectWired(device)
      AudioDevice.Speaker -> audioRouter.resetToSpeaker()
    }
  }

  fun selectInputDevice(device: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice.BluetoothHeadset?) {
    audioRouter.selectPreferredInput(device)
  }

  fun setErrorMessage(message: String) {
    _uiState.update { it.copy(errorMessage = message, pipelineStatus = PipelineStatus.Idle) }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null, pipelineStatus = PipelineStatus.Idle) }
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun retryLastAction() {
    clearError()
    if (_uiState.value.requiredModelsReady) {
      initializeModels()
    }
  }

  fun dismissWelcome() {
    viewModelScope.launch { dataStoreRepository.setHasDismissedBaoTranslateWelcome(true) }
    _uiState.update { it.copy(welcomeDismissed = true) }
  }

  override fun onCleared() {
    super.onCleared()
    recordingController.recordingJob?.cancel()
    viewModelJob.cancel()
    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    cleanupScope.launch {
      pipelines.cleanupPipelines()
      audioRouter.cleanup()
      bleManager.cleanup()
    }
  }
}
