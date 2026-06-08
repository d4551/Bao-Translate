package com.google.ai.edge.gallery.customtasks.baotranslate.translate

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isSourceEcho
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranslation
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

private const val TAG = "TranslationPipeline"

sealed class TranslationOutcome {
  data class Success(val result: TranslationResult) : TranslationOutcome()
  data class Failure(val reason: String, val sourceLanguage: String, val targetLanguage: String) : TranslationOutcome()
}

data class TranslationResult(
  val translatedText: String,
  val sourceLanguage: String,
  val targetLanguage: String,
)

class TranslationPipeline(private val context: Context) {
  private var engine: Engine? = null
  private var isReady = false

  // Serializes native generation against close() so the engine is never freed mid-inference
  // (model delete / switch / onCleared), and serializes concurrent translate calls (the live
  // recording path and the BLE peer path both translate on this single shared engine).
  private val inferenceLock = Any()

  fun initialize(modelPath: String): Boolean {
    val modelFile = File(modelPath)
    if (!modelFile.exists()) {
      BaoLog.w(TAG, "Translation model not found: $modelPath")
      return false
    }

    val config = EngineConfig(
      modelPath = modelPath,
      backend = Backend.CPU(numOfThreads = PipelineConfig.TRANSLATION_THREADS),
      maxNumTokens = PipelineConfig.MAX_TOKENS,
      cacheDir = context.cacheDir.absolutePath,
    )

    val eng = Engine(config)
    eng.initialize()
    engine = eng
    isReady = true
    BaoLog.i(TAG, "Translation engine initialized: ${modelFile.name}")
    return true
  }

  fun translateBlocking(
    sourceText: String,
    sourceLanguage: String,
    targetLanguage: String,
  ): TranslationOutcome = synchronized(inferenceLock) {
    val eng = engine
    if (!isReady || eng == null) {
      return TranslationOutcome.Failure("Translation engine not initialized", sourceLanguage, targetLanguage)
    }
    if (sourceText.isBlank()) {
      return TranslationOutcome.Failure("Empty source text", sourceLanguage, targetLanguage)
    }
    if (sourceText.length > MAX_INPUT_LENGTH) {
      return TranslationOutcome.Failure("Input too long: ${sourceText.length} > $MAX_INPUT_LENGTH", sourceLanguage, targetLanguage)
    }

    val prompt = buildTranslationPrompt(sourceLanguage, targetLanguage, sourceText)

    val samplerConfig = SamplerConfig(
      topK = PipelineConfig.SAMPLER_TOP_K,
      topP = PipelineConfig.SAMPLER_TOP_P,
      temperature = PipelineConfig.SAMPLER_TEMPERATURE,
    )

    val conversationConfig = ConversationConfig(
      samplerConfig = samplerConfig,
    )

    // The native litertlm inference (createConversation/sendMessage) can throw on OOM, a native
    // abort, or a model-state error. translateBlocking promises a total TranslationOutcome, so funnel
    // any throw into Failure instead of letting it escape and crash the recording/peer coroutine
    // (whose launch scope has no CoroutineExceptionHandler). conversation.close() stays in finally.
    val translatedText = runCatching {
      val conversation = eng.createConversation(conversationConfig)
      try {
        val response = conversation.sendMessage(prompt)
        cleanTranslation(extractText(response))
      } finally {
        conversation.close()
      }
    }.getOrElse { e ->
      BaoLog.w(TAG, "Translation inference failed: ${e.message}")
      return TranslationOutcome.Failure(
        "Translation failed: ${e.message ?: "inference error"}",
        sourceLanguage,
        targetLanguage,
      )
    }

    // Guard against the common on-device LLM failure where the model echoes the source verbatim
    // instead of translating.
    if (isSourceEcho(translatedText, sourceText, sourceLanguage, targetLanguage)) {
      BaoLog.w(TAG, "Translation echoed source verbatim")
      return TranslationOutcome.Failure(
        "Translation failed: model echoed the input",
        sourceLanguage,
        targetLanguage,
      )
    }

    if (!isValidTranslation(translatedText, sourceText)) {
      BaoLog.w(TAG, "Translation produced invalid output: $translatedText")
      return TranslationOutcome.Failure(
        "Translation failed: model produced invalid output",
        sourceLanguage,
        targetLanguage,
      )
    }

    return TranslationOutcome.Success(
      TranslationResult(
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
      )
    )
  }

  companion object {
    private const val MAX_INPUT_LENGTH = PipelineConfig.MAX_INPUT_LENGTH
  }

  private fun extractText(message: Message): String {
    return message.toString()
  }

  private fun buildTranslationPrompt(
    sourceLanguage: String,
    targetLanguage: String,
    sourceText: String,
  ): String {
    val srcLang = normalizeLanguage(sourceLanguage)
    val tgtLang = normalizeLanguage(targetLanguage)

    return """You are a professional $srcLang to $tgtLang translator. Your goal is to accurately convey the meaning and nuances of the original text while adhering to $tgtLang grammar, vocabulary, and cultural sensitivities.

Produce only the $tgtLang translation, without any additional explanations, commentary, quotation marks, or prefixes.

Translate the following $srcLang text into $tgtLang:

$sourceText""".trimIndent()
  }

  private fun cleanTranslation(text: String): String {
    return text
      .trim()
      .removeSurrounding("\"")
      .removeSurrounding("'")
      .removePrefix("Translation: ")
      .trim()
  }

  private fun normalizeLanguage(language: String): String {
    return when (language.lowercase()) {
      "auto", "detect" -> "source language"
      "en", "english" -> "English"
      "es", "spanish" -> "Spanish"
      "fr", "french" -> "French"
      "de", "german" -> "German"
      "zh", "chinese" -> "Chinese"
      "ja", "japanese" -> "Japanese"
      "ko", "korean" -> "Korean"
      "pt", "portuguese" -> "Portuguese"
      "it", "italian" -> "Italian"
      "hi", "hindi" -> "Hindi"
      "ar", "arabic" -> "Arabic"
      "ru", "russian" -> "Russian"
      "nl", "dutch" -> "Dutch"
      "pl", "polish" -> "Polish"
      "tr", "turkish" -> "Turkish"
      else -> language
    }
  }

  fun cleanup() {
    synchronized(inferenceLock) {
      engine?.close()
      engine = null
      isReady = false
    }
  }
}
