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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A small FilledTonalButton composable with a label and an optional icon. */
@Composable
fun SmallFilledTonalButton(
  onClick: () -> Unit,
  labelResId: Int = 0,
  imageVector: ImageVector? = null,
  iconResId: Int? = null,
  size: Dp = 18.dp,
  label: String? = null,
  enabled: Boolean = true,
) {
  FilledTonalButton(
    onClick = onClick,
    modifier = Modifier.height(32.dp),
    contentPadding = SmallButtonContentPadding,
    enabled = enabled,
  ) {
    SmallButtonContent(
      imageVector = imageVector,
      iconResId = iconResId,
      size = size,
      labelResId = labelResId,
      label = label,
    )
  }
}
