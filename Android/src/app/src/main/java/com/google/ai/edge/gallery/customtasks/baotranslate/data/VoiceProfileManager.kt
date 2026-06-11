package com.google.ai.edge.gallery.customtasks.baotranslate.data

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.EncryptedBlobStore
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import java.io.File

private const val PROFILES_DIR = "voice_profiles"
private const val DEFAULT_PROFILE_ID = "default"
private const val MAX_VOICE_BYTES = 10 * 1024 * 1024 // 10MB
private const val WAV_SUFFIX = ".wav"
private const val SE_SUFFIX = ".se"
private const val META_SUFFIX = ".meta"

/**
 * Manages voice profile storage for BaoTranslate voice cloning.
 *
 * When [encryptedStore] is provided, WAV clips, speaker embeddings (.se), and display metadata are
 * stored with AES-256-GCM via Android Keystore ([EncryptedBlobStore], backed by Google Tink). Plaintext
 * files in [profilesDir], and any pre-Tink security-crypto blobs, are migrated once on first access.
 */
class VoiceProfileManager(
  private val context: Context,
  private val rootFilesDir: File = context.filesDir,
  private val encryptedStore: EncryptedBlobStore? = null,
  private val defaultVoiceNameProvider: () -> String = {
    context.getString(R.string.bao_translate_default_voice_name)
  },
) {
  private val profilesDir: File by lazy {
    File(rootFilesDir, PROFILES_DIR).also { it.mkdirs() }
  }

  private val useEncryption: Boolean get() = encryptedStore != null

  private val prosodyCache = java.util.concurrent.ConcurrentHashMap<String, VoiceProsody>()

  init {
    if (useEncryption) migratePlaintextProfilesIfNeeded()
  }

  private fun profileFile(profileId: String, extension: String): File {
    val safeId = sanitizeProfileId(profileId)
    val file = File(profilesDir, "$safeId.$extension")
    require(file.canonicalFile.parentFile == profilesDir.canonicalFile) {
      "Voice profile id '$profileId' resolves outside the profiles directory"
    }
    return file
  }

  private fun wavKey(profileId: String) = "${sanitizeProfileId(profileId)}$WAV_SUFFIX"
  private fun seKey(profileId: String) = "${sanitizeProfileId(profileId)}$SE_SUFFIX"
  private fun metaKey(profileId: String) = "${sanitizeProfileId(profileId)}$META_SUFFIX"

  private fun logicalWavPath(profileId: String): String =
    if (useEncryption) "enc://$profileId" else profileFile(profileId, "wav").absolutePath

  fun saveVoice(name: String, wavData: ByteArray): VoiceProfile {
    require(wavData.size <= MAX_VOICE_BYTES) {
      "Voice data exceeds maximum allowed size of ${MAX_VOICE_BYTES / (1024 * 1024)}MB"
    }
    val profile = VoiceProfile(
      name = name,
      wavPath = "",
      durationSec = WavUtils.computeDurationSec(wavData),
    )
    writeWavBytes(profile.id, wavData)
    writeMeta(profile.id, name, profile.durationSec)
    return profile.copy(wavPath = logicalWavPath(profile.id))
  }

  fun saveProfile(
    audioPcm: ShortArray,
    sampleRate: Int,
    profileId: String = DEFAULT_PROFILE_ID,
    displayName: String? = null,
  ): VoiceProfile {
    val safeId = sanitizeProfileId(profileId)
    val name = displayName?.takeIf { it.isNotBlank() } ?: defaultVoiceNameProvider()
    val wavBytes = buildWavBytes(audioPcm, sampleRate)
    writeWavBytes(safeId, wavBytes)

    val durationSec = audioPcm.size.toFloat() / sampleRate
    val prosody = extractProsody(audioPcm, sampleRate)
    prosodyCache[safeId] = prosody
    writeMeta(safeId, name, durationSec)

    return VoiceProfile(
      id = safeId,
      name = name,
      wavPath = logicalWavPath(safeId),
      durationSec = durationSec,
      prosody = prosody,
    )
  }

  fun hasProfile(profileId: String = DEFAULT_PROFILE_ID): Boolean = wavExists(sanitizeProfileId(profileId))

  fun readWavBytes(profileId: String = DEFAULT_PROFILE_ID): ByteArray? {
    val id = sanitizeProfileId(profileId)
    return readWavBytesInternal(id)
  }

  fun loadProfile(profileId: String = DEFAULT_PROFILE_ID): VoiceProfile? {
    val id = sanitizeProfileId(profileId)
    if (!wavExists(id)) return null

    val meta = readMeta(id)
    val prosody = prosodyCache[id] ?: runCatching {
      val bytes = readWavBytesInternal(id) ?: return null
      if (WavUtils.isValidWav(bytes)) {
        val rate = WavUtils.extractSampleRateFromWav(bytes) ?: 16000
        val pcmSamples = WavUtils.extractSamplesFromWav(bytes)
        val shortPcm = ShortArray(pcmSamples.size) {
          (pcmSamples[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        extractProsody(shortPcm, rate).also { prosodyCache[id] = it }
      } else null
    }.onFailure {
      BaoLog.w(TAG, "Failed to extract prosody for profile '$id': ${it.message}")
    }.getOrNull()

    val durationSec = meta?.durationSec ?: readWavBytesInternal(id)?.let { WavUtils.computeDurationSec(it) } ?: 0f

    return VoiceProfile(
      id = id,
      name = meta?.name ?: id,
      wavPath = logicalWavPath(id),
      durationSec = durationSec,
      prosody = prosody,
    )
  }

  fun deleteVoice(profileId: String): Boolean {
    val id = sanitizeProfileId(profileId)
    val existed = wavExists(id)
    deleteProfile(id)
    return existed
  }

  fun deleteProfile(profileId: String = DEFAULT_PROFILE_ID): Boolean {
    val id = sanitizeProfileId(profileId)
    val existed = if (useEncryption) {
      encryptedStore?.exists(wavKey(id)) == true
    } else {
      profileFile(id, "wav").exists()
    }
    if (useEncryption) {
      encryptedStore?.delete(wavKey(id))
      encryptedStore?.delete(seKey(id))
      encryptedStore?.delete(metaKey(id))
    } else {
      profileFile(id, "wav").delete()
      profileFile(id, "se").delete()
      profileFile(id, "meta").delete()
    }
    prosodyCache.remove(id)
    return existed
  }

  fun saveSpeakerEmbedding(embedding: FloatArray, profileId: String = DEFAULT_PROFILE_ID) {
    val id = sanitizeProfileId(profileId)
    val buf = java.nio.ByteBuffer.allocate(embedding.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    embedding.forEach { buf.putFloat(it) }
    val bytes = buf.array()
    if (useEncryption) {
      encryptedStore?.write(seKey(id), bytes)
    } else {
      profileFile(id, "se").writeBytes(bytes)
    }
  }

  fun loadSpeakerEmbedding(profileId: String = DEFAULT_PROFILE_ID): FloatArray? {
    val id = sanitizeProfileId(profileId)
    val bytes = if (useEncryption) {
      encryptedStore?.read(seKey(id))
    } else {
      profileFile(id, "se").takeIf { it.exists() }?.readBytes()
    } ?: return null
    val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buf.getFloat() }
  }

  fun getVoicePath(profileId: String): String? =
    logicalWavPath(sanitizeProfileId(profileId)).takeIf { wavExists(sanitizeProfileId(profileId)) }

  fun listProfiles(): List<VoiceProfile> {
    val ids = if (useEncryption) {
      encryptedStore?.listIdsWithSuffix(WAV_SUFFIX) ?: emptyList()
    } else {
      profilesDir.listFiles { file -> file.extension == "wav" }?.map { it.nameWithoutExtension } ?: emptyList()
    }
    return ids.mapNotNull { loadProfile(it) }
  }

  fun loadProsody(profileId: String = DEFAULT_PROFILE_ID): VoiceProsody? = loadProfile(profileId)?.prosody

  private fun wavExists(profileId: String): Boolean =
    if (useEncryption) encryptedStore?.exists(wavKey(profileId)) == true
    else profileFile(profileId, "wav").exists()

  private fun readWavBytesInternal(profileId: String): ByteArray? =
    if (useEncryption) encryptedStore?.read(wavKey(profileId))
    else profileFile(profileId, "wav").takeIf { it.exists() }?.readBytes()

  private fun writeWavBytes(profileId: String, bytes: ByteArray) {
    if (useEncryption) {
      encryptedStore?.write(wavKey(profileId), bytes)
      profileFile(profileId, "wav").delete()
    } else {
      profileFile(profileId, "wav").writeBytes(bytes)
    }
  }

  private data class ProfileMeta(val name: String, val durationSec: Float)

  private fun writeMeta(profileId: String, name: String, durationSec: Float) {
    val payload = "$name\n$durationSec".toByteArray(Charsets.UTF_8)
    if (useEncryption) {
      encryptedStore?.write(metaKey(profileId), payload)
    } else {
      profileFile(profileId, "meta").writeBytes(payload)
    }
  }

  private fun readMeta(profileId: String): ProfileMeta? {
    val bytes = if (useEncryption) {
      encryptedStore?.read(metaKey(profileId))
    } else {
      profileFile(profileId, "meta").takeIf { it.exists() }?.readBytes()
    } ?: return null
    val text = bytes.toString(Charsets.UTF_8)
    val lines = text.lines()
    if (lines.isEmpty()) return null
    val name = lines[0]
    val duration = lines.getOrNull(1)?.toFloatOrNull() ?: 0f
    return ProfileMeta(name, duration)
  }

  private fun migratePlaintextProfilesIfNeeded() {
    val store = encryptedStore ?: return
    profilesDir.listFiles { f -> f.extension == "wav" }?.forEach { wavFile ->
      val id = wavFile.nameWithoutExtension
      if (!store.exists(wavKey(id))) {
        runCatching { store.write(wavKey(id), wavFile.readBytes()) }
          .onFailure { BaoLog.w(TAG, "Failed to migrate wav for '$id': ${it.message}") }
      }
      profileFile(id, "se").takeIf { it.exists() }?.let { seFile ->
        if (!store.exists(seKey(id))) {
          runCatching { store.write(seKey(id), seFile.readBytes()) }
        }
        seFile.delete()
      }
      wavFile.delete()
    }
  }

  private fun buildWavBytes(samples: ShortArray, sampleRate: Int): ByteArray {
    val temp = File.createTempFile("profile_", ".wav", profilesDir)
    try {
      writeWavFile(temp, samples, sampleRate)
      return temp.readBytes()
    } finally {
      temp.delete()
    }
  }

  private fun extractProsody(audioPcm: ShortArray, sampleRate: Int): VoiceProsody {
    if (audioPcm.isEmpty() || sampleRate <= 0) return VoiceProsody()

    val samples = FloatArray(audioPcm.size) { audioPcm[it].toFloat() / 32768f }

    val windowSamples = (sampleRate * 0.03f).toInt().coerceAtLeast(1)
    val hopSamples = windowSamples / 2
    val energies = mutableListOf<Float>()
    var offset = 0
    while (offset + windowSamples <= samples.size) {
      var sumSq = 0f
      for (i in offset until offset + windowSamples) {
        sumSq += samples[i] * samples[i]
      }
      energies.add(kotlin.math.sqrt(sumSq / windowSamples))
      offset += hopSamples
    }

    val medianEnergy = if (energies.isNotEmpty()) {
      val sorted = energies.sorted()
      sorted[sorted.size / 2]
    } else 0f

    var peakCount = 0
    for (i in 1 until energies.size - 1) {
      if (energies[i] > medianEnergy && energies[i] > energies[i - 1] && energies[i] > energies[i + 1]) {
        peakCount++
      }
    }
    // Measure rate over the VOICED span only. Dividing by the TOTAL clip duration counts the
    // leading/trailing silence of a natural enrollment recording, deflating the estimate into the
    // slowest bucket and wrongly slowing EVERY synthesized utterance to 0.8x for normal speakers —
    // heard as draggy, droning "robotic" delivery on all engines/languages while enrolled. Trimming
    // edge silence (keeping natural internal pauses) measures the speech itself.
    val hopSec = hopSamples.toFloat() / sampleRate
    val activeThreshold = medianEnergy * 0.3f
    val firstVoiced = energies.indexOfFirst { it > activeThreshold }
    val lastVoiced = energies.indexOfLast { it > activeThreshold }
    val voicedSpanSec = if (firstVoiced >= 0) (lastVoiced - firstVoiced + 1) * hopSec else 0f
    val speakingRate = if (voicedSpanSec > 0f) peakCount / voicedSpanSec else 0f

    val pitchWindowSamples = (sampleRate * 0.04f).toInt().coerceAtLeast(1)
    val minLag = (sampleRate / 500f).toInt().coerceAtLeast(1)
    val maxLag = (sampleRate / 70f).toInt().coerceAtLeast(2)
    var pitchSum = 0f
    var pitchCount = 0

    offset = 0
    while (offset + pitchWindowSamples <= samples.size) {
      var frameEnergy = 0f
      for (i in offset until offset + pitchWindowSamples) {
        frameEnergy += samples[i] * samples[i]
      }
      frameEnergy /= pitchWindowSamples

      if (frameEnergy > medianEnergy * medianEnergy * 0.5f) {
        var bestLag = 0
        var bestCorr = 0f
        for (lag in minLag..minOf(maxLag, pitchWindowSamples - 1)) {
          var corr = 0f
          for (i in 0 until pitchWindowSamples - lag) {
            corr += samples[offset + i] * samples[offset + i + lag]
          }
          if (corr > bestCorr) {
            bestCorr = corr
            bestLag = lag
          }
        }
        if (bestLag > 0) {
          val f0 = sampleRate.toFloat() / bestLag
          if (f0 in 70f..500f) {
            pitchSum += f0
            pitchCount++
          }
        }
      }
      offset += pitchWindowSamples / 2
    }

    val averagePitchHz = if (pitchCount > 0) pitchSum / pitchCount else 0f
    val speedMultiplier = when {
      speakingRate <= 0f -> 1.0f
      speakingRate < 3.5f -> 0.8f
      speakingRate < 4.5f -> 0.9f
      speakingRate > 6.5f -> 1.2f
      speakingRate > 5.5f -> 1.1f
      else -> 1.0f
    }

    return VoiceProsody(
      speakingRate = speakingRate,
      averagePitchHz = averagePitchHz,
      speedMultiplier = speedMultiplier,
    )
  }

  private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
    val byteData = ByteArray(samples.size * 2)
    for (i in samples.indices) {
      val sample = samples[i]
      byteData[i * 2] = (sample.toInt() and 0xFF).toByte()
      byteData[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
    }

    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8

    file.outputStream().use { output ->
      output.write("RIFF".toByteArray())
      output.write(intToBytes(36 + byteData.size))
      output.write("WAVE".toByteArray())
      output.write("fmt ".toByteArray())
      output.write(intToBytes(16))
      output.write(shortToBytes(1))
      output.write(shortToBytes(channels))
      output.write(intToBytes(sampleRate))
      output.write(intToBytes(byteRate))
      output.write(shortToBytes(blockAlign))
      output.write(shortToBytes(bitsPerSample))
      output.write("data".toByteArray())
      output.write(intToBytes(byteData.size))
      output.write(byteData)
    }
  }

  private fun intToBytes(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    (value shr 8 and 0xFF).toByte(),
    (value shr 16 and 0xFF).toByte(),
    (value shr 24 and 0xFF).toByte(),
  )

  private fun shortToBytes(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    (value shr 8 and 0xFF).toByte(),
  )

  companion object {
    const val DEFAULT_PROFILE_ID = "default"
    private const val TAG = "VoiceProfileManager"

    fun sanitizeProfileId(profileId: String): String = buildString(profileId.length.coerceAtMost(64)) {
      for (ch in profileId) {
        when {
          ch == '/' || ch == '\\' || ch == '\u0000' -> append('_')
          ch.isLetterOrDigit() || ch == '_' || ch == '-' -> append(ch)
          ch == ' ' -> append('_')
          else -> append('_')
        }
      }
    }.ifBlank { DEFAULT_PROFILE_ID }
  }
}