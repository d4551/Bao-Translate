package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File


/**
 * AES-256-GCM encrypted file storage for sensitive on-device audio blobs (TTS cache, voice profiles).
 */
class EncryptedBlobStore(
  private val appContext: Context,
  private val rootDir: File,
) {
  private val masterKey = MasterKey.Builder(appContext.applicationContext)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

  init {
    rootDir.mkdirs()
  }

  fun write(relativePath: String, bytes: ByteArray) {
    val file = resolve(relativePath)
    file.parentFile?.mkdirs()
    if (file.exists()) file.delete()
    encryptedFile(file).openFileOutput().use { it.write(bytes) }
  }

  fun read(relativePath: String): ByteArray? {
    val file = resolve(relativePath)
    if (!file.exists()) return null
    return runCatching {
      encryptedFile(file).openFileInput().use { it.readBytes() }
    }.getOrNull()
  }

  fun delete(relativePath: String) {
    resolve(relativePath).delete()
  }

  fun exists(relativePath: String): Boolean = resolve(relativePath).exists()

  fun clearAll() {
    rootDir.listFiles()?.forEach { it.delete() }
  }

  /** Lists logical ids for blobs whose on-disk name ends with [suffix] (e.g. ".wav" → profile id). */
  fun listIdsWithSuffix(suffix: String): List<String> =
    rootDir.listFiles()?.mapNotNull { file ->
      file.name.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)
    }?.sorted() ?: emptyList()

  private fun resolve(relativePath: String): File {
    require(!relativePath.contains("..")) { "Path traversal not allowed" }
    return File(rootDir, relativePath)
  }

  private fun encryptedFile(file: File): EncryptedFile =
    EncryptedFile.Builder(
      appContext,
      file,
      masterKey,
      EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

  companion object {
    fun ttsCacheDir(context: Context): File =
      File(context.filesDir, "cache/tts_encrypted")

    fun voiceProfilesDir(context: Context): File =
      File(context.filesDir, "voice_profiles_encrypted")
  }
}