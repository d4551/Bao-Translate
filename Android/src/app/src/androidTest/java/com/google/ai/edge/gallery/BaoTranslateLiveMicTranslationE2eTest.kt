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

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.BaoTranslateModelManager
import com.google.ai.edge.gallery.customtasks.baotranslate.ModelStatus
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.SynthesizedAudio
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import androidx.compose.ui.test.hasContentDescription as hasContentDescriptionMatcher

@RunWith(AndroidJUnit4::class)
class BaoTranslateLiveMicTranslationE2eTest {
  private val composeRule = createAndroidComposeRule<MainActivity>()
  private val liveMicScreenshotRunDir =
    "$LIVE_MIC_SCREENSHOT_ROOT/${System.currentTimeMillis()}"

  @get:Rule
  val ruleChain: TestRule =
    RuleChain.outerRule(LiveMicPermissionRule())
      .around(composeRule)

  @Test
  fun speakerPlaybackCapturedByDefaultMicProducesLiveTranslation_beforeStop() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    prepareDeviceForLiveMicTest(context)
    ensureRequiredModelsReady(context)

    val promptAudio = synthesizeEnglishPrompt(context)
    assertSpeakerPromptReachesDefaultMic(context, promptAudio)

    val taskDescriptionPrefix = composeRule.prepareHome()
    composeRule.openTaskByDescription(taskDescriptionPrefix)
    composeRule.clickTextIfPresent(R.string.bao_translate_welcome_get_started)
    composeRule.waitForText(R.string.bao_translate_title)
    composeRule.waitForContentDescription(
      composeRule.stringResource(R.string.cd_bao_translate_start),
      timeoutMillis = 180_000,
    )
    assertTrue(
      "Live microphone test must start without stale Spanish translation markers; markerCount=${composeRule.spanishMarkerCount()}",
      composeRule.spanishMarkerCount() == 0,
    )

    var recordingStarted = false
    try {
      composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_start))
        .assertIsDisplayed()
        .performClick()
      recordingStarted = true
      composeRule.waitForText(R.string.bao_translate_listening, timeoutMillis = 30_000)
      composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))
        .assertIsDisplayed()

      Thread.sleep(500)
      var promptPlaybackError: Throwable? = null
      val promptPlaybackThread = Thread(
        {
          try {
            playOutLoudThroughBuiltInSpeaker(context, promptAudio)
          } catch (error: Throwable) {
            promptPlaybackError = error
          }
        },
        "bao-live-mic-speaker-prompt",
      )
      promptPlaybackThread.start()
      Thread.sleep(5_000)
      captureLiveMicScreenshot("live_mic_recording_with_speaker_prompt")
      promptPlaybackThread.join(30_000)
      promptPlaybackError?.let { error ->
        throw AssertionError("Speaker prompt playback failed during live microphone translation test", error)
      }
      assertTrue("Speaker prompt playback thread did not finish", !promptPlaybackThread.isAlive)

      assertTrue(
        "Bao Translate did not show live Spanish translation markers while still recording; markerCount=${composeRule.spanishMarkerCount()}",
        composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 120_000),
      )
    } finally {
      if (recordingStarted && composeRule.hasContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))) {
        composeRule.onNodeWithContentDescription(composeRule.stringResource(R.string.cd_bao_translate_stop))
          .performClick()
        composeRule.waitForContentDescription(
          composeRule.stringResource(R.string.cd_bao_translate_start),
          timeoutMillis = 30_000,
        )
      }
    }

    assertTrue(
      "Bao Translate live transcript did not retain Spanish translation markers after stop; markerCount=${composeRule.spanishMarkerCount()}",
      composeRule.waitForSpanishTranslationMarkers(timeoutMillis = 30_000),
    )
  }

  private fun prepareDeviceForLiveMicTest(context: Context) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    listOf(
      "input keyevent KEYCODE_WAKEUP",
      "wm dismiss-keyguard",
      "svc power stayon true",
      "cmd statusbar collapse",
    ).forEach { command ->
      instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.setStreamVolume(
      AudioManager.STREAM_MUSIC,
      audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
      0,
    )
  }

  private fun ensureRequiredModelsReady(context: Context) {
    listOf("whisper_base", "silero_vad", "qwen25_1b", "kokoro_tts").forEach { modelId ->
      if (BaoTranslateModelManager.checkModelStatus(context, modelId) != ModelStatus.Ready) {
        val result = runBlocking {
          BaoTranslateModelManager.downloadModel(context, modelId, wifiOnly = false)
        }
        assertTrue("Failed to download $modelId: ${result.exceptionOrNull()?.message}", result.isSuccess)
      }

      val status = BaoTranslateModelManager.checkModelStatus(context, modelId)
      assertTrue("$modelId is not ready after live-mic provisioning; status=$status", status == ModelStatus.Ready)
    }
  }

  private fun captureLiveMicScreenshot(name: String) {
    runLiveMicShell("mkdir -p $liveMicScreenshotRunDir")
    val path = "$liveMicScreenshotRunDir/$name.png"
    runLiveMicShell("screencap -p $path")
    val listing = runLiveMicShell("ls -ln $path")
    val size = parseRemoteFileSize(listing)
    assertTrue("Live microphone screenshot $path was not created. ls output: $listing", listing.contains(path))
    assertTrue(
      "Live microphone screenshot $path was too small to be useful: $size bytes. ls output: $listing",
      size >= MIN_LIVE_MIC_SCREENSHOT_BYTES,
    )
    Log.i(TAG, "Captured live microphone screenshot $path ($size bytes)")
  }

  private fun synthesizeEnglishPrompt(context: Context): SynthesizedAudio {
    val initLatch = CountDownLatch(1)
    var initStatus = TextToSpeech.ERROR
    val tts = TextToSpeech(context.applicationContext) { status ->
      initStatus = status
      initLatch.countDown()
    }

    try {
      assertTrue(
        "Android TextToSpeech did not initialize for live microphone speaker prompt",
        initLatch.await(30, TimeUnit.SECONDS) && initStatus == TextToSpeech.SUCCESS,
      )
      assertTrue(
        "Android TextToSpeech does not expose a usable US English voice",
        tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE,
      )
      tts.language = Locale.US
      tts.setSpeechRate(0.9f)
      tts.setPitch(1.0f)

      val prompt = (1..8).joinToString(" ") { "Good night." }
      val utteranceId = "bao-live-mic-prompt"
      val promptFile = File(context.cacheDir, "$utteranceId.wav")
      if (promptFile.exists()) {
        assertTrue("Could not replace old live microphone prompt WAV", promptFile.delete())
      }

      val doneLatch = CountDownLatch(1)
      var synthError: String? = null
      tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) = Unit

          override fun onDone(utteranceId: String?) {
            doneLatch.countDown()
          }

          @Deprecated("Deprecated in Android framework")
          override fun onError(utteranceId: String?) {
            synthError = "TextToSpeech failed for utterance=$utteranceId"
            doneLatch.countDown()
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            synthError = "TextToSpeech failed for utterance=$utteranceId errorCode=$errorCode"
            doneLatch.countDown()
          }
        }
      )

      val params = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
      }
      assertTrue(
        "Android TextToSpeech rejected live microphone prompt synthesis",
        tts.synthesizeToFile(prompt, params, promptFile, utteranceId) == TextToSpeech.SUCCESS,
      )
      assertTrue(
        "Android TextToSpeech did not finish live microphone prompt synthesis; error=$synthError",
        doneLatch.await(60, TimeUnit.SECONDS) && synthError == null,
      )

      val wavBytes = promptFile.readBytes()
      assertTrue("Android TextToSpeech did not produce a valid WAV prompt", WavUtils.isValidWav(wavBytes))
      val sampleRate = WavUtils.extractSampleRateFromWav(wavBytes) ?: 0
      val rawSamples = WavUtils.extractSamplesFromWav(wavBytes)
      assertTrue("Android TextToSpeech prompt WAV had invalid sample rate: $sampleRate", sampleRate > 0)
      assertTrue("Android TextToSpeech prompt WAV had no PCM samples", rawSamples.isNotEmpty())

      val samples = rawSamples.withTrailingSilence(sampleRate / 2).normalizedToPeak(0.8f)
      Log.i(TAG, "speaker prompt synthesized samples=${samples.size} sampleRate=$sampleRate peak=${samples.peakAbs()}")
      return SynthesizedAudio(samples = samples, sampleRate = sampleRate)
    } finally {
      tts.shutdown()
    }
  }

  private fun FloatArray.withTrailingSilence(sampleCount: Int): FloatArray =
    copyOf(size + sampleCount)

  private fun FloatArray.normalizedToPeak(targetPeak: Float): FloatArray {
    val peak = peakAbs()
    if (peak <= 0f || peak <= targetPeak) return this
    val gain = targetPeak / peak
    return FloatArray(size) { index -> this[index] * gain }
  }

  private fun FloatArray.peakAbs(): Float =
    maxOfOrNull { abs(it) } ?: 0f

  private fun assertSpeakerPromptReachesDefaultMic(context: Context, audio: SynthesizedAudio) {
    val recorder = createDefaultMicRecorder()
    assertTrue(
      "AudioRecord did not initialize for live microphone acoustic probe",
      recorder.state == AudioRecord.STATE_INITIALIZED,
    )

    try {
      recorder.startRecording()
      assertTrue(
        "AudioRecord did not start for live microphone acoustic probe",
        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING,
      )

      val baseline = readMicEnergy(recorder, durationMillis = 800)
      val playbackThread = Thread {
        playOutLoudThroughBuiltInSpeaker(context, audio)
      }
      playbackThread.start()
      val playback = readMicEnergy(
        recorder = recorder,
        durationMillis = promptDurationMillis(audio) + 1_000,
      )
      playbackThread.join(5_000)

      Log.i(TAG, "live mic acoustic probe baseline=$baseline playback=$playback")
      assertTrue(
        "Default microphone did not capture the speaker prompt strongly enough; baseline=$baseline playback=$playback",
        playback.peakAbs > 1_000 && playback.rms > maxOf(0.008, baseline.rms * 2.0),
      )
    } finally {
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
      }
      recorder.release()
    }
  }

  private fun createDefaultMicRecorder(): AudioRecord {
    val sampleRate = PipelineConfig.STT_SAMPLE_RATE
    val minBuffer = AudioRecord.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    return AudioRecord(
      MediaRecorder.AudioSource.MIC,
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minBuffer.coerceAtLeast(0) * 2, sampleRate / 2),
    )
  }

  private fun readMicEnergy(recorder: AudioRecord, durationMillis: Int): MicEnergy {
    val sampleRate = PipelineConfig.STT_SAMPLE_RATE
    val deadline = System.currentTimeMillis() + durationMillis
    val buffer = ShortArray(sampleRate / 10)
    var frames = 0
    var peakAbs = 0
    var sumSquares = 0.0

    while (System.currentTimeMillis() < deadline) {
      val read = recorder.read(buffer, 0, buffer.size)
      if (read > 0) {
        for (i in 0 until read) {
          val value = buffer[i].toInt()
          val absValue = kotlin.math.abs(value)
          peakAbs = maxOf(peakAbs, absValue)
          sumSquares += value.toDouble() * value.toDouble()
        }
        frames += read
      }
    }

    val rms = if (frames > 0) sqrt(sumSquares / frames) / Short.MAX_VALUE else 0.0
    return MicEnergy(frames = frames, rms = rms, peakAbs = peakAbs)
  }

  private fun promptDurationMillis(audio: SynthesizedAudio): Int =
    ((audio.samples.size.toLong() * 1_000L) / audio.sampleRate).toInt()

  private fun playOutLoudThroughBuiltInSpeaker(context: Context, audio: SynthesizedAudio) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val speaker =
      audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    assertNotNull("Device did not expose a built-in speaker for the live microphone prompt", speaker)

    val minBuffer = AudioTrack.getMinBufferSize(
      audio.sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_FLOAT,
    )
    val track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(audio.sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(maxOf(minBuffer, audio.sampleRate / 2 * Float.SIZE_BYTES))
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()

    try {
      assertTrue(
        "AudioTrack rejected built-in speaker as preferred prompt output",
        track.setPreferredDevice(speaker),
      )
      track.setVolume(AudioTrack.getMaxVolume())
      track.play()

      var writtenFrames = 0
      while (writtenFrames < audio.samples.size) {
        val count = minOf(4096, audio.samples.size - writtenFrames)
        val written = track.write(audio.samples, writtenFrames, count, AudioTrack.WRITE_BLOCKING)
        assertTrue("AudioTrack failed while writing prompt samples to the speaker route: $written", written > 0)
        writtenFrames += written
      }

      val deadline = System.currentTimeMillis() + promptDurationMillis(audio) + 5_000
      while (track.playbackHeadPosition < writtenFrames && System.currentTimeMillis() < deadline) {
        Thread.sleep(50)
      }
      Log.i(TAG, "speaker prompt playback wrote=$writtenFrames head=${track.playbackHeadPosition}")
      assertTrue(
        "Speaker prompt playback did not finish; head=${track.playbackHeadPosition}, written=$writtenFrames",
        track.playbackHeadPosition >= writtenFrames,
      )
    } finally {
      if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
        track.stop()
      }
      track.release()
    }
  }
}

private data class MicEnergy(
  val frames: Int,
  val rms: Double,
  val peakAbs: Int,
)

private class LiveMicPermissionRule : ExternalResource() {
  override fun before() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName
    val permissions =
      buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(Manifest.permission.BLUETOOTH_SCAN)
          add(Manifest.permission.BLUETOOTH_CONNECT)
          add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
    permissions.forEach { permission ->
      instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
    }
  }
}

private typealias LiveMicComposeRule =
  AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

private const val TAG = "BaoTranslateLiveMicE2E"
private const val LIVE_MIC_SCREENSHOT_ROOT = "/sdcard/Download/gallery-baotranslate-live-mic"
private const val MIN_LIVE_MIC_SCREENSHOT_BYTES = 10_000L
private val SPANISH_TRANSLATION_MARKERS = listOf("buenas", "noches")

private fun runLiveMicShell(command: String): String {
  val descriptor =
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
  return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    .bufferedReader()
    .use { it.readText() }
}

private fun parseRemoteFileSize(listing: String): Long {
  val firstLine = listing.lineSequence().firstOrNull { it.isNotBlank() } ?: return 0L
  val columns = firstLine.trim().split(Regex("\\s+"))
  return columns.getOrNull(4)?.toLongOrNull() ?: 0L
}

private fun LiveMicComposeRule.stringResource(@StringRes resId: Int): String =
  activity.getString(resId)

private fun LiveMicComposeRule.prepareHome(): String {
  InstrumentationRegistry.getInstrumentation()
    .uiAutomation
    .executeShellCommand("cmd statusbar collapse")
    .close()

  val taskDescriptionPrefix = "${stringResource(R.string.bao_translate)} task"
  repeat(8) {
    if (clickTextIfPresent(R.string.tos_dialog_accept_and_continue_button_label, 2_000)) {
      return@repeat
    }
    if (clickTextIfPresent(R.string.dismiss, timeoutMillis = 1_000)) {
      return@repeat
    }
    if (hasContentDescription(taskDescriptionPrefix, substring = true)) {
      return taskDescriptionPrefix
    }
  }
  waitForContentDescription(taskDescriptionPrefix, timeoutMillis = 30_000, substring = true)
  return taskDescriptionPrefix
}

private fun LiveMicComposeRule.openTaskByDescription(taskDescriptionPrefix: String) {
  waitForContentDescription(taskDescriptionPrefix, substring = true)
  onNode(
      hasContentDescriptionMatcher(taskDescriptionPrefix, substring = true),
      useUnmergedTree = true,
    )
    .performClick()
}

private fun LiveMicComposeRule.waitForSpanishTranslationMarkers(timeoutMillis: Long): Boolean =
  runCatching { waitUntil(timeoutMillis) { spanishMarkerCount() >= 2 } }.isSuccess

private fun LiveMicComposeRule.spanishMarkerCount(): Int =
  SPANISH_TRANSLATION_MARKERS.count { marker ->
    hasText(marker, substring = true, ignoreCase = true)
  }

private fun LiveMicComposeRule.waitForText(
  @StringRes resId: Int,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitForText(stringResource(resId), timeoutMillis, substring)
}

private fun LiveMicComposeRule.waitForText(
  text: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) { hasText(text, substring = substring) }
}

private fun LiveMicComposeRule.waitForContentDescription(
  contentDescription: String,
  timeoutMillis: Long = 10_000,
  substring: Boolean = false,
) {
  waitUntil(timeoutMillis) { hasContentDescription(contentDescription, substring) }
}

private fun LiveMicComposeRule.clickTextIfPresent(
  @StringRes resId: Int,
  timeoutMillis: Long = 3_000,
): Boolean {
  val text = stringResource(resId)
  val appeared = runCatching { waitForText(text, timeoutMillis) }.isSuccess
  if (appeared) {
    onNodeWithText(text).performClick()
    waitForIdle()
  }
  return appeared
}

private fun LiveMicComposeRule.hasText(
  text: String,
  substring: Boolean = false,
  ignoreCase: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithText(
          text = text,
          substring = substring,
          ignoreCase = ignoreCase,
          useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)

private fun LiveMicComposeRule.hasContentDescription(
  contentDescription: String,
  substring: Boolean = false,
): Boolean =
  runCatching {
      onAllNodesWithContentDescription(contentDescription, substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    .getOrDefault(false)
