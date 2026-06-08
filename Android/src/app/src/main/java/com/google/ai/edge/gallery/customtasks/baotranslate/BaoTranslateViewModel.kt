package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.common.BaoLog
import androidx.annotation.VisibleForTesting
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
import com.google.ai.edge.gallery.data.BaoTranslateStoredSettings
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

sealed interface PipelineStatus {
  data object Idle : PipelineStatus
  data object Initializing : PipelineStatus
  data object StartingRecording : PipelineStatus
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
  val targetLanguage: String = SupportedLanguages.DEFAULT_TARGET_KEY,
  val voiceProfileEnrolled: Boolean = false,
  val voiceProfilePath: String? = null,
  val errorMessage: String? = null,
  val modelsReady: Boolean = false,
  val modelStatuses: Map<String, ModelStatus> = emptyMap(),
  val amplitudes: List<Float> = emptyList(),
  val elapsedSeconds: Float = 0f,
  val liveTranslationPreview: String? = null,
  val currentAudioDevice: AudioDevice = AudioDevice.Speaker,
  val availableAudioDevices: List<AudioDevice> = emptyList(),
  val availableInputDevices: List<com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioInputOption> = emptyList(),
  val preferredInputDevice: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice.BluetoothHeadset? = null,
  val routingStatus: com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus = com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus.IDLE,
  val sttModel: String = "whisper_base",
  val translationModel: String = "qwen25_1b",
  val wifiOnlyDownloads: Boolean = true,
  val storageBreakdown: Map<String, Long> = emptyMap(),
  val localParticipant: Participant? = null,
  val detectedLanguage: String? = null,
  val welcomeDismissed: Boolean = false,
) {
  val isRecording: Boolean get() = pipelineStatus == PipelineStatus.Recording
  val isStartingRecording: Boolean get() = pipelineStatus == PipelineStatus.StartingRecording
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
    resolveTranslationModel = ::resolveAndPersistTranslationModel,
  )

  internal companion object {
    // Test-only overrides: when set, the ViewModel ignores the corresponding persisted setting.
    // Lets instrumentation pin a deterministic, fast config (qwen25_1b + English->Spanish)
    // regardless of whatever a prior session persisted. Never set in production.
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testForcedTranslationModel: String? = null
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testForcedSourceLanguage: String? = null
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testForcedTargetLanguage: String? = null

    // Test-only: the most recently created instance, so instrumentation can reach the live
    // bleManager + uiState to verify multi-speaker receive routing. Cleared in onCleared.
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testInstance: BaoTranslateViewModel? = null
  }

  init {
    testInstance = this
    bleManager.setLocalEmbeddingProvider { pipelines.openVoiceTargetSe }
    val storedSettings = dataStoreRepository.getBaoTranslateSettings()
    _uiState.update {
      it.copy(
        welcomeDismissed = dataStoreRepository.getHasDismissedBaoTranslateWelcome(),
        transcripts = emptyList(),
        // Restore persisted preferences so model/language/tts choices survive a cold start.
        // Null => never saved, keep the in-memory defaults.
        translationModel = testForcedTranslationModel
          ?: storedSettings?.translationModel?.takeIf { m -> m.isNotBlank() } ?: it.translationModel,
        sourceLanguage = testForcedSourceLanguage
          ?: storedSettings?.sourceLanguage?.takeIf { l -> l.isNotBlank() } ?: it.sourceLanguage,
        targetLanguage = testForcedTargetLanguage
          ?: storedSettings?.targetLanguage?.takeIf { l -> l.isNotBlank() } ?: it.targetLanguage,
        wifiOnlyDownloads = storedSettings?.wifiOnlyDownloads ?: it.wifiOnlyDownloads,
      )
    }

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
        // Broadcast once on the committed participant, outside the update{} CAS lambda.
        _uiState.value.localParticipant?.let { bleManager.setLocalParticipant(it) }
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
        // Translation and TTS operate on ISO codes (mirroring the local recording path); the KEYs
        // ("German", "Korean", ...) are kept only for the display fields. Passing a KEY to
        // synthesizeSpeech silently broke platform-TTS for non-Kokoro target languages, because
        // PlatformTtsPipeline feeds it to Locale.forLanguageTag (which needs "de"/"ko", not
        // "German"/"Korean"). codeFor() normalizes a key->code and is a no-op on an already-ISO code.
        val sourceCode = SupportedLanguages.codeFor(bleMsg.sourceLanguage)
        val targetCode = SupportedLanguages.codeFor(targetLang)
        var translationSucceeded = false
        val translatedText = if (translation != null) {
          when (val outcome = translation.translateBlocking(
            sourceText = bleMsg.text,
            sourceLanguage = sourceCode,
            targetLanguage = targetCode,
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
          // Speak the peer's turn in THEIR own cloned voice when they've shared a timbre over BLE
          // (multi-speaker cloning); otherwise synthesizeSpeech falls back to the local voice/TTS.
          val audioPlayed = recordingController.synthesizeSpeech(
            translatedText,
            targetCode,
            speakerSe = bleManager.voiceEmbeddingFor(bleMsg.senderId),
            timbre = SpeechTimbre.PeerOnly,
          )
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

      pipelines.initializePipelines(app, resolveAndPersistTranslationModel(app), sttLanguageCode())
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
      // Re-broadcast metadata so connected peers pick up the (just-loaded) enrolled timbre.
      bleManager.rebroadcastMetadata()

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
      // Cancel and await any in-flight download of this model BEFORE deleting its files, so the
      // delete can't race a live writer (no surviving partial, no model resurrected by a late
      // completion write).
      downloadCoordinator.cancelDownload(modelId)
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
        "qwen25_1b", "gemma4_e2b" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.translationPipeline?.cleanup()
            pipelines.translationPipeline = null
          }
        }
        "kokoro_tts" -> {
          pipelines.pipelineMutex.withLock {
            pipelines.kokoroTts?.cleanup()
            pipelines.kokoroTts = null
          }
        }
      }
      modelManager.deleteModel(app, modelId)
      // If the deleted model was the active translation model, fall back to one that still exists
      // and PERSIST it, so a cold start can't restore a pointer to the deleted model and wedge the
      // pipeline into ModelsNotReady while the required model is present.
      resolveAndPersistTranslationModel(app)
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
      // Cancel and await every in-flight download before wiping the model dirs (avoids the
      // delete-vs-download race and a download re-marking a model Ready after the wipe).
      downloadCoordinator.cancelAllDownloads()
      pipelines.pipelineMutex.withLock {
        pipelines.cleanupPipelinesLocked()
      }
      modelManager.deleteAllModels(app)
      // No translation model remains; reset the persisted selection to the required default.
      resolveAndPersistTranslationModel(app)
      _uiState.update { it.copy(
        storageBreakdown = emptyMap(),
        modelsReady = false,
        pipelineStatus = PipelineStatus.ModelsNotReady,
      ) }
    }
  }

  // Returns a translation model whose files are actually present on disk. When the selected/persisted
  // model is missing (deleted, or a partial install), falls back to the required qwen25_1b (or any
  // other ready translation model) and PERSISTS the correction. Self-heals the cold-start brick where
  // a stale persisted model id forced ModelsNotReady even though a usable model was installed.
  private fun resolveAndPersistTranslationModel(app: Application): String {
    val current = _uiState.value.translationModel
    if (modelManager.checkModelStatus(app, current) == ModelStatus.Ready) return current
    val fallback = listOf("qwen25_1b", "gemma4_e2b")
      .firstOrNull { modelManager.checkModelStatus(app, it) == ModelStatus.Ready }
      ?: "qwen25_1b"
    if (fallback != current) {
      _uiState.update { it.copy(translationModel = fallback) }
      persistBaoTranslateSettings()
      BaoLog.w(TAG, "Translation model '$current' unavailable; fell back to '$fallback'")
    }
    return fallback
  }

  fun startRecording() = recordingController.startRecording()

  fun stopRecording() = recordingController.stopRecording()

  // Clears this device's conversation transcript and any in-flight preview. Local-only: it does NOT
  // touch BLE participants/connection state, the enrolled voice profile, downloaded models, or the
  // recording session. No-op while recording so it can't race RecordingController's append path.
  fun clearTranscripts() {
    if (_uiState.value.isRecording || _uiState.value.isStartingRecording) return
    _uiState.update {
      it.copy(
        transcripts = emptyList(),
        liveTranslationPreview = null,
        detectedLanguage = null,
        errorMessage = null,
      )
    }
  }

  // Leaves a live conversation: disconnects the peer and stops advertising/scanning. Wired to the
  // per-device disconnect control in ConversationModeScreen (the BLE manager already supports it; it
  // was never reachable from the UI before).
  fun disconnectPeer(deviceAddress: String) = bleManager.disconnectFromDevice(deviceAddress)

  fun leaveConversation() = bleManager.stopConversationDiscovery()

  fun setSourceLanguage(language: String) {
    voiceLanguageCoordinator.setSourceLanguage(language)
    persistBaoTranslateSettings()
  }

  fun setTargetLanguage(language: String) {
    voiceLanguageCoordinator.setTargetLanguage(language)
    persistBaoTranslateSettings()
  }

  fun onLanguageChanged(source: String, target: String) {
    voiceLanguageCoordinator.onLanguageChanged(source, target)
    persistBaoTranslateSettings()
  }

  fun swapLanguages() {
    voiceLanguageCoordinator.swapLanguages()
    persistBaoTranslateSettings()
  }

  fun onVoiceEnrolled(audioPath: String) = voiceLanguageCoordinator.onVoiceEnrolled(audioPath)

  fun startEnrollmentRecording(audioPcm: ShortArray, sampleRate: Int) = voiceLanguageCoordinator.startEnrollmentRecording(audioPcm, sampleRate)

  fun deleteVoiceProfile() = voiceLanguageCoordinator.deleteVoiceProfile()

  fun setSttModel(model: String) = voiceLanguageCoordinator.setSttModel(model)

  fun setTranslationModel(model: String) {
    voiceLanguageCoordinator.setTranslationModel(model)
    persistBaoTranslateSettings()
  }

  fun setWifiOnly(enabled: Boolean) {
    voiceLanguageCoordinator.setWifiOnly(enabled)
    persistBaoTranslateSettings()
  }

  private fun persistBaoTranslateSettings() {
    val s = _uiState.value
    viewModelScope.launch(Dispatchers.IO) {
      dataStoreRepository.setBaoTranslateSettings(
        BaoTranslateStoredSettings(
          translationModel = s.translationModel,
          sourceLanguage = s.sourceLanguage,
          targetLanguage = s.targetLanguage,
          wifiOnlyDownloads = s.wifiOnlyDownloads,
        )
      )
    }
  }

  fun onSettingChanged(key: String) = voiceLanguageCoordinator.onSettingChanged(key)

  // Whisper decode language for the chosen source: "" (auto-detect) only when the user picks Auto;
  // otherwise force the selected language so recognition isn't corrupted by mis-detection.
  private fun sttLanguageCode(): String =
    if (_uiState.value.sourceLanguage == SupportedLanguages.AUTO.key) ""
    else SupportedLanguages.codeFor(_uiState.value.sourceLanguage)

  private fun reinitializePipeline(component: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val app = getApplication<Application>()
      _uiState.update { it.copy(pipelineStatus = PipelineStatus.Initializing) }

      pipelines.reinitializePipeline(app, component, _uiState.value.translationModel, sttLanguageCode())

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

  @VisibleForTesting
  internal fun setTestLocalVoiceEmbeddingForTest(embedding: FloatArray?) {
    pipelines.openVoiceTargetSe = embedding
    _uiState.update { it.copy(voiceProfileEnrolled = embedding != null) }
  }

  override fun onCleared() {
    super.onCleared()
    if (testInstance === this) testInstance = null
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
