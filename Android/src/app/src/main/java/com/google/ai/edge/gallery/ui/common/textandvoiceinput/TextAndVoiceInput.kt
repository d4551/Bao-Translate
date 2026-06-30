/*
 * Copyright 2025 Google LLC
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
package com.google.ai.edge.gallery.ui.common.textandvoiceinput

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.bodyLargeNarrow

@Composable
fun TextAndVoiceInput(
  task: Task,
  processing: Boolean,
  holdToDictateViewModel: HoldToDictateViewModel,
  onDone: (String) -> Unit,
  onAmplitudeChanged: (Int) -> Unit,
  modifier: Modifier = Modifier,
  clearTextTrigger: Long = 0L,
  defaultTextInputMode: Boolean = false,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
  ) {
    var textInputMode by remember { mutableStateOf(defaultTextInputMode) }
    var curTextInput by remember { mutableStateOf("") }

    LaunchedEffect(clearTextTrigger) { curTextInput = "" }

    // An icon button to switch between text and voice input.
    Box(
      modifier =
        Modifier.clip(CircleShape)
          .then(
            if (!processing) {
              Modifier.clickable {
                curTextInput = ""
                textInputMode = !textInputMode
              }
            } else {
              Modifier
            }
          )
          .graphicsLayer { alpha = if (!processing) 1f else 0.5f }
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .border(
            width = Dimensions.Stroke.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = CircleShape,
          )
          .size(Dimensions.Icon.xl),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        if (textInputMode) Icons.Outlined.Mic else Icons.Outlined.KeyboardAlt,
        contentDescription =
          stringResource(
            if (textInputMode) R.string.cd_switch_to_voice else R.string.cd_switch_to_keyboard
          ),
        modifier = Modifier.size(Dimensions.Icon.medium),
      )
    }

    AnimatedContent(targetState = textInputMode) { showTextInput ->
      // Text field.
      if (showTextInput) {
        val cdPromptInput = stringResource(R.string.cd_prompt_input_text_field)
        Row(
          modifier =
            Modifier.weight(1f)
              .clip(MaterialTheme.shapes.extraLarge)
              .background(MaterialTheme.colorScheme.surface)
              .border(
                width = Dimensions.Stroke.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraLarge,
              )
              .heightIn(min = Dimensions.Icon.xl),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BasicTextField(
            value = curTextInput,
            enabled = !processing,
            onValueChange = { curTextInput = it },
            textStyle = bodyLargeNarrow.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier =
              Modifier.padding(start = Dimensions.Spacing.medium, end = Dimensions.Spacing.small).padding(vertical = Dimensions.Spacing.xxs).semantics {
                contentDescription = cdPromptInput
              },
            minLines = 1,
            maxLines = 3,
            decorationBox = { innerTextField ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
              ) {
                Box(Modifier.weight(1f).padding(vertical = Dimensions.Spacing.small)) {
                  if (curTextInput.isEmpty()) {
                    Text(
                      text = stringResource(R.string.text_input_placeholder_llm_chat),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  innerTextField()
                }

                // Send button.
                Box(
                  modifier =
                    Modifier.clip(CircleShape)
                      .then(
                        if (!processing) {
                          Modifier.clickable { onDone(curTextInput) }
                        } else {
                          Modifier
                        }
                      )
                      .graphicsLayer { alpha = if (!processing) 1f else 0.5f }
                      .background(getTaskIconColor(task = task))
                      .size(Dimensions.Component.shutterIconSize),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(R.string.cd_send_prompt_icon),
                    modifier = Modifier.offset(x = Dimensions.Spacing.xxs),
                    tint = MaterialTheme.colorScheme.onPrimary,
                  )
                }
              }
            },
          )
        }
      }
      // Hold to talk.
      else {
        HoldToDictate(
          task = task,
          viewModel = holdToDictateViewModel,
          onDone = { text -> onDone(text) },
          onAmplitudeChanged = { onAmplitudeChanged(it) },
          enabled = !processing,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
