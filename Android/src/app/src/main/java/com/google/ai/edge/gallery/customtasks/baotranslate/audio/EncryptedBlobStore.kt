package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.ai.edge.gallery.common.BaoLog
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

private const val TAG = "EncryptedBlobStore"

/**
 * Authenticated-encryption primitive for a single blob. The [associatedData] (the blob's logical
 * path) is authenticated but NOT encrypted — it binds a ciphertext to its location so a blob cannot
 * be silently relocated/substituted. [decrypt] throws on a wrong key, tampered ciphertext, or
 * mismatched associated data.
 */
interface BlobCipher {
  fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray

  fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * [BlobCipher] backed by Google Tink AES-256-GCM. On Android the keyset is wrapped by an Android
 * Keystore master key (hardware-backed where available) via [AndroidKeysetManager]; tests inject a
 * raw in-memory [Aead] so the encrypt/decrypt logic is verifiable on the JVM.
 *
 * RESEARCH_USED: Tink Android `AndroidKeysetManager` + `Aead` is Google's sanctioned replacement for
 * the deprecated `androidx.security:security-crypto` `EncryptedFile`/`MasterKey`
 * (developer.android.com/jetpack/androidx/releases/security; tink-crypto/tink; KINTO 2025 migration
 * guide — StreamingAead/Aead with a legacy fallback to avoid losing previously-encrypted data).
 * context7 lacked Tink-Java coverage (Go only); grounded via Google Tink docs + Maven (tink-android 1.19.0).
 */
class TinkAeadBlobCipher private constructor(private val aead: Aead) : BlobCipher {
  override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
    aead.encrypt(plaintext, associatedData)

  override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
    aead.decrypt(ciphertext, associatedData)

  companion object {
    private const val KEYSET_NAME = "bao_blob_keyset"
    private const val PREF_FILE = "bao_blob_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://bao_blob_master_key"

    /** Android factory: keyset persisted in SharedPreferences, wrapped by an Android Keystore key. */
    fun create(context: Context): TinkAeadBlobCipher {
      AeadConfig.register()
      val handle =
        AndroidKeysetManager.Builder()
          .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE)
          .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
          .withMasterKeyUri(MASTER_KEY_URI)
          .build()
          .keysetHandle
      return TinkAeadBlobCipher(handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java))
    }

    /** JVM/test factory from an already-built [Aead] (e.g. an in-memory keyset). */
    fun fromAead(aead: Aead): TinkAeadBlobCipher = TinkAeadBlobCipher(aead)
  }
}

/**
 * AES-256-GCM encrypted file storage for sensitive on-device audio blobs (TTS cache, voice profiles).
 *
 * Crypto is Google Tink (see [TinkAeadBlobCipher]). Blobs written before this migration used the now-
 * deprecated `androidx.security:security-crypto` `EncryptedFile`; [read] transparently falls back to
 * that legacy reader and RE-ENCRYPTS the blob with Tink, so existing enrolled voices are preserved
 * and converge to Tink on first access. The legacy path is read-only — nothing new is written with it.
 */
class EncryptedBlobStore
internal constructor(
  private val rootDir: File,
  private val cipher: BlobCipher,
  private val legacy: LegacyEncryptedFileReader?,
) {
  /** Production constructor: Keystore-backed Tink cipher + legacy security-crypto read fallback. */
  constructor(
    appContext: Context,
    rootDir: File,
  ) : this(rootDir, TinkAeadBlobCipher.create(appContext), LegacyEncryptedFileReader(appContext))

  init {
    rootDir.mkdirs()
  }

  fun write(relativePath: String, bytes: ByteArray) {
    val file = resolve(relativePath)
    file.parentFile?.mkdirs()
    file.writeBytes(cipher.encrypt(bytes, associatedData(relativePath)))
  }

  fun read(relativePath: String): ByteArray? {
    val file = resolve(relativePath)
    if (!file.exists()) return null
    val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
    runCatching { cipher.decrypt(raw, associatedData(relativePath)) }.onSuccess {
      return it
    }
    // Tink could not decrypt: either a pre-migration security-crypto blob, or genuine tamper.
    val legacyPlain = legacy?.read(file) ?: return null
    // Migrate forward so the next read is a fast Tink path (best-effort; never fail the read).
    runCatching { write(relativePath, legacyPlain) }
      .onFailure { BaoLog.w(TAG, "Tink re-encrypt of legacy blob failed: ${it.message}") }
    return legacyPlain
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
    rootDir
      .listFiles()
      ?.mapNotNull { file -> file.name.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix) }
      ?.sorted() ?: emptyList()

  private fun resolve(relativePath: String): File {
    require(!relativePath.contains("..")) { "Path traversal not allowed" }
    return File(rootDir, relativePath)
  }

  private fun associatedData(relativePath: String): ByteArray = relativePath.toByteArray(Charsets.UTF_8)

  companion object {
    fun ttsCacheDir(context: Context): File = File(context.filesDir, "cache/tts_encrypted")

    fun voiceProfilesDir(context: Context): File = File(context.filesDir, "voice_profiles_encrypted")
  }
}

/**
 * Read-only reader for legacy `androidx.security:security-crypto` blobs, used only to migrate
 * pre-Tink data forward (see [EncryptedBlobStore.read]). Deprecated APIs are isolated here so the
 * rest of the store is Tink-only.
 */
internal class LegacyEncryptedFileReader(private val appContext: Context) {
  @Suppress("DEPRECATION") // security-crypto deprecated; kept read-only for one-time migration.
  private val masterKey =
    MasterKey.Builder(appContext.applicationContext)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

  /** Decrypts a pre-Tink blob; null if [file] isn't a readable security-crypto blob. */
  fun read(file: File): ByteArray? =
    runCatching {
      @Suppress("DEPRECATION")
      EncryptedFile.Builder(
          appContext,
          file,
          masterKey,
          EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        )
        .build()
        .openFileInput()
        .use { it.readBytes() }
    }
      .getOrNull()
}
