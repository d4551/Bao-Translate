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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingSttPipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the streaming-ASR transducer is FIRST-CLASS PROVISIONED, not sideloaded: deletes any
 * existing copy, downloads it through [BaoTranslateModelManager] (the same manifest/allowlist that
 * provisions Whisper/Qwen/Kokoro), and verifies the model extracts app-owned, reports Ready, and
 * loads into the live recognizer. Without this, a clean install would silently fall back to the
 * chunked-Whisper path the product explicitly rejects.
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateStreamingAsrProvisioningE2eTest {
  @Test
  fun streamingAsr_provisionsThroughModelManager_andLoads() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Force a real download: remove any prior/sideloaded copy so a pass can only mean the manager
    // fetched + extracted the model from the manifest URL.
    BaoTranslateModelManager.deleteModel(context, "streaming_asr")
    val before = BaoTranslateModelManager.checkModelStatus(context, "streaming_asr")
    assertEquals(
      "Expected streaming_asr NotDownloaded after delete, was $before",
      ModelStatus.NotDownloaded,
      before,
    )

    val result =
      runBlocking { BaoTranslateModelManager.downloadModel(context, "streaming_asr", wifiOnly = false) }
    assertTrue(
      "streaming_asr provisioning failed: ${result.exceptionOrNull()?.message}",
      result.isSuccess,
    )

    val after = BaoTranslateModelManager.checkModelStatus(context, "streaming_asr")
    assertEquals("streaming_asr not Ready after provisioning, was $after", ModelStatus.Ready, after)

    val dir = BaoTranslateModelManager.getStreamingAsrModelDir(context)
    Log.i(
      "StreamingAsrProvision",
      "STREAMING_ASR_PROVISIONED dir=${dir.absolutePath} bytes=${dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }}",
    )

    // The provisioned model loads into the production recognizer.
    val pipeline = StreamingSttPipeline(dir.absolutePath)
    assertTrue("Provisioned streaming model failed to initialize", pipeline.initialize())
    pipeline.release()
  }
}
