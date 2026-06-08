package com.google.ai.edge.gallery.customtasks.baotranslate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Proves the model-integrity fix: a half-extracted archive (empty espeak-ng-data directory, or a
 * zero-byte file from a killed write) must report incomplete instead of being accepted as Ready and
 * fed into native sherpa-onnx.
 */
class ModelIntegrityTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val kokoroRequired = listOf("model.onnx", "voices.bin", "espeak-ng-data")

  @Test
  fun incomplete_whenDirectoryEntryIsEmpty() {
    val base = tmp.newFolder("base")
    File(base, "model.onnx").writeBytes(ByteArray(16))
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").mkdirs() // present but empty — the confirmed false-Ready case
    assertFalse(BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired))
  }

  @Test
  fun incomplete_whenFileIsZeroBytes() {
    val base = tmp.newFolder("base")
    File(base, "model.onnx").writeBytes(ByteArray(16))
    File(base, "voices.bin").createNewFile() // zero-byte truncated write
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    assertFalse(BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired))
  }

  @Test
  fun incomplete_whenEntryMissing() {
    val base = tmp.newFolder("base")
    File(base, "model.onnx").writeBytes(ByteArray(16))
    assertFalse(BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired))
  }

  @Test
  fun complete_whenAllPopulated() {
    val base = tmp.newFolder("base")
    File(base, "model.onnx").writeBytes(ByteArray(16))
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    assertTrue(BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired))
  }
}
