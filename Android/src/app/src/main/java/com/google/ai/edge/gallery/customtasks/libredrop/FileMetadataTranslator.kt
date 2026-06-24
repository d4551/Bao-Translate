package com.google.ai.edge.gallery.customtasks.libredrop

import com.google.ai.edge.litertlm.Contents

data class TranslationRequest(
  val text: String,
  val sourceLanguage: String,
  val targetLanguage: String,
)

data class TranslationResult(
  val originalText: String,
  val translatedText: String,
  val sourceLanguage: String,
  val targetLanguage: String,
)

class FileMetadataTranslator {

  fun buildTranslationPrompt(request: TranslationRequest): Contents {
    val prompt = """
      |Translate the following text from ${request.sourceLanguage} to ${request.targetLanguage}.
      |Only return the translated text, no explanations.
      |
      |Text: ${request.text}
    """.trimMargin()

    return Contents.of(prompt)
  }

  fun buildFileNameTranslationPrompt(
    fileName: String,
    targetLanguage: String,
  ): Contents {
    val prompt = """
      |Translate this file name to $targetLanguage. Keep it concise and natural.
      |Only return the translated name, no explanations.
      |
      |File name: $fileName
    """.trimMargin()

    return Contents.of(prompt)
  }

  fun detectLanguageFromEndpointInfo(endpointInfo: ByteArray?): String {
    if (endpointInfo == null || endpointInfo.isEmpty()) return "en"
    return "en"
  }

  companion object {
    val SUPPORTED_LANGUAGES = setOf(
      "en", "es", "fr", "de", "zh", "ja", "ko", "pt", "it", "ru", "ar", "hi",
    )
  }
}
