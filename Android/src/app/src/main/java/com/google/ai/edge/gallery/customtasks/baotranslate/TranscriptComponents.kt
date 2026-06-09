package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SwapVert

@Composable
internal fun TranscriptList(
  transcripts: List<TranslationMessage>,
  modifier: Modifier = Modifier,
  listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
  isTablet: Boolean = false,
  onPlayAudio: ((message: TranslationMessage) -> Unit)? = null,
  replayMessageId: String? = null,
  sourceLanguage: String = "",
  targetLanguage: String = "",
) {
  val latestId = transcripts.lastOrNull()?.id
  LazyColumn(
    state = listState, modifier = modifier,
    contentPadding = PaddingValues(vertical = Dimensions.Spacing.small),
    verticalArrangement = Arrangement.spacedBy(if (isTablet) Dimensions.Spacing.medium else Dimensions.Spacing.small),
  ) {
    items(transcripts, key = { it.id }) { message ->
      // Mark only the newest bubble as the live region so TalkBack announces just the latest
      // translation, instead of re-reading the entire visible list on every change.
      val itemModifier = Modifier.animateItem().let {
        if (message.id == latestId) {
          it.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
        } else {
          it
        }
      }
      TranslationBubble(
        message = message,
        modifier = itemModifier,
        isTablet = isTablet,
        onPlayAudio = onPlayAudio,
        isPlaying = replayMessageId == message.id,
      )
    }
    if (transcripts.isEmpty()) {
      item {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = if (isTablet) Dimensions.Spacing.xxl else Dimensions.Spacing.xl), contentAlignment = Alignment.Center) {
          TranscriptEmptyState(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            isTablet = isTablet,
          )
        }
      }
    }
  }
}

@Composable
internal fun TranscriptEmptyState(
  sourceLanguage: String,
  targetLanguage: String,
  isTablet: Boolean = false,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
  ) {
    val infiniteTransition = rememberInfiniteTransition(label = "empty_state_pulse")
    val pulseScale by infiniteTransition.animateFloat(
      initialValue = 1f,
      targetValue = 1.15f,
      animationSpec = infiniteRepeatable(
        animation = tween(1200, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
      ),
      label = "emptyPulse",
    )

    Icon(
      imageVector = Icons.Default.Mic,
      contentDescription = null,
      modifier = Modifier
        .size(if (isTablet) Dimensions.Icon.xl else Dimensions.Icon.large)
        .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale },
      tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    )

    Text(
      text = stringResource(R.string.bao_translate_start_speaking),
      style = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )

    if (sourceLanguage.isNotBlank() && targetLanguage.isNotBlank()) {
      Text(
        text = stringResource(R.string.bao_translate_lang_pair_format, languageDisplayName(sourceLanguage), languageDisplayName(targetLanguage)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Text(
      text = stringResource(R.string.bao_translate_hint_detail),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun TranslationBubble(
  message: TranslationMessage,
  modifier: Modifier = Modifier,
  isTablet: Boolean = false,
  onPlayAudio: ((message: TranslationMessage) -> Unit)? = null,
  isPlaying: Boolean = false,
) {
  val bubbleContainerColor =
    if (message.isUser) {
      MaterialTheme.customColors.userBubbleBgColor
    } else {
      MaterialTheme.customColors.agentBubbleBgColor
    }
  val bubbleContentColor =
    if (message.isUser) {
      MaterialTheme.customColors.userBubbleContentColor
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  val bubbleSecondaryContentColor =
    if (message.isUser) {
      MaterialTheme.customColors.userBubbleSecondaryContentColor
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    }
  val bubbleTertiaryContentColor =
    if (message.isUser) {
      MaterialTheme.customColors.userBubbleTertiaryContentColor
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    }

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
  ) {
    Card(
      modifier = Modifier.widthIn(max = if (isTablet) Dimensions.Component.bubbleMaxWidthTablet else Dimensions.Component.bubbleMaxWidth).fillMaxWidth(if (isTablet) 0.82f else 0.9f),
      colors = CardDefaults.cardColors(
        containerColor = bubbleContainerColor,
        contentColor = bubbleContentColor,
      ),
    ) {
      Column(modifier = Modifier.padding(if (isTablet) Dimensions.Spacing.large else Dimensions.Spacing.medium), verticalArrangement = Arrangement.spacedBy(if (isTablet) Dimensions.Spacing.small else Dimensions.Spacing.small)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = when {
              message.isUser -> stringResource(R.string.chat_you)
              message.speakerName.isNotBlank() -> message.speakerName
              else -> message.sourceLanguage
            },
            style = if (isTablet) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = bubbleContentColor,
          )
          Text(
            text = stringResource(R.string.bao_translate_arrow_format, languageDisplayName(message.targetLanguage)),
            style = if (isTablet) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = bubbleSecondaryContentColor,
          )
        }
        Text(
          message.originalText,
          style = if (isTablet) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
          color = bubbleContentColor,
        )

        if (message.translationError != null) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
            Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.cd_error), modifier = Modifier.size(if (isTablet) Dimensions.Icon.medium else Dimensions.Icon.small), tint = MaterialTheme.colorScheme.error)
            Text(
              text = message.translationError,
              style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        } else {
          Text(
            message.translatedText,
            style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = bubbleSecondaryContentColor,
          )
          if (message.translatedText.isNotBlank() && onPlayAudio != null) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (message.audioPlayed != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
                  Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.cd_bao_translate_playback),
                    modifier = Modifier.size(if (isTablet) Dimensions.Icon.medium else Dimensions.Icon.small),
                    tint = bubbleTertiaryContentColor,
                  )
                  Text(
                    stringResource(if (message.audioPlayed == true) R.string.bao_translate_played else R.string.bao_translate_audio_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = bubbleTertiaryContentColor,
                  )
                }
              }
              TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(stringResource(R.string.cd_bao_translate_replay_audio)) } },
                state = rememberTooltipState(),
              ) {
                IconButton(
                  onClick = { onPlayAudio(message) },
                  modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                  Icon(
                    imageVector = if (isPlaying) Icons.Default.Replay else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_bao_translate_replay_audio),
                    tint = bubbleTertiaryContentColor,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun ConversationModeBadge(
  isFaceToFace: Boolean,
  connectedCount: Int,
  onExit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Dimensions.Spacing.medium),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
      ) {
        Icon(
          imageVector = if (isFaceToFace) Icons.Default.SwapVert else Icons.Default.People,
          contentDescription = null,
          modifier = Modifier.size(Dimensions.Icon.small),
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = if (isFaceToFace) {
            stringResource(R.string.bao_translate_face_to_face_badge)
          } else {
            stringResource(R.string.bao_translate_group_conversation_badge, connectedCount)
          },
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_exit_conversation)) } },
        state = rememberTooltipState(),
      ) {
        IconButton(
          onClick = onExit,
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.bao_translate_exit_conversation),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
    }
  }
}
