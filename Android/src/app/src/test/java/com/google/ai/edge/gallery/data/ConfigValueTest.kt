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

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class ConfigValueTest {

  @Test
  fun from_convertsNumberSliderFloatValueToInt() {
    val converted =
      ConfigValue.from(value = ConfigValue.FloatValue(64f), valueType = ValueType.INT)

    assertEquals(ConfigValue.IntValue(64), converted)
  }

  @Test
  fun modelPreProcess_keepsLlmSamplerDefaultsUsableForRuntime() {
    val model =
      Model(
        name = "m",
        url = "",
        configs = createLlmChatConfigs(defaultTopK = 64, defaultTopP = 0.95f),
        downloadFileName = "m.litertlm",
        isLlm = true,
      )

    model.preProcess()

    assertTrue(model.configValues[ConfigKeys.TOPK.label] is Float)
    assertEquals(64, model.getIntConfigValue(ConfigKeys.TOPK))
    assertEquals(0.95f, model.getFloatConfigValue(ConfigKeys.TOPP), 0.0001f)
  }
}
