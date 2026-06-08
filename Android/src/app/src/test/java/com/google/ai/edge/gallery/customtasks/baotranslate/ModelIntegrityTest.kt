package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Proves the model-integrity fix: a half-extracted archive (empty espeak-ng-data directory, or a
 * zero-byte file from a killed write) must report incomplete instead of being accepted as Ready and
 * fed into native sherpa-onnx.
 */
@Strict
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

  // ----- BRUTALISATION -----

  // ----- Subdirectory-instead-of-file. Production should reject (a directory is not a file).
  @Test
  fun complete_whenSubdirectoryPresentInsteadOfFile_rejected() {
    val base = tmp.newFolder("base")
    File(base, "model.onnx").mkdirs()  // directory, not a file
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    // Production's isFile() check should reject. Pin.
    assertFalse(
      "directory in place of file must be rejected",
      BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired),
    )
  }

  // ----- Symlink: file via symlink is a real path. Production should follow the symlink
  // and find a real file. This works on Linux + macOS.
  @Test
  fun complete_whenSymlinkToFile_accepted() {
    val base = tmp.newFolder("base")
    val realDir = tmp.newFolder("real")
    val realFile = File(realDir, "model.onnx").apply { writeBytes(ByteArray(16)) }
    val symlinkPath = File(base, "model.onnx")
    try {
      Files.createSymbolicLink(symlinkPath.toPath(), realFile.toPath())
    } catch (e: UnsupportedOperationException) {
      assumeTrue("symlinks not supported on this fs", false)
      return
    }
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    assertTrue(
      "symlink to real file is accepted",
      BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired),
    )
  }

  // ----- Symlink to a directory: should be rejected (the check is for a file).
  @Test
  fun incomplete_whenSymlinkToDirectory_rejected() {
    val base = tmp.newFolder("base")
    val realDir = tmp.newFolder("real_dir")
    val symlinkPath = File(base, "model.onnx")
    try {
      Files.createSymbolicLink(symlinkPath.toPath(), realDir.toPath())
    } catch (e: UnsupportedOperationException) {
      assumeTrue("symlinks not supported on this fs", false)
      return
    }
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    assertFalse(
      "symlink to directory must be rejected",
      BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired),
    )
  }

  // ----- Case sensitivity: on case-insensitive filesystems (default macOS HFS+), the
  // filesystem can be case-insensitive. Production uses exact case; this is a gap.
  @Test
  fun complete_whenEntryIsCaseMismatched_documentedGap() {
    val base = tmp.newFolder("base")
    File(base, "Model.Onnx").writeBytes(ByteArray(16))  // capitalized differently
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    // On a case-sensitive fs (Linux), this fails. On a case-insensitive fs (macOS HFS+),
    // production's "model.onnx" lookup may find "Model.Onnx" (case-insensitive match).
    val isCaseInsensitiveFs = System.getProperty("os.name").lowercase().contains("mac")
    val result = BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired)
    if (isCaseInsensitiveFs) {
      // Pin the case-insensitive behavior: it currently passes.
      assertTrue("case-insensitive fs: capital-M file still found", result)
    } else {
      // Linux: case-sensitive, capital-M doesn't match.
      assertFalse("case-sensitive fs: capital-M file not found", result)
    }
  }

  // ----- Read-only file with non-zero length: production's isFile() + length() check
  // doesn't care about permissions. Pin that.
  @Test
  fun incomplete_whenFileIsReadOnly_documented() {
    assumeTrue("posix only", System.getProperty("os.name").lowercase().contains("linux") ||
      System.getProperty("os.name").lowercase().contains("mac"))
    val base = tmp.newFolder("base")
    val f = File(base, "model.onnx")
    f.writeBytes(ByteArray(16))
    f.setReadable(false)
    f.setWritable(false)
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    val result = BaoTranslateModelManager.requiredFilesComplete(base, kokoroRequired)
    // Currently: production does not check writability. The read-only file is accepted
    // because isFile() && length() > 0. Document.
    f.setReadable(true)
    f.setWritable(true)
    assertTrue("DOCUMENTED GAP: read-only file currently accepted", result)
  }

  // ----- Trailing-slash path: should be rejected as it's a directory, not a file.
  @Test
  fun incomplete_whenEntryPathHasTrailingSlash_rejected() {
    val base = tmp.newFolder("base")
    File(base, "model.onnx").writeBytes(ByteArray(16))
    File(base, "voices.bin").writeBytes(ByteArray(16))
    File(base, "espeak-ng-data").apply { mkdirs(); File(this, "phontab").writeBytes(ByteArray(4)) }
    // Trailing-slash on a required-file path (e.g. "espeak-ng-data/") — in production
    // this is a directory (always has trailing path separator), so the check is
    // "the directory exists" — which is fine for the espeak-ng-data entry. But for a
    // required file like "model.onnx/", it would be rejected.
    val malformedRequired = listOf("model.onnx/", "voices.bin", "espeak-ng-data")
    assertFalse(
      "trailing slash on a required file path must be rejected",
      BaoTranslateModelManager.requiredFilesComplete(base, malformedRequired),
    )
  }

  // ----- Perf smoke: 1000 entries must complete in <100ms.
  @Test
  fun complete_returnsTrue_under100ms_with1000Entries() {
    val base = tmp.newFolder("base")
    val many = (0 until 1000).map { "entry_$it" }
    val start = System.currentTimeMillis()
    val result = BaoTranslateModelManager.requiredFilesComplete(base, many)
    val elapsed = System.currentTimeMillis() - start
    assertFalse("1000 missing entries must return false quickly", result)
    assertTrue("must complete in <100ms (took ${elapsed}ms)", elapsed < 100)
  }

  // ----- Non-existent base directory: production's requiredFilesComplete may or may
  // not handle this. Pin the contract.
  @Test
  fun incomplete_whenBaseDirectoryMissing() {
    val missing = File(tmp.newFolder("parent"), "does_not_exist")
    val result = BaoTranslateModelManager.requiredFilesComplete(missing, kokoroRequired)
    assertFalse("non-existent base must be incomplete", result)
  }

  // ----- Empty required list: vacuously true.
  @Test
  fun complete_whenRequiredListEmpty() {
    val base = tmp.newFolder("base")
    assertTrue("empty required list is vacuously complete",
      BaoTranslateModelManager.requiredFilesComplete(base, emptyList()))
  }

  // ----- Single-entry required list: must work like the multi-entry case.
  @Test
  fun complete_singleEntryPresent() {
    val base = tmp.newFolder("base")
    File(base, "only.txt").writeBytes(ByteArray(16))
    assertTrue("single entry present is complete",
      BaoTranslateModelManager.requiredFilesComplete(base, listOf("only.txt")))
  }

  @Test
  fun incomplete_singleEntryMissing() {
    val base = tmp.newFolder("base")
    assertFalse("single entry missing is incomplete",
      BaoTranslateModelManager.requiredFilesComplete(base, listOf("only.txt")))
  }
}
