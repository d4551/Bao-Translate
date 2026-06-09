package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import android.os.Build
import android.provider.Settings
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

  // Peer-facing identity for this device in a conversation: the user-set device name (e.g.
  // "Brandon's Phone") so two phones are never both "You", with the model as a fallback. The local
  // self-card still renders this with the "(You)" suffix; connected peers see the name directly.
  private fun localDeviceName(): String =
    Settings.Global.getString(getApp().contentResolver, Settings.Global.DEVICE_NAME)
      ?.takeIf { it.isNotBlank() }
      ?: Build.MODEL?.takeIf { it.isNotBlank() }
      ?: getApp().getString(R.string.bao_translate_you)

  fun refreshLocalRuntimeState(app: Application) {
    val currentDevice = audioRouter.detectCurrentDevice()
    val availableDevices = audioRouter.getAvailableOutputDevices()
    val profileId = uiState.value.activeVoiceProfileId
    val enrolled = voiceProfileManager.hasProfile(profileId)
    val profile = voiceProfileManager.loadProfile(profileId)

    uiState.update { state ->
      val participant = Participant(
        id = profile?.id ?: state.localParticipant?.id ?: localParticipantId,
        name = localDeviceName(),
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
    return (state.localParticipant ?: Participant(
      id = localParticipantId,
      name = localDeviceName(),
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
