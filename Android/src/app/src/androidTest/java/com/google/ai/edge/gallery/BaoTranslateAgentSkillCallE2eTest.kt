/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery

import android.util.Log
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.common.AgentAction
import com.google.ai.edge.gallery.common.AgentActionName
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.agentchat.injectSkillsAndMcpTools
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.DefaultDataStoreRepository
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the LLM half of the agent-skills path on-device: the provisioned Gemma-4-E2B agent model,
 * given the REAL production agent system prompt + the REAL [AgentTools] registered as a litert-lm
 * tool, actually EMITS a `load_skill` tool call that the engine parses and dispatches.
 *
 * The assertion is on the dispatch, not the model's prose: [AgentTools.loadSkill] sends a
 * [com.google.ai.edge.gallery.common.SkillProgressAgentAction] (name SKILL_PROGRESS) onto its action
 * channel the moment the engine invokes it — so a SKILL_PROGRESS action is hard proof of
 * model -> tool-call -> parse -> dispatch, complementing the skill-EXECUTION proof in
 * [SkillWebViewBridgeE2eTest].
 *
 * Engine/Conversation wiring (constrained decoding, ConversationConfig.tools) mirrors the production
 * LlmChatModelHelper. Gemma-4-E2B is the model the allowlist designates for `llm_agent_chat`.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateAgentSkillCallE2eTest {
  @OptIn(ExperimentalApi::class)
  @Test
  fun gemmaAgent_emitsLoadSkillToolCall_onDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val gemma =
      File(
        BaoTranslateModelManager.getTranslationModelDir(context, "gemma4_e2b"),
        "gemma-4-E2B-it.litertlm",
      )
    if (BaoTranslateModelManager.checkModelStatus(context, "gemma4_e2b") != ModelStatus.Ready) {
      val result =
        runBlocking { BaoTranslateModelManager.downloadModel(context, "gemma4_e2b", wifiOnly = false) }
      assertTrue(
        "Failed to provision agent model gemma4_e2b at ${gemma.absolutePath}: " +
          result.exceptionOrNull()?.message,
        result.isSuccess,
      )
    }
    val gemmaStatus = BaoTranslateModelManager.checkModelStatus(context, "gemma4_e2b")
    assertTrue(
      "Agent model gemma4_e2b was not ready after provisioning at ${gemma.absolutePath}; " +
        "status=$gemmaStatus bytes=${gemma.length()}",
      gemmaStatus == ModelStatus.Ready,
    )

    // Real AgentTools with a real SkillManagerViewModel (unique temp DataStores so we don't collide
    // with the app's live DataStores). SKILL_ALLOWLIST_URL is empty, so construction does no network.
    fun <T> tempStore(serializer: Serializer<T>, name: String) =
      DataStoreFactory.create(serializer = serializer, produceFile = { context.dataStoreFile(name) })
    val repo =
      DefaultDataStoreRepository(
        tempStore(SettingsSerializer, "agenttest_settings.pb"),
        tempStore(UserDataSerializer, "agenttest_userdata.pb"),
        tempStore(CutoutsSerializer, "agenttest_cutouts.pb"),
        tempStore(BenchmarkResultsSerializer, "agenttest_benchmark.pb"),
        tempStore(SkillsSerializer, "agenttest_skills.pb"),
      )
    val skillVm = SkillManagerViewModel(repo, context)
    val agentTools =
      AgentTools().apply {
        this.context = context
        this.skillManagerViewModel = skillVm
        this.taskId = BuiltInTaskId.LLM_AGENT_CHAT
      }

    // The REAL production agent prompt, naming a selected skill so the model knows to call load_skill.
    val skill =
      Skill.newBuilder()
        .setName("calculate-hash")
        .setDescription("Computes cryptographic hashes (SHA-1, SHA-256, MD5) of a given text input.")
        .setSelected(true)
        .build()
    val systemInstruction =
      injectSkillsAndMcpTools(
        baseSystemPrompt = DEFAULT_SYSTEM_PROMPT_SKILLS_ONLY.trimIndent(),
        skills = listOf(skill),
        toolsPrompt = "",
      )

    // Drive the real litert-lm engine + tool registration, mirroring LlmChatModelHelper.
    val engine =
      Engine(
        EngineConfig(
          modelPath = gemma.absolutePath,
          backend = Backend.CPU(),
          // Gemma-4-E2B's configured KV-cache size; the agent system prompt + injected tool schema
          // overrun a smaller cache, which fails prefill tensor allocation.
          maxNumTokens = 4000,
          cacheDir = context.cacheDir.absolutePath,
        )
      )
    val supportsSpeculativeDecoding =
      runCatching {
          Capabilities(gemma.absolutePath).use { it.hasSpeculativeDecodingSupport() }
        }
        .getOrDefault(false)
    val initResult =
      runCatching {
        ExperimentalFlags.enableSpeculativeDecoding = supportsSpeculativeDecoding
        engine.initialize()
      }
    ExperimentalFlags.enableSpeculativeDecoding = false
    if (initResult.isFailure) runCatching { engine.close() }
    assertTrue(
      "Failed to initialize Gemma agent engine; speculative=$supportsSpeculativeDecoding: " +
        initResult.exceptionOrNull()?.message,
      initResult.isSuccess,
    )

    val conversationResult =
      runCatching {
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        engine.createConversation(
          ConversationConfig(
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
            systemInstruction = systemInstruction,
            tools = listOf(tool(agentTools)),
          )
        )
      }
    ExperimentalFlags.enableConversationConstrainedDecoding = false
    assertTrue(
      "Failed to create Gemma agent conversation: ${conversationResult.exceptionOrNull()?.message}",
      conversationResult.isSuccess,
    )
    val conversation = conversationResult.getOrThrow()

    val response = conversation.sendMessage("Calculate the SHA-1 hash of the text: hello")
    Log.i("AgentSkillCallTest", "AGENT_RESPONSE=$response")
    conversation.close()
    engine.close()

    // Drain the action channel: loadSkill dispatch happens synchronously inside sendMessage's
    // tool-calling loop, so every action is buffered (UNLIMITED channel) by the time it returns.
    val dispatched = mutableListOf<AgentAction>()
    while (true) {
      val action = agentTools.actionChannel.tryReceive().getOrNull() ?: break
      dispatched.add(action)
    }

    val emittedLoadSkill = dispatched.any { it.name == AgentActionName.SKILL_PROGRESS }
    Log.i(
      "AgentSkillCallTest",
      "AGENT_LOAD_SKILL_DISPATCHED=$emittedLoadSkill actions=${dispatched.map { it.name }}",
    )
    assertTrue(
      "Gemma agent did NOT emit a load_skill tool call (no SKILL_PROGRESS dispatched). " +
        "Dispatched actions: ${dispatched.map { it.name }}",
      emittedLoadSkill,
    )
  }
}
