package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import com.google.ai.edge.gallery.testkit.Strict
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

/**
 * JVM verification of the Tink-backed [EncryptedBlobStore] crypto + storage logic, using a real
 * in-memory Tink AES-256-GCM keyset (no Android Keystore — that binding is device-verified separately).
 * Proves: round-trip fidelity, path-bound associated data (a blob can't be read under another path),
 * tamper rejection, and the file lifecycle (exists/delete/list). This is the runnable evidence for the
 * EncryptedBlobStore→Tink migration.
 */
@Category(Strict::class)
class EncryptedBlobStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private lateinit var aead: Aead

  @Before
  fun setUp() {
    AeadConfig.register()
    aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
      .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
  }

  private fun newStore() =
    EncryptedBlobStore(
      rootDir = tmp.newFolder("blobs_${System.nanoTime()}"),
      cipher = TinkAeadBlobCipher.fromAead(aead),
      legacy = null, // no legacy fallback in unit scope; Tink-only
    )

  @Test
  fun write_then_read_roundTrips() {
    val store = newStore()
    val payload = ByteArray(4096) { (it * 31 % 251).toByte() }
    store.write("voice_profiles/alice.wav", payload)
    assertTrue(store.exists("voice_profiles/alice.wav"))
    assertArrayEquals("round-trip must return the exact bytes", payload, store.read("voice_profiles/alice.wav"))
  }

  @Test
  fun read_missing_returnsNull() {
    assertNull(newStore().read("nope.wav"))
  }

  @Test
  fun ciphertext_onDisk_isNotPlaintext() {
    val store = newStore()
    val root = tmp.newFolder("peek")
    val s = EncryptedBlobStore(root, TinkAeadBlobCipher.fromAead(aead), legacy = null)
    val secret = "TOP-SECRET-VOICEPRINT".toByteArray()
    s.write("x.wav", secret)
    val onDisk = java.io.File(root, "x.wav").readBytes()
    assertFalse("plaintext must not appear on disk", String(onDisk).contains("TOP-SECRET-VOICEPRINT"))
    assertTrue("ciphertext carries GCM overhead", onDisk.size > secret.size)
  }

  @Test
  fun associatedData_bindsBlobToItsPath() {
    // A blob written at path A must not decrypt when its bytes are presented as path B — the path is
    // authenticated associated data. Simulate by copying the on-disk ciphertext to a different name.
    val root = tmp.newFolder("ad")
    val store = EncryptedBlobStore(root, TinkAeadBlobCipher.fromAead(aead), legacy = null)
    store.write("a.wav", "hello".toByteArray())
    java.io.File(root, "a.wav").copyTo(java.io.File(root, "b.wav"))
    assertArrayEquals("hello".toByteArray(), store.read("a.wav"))
    assertNull("ciphertext moved to another path must fail authentication", store.read("b.wav"))
  }

  @Test
  fun tamperedCiphertext_isRejected() {
    val root = tmp.newFolder("tamper")
    val store = EncryptedBlobStore(root, TinkAeadBlobCipher.fromAead(aead), legacy = null)
    store.write("t.wav", "integrity".toByteArray())
    val f = java.io.File(root, "t.wav")
    val bytes = f.readBytes()
    bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
    f.writeBytes(bytes)
    assertNull("GCM tag mismatch must surface as a failed (null) read, not corrupt plaintext", store.read("t.wav"))
  }

  @Test
  fun delete_and_listIdsWithSuffix() {
    val store = newStore()
    store.write("p1.wav", byteArrayOf(1))
    store.write("p2.wav", byteArrayOf(2))
    store.write("note.txt", byteArrayOf(3))
    assertEquals(listOf("p1", "p2"), store.listIdsWithSuffix(".wav"))
    store.delete("p1.wav")
    assertFalse(store.exists("p1.wav"))
    assertEquals(listOf("p2"), store.listIdsWithSuffix(".wav"))
  }

  @Test
  fun pathTraversal_isRejected() {
    val store = newStore()
    val threw = runCatching { store.write("../escape.wav", byteArrayOf(1)) }.isFailure
    assertTrue("path traversal must throw", threw)
  }
}
