package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.experimental.categories.Category
import org.junit.Test

@Category(Strict::class)
class ModelDownloadCoordinatorTest {

  @Test
  fun `supertonic with required models and live pipelines reinitializes TTS only`() {
    assertEquals(
      PostDownloadInit.TtsOnly,
      postDownloadInitAction(
        modelId = "supertonic_tts",
        requiredModelsReady = true,
        pipelinesReady = true,
      ),
    )
  }

  @Test
  fun `supertonic before required models ready does full pipeline init`() {
    assertEquals(
      PostDownloadInit.FullPipelines,
      postDownloadInitAction(
        modelId = "supertonic_tts",
        requiredModelsReady = false,
        pipelinesReady = false,
      ),
    )
  }

  @Test
  fun `supertonic with models on disk but pipelines not live does full init`() {
    assertEquals(
      PostDownloadInit.FullPipelines,
      postDownloadInitAction(
        modelId = "supertonic_tts",
        requiredModelsReady = true,
        pipelinesReady = false,
      ),
    )
  }

  @Test
  fun `required model download always does full pipeline init`() {
    assertEquals(
      PostDownloadInit.FullPipelines,
      postDownloadInitAction(
        modelId = "kokoro_tts",
        requiredModelsReady = true,
        pipelinesReady = true,
      ),
    )
  }
}