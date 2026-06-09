package com.google.ai.edge.gallery.customtasks.baotranslate

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WAVEFORM_HISTORY
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WaveformRenderer
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.waveformAmplitude
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.isReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EnrollmentState {
  READY,
  RECORDING,
  PROCESSING,
  SUCCESS,
  ERROR,
}

private const val MIN_RECORDING_MS = 3000L
private const val MAX_RECORDING_MS = 10000L
private const val SAMPLE_RATE = PipelineConfig.STT_SAMPLE_RATE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceEnrollmentSheet(
  onDismiss: () -> Unit,
  onEnrollComplete: (ShortArray, Int, String?) -> Unit,
  enrollmentState: EnrollmentState,
  onEnrollmentStateChange: (EnrollmentState) -> Unit,
  // The user's selected source language key (e.g. "English", "Russian"); the read-aloud prompt is
  // shown in this language so a non-English speaker reads a sentence they can actually pronounce.
  sourceLanguage: String,
  errorMessage: String? = null,
  isTablet: Boolean = false,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val micPermissionDenied = stringResource(R.string.bao_translate_permission_mic_denied)
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth
  
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }
	  val micInitFailed = stringResource(R.string.bao_translate_error_microphone_init)
	  val recordingShort = stringResource(R.string.bao_translate_error_recording_short)
	  val reduceMotion = isReducedMotion

  var recordingDurationMs by remember { mutableStateOf(0L) }
  // Rolling amplitude history (newest last) feeding the shared WaveformRenderer — same contract as
  // the live recording path, so enrollment shows the identical scrolling waveform.
  var amplitudes by remember { mutableStateOf(emptyList<Float>()) }
  val audioSamplesRef = remember { mutableStateOf<ShortArray?>(null) }
  var recordingJob by remember { mutableStateOf<Job?>(null) }
  var audioRecord by remember { mutableStateOf<AudioRecord?>(null) }
  var localErrorMessage by remember { mutableStateOf<String?>(null) }
  var profileName by remember { mutableStateOf("") }

  val micPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      startRecording(
        context = context,
        scope = scope,
        onAmplitudeUpdate = { amp -> amplitudes = (amplitudes + amp).takeLast(WAVEFORM_HISTORY) },
        onDurationUpdate = { ms -> recordingDurationMs = ms },
        onSamplesReady = { samples -> audioSamplesRef.value = samples },
        onRecordingStarted = { recorder -> audioRecord = recorder },
        onJobStarted = { job -> recordingJob = job },
        onRecordingFailed = {
          localErrorMessage = micInitFailed
          onEnrollmentStateChange(EnrollmentState.ERROR)
        },
      )
    } else {
      localErrorMessage = micPermissionDenied
      onEnrollmentStateChange(EnrollmentState.ERROR)
    }
  }

  fun requestPermissionAndRecord() {
    val hasPermission = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    if (hasPermission) {
      startRecording(
        context = context,
        scope = scope,
        onAmplitudeUpdate = { amp -> amplitudes = (amplitudes + amp).takeLast(WAVEFORM_HISTORY) },
        onDurationUpdate = { ms -> recordingDurationMs = ms },
        onSamplesReady = { samples -> audioSamplesRef.value = samples },
        onRecordingStarted = { recorder -> audioRecord = recorder },
        onJobStarted = { job -> recordingJob = job },
        onRecordingFailed = {
          localErrorMessage = micInitFailed
          onEnrollmentStateChange(EnrollmentState.ERROR)
        },
      )
    } else {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

	  fun stopAndEnroll() {
	    // Idempotency guard: the watchdog and the Stop button can both invoke this. The first call
	    // consumes audioRecord (set to null below); a second call no-ops instead of launching a
	    // duplicate enroll coroutine (double onEnrollComplete). Both callers run on the main thread.
	    if (audioRecord == null) return
	    audioRecord?.let { recorder ->
	      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
	        recorder.stop()
	      }
	      recorder.release()
	    }
	    recordingJob?.cancel()
	    audioRecord = null

    scope.launch {
      delay(50)
      val samples = audioSamplesRef.value
      if (samples != null && samples.size >= SAMPLE_RATE * (MIN_RECORDING_MS / 1000f)) {
        onEnrollComplete(samples, SAMPLE_RATE, profileName.trim().takeIf { it.isNotBlank() })
        onEnrollmentStateChange(EnrollmentState.SUCCESS)
      } else {
        localErrorMessage = recordingShort
        onEnrollmentStateChange(EnrollmentState.ERROR)
      }
    }
  }

	  LaunchedEffect(enrollmentState) {
	    if (enrollmentState == EnrollmentState.RECORDING) {
	      while (isActive && enrollmentState == EnrollmentState.RECORDING) {
	        if (recordingDurationMs >= MAX_RECORDING_MS) {
	          onEnrollmentStateChange(EnrollmentState.PROCESSING)
	          stopAndEnroll()
	          break
        }
        delay(100)
      }
    }
  }

  ModalBottomSheet(
	    onDismissRequest = {
	      audioRecord?.let { recorder ->
	        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
	          recorder.stop()
	        }
	        recorder.release()
	      }
	      recordingJob?.cancel()
	      onDismiss()
    },
    sheetState = sheetState,
    modifier = Modifier.focusRequester(focusRequester),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = maxWidth)
        .padding(Dimensions.Spacing.large),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.bao_translate_record_voice_title),
        style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

      Text(
        text = stringResource(R.string.bao_translate_record_voice_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      OutlinedTextField(
        value = profileName,
        onValueChange = { profileName = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.bao_translate_voice_profile_name_label)) },
        placeholder = { Text(stringResource(R.string.bao_translate_voice_profile_name_hint)) },
        singleLine = true,
        enabled = enrollmentState == EnrollmentState.READY || enrollmentState == EnrollmentState.ERROR,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.large))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(Dimensions.Spacing.large),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = stringResource(R.string.bao_translate_read_prompt_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

          Text(
            text = stringResource(SupportedLanguages.samplePromptResFor(sourceLanguage)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
          )

          if (enrollmentState == EnrollmentState.RECORDING) {
            Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
	              val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
	              val pulseAlpha by infiniteTransition.animateFloat(
	                initialValue = if (reduceMotion) 1f else 0.4f,
	                targetValue = 1f,
	                animationSpec = infiniteRepeatable(
	                  animation = tween(if (reduceMotion) 0 else 800, easing = LinearEasing),
	                  repeatMode = RepeatMode.Reverse,
	                ),
                label = "pulseAlpha",
              )

              Box(
                modifier = Modifier
                  .size(Dimensions.Indicator.medium)
                  .clip(CircleShape)
                  .background(
                    MaterialTheme.customColors.recordButtonBgColor.copy(alpha = pulseAlpha)
                  )
              )
              Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
              Text(
                text = stringResource(
                  R.string.bao_translate_recording_seconds_format,
                  recordingDurationMs / 1000f
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.customColors.recordButtonBgColor,
              )
            }

            Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

            WaveformRenderer(
              amplitudes = amplitudes,
              isActive = true,
              modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.Waveform.height),
            )

            Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

            if (recordingDurationMs >= MIN_RECORDING_MS) {
              Text(
                text = stringResource(R.string.bao_translate_mic_working),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.customColors.successColor,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.large))

      when (enrollmentState) {
        EnrollmentState.READY -> {
          Button(
            onClick = {
              onEnrollmentStateChange(EnrollmentState.RECORDING)
              recordingDurationMs = 0L
              amplitudes = emptyList()
              audioSamplesRef.value = null
              localErrorMessage = null
              requestPermissionAndRecord()
            },
            modifier = Modifier.size(Dimensions.Component.fabSize),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.customColors.recordButtonBgColor,
            ),
          ) {
            Icon(
              imageVector = Icons.Default.Mic,
              contentDescription = stringResource(R.string.cd_bao_translate_start_recording),
              modifier = Modifier.size(Dimensions.Icon.xl),
            )
          }

          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

          Text(
            text = stringResource(R.string.bao_translate_tap_to_record),
            style = MaterialTheme.typography.labelLarge,
          )
        }

        EnrollmentState.RECORDING -> {
          Button(
            onClick = {
              onEnrollmentStateChange(EnrollmentState.PROCESSING)
              stopAndEnroll()
            },
            modifier = Modifier.size(Dimensions.Component.fabSize),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
            ),
          ) {
            Icon(
              imageVector = Icons.Default.Stop,
              contentDescription = stringResource(R.string.cd_bao_translate_stop_recording),
              modifier = Modifier.size(Dimensions.Icon.xl),
            )
          }

          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

          Text(
            text = if (recordingDurationMs < MIN_RECORDING_MS) {
              stringResource(R.string.bao_translate_recording_remaining_format, (MIN_RECORDING_MS - recordingDurationMs) / 1000f)
            } else {
              stringResource(R.string.bao_translate_tap_to_stop)
            },
            style = MaterialTheme.typography.labelLarge,
          )
        }

        EnrollmentState.PROCESSING -> {
          CircularProgressIndicator()
          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
          Text(
            text = stringResource(R.string.bao_translate_extracting_voice),
            style = MaterialTheme.typography.labelLarge,
          )
        }

        EnrollmentState.SUCCESS -> {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.cd_bao_translate_voice_enrolled_icon),
            tint = MaterialTheme.customColors.successColor,
            modifier = Modifier.size(Dimensions.Spacing.xxl),
          )
          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
          Text(
            text = stringResource(R.string.bao_translate_voice_enrolled_success),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.customColors.successColor,
          )

          LaunchedEffect(Unit) {
            delay(1500)
            onDismiss()
          }
        }

        EnrollmentState.ERROR -> {
          Icon(
            imageVector = Icons.Default.Error,
            contentDescription = stringResource(R.string.generic_error),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Dimensions.Spacing.xxl),
          )
          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
          Text(
            text = errorMessage ?: localErrorMessage ?: stringResource(R.string.bao_translate_recording_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
          Button(onClick = {
            onEnrollmentStateChange(EnrollmentState.READY)
            recordingDurationMs = 0L
            amplitudes = emptyList()
            audioSamplesRef.value = null
            localErrorMessage = null
          }) {
            Text(stringResource(R.string.bao_translate_try_again))
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      Text(
        text = stringResource(R.string.bao_translate_min_record),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.large))
    }
  }
}

private fun startRecording(
  context: android.content.Context,
  scope: kotlinx.coroutines.CoroutineScope,
  onAmplitudeUpdate: (Float) -> Unit,
  onDurationUpdate: (Long) -> Unit,
  onSamplesReady: (ShortArray) -> Unit,
  onRecordingStarted: (AudioRecord) -> Unit,
  onJobStarted: (Job) -> Unit,
  onRecordingFailed: () -> Unit,
) {
  val minBufferSize = AudioRecord.getMinBufferSize(
    SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
  )
  if (minBufferSize <= 0) {
    onRecordingFailed()
    return
  }

  if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
    PackageManager.PERMISSION_GRANTED
  ) {
    onRecordingFailed()
    return
  }

  // Oversize the capture buffer (0.5 s of PCM16, never below 4x the OS minimum) so stalls in the
  // enrollment read loop never overrun AudioRecord and drop frames — corrupt enrollment audio
  // permanently degrades the cloned-voice embedding. Mirrors RecordingController's capture sizing.
  val captureBufferBytes = maxOf(minBufferSize * 4, SAMPLE_RATE * Short.SIZE_BYTES / 2)
  val recorder = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    captureBufferBytes,
  )

  if (recorder.state != AudioRecord.STATE_INITIALIZED) {
    recorder.release()
    onRecordingFailed()
    return
  }

  val buffer = ShortArray((minBufferSize / 2).coerceAtLeast(SAMPLE_RATE / 10))
  val maxSamples = SAMPLE_RATE * 30
  val allSamples = ShortArray(maxSamples)
  var sampleCount = 0

  recorder.startRecording()

  if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
    recorder.release()
    onRecordingFailed()
    return
  }

  onRecordingStarted(recorder)

  val job = scope.launch(Dispatchers.IO) {
    while (isActive) {
      val readCount = recorder.read(buffer, 0, buffer.size)
      if (readCount > 0) {
        val copyCount = minOf(readCount, maxSamples - sampleCount)
        if (copyCount > 0) {
          System.arraycopy(buffer, 0, allSamples, sampleCount, copyCount)
          sampleCount += copyCount
        }

        val amplitude = waveformAmplitude(buffer, readCount)

        withContext(Dispatchers.Main) {
          onAmplitudeUpdate(amplitude)
          onDurationUpdate((sampleCount * 1000L) / SAMPLE_RATE)
          onSamplesReady(allSamples.copyOf(sampleCount))
        }
      } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_BAD_VALUE) {
        break
      }
    }

    withContext(Dispatchers.Main) {
      onSamplesReady(allSamples.copyOf(sampleCount))
    }
  }

  onJobStarted(job)
}
