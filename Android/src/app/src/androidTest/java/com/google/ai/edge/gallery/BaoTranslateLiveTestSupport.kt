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

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioResampler
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import kotlin.math.abs
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

/**
 * Reusable on-device support for live-translation pipeline tests: device prep, model provisioning,
 * and a deterministic English speech prompt (a pre-rendered WAV bundled in androidTest assets) as
 * 16 kHz PCM for injection through
 * [com.google.ai.edge.gallery.customtasks.baotranslate.RecordingController.testPcmSource].
 *
 * The prompt is a bundled WAV (not the platform TextToSpeech) so the test runs on ANY device — some
 * of the target devices ship without a system TTS engine, which would otherwise fail the audio
 * source before the pipeline under test is even exercised.
 */
object BaoTranslateLiveTestSupport {
  private const val TAG = "BaoLiveTestSupport"
  private const val PROMPT_ASSET = "bao_live_prompt_en.wav"
  private const val PROMPT_ASSET_ES = "bao_live_prompt_es.wav"

  /** Models the live pipeline needs before it will start recording. */
  val REQUIRED_MODELS =
    listOf("whisper_base", "streaming_asr", "silero_vad", "qwen25_1b", "kokoro_tts")

  fun prepareDevice(context: Context) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    listOf(
        "input keyevent KEYCODE_WAKEUP",
        "wm dismiss-keyguard",
        "svc power stayon true",
        "cmd statusbar collapse",
      )
      .forEach { command -> instrumentation.uiAutomation.executeShellCommand(command).close() }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.setStreamVolume(
      AudioManager.STREAM_MUSIC,
      audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
      0,
    )
  }

  fun ensureRequiredModelsReady(context: Context) {
    REQUIRED_MODELS.forEach { modelId ->
      if (BaoTranslateModelManager.checkModelStatus(context, modelId) != ModelStatus.Ready) {
        val result =
          runBlocking { BaoTranslateModelManager.downloadModel(context, modelId, wifiOnly = false) }
        require(result.isSuccess) {
          "Failed to download $modelId: ${result.exceptionOrNull()?.message}"
        }
      }
      val status = BaoTranslateModelManager.checkModelStatus(context, modelId)
      require(status == ModelStatus.Ready) { "$modelId not ready; status=$status" }
    }
  }

  /** The bundled English speech prompt, resampled to the STT rate, as 16-bit PCM for injection. */
  fun englishPromptAsSttPcm(): ShortArray = promptAsSttPcm(PROMPT_ASSET)

  /** The bundled Spanish speech prompt (for multilingual streaming-caption verification). */
  fun spanishPromptAsSttPcm(): ShortArray = promptAsSttPcm(PROMPT_ASSET_ES)

  /** The bundled speech prompt for any supported language code (bao_live_prompt_<code>.wav). */
  fun promptForLanguage(code: String): ShortArray = promptAsSttPcm("bao_live_prompt_$code.wav")

  private fun promptAsSttPcm(asset: String): ShortArray {
    val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    val wavBytes = testAssets.open(asset).use { it.readBytes() }
    require(WavUtils.isValidWav(wavBytes)) { "Bundled prompt WAV ($asset) is not a valid WAV" }
    val sampleRate = WavUtils.extractSampleRateFromWav(wavBytes) ?: 0
    val samples = WavUtils.extractSamplesFromWav(wavBytes)
    require(sampleRate > 0 && samples.isNotEmpty()) { "Bundled prompt WAV had no usable PCM" }

    val atSttRate =
      if (sampleRate == PipelineConfig.STT_SAMPLE_RATE) samples
      else AudioResampler.resample(samples, sampleRate, PipelineConfig.STT_SAMPLE_RATE)
    val normalized = normalizedToPeak(atSttRate, 0.8f)
    val pcm = ShortArray(normalized.size) { i -> (normalized[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    Log.i(TAG, "injected prompt PCM samples=${pcm.size} @${PipelineConfig.STT_SAMPLE_RATE}Hz")
    return pcm
  }

  private fun normalizedToPeak(samples: FloatArray, targetPeak: Float): FloatArray {
    var peak = 0f
    for (s in samples) peak = maxOf(peak, abs(s))
    if (peak <= 0f || peak <= targetPeak) return samples
    val gain = targetPeak / peak
    return FloatArray(samples.size) { i -> samples[i] * gain }
  }
}
