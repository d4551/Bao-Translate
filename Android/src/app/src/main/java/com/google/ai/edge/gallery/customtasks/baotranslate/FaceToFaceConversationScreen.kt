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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.google.ai.edge.gallery.ui.theme.isReducedMotion

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
  onPlayAudio: ((TranslationMessage) -> Unit)? = null,
  replayMessageId: String? = null,
  modifier: Modifier = Modifier,
  isTablet: Boolean = false,
) {
  val langAKey = uiState.sourceLanguage
  val langBKey = uiState.targetLanguage
  val langACode = SupportedLanguages.codeFor(langAKey)
  val langBCode = SupportedLanguages.codeFor(langBKey)
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
  onPlayAudio: ((TranslationMessage) -> Unit)? = null,
  replayMessageId: String? = null,
  rotated: Boolean,
  isTablet: Boolean,
  modifier: Modifier = Modifier,
) {
  // Rotating the whole panel 180° flips its content for the person sitting opposite. Touch targets
  // rotate with it, so the controls still work for that reader.
  Box(modifier = modifier.then(if (rotated) Modifier.rotate(180f) else Modifier)) {
    Column(
      modifier = Modifier.fillMaxSize().padding(Dimensions.Spacing.medium),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      LanguageDropdown(
        label = stringResource(R.string.bao_face_to_face_speaks),
        supportingText = "",
        selectedLanguage = languageKey,
        languages = languages,
        onLanguageSelected = onLanguageSelected,
        isTablet = isTablet,
      )
      TranscriptList(
        transcripts = transcripts,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        isTablet = isTablet,
        onPlayAudio = onPlayAudio,
        replayMessageId = replayMessageId,
      )
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

  // Gentle alpha pulse while listening; static when the user prefers reduced motion.
  val reduceMotion = isReducedMotion
  val pulse = rememberInfiniteTransition(label = "f2f_listen_pulse")
  val pulseAlpha by pulse.animateFloat(
    initialValue = 1f,
    targetValue = 0.6f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "f2f_listen_alpha",
  )
  val listening = active && phase == ConversationPhase.Listening
  val circleAlpha = if (listening && !reduceMotion) pulseAlpha else 1f

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
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(Dimensions.Icon.large),
          )
        else ->
          Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
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
