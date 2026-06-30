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

package com.google.ai.edge.gallery.ui.common

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.MODEL_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.common.BaoLog
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BooleanSwitchConfig
import com.google.ai.edge.gallery.data.BottomSheetSelectorConfig
import com.google.ai.edge.gallery.data.BottomSheetSelectorItem
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.ConfigValue
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGConfigDialog"

private data class Tab(@StringRes val labelResId: Int)

private val TABS =
  listOf(
    Tab(labelResId = R.string.config_dialog_tab_model_configs),
    Tab(labelResId = R.string.config_dialog_tab_system_prompt),
  )

/**
 * Displays a configuration dialog allowing users to modify settings through various input controls.
 */
@Composable
fun ConfigDialog(
  title: String,
  configs: List<Config>,
  initialValues: Map<String, Any>,
  onDismissed: () -> Unit,
  onOk: (values: Map<String, Any>, oldSystemPrompt: String, newSystemPrompt: String) -> Unit,
  okBtnLabel: String = "OK",
  subtitle: String = "",
  showCancel: Boolean = true,
  showSystemPromptEditorTab: Boolean = false,
  defaultSystemPrompt: String = "",
  curSystemPrompt: String = "",
) {
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  val savedSystemPrompt = remember { curSystemPrompt }
  var systemPrompt by remember { mutableStateOf(curSystemPrompt) }

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth()
          .clickable(
            interactionSource = interactionSource,
            indication = null, // Disable the ripple effect
          ) {
            focusManager.clearFocus()
          }
          .imePadding(),
      shape = MaterialTheme.shapes.large,
    ) {
      Column(
        modifier = Modifier.padding(Dimensions.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = Dimensions.Spacing.small),
          )
          // Subtitle.
          if (subtitle.isNotEmpty()) {
            Text(
              subtitle,
              style = labelSmallNarrow,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.offset(y = -Dimensions.Spacing.sm),
            )
          }
        }

        // Tab.
        if (showSystemPromptEditorTab) {
          PrimaryTabRow(selectedTabIndex = selectedTabIndex, containerColor = Color.Transparent) {
            TABS.forEachIndexed { index, tab ->
              Tab(
                selected = selectedTabIndex == index,
                onClick = { selectedTabIndex = index },
                text = {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
                  ) {
                    val titleColor =
                      if (selectedTabIndex == index) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(stringResource(tab.labelResId), color = titleColor)
                  }
                },
              )
            }
          }
        }

        if (selectedTabIndex == 0) {
          // List of config rows.
          Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
          ) {
            ConfigEditorsPanel(configs = configs, values = values)
          }
        } else if (selectedTabIndex == 1) {
          OutlinedTextField(
            value = systemPrompt,
            modifier = Modifier.weight(1f, fill = false),
            textStyle = MaterialTheme.typography.bodySmall,
            onValueChange = { systemPrompt = it },
            placeholder = {
              Text(
                text = stringResource(R.string.system_prompt_placeholder),
                modifier = Modifier.offset(y = Dimensions.Spacing.xs), // Adjust to align the cursor with the text.
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
              )
            },
          )
        }

        // Button row.
        Row(
          horizontalArrangement =
            if (showSystemPromptEditorTab && selectedTabIndex == 1) {
              Arrangement.SpaceBetween
            } else {
              Arrangement.End
            },
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = Dimensions.Spacing.small),
        ) {
          // Restore default button to restore system prompt.
          if (showSystemPromptEditorTab && selectedTabIndex == 1) {
            OutlinedButton(
              onClick = { systemPrompt = defaultSystemPrompt },
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
            ) {
              Text(stringResource(R.string.restore_default))
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Cancel button.
            if (showCancel) {
              TextButton(onClick = { onDismissed() }) { Text(stringResource(R.string.cancel)) }
            }

            // Ok button
            Button(
              onClick = {
                BaoLog.d(TAG, "Values from dialog: $values")
                onOk(values.toMap(), savedSystemPrompt, systemPrompt)
              }
            ) {
              Text(okBtnLabel)
            }
          }
        }
      }
    }
  }
}

/** Composable function to display a list of config editor rows. */
@Composable
fun ConfigEditorsPanel(configs: List<Config>, values: SnapshotStateMap<String, Any>) {
  for (config in configs) {
    when (config) {
      // Label.
      is LabelConfig -> {
        LabelRow(config = config, values = values)
      }

      // Number slider.
      is NumberSliderConfig -> {
        NumberSliderRow(config = config, values = values)
      }

      // Boolean switch.
      is BooleanSwitchConfig -> {
        BooleanSwitchRow(config = config, values = values)
      }

      // Segmented button.
      is SegmentedButtonConfig -> {
        SegmentedButtonRow(config = config, values = values)
      }

      // Bottom sheet selector.
      is BottomSheetSelectorConfig -> {
        BottomSheetSelectorRow(config = config, values = values)
      }

      else -> {}
    }
  }
}

@Composable
fun LabelRow(config: LabelConfig, values: SnapshotStateMap<String, Any>) {
  Column(modifier = Modifier.fillMaxWidth()) {
    // Field label.
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    // Content label.
    val label = values[config.key.label]?.toString().orEmpty()
    Text(label, style = MaterialTheme.typography.bodyMedium)
  }
}

fun getTextFieldDisplayValue(valueType: ValueType, value: Float): String {
  return runCatching {
    when (valueType) {
      ValueType.FLOAT -> {
        "%.2f".format(value)
      }

      ValueType.INT -> {
        "${value.toInt()}"
      }

      else -> {
        ""
      }
    }
  }.getOrElse {
    ""
  }
}

/** Returns a numeric config value as a slider-ready float. */
private fun numericConfigValue(value: Any?): Float =
  when (value) {
    is ConfigValue.FloatValue -> value.value
    is ConfigValue.DoubleValue -> value.value.toFloat()
    is ConfigValue.IntValue -> value.value.toFloat()
    is Number -> value.toFloat()
    is String -> value.toFloatOrNull() ?: 0f
    else -> 0f
  }

/**
 * Composable function to display a number slider with an associated text input field.
 *
 * This function renders a row containing a slider and a text field, both used to modify a numeric
 * value. The slider allows users to visually adjust the value within a specified range, while the
 * text field provides precise numeric input.
 */
@Composable
fun NumberSliderRow(config: NumberSliderConfig, values: SnapshotStateMap<String, Any>) {
  val focusManager = LocalFocusManager.current

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    // Field label.
    val minStr = getTextFieldDisplayValue(config.valueType, config.sliderMin)
    val maxStr = getTextFieldDisplayValue(config.valueType, config.sliderMax)
    Text("${config.key.label} ($minStr-$maxStr)", style = MaterialTheme.typography.titleSmall)

    // Controls row.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      var isFocused by remember { mutableStateOf(false) }
      val focusRequester = remember { FocusRequester() }

      // Display value for the text field. It can temporarily hold invalid or out-of-range input
      // while the user is editing.
      var textFieldDisplayValue by remember {
        mutableStateOf(
          getTextFieldDisplayValue(config.valueType, numericConfigValue(values[config.key.label]))
        )
      }

      // Number slider.
      val sliderValue = numericConfigValue(values[config.key.label])

      Slider(
        modifier = Modifier.height(Dimensions.Icon.medium).weight(1f).padding(end = Dimensions.Spacing.small),
        value = sliderValue,
        valueRange = config.sliderMin..config.sliderMax,
        onValueChange = {
          values[config.key.label] = it
          textFieldDisplayValue = getTextFieldDisplayValue(config.valueType, it)
        },
      )

      Spacer(modifier = Modifier.width(Dimensions.Spacing.small))

      // A smaller text field.
      BasicTextField(
        value = textFieldDisplayValue,
        modifier =
          Modifier.width(Dimensions.Component.imagePreviewHeight).focusRequester(focusRequester).onFocusChanged {
            isFocused = it.isFocused

            // When leaving focus, display the internal value so that any invalid value is cleared.
            if (!isFocused) {
              textFieldDisplayValue =
                getTextFieldDisplayValue(config.valueType, numericConfigValue(values[config.key.label]))
            }
          },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        singleLine = true,
        onValueChange = {
          // Always update the display value to reflect the update on the UI.
          textFieldDisplayValue = it

          // Only if the new value could be converted to a float, then update the internal value,
          // bounded by the slider range. It prevents invalid values like NaN from crashing the app.
          it.toFloatOrNull()?.let { floatValue ->
            values[config.key.label] = minOf(maxOf(floatValue, config.sliderMin), config.sliderMax)
          }
        },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
      ) { innerTextField ->
        Box(
          modifier =
            Modifier.border(
              width = if (isFocused) Dimensions.Component.strokeWidth else Dimensions.Stroke.hairline,
              color =
                if (isFocused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
              shape = MaterialTheme.shapes.extraSmall,
            )
        ) {
          Box(modifier = Modifier.padding(Dimensions.Spacing.small)) { innerTextField() }
        }
      }
    }

    if (config.key == ConfigKeys.MAX_TOKENS) {
      val sliderValue = numericConfigValue(values[config.key.label])
      if (sliderValue >= 10000f) {
        Text(
          text = stringResource(R.string.max_tokens_warning_message),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = Dimensions.Spacing.small),
        )
      }
    }
  }
}

/**
 * Composable function to display a row with a boolean switch.
 *
 * This function renders a row containing a label and a switch, allowing users to toggle a boolean
 * value.
 */
@Composable
fun BooleanSwitchRow(config: BooleanSwitchConfig, values: SnapshotStateMap<String, Any>) {
  val switchValue = values[config.key.label] == true
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    Switch(checked = switchValue, onCheckedChange = { values[config.key.label] = it })
  }
}

/**
 * Composable function to display a row with a segmented button.
 *
 * This function renders a row containing a label and a segmented button, allowing users to select
 * one or more options from a list.
 */
@Composable
fun SegmentedButtonRow(config: SegmentedButtonConfig, values: SnapshotStateMap<String, Any>) {
  val selectedOptions: List<String> = remember {
    values[config.key.label]?.toString().orEmpty().split(",")
  }
  var selectionStates: List<Boolean> by remember {
    mutableStateOf(
      List(config.options.size) { index -> selectedOptions.contains(config.options[index]) }
    )
  }

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    MultiChoiceSegmentedButtonRow {
      config.options.forEachIndexed { index, label ->
        SegmentedButton(
          shape = SegmentedButtonDefaults.itemShape(index = index, count = config.options.size),
          onCheckedChange = {
            var newSelectionStates = selectionStates.toMutableList()
            val selectedCount = newSelectionStates.count { it }

            // Single select.
            if (!config.allowMultiple) {
              if (!newSelectionStates[index]) {
                newSelectionStates = MutableList(config.options.size) { it == index }
              }
            }
            // Multiple select.
            else {
              if (!(selectedCount == 1 && newSelectionStates[index])) {
                newSelectionStates[index] = !newSelectionStates[index]
              }
            }
            selectionStates = newSelectionStates

            values[config.key.label] =
              config.options
                .filterIndexed { index, option -> selectionStates[index] }
                .joinToString(",")
          },
          checked = selectionStates[index],
          label = { Text(label) },
        )
      }
    }
  }
}

/**
 * Composable function to display a row with a bottom sheet selector.
 *
 * This function renders a row containing a label and a button, allowing users to select an option
 * from a bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetSelectorRow(
  config: BottomSheetSelectorConfig,
  values: SnapshotStateMap<String, Any>,
  showLabel: Boolean = true,
  onSelected: (BottomSheetSelectorItem) -> Unit = {},
) {
  var selectedOption by remember {
    mutableStateOf(
      if (config.options.isEmpty()) {
        null
      } else {
        config.options.find { option ->
          when (val value = config.defaultValue) {
            is ConfigValue.StringValue -> option.label == value.value
            else -> false
          }
        }
      }
    )
  }
  var showBottomSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  Column(
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
  ) {
    if (showLabel) {
      Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    }
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.height(Dimensions.Component.rowHeight)
          .clip(CircleShape)
          .clickable { showBottomSheet = true }
          .border(Dimensions.Stroke.hairline, MaterialTheme.colorScheme.outline, CircleShape)
          .padding(start = Dimensions.Spacing.md, end = Dimensions.Spacing.small),
    ) {
      Text(
        selectedOption?.label ?: "-",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
      Icon(
        Icons.Rounded.ArrowDropDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
  }

  if (showBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showBottomSheet = false },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        val titleResId = config.bottomSheetTitleResId
        if (titleResId != null) {
          Text(
            stringResource(titleResId),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(Dimensions.Spacing.medium),
          )
        }
        LazyColumn {
          items(config.options) { option ->
            Row(
              modifier =
                Modifier.clickable {
                    selectedOption = option
                    values[config.key.label] = option.label
                    onSelected(option)
                    scope.launch {
                      delay(200)
                      sheetState.hide()
                      showBottomSheet = false
                    }
                  }
                  .padding(horizontal = Dimensions.Spacing.medium, vertical = Dimensions.Spacing.md)
                  .fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
            ) {
              Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.alpha(if (option == selectedOption) 1f else 0f),
              )
              Text(
                option.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }
    }
  }
}
