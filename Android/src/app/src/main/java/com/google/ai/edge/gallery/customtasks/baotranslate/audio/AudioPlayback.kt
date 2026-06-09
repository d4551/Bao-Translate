package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

private const val TAG = "AudioPlayback"
private const val DEFAULT_SAMPLE_RATE = PipelineConfig.TTS_SAMPLE_RATE
private const val LOW_LATENCY_SAMPLE_RATE = PipelineConfig.STT_SAMPLE_RATE
private const val PLAYBACK_DRAIN_GRACE_MS = 2_000L
private const val FADE_MS = 50L // Fade-in / fade-out duration to prevent clicking on speaker transitions

object AudioPlayback {
  data class RouteResult(
    val preferredDeviceApplied: Boolean,
    val preferredDeviceName: String?,
    val preferredDeviceId: Int?,
    val routedDeviceName: String?,
    val routedDeviceId: Int?,
  )

  // `stateLock` guards the cached track + its metadata and is only ever held for short critical
  // sections. `playbackLock` serializes whole playbacks and is held across the blocking write+drain;
  // it is NOT held while mutating state, so `releaseTrack()` can preempt an in-flight playback by
  // setting `abortRequested` and stopping the track, then reclaim the handle once the (now
  // fast-exiting) playback releases `playbackLock`. The previous design held a single lock across
  // the entire drain, so teardown blocked for the full audio duration and could not be cancelled.
  private val stateLock = Any()
  private val playbackLock = ReentrantLock()
  @Volatile private var abortRequested = false

  private var activeTrack: AudioTrack? = null
  private var cachedSampleRate: Int = 0
  private var cachedUsage: Int = 0

  private fun ensureTrackLocked(sampleRate: Int, useCommunicationRoute: Boolean): AudioTrack? {
    val usage = if (useCommunicationRoute) {
      AudioAttributes.USAGE_VOICE_COMMUNICATION
    } else {
      AudioAttributes.USAGE_MEDIA
    }
    val existing = activeTrack
    if (
      existing != null &&
        cachedSampleRate == sampleRate &&
        cachedUsage == usage &&
        existing.state == AudioTrack.STATE_INITIALIZED
    ) {
      return existing
    }

    releaseTrackLocked()

    val minBufferSize = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_FLOAT,
    )

    val bufferSize = maxOf(minBufferSize.coerceAtLeast(0) * 4, sampleRate * 4)

    val track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(usage)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(bufferSize)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()

    if (track.state != AudioTrack.STATE_INITIALIZED) {
      BaoLog.e(TAG, "AudioTrack failed to initialize")
      track.release()
      return null
    }

    activeTrack = track
    cachedSampleRate = sampleRate
    cachedUsage = usage
    return track
  }

  fun playPcmFloat(
    samples: FloatArray,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    preferredDevice: AudioDeviceInfo? = null,
    useCommunicationRoute: Boolean = false,
  ): RouteResult {
    if (samples.isEmpty()) {
      return RouteResult(
        preferredDeviceApplied = preferredDevice == null,
        preferredDeviceName = preferredDevice?.productName?.toString(),
        preferredDeviceId = preferredDevice?.id,
        routedDeviceName = null,
        routedDeviceId = null,
      )
    }

    playbackLock.withLock {
      // Neural-vocoder float output (Kokoro VITS, OpenVoice ToneColorConverter) is not range-bounded;
      // writing samples outside [-1,1] to an ENCODING_PCM_FLOAT track makes AudioFlinger HARD-CLIP the
      // peaks => scratchy distortion. Attenuate the whole buffer so its peak fits in [-1,1] (shape-
      // preserving; never boost => no inter-utterance loudness pump), leaving 0.99 true-peak headroom
      // for the OS resample to the device-native rate. Also scrubs any NaN/Inf the vocoder may emit.
      // Common case (already in range) returns the caller's array unchanged — zero allocation/copy.
      val playbackSamples = run {
        var peak = 0f
        var hasNonFinite = false
        for (s in samples) {
          if (!s.isFinite()) { hasNonFinite = true; continue }
          val a = abs(s)
          if (a > peak) peak = a
        }
        val normalized = when {
          hasNonFinite -> {
            val g = if (peak > 1f) 0.99f / peak else 1f
            FloatArray(samples.size) { val s = samples[it]; if (s.isFinite()) s * g else 0f }
          }
          peak > 1f -> FloatArray(samples.size) { samples[it] * (0.99f / peak) }
          else -> samples
        }
        // Apply fade-in / fade-out envelope to prevent clicking during speaker transitions.
        // Only mutates when the buffer is long enough to hold both fade windows.
        applyFade(normalized, sampleRate)
      }
      val track: AudioTrack
      val startHeadPosition: Int
      val preferredApplied: Boolean
      synchronized(stateLock) {
        abortRequested = false
        track = ensureTrackLocked(sampleRate, useCommunicationRoute) ?: return RouteResult(
          preferredDeviceApplied = false,
          preferredDeviceName = preferredDevice?.productName?.toString(),
          preferredDeviceId = preferredDevice?.id,
          routedDeviceName = null,
          routedDeviceId = null,
        )
        preferredApplied = track.setPreferredDevice(preferredDevice)
        track.flush()
        startHeadPosition = track.playbackHeadPosition
        track.play()
      }

      // Write + drain WITHOUT holding stateLock so releaseTrack() can interrupt by stopping the
      // track and flipping abortRequested.
      val chunkSize = sampleRate
      var offset = 0
      while (offset < samples.size && !abortRequested) {
        val remaining = samples.size - offset
        val writeSize = minOf(chunkSize, remaining)
        val written = track.write(playbackSamples, offset, writeSize, AudioTrack.WRITE_BLOCKING)
        if (written <= 0) {
          if (!abortRequested) BaoLog.w(TAG, "AudioTrack write failed: $written")
          break
        }
        offset += written
      }

      if (!abortRequested) {
        waitForPlaybackDrain(
          track = track,
          startHeadPosition = startHeadPosition,
          writtenFrames = offset,
          sampleRate = sampleRate,
        )
      }

      synchronized(stateLock) {
        // Only touch the track if it is still ours and was not reclaimed by releaseTrack().
        if (activeTrack === track && track.state == AudioTrack.STATE_INITIALIZED) {
          val routedDevice = track.routedDevice
          if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.stop()
          }
          track.flush()
          return RouteResult(
            preferredDeviceApplied =
              preferredApplied && (preferredDevice == null || routedDevice?.id == preferredDevice.id),
            preferredDeviceName = preferredDevice?.productName?.toString(),
            preferredDeviceId = preferredDevice?.id,
            routedDeviceName = routedDevice?.productName?.toString(),
            routedDeviceId = routedDevice?.id,
          )
        }
        return RouteResult(
          preferredDeviceApplied = false,
          preferredDeviceName = preferredDevice?.productName?.toString(),
          preferredDeviceId = preferredDevice?.id,
          routedDeviceName = null,
          routedDeviceId = null,
        )
      }
    }
  }

  fun playPcmShort(samples: ShortArray, sampleRate: Int = LOW_LATENCY_SAMPLE_RATE): RouteResult {
    if (samples.isEmpty()) {
      return RouteResult(
        preferredDeviceApplied = true,
        preferredDeviceName = null,
        preferredDeviceId = null,
        routedDeviceName = null,
        routedDeviceId = null,
      )
    }

    val floatSamples = FloatArray(samples.size) { samples[it].toFloat() / 32768f }
    return playPcmFloat(floatSamples, sampleRate)
  }

  fun releaseTrack() {
    // Signal any in-flight playback to abort and stop the track so its blocking write/drain returns
    // promptly, instead of waiting out the remaining audio.
    synchronized(stateLock) {
      abortRequested = true
      activeTrack?.let { track ->
        if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
          track.stop()
        }
      }
    }
    // Reclaim the handle once no playback is in-flight (playbackLock is free).
    playbackLock.withLock {
      synchronized(stateLock) {
        releaseTrackLocked()
      }
    }
  }

  private fun releaseTrackLocked() {
    activeTrack?.let { track ->
      if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
        track.stop()
      }
      track.flush()
      track.release()
    }
    activeTrack = null
    cachedSampleRate = 0
    cachedUsage = 0
  }

  private fun waitForPlaybackDrain(
    track: AudioTrack,
    startHeadPosition: Int,
    writtenFrames: Int,
    sampleRate: Int,
  ) {
    if (writtenFrames <= 0 || sampleRate <= 0) return

    val timeoutMs = (writtenFrames.toLong() * 1_000L / sampleRate) + PLAYBACK_DRAIN_GRACE_MS
    val deadline = System.currentTimeMillis() + timeoutMs
    while (
      !abortRequested &&
        framesAdvanced(startHeadPosition, track.playbackHeadPosition) < writtenFrames &&
        System.currentTimeMillis() < deadline
    ) {
      Thread.sleep(20)
    }

    if (abortRequested) return
    val advanced = framesAdvanced(startHeadPosition, track.playbackHeadPosition)
    if (advanced < writtenFrames) {
      BaoLog.w(TAG, "AudioTrack playback drain timed out: played=$advanced written=$writtenFrames")
    }
  }

  /**
   * Applies a fade-in / fade-out envelope to [samples] to prevent audible clicking when a speaker
   * transition starts or ends. The fade duration is [FADE_MS]. Returns a new array only when a
   * fade is actually applied; otherwise returns the original reference to avoid allocation.
   */
  private fun applyFade(samples: FloatArray, sampleRate: Int): FloatArray {
    val fadeSamples = (sampleRate * FADE_MS / 1000L).toInt().coerceAtLeast(1)
    if (samples.size <= fadeSamples * 2) return samples
    var changed = false
    val out = FloatArray(samples.size) { i ->
      val envelope = when {
        i < fadeSamples -> {
          changed = true
          i.toFloat() / fadeSamples
        }
        i >= samples.size - fadeSamples -> {
          changed = true
          (samples.size - 1 - i).toFloat() / fadeSamples
        }
        else -> 1f
      }
      samples[i] * envelope
    }
    return if (changed) out else samples
  }

  private fun framesAdvanced(start: Int, current: Int): Long {
    val startLong = start.toLong() and 0xFFFF_FFFFL
    val currentLong = current.toLong() and 0xFFFF_FFFFL
    return if (currentLong >= startLong) {
      currentLong - startLong
    } else {
      (0x1_0000_0000L - startLong) + currentLong
    }
  }
}
