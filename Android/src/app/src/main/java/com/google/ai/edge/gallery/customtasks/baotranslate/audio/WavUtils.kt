package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import java.io.File

/**
 * Shared WAV file parsing utilities.
 *
 * Supports standard RIFF/WAVE format with chunk walking for `fmt ` and `data` chunks.
 */
object WavUtils {

  fun isValidWav(wavBytes: ByteArray): Boolean {
    if (wavBytes.size < 12) return false
    val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
    val wave = String(wavBytes, 8, 4, Charsets.US_ASCII)
    return riff == "RIFF" && wave == "WAVE"
  }

  fun readLittleEndianU16(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or
      ((data[offset + 1].toInt() and 0xFF) shl 8)
  }

  fun readLittleEndianU32(data: ByteArray, offset: Int): Long {
    return (data[offset].toLong() and 0xFF) or
      ((data[offset + 1].toLong() and 0xFF) shl 8) or
      ((data[offset + 2].toLong() and 0xFF) shl 16) or
      ((data[offset + 3].toLong() and 0xFF) shl 24)
  }

  fun extractSampleRateFromWav(wavBytes: ByteArray): Int? {
    if (wavBytes.size < 44) return null
    return readLittleEndianU32(wavBytes, 24).toInt()
  }

  fun extractSamplesFromWav(wavBytes: ByteArray): FloatArray {
    if (wavBytes.size < 44) return floatArrayOf()

    var fmtBitsPerSample: Int? = null
    var offset = 12
    while (offset < wavBytes.size - 8) {
      val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
      val chunkSize = readLittleEndianU32(wavBytes, offset + 4)

      if (chunkSize > (wavBytes.size - offset - 8).toLong()) return floatArrayOf()

      if (chunkId == "fmt " && chunkSize >= 16L) {
        fmtBitsPerSample = readLittleEndianU16(wavBytes, offset + 22)
      }

      if (chunkId == "data") {
        val dataStart = offset + 8
        val dataEnd = minOf(dataStart + chunkSize.toInt(), wavBytes.size)
        val dataBytes = wavBytes.sliceArray(dataStart until dataEnd)

        val bitsPerSample = fmtBitsPerSample ?: return floatArrayOf()

        return when (bitsPerSample) {
          16 -> {
            val numSamples = dataBytes.size / 2
            FloatArray(numSamples) { i ->
              val sample = ((dataBytes[i * 2 + 1].toInt() and 0xFF) shl 8) or
                (dataBytes[i * 2].toInt() and 0xFF)
              val signed = if (sample >= 32768) sample - 65536 else sample
              signed.toFloat() / 32768f
            }
          }
          else -> floatArrayOf()
        }
      }

      offset += 8 + chunkSize.toInt()
    }

    return floatArrayOf()
  }

  fun computeDurationSec(wavData: ByteArray): Float {
    if (wavData.size < 44) return 0f
    val sampleRate = readLittleEndianU32(wavData, 24).toInt()
    val bitsPerSample = readLittleEndianU16(wavData, 34)

    var dataSize = 0L
    var offset = 12
    while (offset < wavData.size - 8) {
      val chunkId = String(wavData, offset, 4, Charsets.US_ASCII)
      val chunkSize = readLittleEndianU32(wavData, offset + 4)
      if (chunkSize > (wavData.size - offset - 8).toLong()) break
      if (chunkId == "data") {
        dataSize = chunkSize
        break
      }
      offset += 8 + chunkSize.toInt()
    }

    val bytesPerSample = bitsPerSample / 8
    return if (bytesPerSample > 0 && sampleRate > 0) {
      dataSize.toFloat() / (sampleRate * bytesPerSample)
    } else {
      0f
    }
  }

  fun computeDurationSecFromHeader(wavFile: File): Float {
    if (wavFile.length() < 44) return 0f
    val header = ByteArray(44)
    wavFile.inputStream().use { it.read(header) }
    val sampleRate = readLittleEndianU32(header, 24).toInt()
    val bitsPerSample = readLittleEndianU16(header, 34)

    var dataSize = 0L
    wavFile.inputStream().use { stream ->
      stream.skip(12)
      var offset = 12L
      val chunkHeader = ByteArray(8)
      while (offset < wavFile.length() - 8) {
        if (stream.read(chunkHeader) != 8) break
        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        val chunkSize = (chunkHeader[4].toLong() and 0xFF) or
          ((chunkHeader[5].toLong() and 0xFF) shl 8) or
          ((chunkHeader[6].toLong() and 0xFF) shl 16) or
          ((chunkHeader[7].toLong() and 0xFF) shl 24)
        if (chunkSize > (wavFile.length() - offset - 8)) break
        if (chunkId == "data") {
          dataSize = chunkSize
          break
        }
        stream.skip(chunkSize)
        offset += 8 + chunkSize
      }
    }

    val bytesPerSample = bitsPerSample / 8
    return if (bytesPerSample > 0 && sampleRate > 0) {
      dataSize.toFloat() / (sampleRate * bytesPerSample)
    } else {
      0f
    }
  }
}
