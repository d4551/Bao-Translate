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

package com.google.ai.edge.gallery.data

import androidx.annotation.StringRes
import kotlin.math.abs

/**
 * Typed config value. Replaces `Any` in [Config.defaultValue] and [getConfigValueString]. Use the
 * [ConfigValue.from] factory to adapt an untyped value (e.g. from a DataStore JSON map) into a
 * typed [ConfigValue].
 */
sealed interface ConfigValue {
  data class IntValue(val value: Int) : ConfigValue

  data class FloatValue(val value: Float) : ConfigValue

  data class DoubleValue(val value: Double) : ConfigValue

  data class StringValue(val value: String) : ConfigValue

  data class BooleanValue(val value: Boolean) : ConfigValue

  companion object {
    /** Adapt an untyped value (e.g. from a JSON map) into a [ConfigValue] of [valueType]. */
    fun from(value: Any?, valueType: ValueType): ConfigValue =
      when (valueType) {
        ValueType.INT -> IntValue(value.toConfigInt())
        ValueType.FLOAT -> FloatValue(value.toConfigFloat())
        ValueType.DOUBLE -> DoubleValue(value.toConfigDouble())
        ValueType.STRING -> StringValue(value.toConfigString())
        ValueType.BOOLEAN -> BooleanValue(value.toConfigBoolean())
      }
  }
}

private fun Any?.toConfigInt(): Int =
  when (this) {
    is ConfigValue.IntValue -> value
    is ConfigValue.FloatValue -> value.toInt()
    is ConfigValue.DoubleValue -> value.toInt()
    is Number -> toInt()
    is String -> toIntOrNull() ?: 0
    else -> 0
  }

private fun Any?.toConfigFloat(): Float =
  when (this) {
    is ConfigValue.IntValue -> value.toFloat()
    is ConfigValue.FloatValue -> value
    is ConfigValue.DoubleValue -> value.toFloat()
    is Number -> toFloat()
    is String -> toFloatOrNull() ?: 0f
    else -> 0f
  }

private fun Any?.toConfigDouble(): Double =
  when (this) {
    is ConfigValue.IntValue -> value.toDouble()
    is ConfigValue.FloatValue -> value.toDouble()
    is ConfigValue.DoubleValue -> value
    is Number -> toDouble()
    is String -> toDoubleOrNull() ?: 0.0
    else -> 0.0
  }

private fun Any?.toConfigString(): String =
  when (this) {
    is ConfigValue.IntValue -> value.toString()
    is ConfigValue.FloatValue -> value.toString()
    is ConfigValue.DoubleValue -> value.toString()
    is ConfigValue.StringValue -> value
    is ConfigValue.BooleanValue -> value.toString()
    else -> this?.toString() ?: ""
  }

private fun Any?.toConfigBoolean(): Boolean =
  when (this) {
    is ConfigValue.BooleanValue -> value
    is Boolean -> this
    is String -> equals("true", ignoreCase = true)
    else -> false
  }

fun ConfigValue.rawValue(): Any =
  when (this) {
    is ConfigValue.IntValue -> value
    is ConfigValue.FloatValue -> value
    is ConfigValue.DoubleValue -> value
    is ConfigValue.StringValue -> value
    is ConfigValue.BooleanValue -> value
  }

/**
 * The types of configuration editors available.
 *
 * This enum defines the different UI components used to edit configuration values. Each type
 * corresponds to a specific editor widget, such as a slider or a switch.
 */
enum class ConfigEditorType {
  LABEL,
  NUMBER_SLIDER,
  BOOLEAN_SWITCH,
  SEGMENTED_BUTTON,
  BOTTOMSHEET_SELECTOR,
}

/** The data types of configuration values. */
enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
}

data class ConfigKey(val id: String, val label: String)

object ConfigKeys {
  val MAX_TOKENS = ConfigKey("max_tokens", "Max tokens")
  val TOPK = ConfigKey("topk", "TopK")
  val TOPP = ConfigKey("topp", "TopP")
  val TEMPERATURE = ConfigKey("temperature", "Temperature")
  val DEFAULT_MAX_TOKENS = ConfigKey("default_max_tokens", "Default max tokens")
  val DEFAULT_TOPK = ConfigKey("default_topk", "Default TopK")
  val DEFAULT_TOPP = ConfigKey("default_topp", "Default TopP")
  val DEFAULT_TEMPERATURE = ConfigKey("default_temperature", "Default temperature")
  val SUPPORT_IMAGE = ConfigKey("support_image", "Support image")
  val SUPPORT_AUDIO = ConfigKey("support_audio", "Support audio")
  val SUPPORT_TINY_GARDEN = ConfigKey("support_tiny_garden", "Support tiny garden")
  val SUPPORT_MOBILE_ACTIONS = ConfigKey("support_mobile_actions", "Support mobile actions")
  val SUPPORT_THINKING = ConfigKey("support_thinking", "Support thinking")
  val SUPPORT_SPECULATIVE_DECODING =
    ConfigKey("support_speculative_decoding", "Support speculative decoding")
  val ENABLE_THINKING = ConfigKey("enable_thinking", "Enable thinking")
  val ENABLE_SPECULATIVE_DECODING =
    ConfigKey("enable_speculative_decoding", "Enable speculative decoding")
  val MAX_RESULT_COUNT = ConfigKey("max_result_count", "Max result count")
  val USE_GPU = ConfigKey("use_gpu", "Use GPU")
  val ACCELERATOR = ConfigKey("accelerator", "Accelerator")
  val VISION_ACCELERATOR = ConfigKey("vision_accelerator", "Vision accelerator")
  val COMPATIBLE_ACCELERATORS = ConfigKey("compatible_accelerators", "Compatible accelerators")
  val WARM_UP_ITERATIONS = ConfigKey("warm_up_iterations", "Warm up iterations")
  val BENCHMARK_ITERATIONS = ConfigKey("benchmark_iterations", "Benchmark iterations")
  val ITERATIONS = ConfigKey("iterations", "Iterations")
  val THEME = ConfigKey("theme", "Theme")
  val NAME = ConfigKey("name", "Name")
  val MODEL_TYPE = ConfigKey("model_type", "Model type")
  val MODEL = ConfigKey("model", "Model")
  val RESET_CONVERSATION_TURN_COUNT =
    ConfigKey("reset_conversation_turn_count", "Number of turns before the conversation resets")
  val PREFILL_TOKENS = ConfigKey("prefill_tokens", "Prefill tokens")
  val DECODE_TOKENS = ConfigKey("decode_tokens", "Decode tokens")
  val NUMBER_OF_RUNS = ConfigKey("number_of_runs", "Number of runs")
}

/**
 * Base class for configuration settings.
 *
 * @param type The type of configuration editor.
 * @param key The unique key for the configuration setting.
 * @param defaultValue The default value for the configuration setting.
 * @param valueType The data type of the configuration value.
 * @param needReinitialization Indicates whether the model needs to be reinitialized after changing
 *   this config.
 */
open class Config(
  val type: ConfigEditorType,
  open val key: ConfigKey,
  open val defaultValue: ConfigValue,
  open val valueType: ValueType,
  // Changes on any configs with this field set to true will automatically trigger a model
  // re-initialization.
  open val needReinitialization: Boolean = true,
)

/** Configuration setting for a label. */
class LabelConfig(
  override val key: ConfigKey,
  defaultValue: String = "",
) : Config(
    type = ConfigEditorType.LABEL,
    key = key,
    defaultValue = ConfigValue.StringValue(defaultValue),
    valueType = ValueType.STRING,
  ) {
  override val defaultValue: ConfigValue = ConfigValue.StringValue(defaultValue)
}

/**
 * Configuration setting for a number slider.
 *
 * @param sliderMin The minimum value of the slider.
 * @param sliderMax The maximum value of the slider.
 */
class NumberSliderConfig(
  override val key: ConfigKey,
  val sliderMin: Float,
  val sliderMax: Float,
  defaultValue: Float,
  override val valueType: ValueType,
  override val needReinitialization: Boolean = true,
) : Config(
    type = ConfigEditorType.NUMBER_SLIDER,
    key = key,
    defaultValue = ConfigValue.FloatValue(defaultValue),
    valueType = valueType,
  ) {
  override val defaultValue: ConfigValue = ConfigValue.FloatValue(defaultValue)
}

/** Configuration setting for a boolean switch. */
class BooleanSwitchConfig(
  override val key: ConfigKey,
  defaultValue: Boolean,
  override val needReinitialization: Boolean = true,
) : Config(
    type = ConfigEditorType.BOOLEAN_SWITCH,
    key = key,
    defaultValue = ConfigValue.BooleanValue(defaultValue),
    valueType = ValueType.BOOLEAN,
  ) {
  override val defaultValue: ConfigValue = ConfigValue.BooleanValue(defaultValue)
}

/** Configuration setting for a segmented button. */
class SegmentedButtonConfig(
  override val key: ConfigKey,
  defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) : Config(
    type = ConfigEditorType.SEGMENTED_BUTTON,
    key = key,
    defaultValue = ConfigValue.StringValue(defaultValue),
    // The emitted value will be comma-separated labels when allowMultiple=true.
    valueType = ValueType.STRING,
  ) {
  override val defaultValue: ConfigValue = ConfigValue.StringValue(defaultValue)
}

/** Configuration setting for a bottom sheet selector. */
class BottomSheetSelectorConfig(
  override val key: ConfigKey,
  defaultValue: String,
  val options: List<BottomSheetSelectorItem>,
  @StringRes val bottomSheetTitleResId: Int? = null,
) : Config(
    type = ConfigEditorType.BOTTOMSHEET_SELECTOR,
    key = key,
    defaultValue = ConfigValue.StringValue(defaultValue),
    valueType = ValueType.STRING,
  ) {
  override val defaultValue: ConfigValue = ConfigValue.StringValue(defaultValue)
}

data class BottomSheetSelectorItem(val label: String)

fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  supportThinking: Boolean = false,
  supportSpeculativeDecoding: Boolean = false,
): List<Config> {
  var maxTokensConfig: Config =
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken")
  if (defaultMaxContextLength != null) {
    maxTokensConfig =
      NumberSliderConfig(
        key = ConfigKeys.MAX_TOKENS,
        sliderMin = 2000f,
        sliderMax = defaultMaxContextLength.toFloat(),
        defaultValue = defaultMaxToken.toFloat(),
        valueType = ValueType.INT,
      )
  }
  val configs =
    listOf(
        maxTokensConfig,
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = defaultTopK.toFloat(),
          valueType = ValueType.INT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TOPP,
          sliderMin = 0.0f,
          sliderMax = 1.0f,
          defaultValue = defaultTopP,
          valueType = ValueType.FLOAT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0.0f,
          sliderMax = 2.0f,
          defaultValue = defaultTemperature,
          valueType = ValueType.FLOAT,
        ),
        SegmentedButtonConfig(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = accelerators[0].label,
          options = accelerators.map { it.label },
        ),
      )
      .toMutableList()

  if (supportThinking) {
    configs.add(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false))
  }
  if (supportSpeculativeDecoding) {
    configs.add(
      BooleanSwitchConfig(key = ConfigKeys.ENABLE_SPECULATIVE_DECODING, defaultValue = false)
    )
  }
  return configs
}

/**
 * Creates the configuration settings for an LLM model that only supports NPU.
 *
 * For now NPU models don't support setting topK, topP, and temperature.
 */
fun createLlmChatConfigsForNpuModel(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )
}

/**
 * Creates the configuration settings for an AICore model.
 *
 * AICore models support setting topK and temperature (clamped between 0.0 and 1.0), but not topP.
 */
fun createAICoreConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    NumberSliderConfig(
      key = ConfigKeys.TOPK,
      sliderMin = 5f,
      sliderMax = 100f,
      defaultValue = defaultTopK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = defaultTemperature,
      valueType = ValueType.FLOAT,
    ),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )
}

fun getConfigValueString(value: ConfigValue, config: Config): String =
  when (value) {
    is ConfigValue.FloatValue -> "%.2f".format(value.value)
    is ConfigValue.DoubleValue -> "%.2f".format(value.value)
    is ConfigValue.IntValue -> value.value.toString()
    is ConfigValue.StringValue -> value.value
    is ConfigValue.BooleanValue -> value.value.toString()
  }
