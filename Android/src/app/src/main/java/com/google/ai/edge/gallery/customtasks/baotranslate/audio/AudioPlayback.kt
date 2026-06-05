package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig

private const val TAG = "AudioPlayback"
private const val DEFAULT_SAMPLE_RATE = PipelineConfig.TTS_SAMPLE_RATE
private const val LOW_LATENCY_SAMPLE_RATE = PipelineConfig.STT_SAMPLE_RATE

object AudioPlayback {
  data class RouteResult(
    val preferredDeviceApplied: Boolean,
    val preferredDeviceName: String?,
    val preferredDeviceId: Int?,
    val routedDeviceName: String?,
    val routedDeviceId: Int?,
  )

  private val lock = Any()
  private var activeTrack: AudioTrack? = null
  private var cachedSampleRate: Int = 0
  private var cachedUsage: Int = 0

  private fun ensureTrack(sampleRate: Int, useCommunicationRoute: Boolean): AudioTrack? {
    synchronized(lock) {
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

    val track = ensureTrack(sampleRate, useCommunicationRoute) ?: return RouteResult(
      preferredDeviceApplied = false,
      preferredDeviceName = preferredDevice?.productName?.toString(),
      preferredDeviceId = preferredDevice?.id,
      routedDeviceName = null,
      routedDeviceId = null,
    )
    val preferredApplied = track.setPreferredDevice(preferredDevice)

    track.play()

    val chunkSize = sampleRate
    var offset = 0
    while (offset < samples.size) {
      val remaining = samples.size - offset
      val writeSize = minOf(chunkSize, remaining)
      val written = track.write(samples, offset, writeSize, AudioTrack.WRITE_BLOCKING)
      if (written <= 0) {
        BaoLog.w(TAG, "AudioTrack write failed: $written")
        break
      }
      offset += written
    }

    val routedDevice = track.routedDevice
    track.stop()
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
    synchronized(lock) {
      releaseTrackLocked()
    }
  }

  private fun releaseTrackLocked() {
    activeTrack?.let { track ->
      if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
        track.stop()
      }
      track.flush()
      track.release()
    }
    activeTrack = null
    cachedSampleRate = 0
    cachedUsage = 0
  }
}
