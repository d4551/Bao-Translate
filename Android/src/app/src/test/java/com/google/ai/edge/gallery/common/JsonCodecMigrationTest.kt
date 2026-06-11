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
package com.google.ai.edge.gallery.common

import com.google.ai.edge.gallery.customtasks.agentchat.ReadCalendarEventsResponse
import com.google.ai.edge.gallery.customtasks.agentchat.CalendarEventDto
import com.google.ai.edge.gallery.customtasks.agentchat.ScheduleNotificationParams
import com.google.ai.edge.gallery.customtasks.agentchat.SendEmailParams
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SkillAllowlist
import com.google.ai.edge.gallery.testkit.Strict
import java.io.File
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Wire-compatibility net for the gson/moshi -> kotlinx.serialization consolidation.
 *
 * Pins the semantics the old parsers provided so the single [LenientJson]/[StrictJson]
 * codec layer cannot silently regress them:
 * - every shipped allowlist snapshot (every JSON file under `model_allowlists` + the root
 *   `model_allowlist.json`) must decode, including release-specific extra keys;
 * - unknown enum strings degrade to the field's null default (gson parity for stale
 *   on-disk caches), they must not fail the whole document;
 * - the JS-skill result parse stays strict: unknown keys reject the document
 *   (Moshi `failOnUnknown` parity) so arbitrary JSON falls back to plain text;
 * - the LLM tool-call DTOs keep their snake_case wire names;
 * - parse failures map to [AppError.Parse].
 */
@Category(Strict::class)
class JsonCodecMigrationTest {

  /** Resolves the repo root from the Gradle test working dir (Android/src/app). */
  private fun repoFile(relative: String): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null && !File(dir, "model_allowlists").isDirectory) dir = dir.parentFile
    val root = requireNotNull(dir) { "repo root with model_allowlists/ not found from user.dir" }
    return File(root, relative)
  }

  @Test
  fun everyShippedAllowlistSnapshot_decodes() {
    val snapshots =
      requireNotNull(repoFile("model_allowlists").listFiles { f -> f.extension == "json" })
        .sortedBy { it.name }
    assertTrue("expected versioned snapshots", snapshots.isNotEmpty())
    for (snapshot in snapshots) {
      val allowlist = LenientJson.decodeFromString<ModelAllowlist>(snapshot.readText())
      assertTrue("${snapshot.name}: no models", allowlist.models.isNotEmpty())
      assertEquals(
        "${snapshot.name}: duplicate model names",
        allowlist.models.size,
        allowlist.models.map { it.name }.toSet().size,
      )
    }
  }

  @Test
  fun rootAllowlist_mirrorsActiveRelease_andDecodes() {
    val root = LenientJson.decodeFromString<ModelAllowlist>(repoFile("model_allowlist.json").readText())
    assertTrue(root.models.isNotEmpty())
    // Required fields are hard requirements now (gson silently null-injected them).
    for (m in root.models) {
      assertTrue("model ${m.name} has blank commitHash", m.commitHash.isNotBlank())
      assertTrue("model ${m.name} has no taskTypes", m.taskTypes.isNotEmpty())
    }
  }

  @Test
  fun allowlist_unknownEnumString_degradesToNull_notDocumentFailure() {
    // A future runtimeType in a cached (old-app-written) allowlist must not brick startup.
    val json =
      """{"models":[{"name":"n","modelId":"id","modelFile":"f","commitHash":"c","description":"d",
         "sizeInBytes":1,"defaultConfig":{},"taskTypes":["llm_chat"],"runtimeType":"hologram_core",
         "futureKey":{"nested":true}}]}"""
    val allowlist = LenientJson.decodeFromString<ModelAllowlist>(json)
    assertNull(allowlist.models.single().runtimeType)
  }

  @Test
  fun allowlist_enumWireNames_areSnakeCase() {
    val json =
      """{"models":[{"name":"n","modelId":"id","modelFile":"f","commitHash":"c","description":"d",
         "sizeInBytes":1,"defaultConfig":{},"taskTypes":["llm_chat"],"runtimeType":"litert_lm",
         "capabilities":["llm_thinking","speculative_decoding"],
         "capabilityToTaskTypes":{"llm_thinking":["llm_chat"]}}]}"""
    val model = LenientJson.decodeFromString<ModelAllowlist>(json).models.single()
    assertEquals(RuntimeType.LITERT_LM, model.runtimeType)
    assertEquals(
      listOf(ModelCapability.LLM_THINKING, ModelCapability.SPECULATIVE_DECODING),
      model.capabilities,
    )
    assertEquals(listOf("llm_chat"), model.capabilityToTaskTypes?.get(ModelCapability.LLM_THINKING))
  }

  @Test
  fun allowlist_missingRequiredField_failsLoudly() {
    // gson silently produced a null name; the consolidated codec fails the document instead.
    val json = """{"models":[{"modelId":"id","modelFile":"f","commitHash":"c","description":"d",
       "sizeInBytes":1,"defaultConfig":{},"taskTypes":[]}]}"""
    assertThrows(SerializationException::class.java) {
      LenientJson.decodeFromString<ModelAllowlist>(json)
    }
  }

  @Test
  fun skillAllowlist_decodes_withOptionalAttribution() {
    val json =
      """{"featuredSkills":[{"name":"s","description":"d","skillUrl":"https://x","extra":1}]}"""
    val skills = LenientJson.decodeFromString<SkillAllowlist>(json).featuredSkills
    assertEquals("s", skills.single().name)
    assertNull(skills.single().attributionLabel)
  }

  @Test
  fun releaseInfo_decodes_fromGithubStyleResponse() {
    val json = """{"html_url":"https://github.com/r/x","tag_name":"v1.2.3","draft":false,"id":9}"""
    val info = requireNotNull(parseJson<com.google.ai.edge.gallery.ui.home.ReleaseInfo>(json))
    assertEquals("v1.2.3", info.tag_name)
  }

  @Test
  fun callJsSkillResult_unknownKey_rejects_failOnUnknownParity() {
    // Arbitrary skill JSON must NOT half-parse into an all-null result; the caller treats a
    // failed parse as "the whole payload is the result string".
    val arbitrary = """{"temperature":21,"city":"Tokyo"}"""
    assertNull(
      runCatching { StrictJson.decodeFromString<CallJsSkillResult>(arbitrary) }.getOrNull()
    )
  }

  @Test
  fun callJsSkillResult_conformingPayload_parses_withAbsentFieldsNull() {
    val payload = """{"result":"ok","webview":{"url":"https://w","iframe":true}}"""
    val parsed = StrictJson.decodeFromString<CallJsSkillResult>(payload)
    assertEquals("ok", parsed.result)
    assertNull(parsed.error)
    assertNull(parsed.image)
    assertEquals("https://w", parsed.webview?.url)
    assertNull(parsed.webview?.aspectRatio)
  }

  @Test
  fun intentParams_keepSnakeCaseWireNames_andTolerateExtras() {
    val email =
      LenientJson.decodeFromString<SendEmailParams>(
        """{"extra_email":"a@b.c","extra_subject":"s","extra_text":"t","model_extra":"x"}"""
      )
    assertEquals("a@b.c", email.extra_email)

    val sched =
      LenientJson.decodeFromString<ScheduleNotificationParams>(
        """{"title":"T","message":"M","hour":9,"minute":30,"repeat_daily":true}"""
      )
    assertEquals(true, sched.repeat_daily)
    assertNull(sched.deeplink)
  }

  @Test
  fun readCalendarEventsResponse_encodesWireShape() {
    val encoded =
      LenientJson.encodeToString(
        ReadCalendarEventsResponse(
          listOf(
            CalendarEventDto(
              title = "Standup",
              description = "",
              begin_time = "2026-06-11T09:00:00",
              end_time = "2026-06-11T09:15:00",
            )
          )
        )
      )
    assertTrue(encoded.contains("\"events\":["))
    assertTrue(encoded.contains("\"begin_time\":\"2026-06-11T09:00:00\""))
    assertFalse("no class discriminator on the wire", encoded.contains("type"))
  }

  @Test
  fun serializationFailure_classifiesAsParseError() {
    val error =
      runCatching { LenientJson.decodeFromString<SkillAllowlist>("not json") }
        .exceptionOrNull()
    assertNotNull(error)
    assertTrue(requireNotNull(error).toAppError() is AppError.Parse)
  }
}
