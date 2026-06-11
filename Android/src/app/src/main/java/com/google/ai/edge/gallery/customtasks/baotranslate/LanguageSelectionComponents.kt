package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import com.google.ai.edge.gallery.ui.theme.isReducedMotion
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfile
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
internal fun languageDisplayName(language: String): String {
  val key = SupportedLanguages.keyForCode(language) ?: language
  val supportedLanguage = SupportedLanguages.ALL.firstOrNull { it.key == key }
  return supportedLanguage?.let { stringResource(it.displayNameRes) } ?: language.uppercase()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSelectionBar(
  sourceLanguage: String,
  targetLanguage: String,
  onSourceLanguageChange: (String) -> Unit,
  onTargetLanguageChange: (String) -> Unit,
  onSwapLanguages: () -> Unit,
  detectedLanguage: String?,
  voiceProfileEnrolled: Boolean = true,
  activeVoiceProfileId: String = VoiceProfileManager.DEFAULT_PROFILE_ID,
  voiceProfiles: List<VoiceProfile> = emptyList(),
  onSwitchVoiceProfile: ((String) -> Unit)? = null,
  onEnrollVoice: (() -> Unit)? = null,
  isTablet: Boolean = false,
) {
  val activeProfile = voiceProfiles.find { it.id == activeVoiceProfileId }
    ?: voiceProfiles.firstOrNull()
  Card(
    modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.small),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(if (isTablet) Dimensions.Spacing.large else Dimensions.Spacing.medium)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LanguageDropdown(
          label = stringResource(R.string.bao_translate_source_label),
          supportingText = if (sourceLanguage == SupportedLanguages.AUTO.key) {
            detectedLanguage?.let { stringResource(R.string.bao_translate_detected_language, languageDisplayName(it)) }
              ?: stringResource(R.string.bao_translate_detect_automatically)
          } else {
            stringResource(R.string.bao_translate_source_language_hint)
          },
          selectedLanguage = sourceLanguage,
          languages = SupportedLanguages.ALL,
          onLanguageSelected = onSourceLanguageChange,
          modifier = Modifier.weight(1f), isTablet = isTablet,
        )
        val swapEnabled = sourceLanguage != SupportedLanguages.AUTO.key
        val swapDesc = stringResource(R.string.cd_bao_translate_swap)
        val reduceMotion = isReducedMotion
        var swapRotation by remember { mutableStateOf(0f) }
        // Snap instantly under reduced motion instead of spinning the swap icon.
        val animatedRotation by animateFloatAsState(
          targetValue = swapRotation,
          animationSpec = if (reduceMotion) snap() else spring(),
          label = "swapRotation",
        )
        TooltipBox(
          positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
          tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_tooltip_swap)) } },
          state = rememberTooltipState(isPersistent = true),
        ) {
          IconButton(
            onClick = {
              if (swapEnabled) {
                swapRotation += 180f
                onSwapLanguages()
              }
            },
            enabled = swapEnabled,
            modifier = Modifier
              .size(if (isTablet) Dimensions.Icon.xl else Dimensions.Icon.large)
              .rotate(animatedRotation)
              .semantics { contentDescription = swapDesc },
          ) {
            Icon(Icons.Default.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isTablet) Dimensions.Icon.large else Dimensions.Icon.medium))
          }
        }
        LanguageDropdown(
          label = stringResource(R.string.bao_translate_target_label),
          supportingText = stringResource(R.string.bao_translate_target_language_hint),
          selectedLanguage = targetLanguage,
          languages = SupportedLanguages.TRANSLATION_TARGETS,
          onLanguageSelected = onTargetLanguageChange,
          modifier = Modifier.weight(1f), isTablet = isTablet,
        )
      }

      if (onEnrollVoice != null) {
        if (voiceProfileEnrolled && activeProfile != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            val chipLabel = stringResource(
              R.string.bao_translate_voice_profile_chip_format,
              activeProfile.name,
              activeProfile.durationSec,
            )
            var profileMenuExpanded by remember { mutableStateOf(false) }
            SuggestionChip(
              onClick = {
                if (voiceProfiles.size > 1) profileMenuExpanded = true else onEnrollVoice()
              },
              label = { Text(chipLabel) },
              icon = {
                Icon(
                  Icons.Default.Person,
                  contentDescription = null,
                  modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                )
              },
              colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.customColors.successColor.copy(alpha = 0.12f),
                labelColor = MaterialTheme.customColors.successColor,
                iconContentColor = MaterialTheme.customColors.successColor,
              ),
              modifier = Modifier.semantics { contentDescription = chipLabel },
            )
            if (voiceProfiles.size > 1 && onSwitchVoiceProfile != null) {
              DropdownMenu(
                expanded = profileMenuExpanded,
                onDismissRequest = { profileMenuExpanded = false },
              ) {
                voiceProfiles.forEach { profile ->
                  DropdownMenuItem(
                    text = {
                      Text(
                        stringResource(
                          R.string.bao_translate_voice_profile_chip_format,
                          profile.name,
                          profile.durationSec,
                        ),
                      )
                    },
                    onClick = {
                      profileMenuExpanded = false
                      onSwitchVoiceProfile(profile.id)
                    },
                  )
                }
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.bao_translate_re_record_voice)) },
                  onClick = {
                    profileMenuExpanded = false
                    onEnrollVoice()
                  },
                )
              }
            }
          }
        } else {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEnrollVoice) {
              Text(stringResource(R.string.bao_translate_enroll_voice))
            }
          }
        }
      }

      // Live language detection chip: visible only when source is AUTO and a language was detected.
      if (sourceLanguage == SupportedLanguages.AUTO.key && detectedLanguage != null) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
          val detectedDesc = stringResource(R.string.bao_translate_language_detected_chip, languageDisplayName(detectedLanguage))
          InputChip(
            onClick = { onSourceLanguageChange(detectedLanguage) },
            label = {
              Text(
                stringResource(R.string.bao_translate_language_detected_chip, languageDisplayName(detectedLanguage)),
                style = MaterialTheme.typography.labelMedium,
              )
            },
            selected = false,
            colors = InputChipDefaults.inputChipColors(
              containerColor = MaterialTheme.colorScheme.tertiaryContainer,
              labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
            modifier = Modifier.semantics { contentDescription = detectedDesc },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageDropdown(
  label: String,
  supportingText: String,
  selectedLanguage: String,
  languages: List<SupportedLanguage>,
  onLanguageSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
  isTablet: Boolean = false,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLang = remember(selectedLanguage) { SupportedLanguages.ALL.find { it.key == selectedLanguage } }

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
    Column(
      modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth().padding(if (isTablet) Dimensions.Spacing.medium else Dimensions.Spacing.small),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
        Text(
          text = selectedLang?.let { stringResource(it.displayNameRes) } ?: selectedLanguage,
          style = if (isTablet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        )
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (isTablet) Dimensions.Icon.medium else Dimensions.Icon.small))
      }
      Text(
        text = supportingText,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      languages.forEach { language ->
        DropdownMenuItem(
          text = { Text(stringResource(language.displayNameRes)) },
          onClick = { onLanguageSelected(language.key); expanded = false },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
        )
      }
    }
  }
}
