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

package com.google.ai.edge.gallery.data

import kotlinx.serialization.Serializable

/** A skill in the skill allowlist. */
@Serializable
data class AllowedSkill(
  val name: String,
  val description: String,
  val skillUrl: String,
  val attributionLabel: String? = null,
  val attributionUrl: String? = null,
)

/** The skill allowlist. */
@Serializable data class SkillAllowlist(val featuredSkills: List<AllowedSkill>)
