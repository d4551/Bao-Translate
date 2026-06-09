package com.google.ai.edge.gallery.customtasks.baotranslate.data

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import java.io.File

private const val PROFILES_DIR = "voice_profiles"
private const val DEFAULT_PROFILE_ID = "default"
private const val MAX_VOICE_BYTES = 10 * 1024 * 1024 // 10MB

/**
 * Manages voice profile storage for BaoTranslate voice cloning.
 *
 * **Security Notice:** Voice data is biometric data under GDPR (Article 9) and BIPA.
 * Files are stored in the app's private internal storage directory, protected by the
 * Android application sandbox. On rooted or compromised devices, this data may be
 * accessible to other processes. Future hardening should consider Android Keystore
 * with AES-256-GCM encryption at rest.
 */
class VoiceProfileManager(
  private val context: Context,
  private val rootFilesDir: File = context.filesDir,
  private val defaultVoiceNameProvider: () -> String = {
    context.getString(R.string.bao_translate_default_voice_name)
  },
) {
  private val profilesDir: File by lazy {
    File(rootFilesDir, PROFILES_DIR).also { it.mkdirs() }
  }

  /**
   * Resolves a child file inside [profilesDir] for an untrusted [profileId].
   *
   * Profile ids originate from user / voice-enrollment input and must never be allowed to
   * escape the profiles directory via path traversal. Every character that could introduce a
   * new path segment is neutralised: the directory separators `/` and `\`, and the null byte
   * (which can truncate paths on some platforms). After neutralisation the id is a single
   * filename segment, so a leftover `..` (e.g. from `../etc/passwd` collapsing to
   * `.._etc_passwd`) is just a literal filename and cannot traverse upward. A final
   * canonical-path containment check guarantees the resolved file's parent is exactly
   * [profilesDir] before the handle is returned, defending against any platform-specific edge
   * case.
   */
  private fun profileFile(profileId: String, extension: String): File {
    val safeId = buildString(profileId.length) {
      for (ch in profileId) {
        append(if (ch == '/' || ch == '\\' || ch == '\u0000') '_' else ch)
      }
    }
    val file = File(profilesDir, "$safeId.$extension")
    require(file.canonicalFile.parentFile == profilesDir.canonicalFile) {
      "Voice profile id '$profileId' resolves outside the profiles directory"
    }
    return file
  }

  fun saveVoice(name: String, wavData: ByteArray): VoiceProfile {
    require(wavData.size <= MAX_VOICE_BYTES) {
      "Voice data exceeds maximum allowed size of ${MAX_VOICE_BYTES / (1024 * 1024)}MB"
    }
    val profile = VoiceProfile(
      name = name,
      wavPath = "",
      durationSec = WavUtils.computeDurationSec(wavData),
    )

    val wavFile = profileFile(profile.id, "wav")
    wavFile.writeBytes(wavData)

    return profile.copy(wavPath = wavFile.absolutePath)
  }

  fun saveProfile(audioPcm: ShortArray, sampleRate: Int, profileId: String = DEFAULT_PROFILE_ID): VoiceProfile {
    val wavFile = profileFile(profileId, "wav")
    writeWavFile(wavFile, audioPcm, sampleRate)

    val durationSec = audioPcm.size.toFloat() / sampleRate
    return VoiceProfile(
      id = profileId,
      name = defaultVoiceNameProvider(),
      wavPath = wavFile.absolutePath,
      durationSec = durationSec,
    )
  }

  fun hasProfile(profileId: String = DEFAULT_PROFILE_ID): Boolean {
    return profileFile(profileId, "wav").exists()
  }

  fun loadProfile(profileId: String = DEFAULT_PROFILE_ID): VoiceProfile? {
    val wavFile = profileFile(profileId, "wav")
    if (!wavFile.exists()) return null

    return VoiceProfile(
      id = profileId,
      name = defaultVoiceNameProvider(),
      wavPath = wavFile.absolutePath,
      durationSec = WavUtils.computeDurationSecFromHeader(wavFile),
    )
  }

  fun deleteVoice(profileId: String): Boolean {
    return profileFile(profileId, "wav").delete()
  }

  fun deleteProfile(profileId: String = DEFAULT_PROFILE_ID) {
    profileFile(profileId, "wav").delete()
    profileFile(profileId, "se").delete()
  }

  /** Persists the OpenVoice speaker embedding (256 floats) derived from the enrollment clip. */
  fun saveSpeakerEmbedding(embedding: FloatArray, profileId: String = DEFAULT_PROFILE_ID) {
    val buf = java.nio.ByteBuffer.allocate(embedding.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    embedding.forEach { buf.putFloat(it) }
    profileFile(profileId, "se").writeBytes(buf.array())
  }

  fun loadSpeakerEmbedding(profileId: String = DEFAULT_PROFILE_ID): FloatArray? {
    val file = profileFile(profileId, "se")
    if (!file.exists()) return null
    val bytes = file.readBytes()
    val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buf.getFloat() }
  }

  fun getVoicePath(profileId: String): String? {
    val wavFile = profileFile(profileId, "wav")
    return wavFile.takeIf { it.exists() }?.absolutePath
  }

  fun listProfiles(): List<VoiceProfile> {
    val files = profilesDir.listFiles { file -> file.extension == "wav" } ?: return emptyList()
    return files.map { file ->
      VoiceProfile(
        id = file.nameWithoutExtension,
        name = file.nameWithoutExtension,
        wavPath = file.absolutePath,
        durationSec = WavUtils.computeDurationSecFromHeader(file),
      )
    }
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
}
