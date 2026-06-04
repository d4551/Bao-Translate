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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Project shape tokens. Mirrors the raw `RoundedCornerShape(N.dp)` values used historically so
 * visuals are unchanged when sites migrate to `MaterialTheme.shapes.*`. Values follow Material 3
 * shape categories:
 * - `extraSmall` 4dp  — used inside dense controls (e.g. text-field outlines).
 * - `small` 8dp       — small buttons, secondary chips.
 * - `medium` 12dp     — cards, dialogs (matches `Dimensions.Component.rowCornerRadius`).
 * - `large` 16dp      — dialogs, banners.
 * - `extraLarge` 28dp — full-screen dialogs (TOS).
 */
val AppShapes =
  Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )
