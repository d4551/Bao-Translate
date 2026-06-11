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

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.EncryptedBlobStore
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device verification of the [EncryptedBlobStore] Tink migration's Android-only parts — the bits the
 * JVM `EncryptedBlobStoreTest` can't reach: the real Android Keystore-backed `AndroidKeysetManager`
 * keyset, and the legacy `security-crypto` → Tink migration-on-read. Closes the OOS-AUDIT-014 device
 * ceiling (Keystore master-key binding).
 */
@RunWith(AndroidJUnit4::class)
class BaoTranslateEncryptedBlobStoreInstrumentedTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun freshDir(name: String): File =
    File(context.cacheDir, "blobstore_it_${name}_${System.nanoTime()}").also {
      it.deleteRecursively()
      it.mkdirs()
    }

  @Test
  fun keystoreBackedRoundTrip_andCiphertextOnDiskIsOpaque() {
    val dir = freshDir("rt")
    val store = EncryptedBlobStore(context, dir)
    val secret = "biometric-voiceprint-bytes".toByteArray() + ByteArray(2048) { (it % 7).toByte() }

    store.write("voice_profiles/alice.wav", secret)
    assertTrue(store.exists("voice_profiles/alice.wav"))
    assertArrayEquals("Keystore-backed Tink round-trip must be lossless", secret, store.read("voice_profiles/alice.wav"))

    val onDisk = File(dir, "voice_profiles/alice.wav").readBytes()
    assertFalse(
      "plaintext must never appear in the on-disk ciphertext",
      String(onDisk).contains("biometric-voiceprint-bytes"),
    )
  }

  @Test
  fun tamperedKeystoreBlob_failsClosed() {
    val dir = freshDir("tamper")
    val store = EncryptedBlobStore(context, dir)
    store.write("t.wav", "integrity".toByteArray())
    val f = File(dir, "t.wav")
    val b = f.readBytes()
    b[b.size - 1] = (b[b.size - 1].toInt() xor 0x01).toByte()
    f.writeBytes(b)
    assertNull("a tampered Keystore blob must read as null, not corrupt plaintext", store.read("t.wav"))
  }

  /**
   * A blob written by the OLD security-crypto `EncryptedFile` must still be readable through the new
   * Tink store (legacy fallback) AND be migrated forward (re-encrypted) on first read.
   */
  @Suppress("DEPRECATION") // intentionally writes a legacy security-crypto blob to test Tink migration.
  @Test
  fun legacySecurityCryptoBlob_isReadAndMigratedToTink() {
    val dir = freshDir("migrate")
    val rel = "legacy_profile.wav"
    val payload = "enrolled-before-the-tink-migration".toByteArray()

    // Write a legacy EncryptedFile blob exactly as the pre-migration code would have.
    val masterKey =
      MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    val target = File(dir, rel)
    EncryptedFile.Builder(context, target, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
      .build()
      .openFileOutput()
      .use { it.write(payload) }
    val legacyBytes = target.readBytes()

    val store = EncryptedBlobStore(context, dir)
    assertArrayEquals("legacy security-crypto blob must still be readable", payload, store.read(rel))

    // After the migrating read the on-disk bytes must have changed (now Tink), yet still decrypt.
    val afterBytes = target.readBytes()
    assertFalse("blob should have been re-encrypted to Tink on read", legacyBytes.contentEquals(afterBytes))
    assertArrayEquals("re-encrypted (Tink) blob must still round-trip", payload, store.read(rel))
  }
}
