package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ParticipantStateManager(
  private val pipelines: PipelineLifecycleManager,
  private val audioRouter: AudioRouter,
  private val voiceProfileManager: VoiceProfileManager,
  private val bleManager: BleConversationManager,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val localParticipantId: String,
  private val getApp: () -> Application,
) {

  fun refreshLocalRuntimeState(app: Application) {
    val currentDevice = audioRouter.detectCurrentDevice()
    val availableDevices = audioRouter.getAvailableOutputDevices()
    val enrolled = voiceProfileManager.hasProfile()
    val profile = voiceProfileManager.loadProfile()

    uiState.update { state ->
      val participant = Participant(
        id = profile?.id ?: state.localParticipant?.id ?: localParticipantId,
        name = app.getString(R.string.bao_translate_you),
        sourceLanguage = state.sourceLanguage,
        targetLanguage = state.targetLanguage,
        isConnected = true,
        hasVoiceProfile = enrolled,
        audioDeviceName = currentDevice.toString(),
      )
      state.copy(
        voiceProfileEnrolled = enrolled,
        voiceProfilePath = profile?.wavPath,
        currentAudioDevice = currentDevice,
        availableAudioDevices = availableDevices,
        localParticipant = participant,
      )
    }
    // Broadcast once on the committed value, outside the CAS retry loop.
    uiState.value.localParticipant?.let { bleManager.setLocalParticipant(it) }
  }

  // Pure: builds the participant from the given state. Callers broadcast the committed result
  // once, outside any MutableStateFlow.update{} lambda (which may re-run under CAS contention).
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
