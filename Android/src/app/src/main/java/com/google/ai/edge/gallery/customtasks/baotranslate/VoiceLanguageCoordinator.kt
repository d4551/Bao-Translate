package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

internal class VoiceLanguageCoordinator(
  private val pipelines: PipelineLifecycleManager,
  private val voiceProfileManager: VoiceProfileManager,
  private val bleManager: BleConversationManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  private val getApp: () -> Application,
  private val localParticipantId: String,
  private val reinitializePipeline: (String) -> Unit,
) {

  fun setSourceLanguage(language: String) {
    uiState.update { state ->
      val updated = state.copy(sourceLanguage = language, detectedLanguage = null)
      val participant = updateLocalParticipant(updated)
      updated.copy(localParticipant = participant)
    }
  }

  fun setTargetLanguage(language: String) {
    if (language == SupportedLanguages.AUTO.key) return
    uiState.update { state ->
      val updated = state.copy(targetLanguage = language)
      val participant = updateLocalParticipant(updated)
      updated.copy(localParticipant = participant)
    }
  }

  fun onLanguageChanged(source: String, target: String) {
    if (target == SupportedLanguages.AUTO.key) return
    uiState.update { state ->
      val updated = state.copy(sourceLanguage = source, targetLanguage = target)
      val participant = updateLocalParticipant(updated)
      updated.copy(localParticipant = participant)
    }
  }

  fun swapLanguages() {
    if (uiState.value.sourceLanguage == SupportedLanguages.AUTO.key) return
    val src = uiState.value.sourceLanguage
    val tgt = uiState.value.targetLanguage
    uiState.update { state ->
      val updated = state.copy(
        sourceLanguage = tgt,
        targetLanguage = src,
      )
      val participant = updateLocalParticipant(updated)
      updated.copy(localParticipant = participant)
    }
  }

  fun onVoiceEnrolled(audioPath: String) {
    uiState.update { state ->
      val updated = state.copy(
        voiceProfileEnrolled = true,
        voiceProfilePath = audioPath,
      )
      val participant = updateLocalParticipant(updated)
      updated.copy(localParticipant = participant)
    }

    viewModelScope.launch(Dispatchers.IO) {
      val file = File(audioPath)
      if (file.exists()) {
        pipelines.voiceCloneTts?.setReferenceAudio(audioPath)
      }
    }
  }

  fun startEnrollmentRecording(audioPcm: ShortArray, sampleRate: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val profile = voiceProfileManager.saveProfile(audioPcm, sampleRate)
      uiState.update { state ->
        val updated = state.copy(
          voiceProfileEnrolled = true,
          voiceProfilePath = profile.wavPath,
        )
        val participant = updateLocalParticipant(updated.copy(localParticipant = updated.localParticipant?.copy(id = profile.id)))
        updated.copy(localParticipant = participant)
      }
      pipelines.voiceCloneTts?.setReferenceAudio(profile.wavPath)
    }
  }

  fun deleteVoiceProfile() {
    viewModelScope.launch(Dispatchers.IO) {
      voiceProfileManager.deleteProfile()
      pipelines.voiceCloneTts?.clearReferenceAudio()
      uiState.update { state ->
        val updated = state.copy(
          voiceProfileEnrolled = false,
          voiceProfilePath = null,
          ttsEngine = if (state.ttsEngine == "pocket_tts") "kokoro" else state.ttsEngine,
        )
        val participant = updateLocalParticipant(updated)
        updated.copy(localParticipant = participant)
      }
    }
  }

  fun setSttModel(model: String) {
    val supportedSttModels = setOf("whisper_base")
    if (model !in supportedSttModels) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Error(getApp().getString(R.string.bao_translate_error_model_not_available, model))) }
      return
    }
    uiState.update { it.copy(sttModel = model) }
    reinitializePipeline("stt")
  }

  fun setTranslationModel(model: String) {
    val supportedTranslationModels = setOf("qwen25_1b", "gemma4_e2b")
    if (model !in supportedTranslationModels) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Error(getApp().getString(R.string.bao_translate_error_model_not_available, model))) }
      return
    }
    if (uiState.value.modelStatuses[model] != ModelStatus.Ready) {
      uiState.update {
        it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_translation_model_not_ready))
      }
      return
    }
    uiState.update { it.copy(translationModel = model) }
    reinitializePipeline("translation")
  }

  fun setTtsEngine(engine: String) {
    val supported = setOf("kokoro", "pocket_tts")
    if (engine !in supported) return
    if (engine == "pocket_tts") {
      val state = uiState.value
      val pocketReady = state.modelStatuses["pocket_tts"] == ModelStatus.Ready && pipelines.voiceCloneTts != null
      if (!pocketReady || !state.voiceProfileEnrolled) {
        uiState.update {
          it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_voice_clone_not_ready))
        }
        return
      }
    }
    uiState.update { it.copy(ttsEngine = engine) }
  }

  fun setWifiOnly(enabled: Boolean) {
    uiState.update { it.copy(wifiOnlyDownloads = enabled) }
  }

  fun onSettingChanged(key: String) {
    reinitializePipeline(key)
  }

  fun updateLocalParticipant(state: BaoTranslateUiState): Participant {
    val app = getApp()
    val participant = (state.localParticipant ?: Participant(
      id = localParticipantId,
      name = app.getString(R.string.bao_translate_you),
      sourceLanguage = state.sourceLanguage,
      targetLanguage = state.targetLanguage,
      isConnected = true,
      hasVoiceProfile = state.voiceProfileEnrolled,
      audioDeviceName = state.currentAudioDevice.toString(),
    )).copy(
      sourceLanguage = state.sourceLanguage,
      targetLanguage = state.targetLanguage,
      hasVoiceProfile = state.voiceProfileEnrolled,
      audioDeviceName = state.currentAudioDevice.toString(),
    )
    bleManager.setLocalParticipant(participant)
    return participant
  }
}
