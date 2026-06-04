package com.google.ai.edge.gallery.customtasks.baotranslate.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock

class VoiceProfileManagerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var manager: VoiceProfileManager

  @Before
  fun setup() {
    val filesDir = tempFolder.newFolder("files")
    context = mock()
    manager = VoiceProfileManager(
      context = context,
      rootFilesDir = filesDir,
      defaultVoiceNameProvider = { "Default Voice" },
    )
  }

  @Test
  fun `saveProfile and loadProfile roundtrip`() {
    val samples = ShortArray(16000) { (it % 1000).toShort() }
    val saved = manager.saveProfile(samples, 16000, "test_profile")

    assertNotNull(saved)
    assertEquals("test_profile", saved.id)
    assertEquals(1.0f, saved.durationSec, 0.01f)
    assertTrue(saved.wavPath.isNotBlank())

    val loaded = manager.loadProfile("test_profile")
    assertNotNull(loaded)
    assertEquals(saved.id, loaded?.id)
    assertEquals(saved.wavPath, loaded?.wavPath)
  }

  @Test
  fun `hasProfile returns false when missing`() {
    assertFalse(manager.hasProfile("missing"))
  }

  @Test
  fun `hasProfile returns true after save`() {
    val samples = ShortArray(16000) { 0 }
    manager.saveProfile(samples, 16000, "exists")
    assertTrue(manager.hasProfile("exists"))
  }

  @Test
  fun `deleteProfile removes file`() {
    val samples = ShortArray(16000) { 0 }
    manager.saveProfile(samples, 16000, "to_delete")
    assertTrue(manager.hasProfile("to_delete"))

    manager.deleteProfile("to_delete")
    assertFalse(manager.hasProfile("to_delete"))
    assertNull(manager.loadProfile("to_delete"))
  }

  @Test
  fun `listProfiles returns saved profiles`() {
    val samples = ShortArray(16000) { 0 }
    manager.saveProfile(samples, 16000, "profile_a")
    manager.saveProfile(samples, 16000, "profile_b")

    val list = manager.listProfiles()
    assertEquals(2, list.size)
    assertTrue(list.any { it.id == "profile_a" })
    assertTrue(list.any { it.id == "profile_b" })
  }

  @Test
  fun `saveVoice with wavData roundtrip`() {
    val wavHeader = createMinimalWavHeader(sampleRate = 16000, dataSize = 32000)
    val pcmData = ByteArray(32000) { (it % 256).toByte() }
    val wavData = wavHeader + pcmData

    val profile = manager.saveVoice("Test Voice", wavData)
    assertEquals("Test Voice", profile.name)
    assertTrue(profile.wavPath.endsWith(".wav"))

    val loaded = manager.loadProfile(profile.id)
    assertNotNull(loaded)
    assertEquals(profile.wavPath, loaded?.wavPath)
  }

  @Test
  fun `getVoicePath returns path for existing profile`() {
    val samples = ShortArray(16000) { 0 }
    manager.saveProfile(samples, 16000, "path_test")
    val path = manager.getVoicePath("path_test")
    assertNotNull(path)
    assertTrue(path!!.endsWith("path_test.wav"))
  }

  @Test
  fun `getVoicePath returns null for missing profile`() {
    assertNull(manager.getVoicePath("missing"))
  }

  private fun createMinimalWavHeader(sampleRate: Int, dataSize: Int): ByteArray {
    val byteRate = sampleRate * 1 * 16 / 8
    val blockAlign = 1 * 16 / 8
    val totalSize = 36 + dataSize

    return ByteArray(44).apply {
      "RIFF".toByteArray().copyInto(this, 0)
      intToBytes(totalSize).copyInto(this, 4)
      "WAVE".toByteArray().copyInto(this, 8)
      "fmt ".toByteArray().copyInto(this, 12)
      intToBytes(16).copyInto(this, 16)
      shortToBytes(1).copyInto(this, 20)
      shortToBytes(1).copyInto(this, 22)
      intToBytes(sampleRate).copyInto(this, 24)
      intToBytes(byteRate).copyInto(this, 28)
      shortToBytes(blockAlign).copyInto(this, 32)
      shortToBytes(16).copyInto(this, 34)
      "data".toByteArray().copyInto(this, 36)
      intToBytes(dataSize).copyInto(this, 40)
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
