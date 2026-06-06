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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.ui.common.EmptyState
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
internal fun TranscriptList(
  transcripts: List<TranslationMessage>,
  modifier: Modifier = Modifier,
  listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
  isTablet: Boolean = false,
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
      TranslationBubble(message = message, modifier = itemModifier, isTablet = isTablet)
    }
    if (transcripts.isEmpty()) {
      item {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = if (isTablet) Dimensions.Spacing.xxl else Dimensions.Spacing.xl), contentAlignment = Alignment.Center) {
          EmptyState(
            icon = Icons.Default.Mic,
            titleResId = R.string.bao_translate_start_speaking,
            descriptionResId = R.string.bao_translate_hint_detail,
          )
        }
      }
    }
  }
}

@Composable
internal fun TranslationBubble(message: TranslationMessage, modifier: Modifier = Modifier, isTablet: Boolean = false) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
  ) {
    Card(
      modifier = Modifier.widthIn(max = if (isTablet) Dimensions.Component.bubbleMaxWidthTablet else Dimensions.Component.bubbleMaxWidth).fillMaxWidth(if (isTablet) 0.82f else 0.9f),
      colors = CardDefaults.cardColors(containerColor = if (message.isUser) MaterialTheme.customColors.userBubbleBgColor else MaterialTheme.customColors.agentBubbleBgColor),
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
            color = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(R.string.bao_translate_arrow_format, languageDisplayName(message.targetLanguage)),
            style = if (isTablet) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = if (message.isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(message.originalText, style = if (isTablet) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, color = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        
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
          Text(message.translatedText, style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = if (message.isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
          if (message.audioPlayed != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
              Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.cd_bao_translate_playback), modifier = Modifier.size(if (isTablet) Dimensions.Icon.medium else Dimensions.Icon.small), tint = if (message.isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                stringResource(if (message.audioPlayed) R.string.bao_translate_played else R.string.bao_translate_audio_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
