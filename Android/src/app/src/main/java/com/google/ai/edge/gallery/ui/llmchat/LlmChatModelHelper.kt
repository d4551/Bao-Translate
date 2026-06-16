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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
      }
    BaoLog.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        // litert-lm requires maxNumTokens to be positive or null; a model whose MAX_TOKENS config is
        // 0/unset (e.g. Qwen2.5-1.5B) would otherwise crash EngineConfig. null => use model default.
        maxNumTokens = maxTokens.takeIf { it > 0 },
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    var supportsSpeculativeDecoding = false
    runCatching {
        com.google.ai.edge.litertlm.Capabilities(modelPath).use {
          supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
        }
      }
      .onFailure { e ->
        BaoLog.w(TAG, "Failed to inspect LiteRT-LM capabilities for '${model.name}': ${e.message}")
      }

    val configuredSpeculativeDecoding =
      supportsSpeculativeDecoding &&
        model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) == true &&
        model.getBooleanConfigValue(
          key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
          defaultValue = false,
        )

    fun createModelInstance(speculativeDecoding: Boolean): Result<LlmModelInstance> {
      var createdEngine: Engine? = null
      val result =
        runCatching {
          ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
          BaoLog.d(TAG, "Speculative decoding enabled: $speculativeDecoding")

          val engine = Engine(engineConfig)
          createdEngine = engine
          engine.initialize()

          ExperimentalFlags.enableConversationConstrainedDecoding =
            enableConversationConstrainedDecoding
          val conversation =
            engine.createConversation(
              ConversationConfig(
                samplerConfig =
                  if (preferredBackend is Backend.NPU) {
                    null
                  } else {
                    SamplerConfig(
                      topK = topK,
                      topP = topP.toDouble(),
                      temperature = temperature.toDouble(),
                    )
                  },
                systemInstruction = systemInstruction,
                tools = tools,
              )
            )
          LlmModelInstance(engine = engine, conversation = conversation)
        }

      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      result.onFailure {
        createdEngine?.let { engine ->
          runCatching { engine.close() }
            .onFailure { closeError ->
              BaoLog.e(TAG, "Failed to close engine after initialization failure", closeError)
            }
        }
      }
      return result
    }

    val firstInit = createModelInstance(configuredSpeculativeDecoding)
    val initializedInstance =
      if (
        firstInit.isFailure &&
          !configuredSpeculativeDecoding &&
          supportsSpeculativeDecoding &&
          firstInit.exceptionOrNull()?.message?.contains("TF_LITE_PREFILL_DECODE") == true
      ) {
        BaoLog.w(
          TAG,
          "Model '${model.name}' has no non-speculative prefill/decode signature; " +
            "retrying with speculative decoding enabled.",
        )
        createModelInstance(speculativeDecoding = true)
      } else {
        firstInit
      }

    initializedInstance
      .onSuccess { model.instance = it }
      .onFailure { e ->
        onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
        return
      }
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    runCatching {

      BaoLog.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      BaoLog.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      val accelerator =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (accelerator == Accelerator.NPU.label || accelerator == Accelerator.TPU.label) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
            initialMessages = initialMessages,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation

      BaoLog.d(TAG, "Resetting done")
    
}.onFailure { e ->

      BaoLog.e(TAG, "Failed to reset conversation", e)
    
}
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as? LlmModelInstance ?: return

    runCatching {

      instance.conversation.close()
    
}.onFailure { e ->

      BaoLog.e(TAG, "Failed to close the conversation: ${e.message}")
    
}

    runCatching {

      instance.engine.close()
    
}.onFailure { e ->

      BaoLog.e(TAG, "Failed to close the engine: ${e.message}")
    
}

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    onDone()
    BaoLog.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    instance.conversation.cancelProcess()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    val contents = mutableListOf<Content>()
    for (image in images) {
      contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (input.trim().isNotEmpty()) {
      contents.add(Content.Text(input))
    }

    conversation.sendMessageAsync(
      Contents.of(contents),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          resultListener(message.toString(), false, message.channels["thought"])
        }

        override fun onDone() {
          resultListener("", true, null)
        }

        override fun onError(throwable: Throwable) {
          if (throwable is CancellationException) {
            BaoLog.i(TAG, "The inference is cancelled.")
            resultListener("", true, null)
          } else {
            BaoLog.e(TAG, "onError", throwable)
            onError("Error: ${throwable.message}")
          }
        }
      },
      extraContext ?: emptyMap(),
    )
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
