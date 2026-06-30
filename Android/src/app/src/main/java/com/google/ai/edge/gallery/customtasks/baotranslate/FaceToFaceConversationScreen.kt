/*
 * Copyright 2026 Google LLC
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
package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.rememberPulseFloat

/**
 * Single-device, 2-speaker FACE-TO-FACE conversation. Two mirrored panels share one phone laid flat
 * between two people: the TOP panel is rotated 180° so the person opposite reads it upright. Each
 * panel shows the translations destined for ITS language ([TranslationMessage.targetLanguage]) and
 * has a tap-to-talk control. There is one microphone, so both controls drive the same recording —
 * the engine (RecordingController.processAudioSegment, faceToFaceMode) auto-detects which of the two
 * paired languages was spoken and routes the translation to the OTHER one. DRY: reuses
 * [LanguageDropdown], [TranscriptList], [Dimensions] and the design tokens.
 */
@Composable
internal fun FaceToFaceConversationScreen(
  uiState: BaoTranslateUiState,
  isRecording: Boolean,
  onSetLanguageA: (String) -> Unit,
  onSetLanguageB: (String) -> Unit,
  onToggleRecording: () -> Unit,
  modifier: Modifier = Modifier,
  onPlayAudio: ((TranslationMessage) -> Unit)? = null,
  replayMessageId: String? = null,
  // Per-speaker output routing: assign the output device a given speaker's translations play on,
  // keyed by their ISO language code.
  onSetSpeakerOutput: (String, com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice) -> Unit = { _, _ -> },
  isTablet: Boolean = false,
) {
  val langAKey = uiState.sourceLanguage
  val langBKey = uiState.targetLanguage
  val langACode = SupportedLanguages.codeFor(langAKey)
  val langBCode = SupportedLanguages.codeFor(langBKey)
  val outputs = uiState.availableAudioDevices
  fun outputFor(code: String) = uiState.faceToFaceOutputs[code] ?: uiState.currentAudioDevice
  // Selectable pair excludes AUTO — face-to-face needs two concrete languages to route between.
  val pickable = SupportedLanguages.TRANSLATION_TARGETS

  Column(modifier = modifier.fillMaxSize()) {
    SpeakerPanel(
      languageKey = langBKey,
      languages = pickable,
      onLanguageSelected = onSetLanguageB,
      // The OTHER speaker (A) talks -> this panel (B) receives B-language translations.
      transcripts = uiState.transcripts.filter { it.targetLanguage == langBCode },
      isRecording = isRecording,
      phase = uiState.conversationPhase,
      onToggleRecording = onToggleRecording,
      onPlayAudio = onPlayAudio,
      replayMessageId = replayMessageId,
      liveSourcePreview = if (uiState.detectedLanguage == langBKey) uiState.liveSourcePreview else null,
      outputDevice = outputFor(langBCode),
      availableOutputs = outputs,
      onSelectOutput = { onSetSpeakerOutput(langBCode, it) },
      rotated = true,
      isTablet = isTablet,
      modifier = Modifier.weight(1f).fillMaxWidth(),
    )
    HorizontalDivider(thickness = Dimensions.Spacing.xxs, color = MaterialTheme.colorScheme.outlineVariant)
    SpeakerPanel(
      languageKey = langAKey,
      languages = pickable,
      onLanguageSelected = onSetLanguageA,
      transcripts = uiState.transcripts.filter { it.targetLanguage == langACode },
      isRecording = isRecording,
      phase = uiState.conversationPhase,
      onToggleRecording = onToggleRecording,
      onPlayAudio = onPlayAudio,
      replayMessageId = replayMessageId,
      liveSourcePreview = if (uiState.detectedLanguage == langAKey) uiState.liveSourcePreview else null,
      outputDevice = outputFor(langACode),
      availableOutputs = outputs,
      onSelectOutput = { onSetSpeakerOutput(langACode, it) },
      rotated = false,
      isTablet = isTablet,
      modifier = Modifier.weight(1f).fillMaxWidth(),
    )
  }
}

@Composable
private fun SpeakerPanel(
  languageKey: String,
  languages: List<com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguage>,
  onLanguageSelected: (String) -> Unit,
  transcripts: List<TranslationMessage>,
  isRecording: Boolean,
  phase: ConversationPhase,
  onToggleRecording: () -> Unit,
  modifier: Modifier = Modifier,
  onPlayAudio: ((TranslationMessage) -> Unit)? = null,
  replayMessageId: String? = null,
  liveSourcePreview: String? = null,
  outputDevice: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice,
  availableOutputs: List<com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice>,
  onSelectOutput: (com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice) -> Unit,
  rotated: Boolean,
  isTablet: Boolean,
) {
  // Rotating the whole panel 180° flips its content for the person sitting opposite. Touch targets
  // rotate with it, so the controls still work for that reader.
  Box(modifier = modifier.then(if (rotated) Modifier.rotate(180f) else Modifier)) {
    Column(
      modifier = Modifier.fillMaxSize().padding(Dimensions.Spacing.medium),
      horizontalAlignment = Alignment.CenterHorizontally,
      // Center the content group so a sparse conversation balances the empty space above/below the
      // control instead of stranding it at the panel edge with a dead gap in the middle.
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small, Alignment.CenterVertically),
    ) {
      LanguageDropdown(
        label = stringResource(R.string.bao_face_to_face_speaks),
        supportingText = "",
        selectedLanguage = languageKey,
        languages = languages,
        onLanguageSelected = onLanguageSelected,
        isTablet = isTablet,
      )
      // This speaker hears their translations on THEIR chosen output (e.g. their own earbuds while
      // the other person uses the phone speaker). Defaults to the global output.
      SpeakerOutputChip(
        current = outputDevice,
        available = availableOutputs,
        onSelect = onSelectOutput,
      )
      TranscriptList(
        // fill = false: take only as much height as the messages need (up to the available space),
        // so the centered group stays compact when the conversation is short.
        transcripts = transcripts,
        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
        isTablet = isTablet,
        onPlayAudio = onPlayAudio,
        replayMessageId = replayMessageId,
        // Anchor the latest translation next to this speaker's control so sparse history doesn't
        // leave a dead gap above the tap-to-talk button.
        reverseLayout = true,
      )
      // Interim recognized caption for THIS speaker — shown the instant STT completes, before the
      // translation lands in the opposite panel, so the turn reads as live.
      if (!liveSourcePreview.isNullOrBlank()) {
        Text(
          text = liveSourcePreview,
          modifier = Modifier.fillMaxWidth().padding(horizontal = Dimensions.Spacing.small),
          style = MaterialTheme.typography.bodyMedium,
          fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          maxLines = 2,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
      }
      ConversationTurnControl(
        phase = phase,
        isRecording = isRecording,
        onToggleRecording = onToggleRecording,
      )
    }
  }
}

/**
 * Hands-free conversation status + control. The session is live the whole time the screen is open
 * (auto-started, no silence auto-stop), so this control's main job is SURFACING the turn state —
 * Listening (pulsing mic) / Translating (progress) / Speaking (volume) — with stop/restart as the
 * secondary tap action. The status label is a polite live region so screen readers track turns.
 */
/** Compact per-speaker output-device selector: tap to route THIS speaker's translations elsewhere. */
@Composable
private fun SpeakerOutputChip(
  current: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice,
  available: List<com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice>,
  onSelect: (com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val (label, icon, _) = describeDevice(current)
  // TalkBack would otherwise announce only the bare device name ("Speaker") with no hint that this
  // control selects THIS speaker's audio output. State the purpose + current value.
  val chipDesc = stringResource(R.string.bao_face_to_face_output_device_cd, label)
  val options =
    available.ifEmpty {
      listOf(com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice.Speaker)
    }
  Box {
    Surface(
      onClick = { expanded = true },
      shape = RoundedCornerShape(percent = 50),
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.semantics {
        role = Role.DropdownList
        contentDescription = chipDesc
      },
    ) {
      Row(
        modifier = Modifier.padding(horizontal = Dimensions.Spacing.small, vertical = Dimensions.Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xxs),
      ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.small), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(Dimensions.Icon.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { dev ->
        val (l, ic, _) = describeDevice(dev)
        DropdownMenuItem(
          text = { Text(l) },
          leadingIcon = { Icon(ic, contentDescription = null) },
          onClick = {
            onSelect(dev)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun ConversationTurnControl(
  phase: ConversationPhase,
  isRecording: Boolean,
  onToggleRecording: () -> Unit,
) {
  val active = isRecording || phase != ConversationPhase.Idle
  val statusText = when {
    !active -> stringResource(R.string.cd_bao_translate_start)
    phase == ConversationPhase.Processing -> stringResource(R.string.bao_translate_translating)
    phase == ConversationPhase.Speaking || phase == ConversationPhase.Cooldown ->
      stringResource(R.string.bao_translate_speaking)
    else -> stringResource(R.string.bao_translate_listening)
  }
  val actionDesc =
    if (active) stringResource(R.string.cd_bao_translate_stop)
    else stringResource(R.string.cd_bao_translate_start)

  // Gentle alpha pulse while listening; static (1f) under reduced motion via the shared helper.
  val pulseAlpha by rememberPulseFloat(
    initialValue = 1f, targetValue = 0.6f, durationMillis = 900, restValue = 1f, label = "f2f_listen_pulse")
  val listening = active && phase == ConversationPhase.Listening
  val circleAlpha = if (listening) pulseAlpha else 1f
  // Icon/label color that contrasts on the current container: onPrimary on the idle primary circle,
  // and the dedicated high-contrast record-button on-color on the coral active circle (the previous
  // onPrimary went dark on the coral, washing the icon out).
  val onActive = MaterialTheme.customColors.recordButtonOnColor
  val iconTint = if (active) onActive else MaterialTheme.colorScheme.onPrimary

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier = Modifier
        .padding(Dimensions.Spacing.xs)
        .size(Dimensions.Component.fabSize)
        .graphicsLayer { alpha = circleAlpha }
        .clip(CircleShape)
        .background(
          when {
            !active -> MaterialTheme.colorScheme.primary
            phase == ConversationPhase.Processing -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.customColors.recordButtonBgColor
          }
        )
        .clickable(onClick = onToggleRecording)
        .semantics {
          contentDescription = actionDesc
          role = Role.Button
        },
      contentAlignment = Alignment.Center,
    ) {
      when {
        active && phase == ConversationPhase.Processing ->
          CircularProgressIndicator(
            modifier = Modifier.size(Dimensions.Icon.large),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        active && (phase == ConversationPhase.Speaking || phase == ConversationPhase.Cooldown) ->
          Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(Dimensions.Icon.large),
          )
        else ->
          Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(Dimensions.Icon.large),
          )
      }
    }
    Text(
      text = statusText,
      style = MaterialTheme.typography.labelLarge,
      color = if (active) MaterialTheme.customColors.recordButtonBgColor
        else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}
