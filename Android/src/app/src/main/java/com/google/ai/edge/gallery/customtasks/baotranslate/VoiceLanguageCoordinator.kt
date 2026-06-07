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
    if (uiState.value.sourceLanguage == language) return
    applyLocalParticipantUpdate { it.copy(sourceLanguage = language, detectedLanguage = null) }
    // Re-init STT so Whisper decodes in the newly-selected language. Auto-detect mis-IDs several
    // languages (es/it/pt -> "Latin"); forcing the chosen one fixes recognition.
    reinitializePipeline("stt")
  }

  fun setTargetLanguage(language: String) {
    if (language == SupportedLanguages.AUTO.key) return
    applyLocalParticipantUpdate { it.copy(targetLanguage = language) }
  }

  fun onLanguageChanged(source: String, target: String) {
    if (target == SupportedLanguages.AUTO.key) return
    val sourceChanged = uiState.value.sourceLanguage != source
    applyLocalParticipantUpdate { it.copy(sourceLanguage = source, targetLanguage = target) }
    if (sourceChanged) reinitializePipeline("stt")
  }

  fun swapLanguages() {
    if (uiState.value.sourceLanguage == SupportedLanguages.AUTO.key) return
    val src = uiState.value.sourceLanguage
    val tgt = uiState.value.targetLanguage
    if (src == tgt) return
    applyLocalParticipantUpdate { it.copy(sourceLanguage = tgt, targetLanguage = src) }
    // Source language changed -> STT must re-init for the new language.
    reinitializePipeline("stt")
  }

  fun onVoiceEnrolled(audioPath: String) {
    applyLocalParticipantUpdate { it.copy(voiceProfileEnrolled = true, voiceProfilePath = audioPath) }
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
      uiState.value.localParticipant?.let { bleManager.setLocalParticipant(it) }

      // OpenVoice cross-lingual clone: derive + persist the speaker embedding from the enrollment
      // clip so the synth path can speak any target language in the user's timbre.
      pipelines.openVoiceConverter?.let { converter ->
        val floats = FloatArray(audioPcm.size) { audioPcm[it] / 32768f }
        converter.computeSpeakerEmbedding(floats, sampleRate)?.let { embedding ->
          voiceProfileManager.saveSpeakerEmbedding(embedding)
          pipelines.openVoiceTargetSe = embedding
        }
      }
    }
  }

  fun deleteVoiceProfile() {
    viewModelScope.launch(Dispatchers.IO) {
      voiceProfileManager.deleteProfile()
      pipelines.openVoiceTargetSe = null
      uiState.update { state ->
        val updated = state.copy(
          voiceProfileEnrolled = false,
          voiceProfilePath = null,
        )
        val participant = updateLocalParticipant(updated)
        updated.copy(localParticipant = participant)
      }
      uiState.value.localParticipant?.let { bleManager.setLocalParticipant(it) }
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

  fun setWifiOnly(enabled: Boolean) {
    uiState.update { it.copy(wifiOnlyDownloads = enabled) }
  }

  fun onSettingChanged(key: String) {
    reinitializePipeline(key)
  }

  // Updates uiState with a fresh local participant, then broadcasts it to BLE peers exactly once.
  // The broadcast must run AFTER commit, outside the update{} lambda: MutableStateFlow.update{} may
  // re-evaluate its transform under CAS contention, so doing the side effect inside it would push
  // transient/duplicate participant snapshots onto the wire.
  private fun applyLocalParticipantUpdate(transform: (BaoTranslateUiState) -> BaoTranslateUiState) {
    uiState.update { state ->
      val updated = transform(state)
      updated.copy(localParticipant = updateLocalParticipant(updated))
    }
    uiState.value.localParticipant?.let { bleManager.setLocalParticipant(it) }
  }

  // Pure: builds the participant from the given state. Callers broadcast the committed result.
  fun updateLocalParticipant(state: BaoTranslateUiState): Participant {
    val app = getApp()
    return (state.localParticipant ?: Participant(
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
  }
}
