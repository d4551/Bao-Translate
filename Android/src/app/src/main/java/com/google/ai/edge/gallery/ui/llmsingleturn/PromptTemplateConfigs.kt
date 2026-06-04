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

package com.google.ai.edge.gallery.ui.llmsingleturn

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.geminiGradientBlue
import com.google.ai.edge.gallery.ui.theme.geminiGradientCoral
import com.google.ai.edge.gallery.ui.theme.geminiGradientPurple

enum class PromptTemplateInputEditorType {
  SINGLE_SELECT
}

enum class RewriteToneType(val value: String, @param:StringRes val displayRes: Int) {
  FORMAL(value = "Formal", displayRes = R.string.rewrite_tone_formal),
  CASUAL(value = "Casual", displayRes = R.string.rewrite_tone_casual),
  FRIENDLY(value = "Friendly", displayRes = R.string.rewrite_tone_friendly),
  POLITE(value = "Polite", displayRes = R.string.rewrite_tone_polite),
  ENTHUSIASTIC(value = "Enthusiastic", displayRes = R.string.rewrite_tone_enthusiastic),
  CONCISE(value = "Concise", displayRes = R.string.rewrite_tone_concise),
}

enum class SummarizationType(val value: String, @param:StringRes val displayRes: Int) {
  KEY_BULLET_POINT(value = "Key bullet points (3-5)", displayRes = R.string.summarization_key_bullet_point),
  SHORT_PARAGRAPH(value = "Short paragraph (1-2 sentences)", displayRes = R.string.summarization_short_paragraph),
  CONCISE_SUMMARY(value = "Concise summary (~50 words)", displayRes = R.string.summarization_concise_summary),
  HEADLINE_TITLE(value = "Headline / title", displayRes = R.string.summarization_headline_title),
  ONE_SENTENCE_SUMMARY(value = "One-sentence summary", displayRes = R.string.summarization_one_sentence_summary),
}

enum class LanguageType(val value: String, @param:StringRes val displayRes: Int) {
  CPP(value = "C++", displayRes = R.string.language_cpp),
  JAVA(value = "Java", displayRes = R.string.language_java),
  JAVASCRIPT(value = "JavaScript", displayRes = R.string.language_javascript),
  KOTLIN(value = "Kotlin", displayRes = R.string.language_kotlin),
  PYTHON(value = "Python", displayRes = R.string.language_python),
  SWIFT(value = "Swift", displayRes = R.string.language_swift),
  TYPESCRIPT(value = "TypeScript", displayRes = R.string.language_typescript),
}

enum class InputEditorLabel(val key: String, @param:StringRes val labelRes: Int) {
  TONE(key = "Tone", labelRes = R.string.prompt_template_label_tone),
  STYLE(key = "Style", labelRes = R.string.prompt_template_label_style),
  LANGUAGE(key = "Language", labelRes = R.string.prompt_template_label_language),
}

open class PromptTemplateInputEditor(
  open val label: String,
  open val type: PromptTemplateInputEditorType,
  open val defaultOption: String = "",
)

/** Single select that shows options in bottom sheet. */
class PromptTemplateSingleSelectInputEditor(
  override val label: String,
  @param:StringRes val labelRes: Int,
  val options: List<String> = listOf(),
  val optionDisplayResIds: Map<String, Int> = emptyMap(),
  override val defaultOption: String = "",
) :
  PromptTemplateInputEditor(
    label = label,
    type = PromptTemplateInputEditorType.SINGLE_SELECT,
    defaultOption = defaultOption,
  )

data class PromptTemplateConfig(val inputEditors: List<PromptTemplateInputEditor> = listOf())

private val GEMINI_GRADIENT_STYLE =
  SpanStyle(
    brush = linearGradient(colors = listOf(geminiGradientBlue, geminiGradientPurple, geminiGradientCoral))
  )

enum class PromptTemplateType(
  val key: String,
  @param:StringRes val titleRes: Int,
  val config: PromptTemplateConfig,
  val examplePrompts: List<String> = listOf(),
) {
  FREE_FORM(
    key = "Free form",
    titleRes = R.string.prompt_template_free_form,
    config = PromptTemplateConfig(),
    examplePrompts =
      listOf(
        "Suggest 3 topics for a podcast about \"Friendships in your 20s\".",
        "Outline the key sections needed in a basic logo design brief.",
        "List 3 pros and 3 cons to consider before buying a smart watch.",
        "Write a short, optimistic quote about the future of technology.",
        "Generate 3 potential names for a mobile app that helps users identify plants.",
        "Explain the difference between AI and machine learning in 2 sentences.",
        "Create a simple haiku about a cat sleeping in the sun.",
        "List 3 ways to make instant noodles taste better using common kitchen ingredients.",
      ),
  ),
  REWRITE_TONE(
    key = "Rewrite tone",
    titleRes = R.string.prompt_template_rewrite_tone,
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              label = InputEditorLabel.TONE.key,
              labelRes = InputEditorLabel.TONE.labelRes,
              options = RewriteToneType.entries.map { it.value },
              optionDisplayResIds = RewriteToneType.entries.associate { it.value to it.displayRes },
              defaultOption = RewriteToneType.FORMAL.value,
            )
          )
      ),
    examplePrompts =
      listOf(
        "Hey team, just wanted to remind everyone about the meeting tomorrow @ 10. Be there!",
        "Our new software update includes several bug fixes and performance improvements.",
        "Due to the fact that the weather was bad, we decided to postpone the event.",
        "Please find attached the requested documentation for your perusal.",
        "Welcome to the team. Review the onboarding materials.",
      ),
  ),
  SUMMARIZE_TEXT(
    key = "Summarize text",
    titleRes = R.string.prompt_template_summarize_text,
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              label = InputEditorLabel.STYLE.key,
              labelRes = InputEditorLabel.STYLE.labelRes,
              options = SummarizationType.entries.map { it.value },
              optionDisplayResIds = SummarizationType.entries.associate { it.value to it.displayRes },
              defaultOption = SummarizationType.KEY_BULLET_POINT.value,
            )
          )
      ),
    examplePrompts =
      listOf(
        "The new Pixel phone features an advanced camera system with improved low-light performance and AI-powered editing tools. The display is brighter and more energy-efficient. It runs on the latest Tensor chip, offering faster processing and enhanced security features. Battery life has also been extended, providing all-day power for most users.",
        "Beginning this Friday, January 24, giant pandas Bao Li and Qing Bao are officially on view to the public at the Smithsonian’s National Zoo and Conservation Biology Institute (NZCBI). The 3-year-old bears arrived in Washington this past October, undergoing a quarantine period before making their debut. Under NZCBI’s new agreement with the CWCA, Qing Bao and Bao Li will remain in the United States for ten years, until April 2034, in exchange for an annual fee of \$1 million. The pair are still too young to breed, as pandas only reach sexual maturity between ages 4 and 7. “Kind of picture them as like awkward teenagers right now,” Lally told WUSA9. “We still have about two years before we would probably even see signs that they’re ready to start mating.”",
      ),
  ),
  CODE_SNIPPET(
    key = "Code snippet",
    titleRes = R.string.prompt_template_code_snippet,
    config =
      PromptTemplateConfig(
        inputEditors =
          listOf(
            PromptTemplateSingleSelectInputEditor(
              label = InputEditorLabel.LANGUAGE.key,
              labelRes = InputEditorLabel.LANGUAGE.labelRes,
              options = LanguageType.entries.map { it.value },
              optionDisplayResIds = LanguageType.entries.associate { it.value to it.displayRes },
              defaultOption = LanguageType.JAVASCRIPT.value,
            )
          )
      ),
    examplePrompts =
      listOf(
        "Create an alert box that says \"Hello, World!\"",
        "Declare an immutable variable named 'appName' with the value \"AI Gallery\"",
        "Print the numbers from 1 to 5 using a for loop.",
        "Write a function that returns the square of an integer input.",
      ),
  ),
}

private fun PromptTemplateType.editorValueOrFallback(
  values: Map<String, Any>,
  label: String,
  fallback: String,
): String = (values[label] as? String) ?: fallback

fun PromptTemplateType.genFullPrompt(
  userInput: String,
  inputEditorValues: Map<String, Any>,
): AnnotatedString =
  when (this) {
    PromptTemplateType.FREE_FORM -> AnnotatedString(userInput)
    PromptTemplateType.REWRITE_TONE ->
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) {
          val tone =
            editorValueOrFallback(inputEditorValues, InputEditorLabel.TONE.key, "formal")
          append("Rewrite the following text using a ${tone.lowercase()} tone: ")
        }
        append(userInput)
      }
    PromptTemplateType.SUMMARIZE_TEXT ->
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) {
          val style =
            editorValueOrFallback(inputEditorValues, InputEditorLabel.STYLE.key, "bullet")
          append("Please summarize the following in ${style.lowercase()}: ")
        }
        append(userInput)
      }
    PromptTemplateType.CODE_SNIPPET ->
      buildAnnotatedString {
        withStyle(GEMINI_GRADIENT_STYLE) {
          val language =
            editorValueOrFallback(inputEditorValues, InputEditorLabel.LANGUAGE.key, "code")
          append("Write a $language code snippet to ")
        }
        append(userInput)
      }
  }
