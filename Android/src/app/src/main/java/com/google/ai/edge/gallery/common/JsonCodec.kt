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

import kotlinx.serialization.json.Json

/**
 * The app's single shared JSON codec layer (kotlinx.serialization). All JSON
 * (de)serialization outside the self-contained BLE wire codec goes through one of these
 * two instances — no per-callsite parser construction, no parallel JSON libraries.
 */

/**
 * Tolerant codec for payloads whose producer may add fields over time: model/skill
 * allowlists (versioned snapshots carry release-specific extras), the GitHub releases
 * API, and LLM-produced tool arguments.
 *
 * `coerceInputValues` preserves the old Gson degradation for unknown enum strings
 * (e.g. a future `runtimeType` value in a cached allowlist coerces to the field's
 * `null` default instead of failing the whole document).
 */
val LenientJson: Json = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
}

/**
 * Strict codec: unknown keys reject the document. Used for JS-skill results where
 * "does this even look like a [com.google.ai.edge.gallery.common.CallJsSkillResult]"
 * is the signal that decides between structured handling and treating the payload as
 * plain text (parity with the previous Moshi `failOnUnknown()` semantics).
 */
val StrictJson: Json = Json
