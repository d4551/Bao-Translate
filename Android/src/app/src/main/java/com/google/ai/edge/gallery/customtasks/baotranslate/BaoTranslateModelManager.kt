package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelStatus {
  data object NotDownloaded : ModelStatus
  data class Downloading(
    val progress: Float,
    val bytesReceived: Long,
    val totalBytes: Long,
  ) : ModelStatus
  data object Extracting : ModelStatus
  data object Ready : ModelStatus
  data class Error(val reason: String) : ModelStatus
}

data class ModelInfo(
  val id: String,
  val displayNameRes: Int,
  val category: ModelCategory,
  val estimatedSizeMb: Long,
)

enum class ModelCategory {
  STT, TRANSLATION, TTS, VAD, VOICE_CLONE
}

/**
 * Singleton manager for BaoTranslate ML models.
 *
 * Lifecycle: This object persists for the entire process lifetime and survives
 * Activity configuration changes. The [modelStatuses] StateFlow maintains download
 * progress across screen rotations and navigation.
 *
 * Thread safety: All public methods are safe to call from any thread. Internal
 * state updates use [MutableStateFlow.update] for atomic operations.
 *
 * Memory: Holds references to download jobs and status maps. Call [cleanup] when
 * BaoTranslate feature is no longer needed to release resources.
 */
object BaoTranslateModelManager {
  private const val TAG = "BaoTranslateModels"
  private const val SHERPA_ONNX_DIR = "sherpa_onnx_models"
  private const val TRANSLATION_DIR = "translation_models"
  private const val VERSION_FILE = "version.json"

  private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
  val modelStatuses: StateFlow<Map<String, ModelStatus>> = _modelStatuses.asStateFlow()

  fun updateStatusExternal(modelId: String, status: ModelStatus) {
    _modelStatuses.update { current ->
      current.toMutableMap().apply {
        this[modelId] = status
      }
    }
  }

  val ALL_MODELS = listOf(
    ModelInfo(
      id = "kokoro_tts",
      displayNameRes = R.string.bao_model_kokoro_tts,
      category = ModelCategory.TTS,
      estimatedSizeMb = 142,
    ),
    ModelInfo(
      id = "silero_vad",
      displayNameRes = R.string.bao_model_silero_vad,
      category = ModelCategory.VAD,
      estimatedSizeMb = 2,
    ),
    ModelInfo(
      id = "whisper_base",
      displayNameRes = R.string.bao_model_whisper_base,
      category = ModelCategory.STT,
      estimatedSizeMb = 148,
    ),
    ModelInfo(
      id = "qwen25_1b",
      displayNameRes = R.string.bao_model_qwen25_1b,
      category = ModelCategory.TRANSLATION,
      estimatedSizeMb = 1523,
    ),
    ModelInfo(
      id = "gemma4_e2b",
      displayNameRes = R.string.bao_model_gemma4_e2b,
      category = ModelCategory.TRANSLATION,
      estimatedSizeMb = 2468,
    ),
    ModelInfo(
      id = "openvoice",
      displayNameRes = R.string.bao_model_openvoice,
      category = ModelCategory.VOICE_CLONE,
      estimatedSizeMb = 125,
    ),
    ModelInfo(
      id = "supertonic_tts",
      displayNameRes = R.string.bao_model_supertonic_tts,
      category = ModelCategory.TTS,
      estimatedSizeMb = 80,
    ),
  )

  // The required model set — the SINGLE SOURCE OF TRUTH for auto-provisioning, "Required" settings
  // grouping, and readiness. Every member is treated identically: a download failure for ANY of them
  // is fatal to the provisioning run (downloadRequiredModels), and areRequiredModelsReady() requires
  // all of them. OpenVoice voice cloning is a first-class member — not an optional upgrade — so it is
  // always provisioned; the cloned voice activates the moment the user enrolls.
  val REQUIRED_MODEL_IDS = listOf("whisper_base", "qwen25_1b", "silero_vad", "kokoro_tts", "openvoice")

  private data class ArchiveSpec(
    val modelId: String,
    val archiveFileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val requiredFiles: List<String>,
    val extractDir: String,
  )

  private data class FileSpec(
    val modelId: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
  )

  private data class TranslationModelSpec(
    val modelId: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
  )

  private val ARCHIVES = listOf(
    ArchiveSpec(
      modelId = "kokoro_tts",
      archiveFileName = "kokoro-multi-lang-v1_0.tar.bz2",
      downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
      sizeBytes = 350_000_000L,
      requiredFiles = listOf(
        "kokoro-multi-lang-v1_0/model.onnx",
        "kokoro-multi-lang-v1_0/voices.bin",
        "kokoro-multi-lang-v1_0/tokens.txt",
        "kokoro-multi-lang-v1_0/espeak-ng-data",
      ),
      extractDir = "kokoro-multi-lang-v1_0",
    ),
    ArchiveSpec(
      modelId = "supertonic_tts",
      archiveFileName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
      downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
      sizeBytes = 80_000_000L,
      requiredFiles = listOf(
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/duration_predictor.int8.onnx",
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/text_encoder.int8.onnx",
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/vector_estimator.int8.onnx",
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/vocoder.int8.onnx",
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/tts.json",
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/unicode_indexer.bin",
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/voice.bin",
      ),
      extractDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
    ),
  )

  private val FILES = listOf(
    FileSpec(
      modelId = "silero_vad",
      fileName = "silero_vad.onnx",
      downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
      sizeBytes = 2_000_000L,
    ),
  )

  private val TRANSLATION_MODELS = listOf(
    TranslationModelSpec(
      modelId = "qwen25_1b",
      fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
      downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
      sizeBytes = 1_597_931_520L,
    ),
    TranslationModelSpec(
      modelId = "gemma4_e2b",
      fileName = "gemma-4-E2B-it.litertlm",
      downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
      sizeBytes = 2_588_147_712L,
    ),
  )

  private data class OpenVoiceFileSpec(
    val downloadUrl: String,
    val targetName: String,
    val sizeBytes: Long,
  )

  // OpenVoice v2 ToneColorConverter ONNX (public, downloadable export). ONE pair clones EVERY voice
  // and language — the speaker identity is a 256-d embedding computed on-device at enrollment, never
  // baked into the model — so there is no per-voice download. Saved under the app's local names
  // ([getOpenVoiceConverterFile] / [getOpenVoiceRefEncFile]); sizes pinned to the HF LFS blob sizes
  // so a truncated download is rejected (see [downloadOpenVoiceModels]).
  private val OPENVOICE_FILES = listOf(
    OpenVoiceFileSpec(
      downloadUrl = "https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx/resolve/main/tone_clone_model.onnx",
      targetName = "ov_converter.onnx",
      sizeBytes = 127_891_564L,
    ),
    OpenVoiceFileSpec(
      downloadUrl = "https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx/resolve/main/tone_color_extract_model.onnx",
      targetName = "ov_refenc.onnx",
      sizeBytes = 3_257_992L,
    ),
  )

  fun getSherpaOnnxDir(context: Context): File =
    File(context.filesDir, SHERPA_ONNX_DIR).also { it.mkdirs() }

  fun getTranslationDir(context: Context): File =
    File(context.filesDir, TRANSLATION_DIR).also { it.mkdirs() }

  fun getKokoroModelDir(context: Context): File =
    File(getSherpaOnnxDir(context), "kokoro-multi-lang-v1_0")

  fun getSupertonicModelDir(context: Context): File =
    File(getSherpaOnnxDir(context), "sherpa-onnx-supertonic-3-tts-int8-2026-05-11")

  fun getVadModelPath(context: Context): String =
    File(getSherpaOnnxDir(context), "silero_vad.onnx").absolutePath

  fun getWhisperModelDir(context: Context): File =
    File(getSherpaOnnxDir(context), "sherpa-onnx-whisper-base")

  // OpenVoice cross-lingual voice-clone ONNX artifacts (cloned voice in the target language),
  // run at exact length on ONNX Runtime for crisp output.
  fun getOpenVoiceDir(context: Context): File =
    File(context.filesDir, "openvoice").also { it.mkdirs() }

  fun getOpenVoiceConverterFile(context: Context): File =
    File(getOpenVoiceDir(context), "ov_converter.onnx")

  fun getOpenVoiceRefEncFile(context: Context): File =
    File(getOpenVoiceDir(context), "ov_refenc.onnx")

  // length()>0 (not just exists()): a zero-byte file left by a killed write must not count as ready
  // (it would feed a corrupt model into ONNX Runtime).
  fun isOpenVoiceCloneAvailable(context: Context): Boolean =
    getOpenVoiceConverterFile(context).length() > 0 && getOpenVoiceRefEncFile(context).length() > 0

  fun getTranslationModelDir(context: Context, modelId: String = "qwen25_1b"): File =
    File(getTranslationDir(context), modelId)

  fun refreshStatuses(context: Context) {
    // Merge-preserve transient in-flight statuses. A full replace from on-disk presence would
    // clobber a model that is actively Downloading/Extracting back to NotDownloaded (the files are
    // not fully present yet) if refreshStatuses runs during a download, hiding the progress UI even
    // though the background download is still running.
    _modelStatuses.update { current ->
      ALL_MODELS.associate { model ->
        val existing = current[model.id]
        model.id to if (existing is ModelStatus.Downloading || existing is ModelStatus.Extracting) {
          existing
        } else {
          checkModelStatus(context, model.id)
        }
      }
    }
  }

  fun checkModelStatus(context: Context, modelId: String): ModelStatus {
    return when (modelId) {
      "kokoro_tts" -> {
        val archive = ARCHIVES.first { it.modelId == modelId }
        if (isArchiveExtracted(context, archive)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "silero_vad" -> {
        val file = FILES.first { it.modelId == modelId }
        if (isFileDownloaded(context, file)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "whisper_base" -> {
        val dir = getWhisperModelDir(context)
        val encoderFile = File(dir, "base-encoder.int8.onnx")
        val decoderFile = File(dir, "base-decoder.int8.onnx")
        val tokensFile = File(dir, "base-tokens.txt")
        // length()>0 (not just exists()): a zero-byte file left by a killed write must not be Ready.
        if (encoderFile.length() > 0 && decoderFile.length() > 0 && tokensFile.length() > 0) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "qwen25_1b", "gemma4_e2b" -> {
        if (isTranslationModelDownloaded(context, modelId)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "openvoice" -> {
        if (isOpenVoiceCloneAvailable(context)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "supertonic_tts" -> {
        val archive = ARCHIVES.first { it.modelId == modelId }
        if (isArchiveExtracted(context, archive)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      else -> ModelStatus.NotDownloaded
    }
  }

  fun areRequiredModelsReady(context: Context): Boolean =
    REQUIRED_MODEL_IDS.all { checkModelStatus(context, it) == ModelStatus.Ready }

  fun areAllModelsReady(context: Context): Boolean =
    ALL_MODELS.all { checkModelStatus(context, it.id) == ModelStatus.Ready }

  fun getStorageBreakdown(context: Context): Map<String, Long> {
    val baseDir = getSherpaOnnxDir(context)
    val transDir = getTranslationDir(context)

    return ALL_MODELS.associate { model ->
      val size = when (model.id) {
        "kokoro_tts" -> dirSize(File(baseDir, "kokoro-multi-lang-v1_0"))
        "silero_vad" -> File(baseDir, "silero_vad.onnx").takeIf { it.exists() }?.length() ?: 0L
        "whisper_base" -> dirSize(getWhisperModelDir(context))
        "qwen25_1b", "gemma4_e2b" -> dirSize(getTranslationModelDir(context, model.id))
        "openvoice" -> dirSize(getOpenVoiceDir(context))
        "supertonic_tts" -> dirSize(getSupertonicModelDir(context))
        else -> 0L
      }
      model.id to size
    }
  }

  fun getDownloadedSizeBytes(context: Context): Long =
    getStorageBreakdown(context).values.sum()

  fun getTotalSizeBytes(): Long = ALL_MODELS.sumOf { it.estimatedSizeMb * 1024 * 1024 }

  suspend fun downloadModel(
    context: Context,
    modelId: String,
    wifiOnly: Boolean = false,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    if (wifiOnly && !isWifiConnected(context)) {
      return@withContext Result.failure(Exception(context.getString(R.string.bao_translate_error_wifi_required)))
    }

    updateStatus(modelId, ModelStatus.Downloading(0f, 0L, 0L))

    // A mid-stream network drop or disk error during a multi-GB download throws (connect/read/
    // write/responseCode), which would otherwise escape `withContext`, bypass the fold below, and
    // crash the app while leaving the status stuck on Downloading. runCatchingCancellable converts
    // any such throw into the Result.failure the fold handles — but RETHROWS CancellationException
    // so a delete-triggered cancel actually cancels (and is not mislabeled as a download Error).
    val result = runCatchingCancellable {
      when (modelId) {
        "kokoro_tts" -> {
          val archive = ARCHIVES.first { it.modelId == modelId }
          downloadAndExtractArchive(context, archive, modelId)
        }
        "silero_vad" -> {
          val file = FILES.first { it.modelId == modelId }
          downloadSingleFile(context, file, modelId)
        }
        "whisper_base" -> downloadWhisperModel(context, modelId)
        "qwen25_1b", "gemma4_e2b" -> downloadTranslationModel(context, modelId)
        "openvoice" -> downloadOpenVoiceModels(context, modelId)
        "supertonic_tts" -> {
          val archive = ARCHIVES.first { it.modelId == modelId }
          downloadAndExtractArchive(context, archive, modelId)
        }
        else -> Result.failure(
          IllegalArgumentException(context.getString(R.string.bao_translate_error_unknown_model, modelId))
        )
      }
    }.getOrElse { Result.failure(it) }

    result.fold(
      onSuccess = {
        updateStatus(modelId, ModelStatus.Ready)
        Result.success(Unit)
      },
      onFailure = { e ->
        updateStatus(modelId, ModelStatus.Error(e.message ?: context.getString(R.string.bao_error_unknown)))
        Result.failure(e)
      },
    )
  }

  suspend fun downloadRequiredModels(
    context: Context,
    wifiOnly: Boolean = false,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    REQUIRED_MODEL_IDS.mapNotNull { id -> ALL_MODELS.firstOrNull { it.id == id } }.forEach { model ->
      if (checkModelStatus(context, model.id) != ModelStatus.Ready) {
        val result = downloadModel(context, model.id, wifiOnly = wifiOnly)
        if (result.isFailure) return@withContext result
      }
    }
    Result.success(Unit)
  }

  fun deleteModel(context: Context, modelId: String) {
    when (modelId) {
      "kokoro_tts" -> File(getSherpaOnnxDir(context), "kokoro-multi-lang-v1_0").deleteRecursively()
      "silero_vad" -> File(getSherpaOnnxDir(context), "silero_vad.onnx").delete()
      "whisper_base" -> getWhisperModelDir(context).deleteRecursively()
      "qwen25_1b", "gemma4_e2b" -> getTranslationModelDir(context, modelId).deleteRecursively()
      "openvoice" -> getOpenVoiceDir(context).deleteRecursively()
      "supertonic_tts" -> getSupertonicModelDir(context).deleteRecursively()
    }
    _modelStatuses.update { it + (modelId to ModelStatus.NotDownloaded) }
    BaoLog.i(TAG, "Deleted model: $modelId")
  }

  fun deleteAllModels(context: Context) {
    getSherpaOnnxDir(context).deleteRecursively()
    getTranslationDir(context).deleteRecursively()
    _modelStatuses.value = ALL_MODELS.associate { it.id to ModelStatus.NotDownloaded }
    BaoLog.i(TAG, "Deleted all models")
  }

  private fun isArchiveExtracted(context: Context, archive: ArchiveSpec): Boolean =
    requiredFilesComplete(getSherpaOnnxDir(context), archive.requiredFiles)

  // Existence alone is insufficient: an interrupted untar (or process kill) leaves
  // espeak-ng-data present-but-empty, or files zero-length. Require directories to be
  // non-empty and files to be non-zero, so a truncated/partial extraction reports NotDownloaded and
  // re-downloads instead of feeding a corrupt model into native sherpa-onnx (SIGSEGV / garbage TTS).
  // A trailing slash on a required entry denotes a directory entry (archive convention). Java's
  // File silently strips the trailing separator, so a path like "model.onnx/" would otherwise match
  // a regular file. Detect the marker before normalization and require such an entry to resolve to a
  // non-empty directory, never a file — a directory entry pointing at a plain file is a type mismatch.
  internal fun requiredFilesComplete(baseDir: File, requiredFiles: List<String>): Boolean =
    requiredFiles.all { rel ->
      val expectsDirectory = rel.endsWith("/") || rel.endsWith(File.separator)
      val f = File(baseDir, rel)
      when {
        !f.exists() -> false
        f.isDirectory -> f.listFiles()?.isNotEmpty() == true
        expectsDirectory -> false
        else -> f.length() > 0
      }
    }

  private fun isFileDownloaded(context: Context, file: FileSpec): Boolean {
    val target = File(getSherpaOnnxDir(context), file.fileName)
    return target.exists() && target.length() > 0
  }

  private suspend fun downloadAndExtractArchive(
    context: Context,
    archive: ArchiveSpec,
    modelId: String,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    if (isArchiveExtracted(context, archive)) {
      BaoLog.i(TAG, "${archive.modelId} already extracted, skipping")
      return@withContext Result.success(Unit)
    }

    val baseDir = getSherpaOnnxDir(context)
    val archiveFile = File(baseDir, archive.archiveFileName)

    if (!archiveFile.exists()) {
      val downloadResult = downloadFileWithProgress(
        context,
        archive.downloadUrl, archiveFile, archive.sizeBytes, archive.sizeBytes,
      ) { downloaded, total ->
        val progress = if (total > 0) downloaded.toFloat() / total else 0f
        updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
      }
      if (downloadResult.isFailure) {
        archiveFile.delete()
        return@withContext downloadResult
      }
    }

    updateStatus(modelId, ModelStatus.Extracting)
    BaoLog.i(TAG, "Extracting ${archive.modelId}...")

    if (hasSymlinks(baseDir)) {
      return@withContext Result.failure(SecurityException("Symlinks detected in extraction directory"))
    }

    TarArchiveInputStream(
      BZip2CompressorInputStream(
        BufferedInputStream(archiveFile.inputStream())
      )
	    ).use { tar ->
	      var entry = tar.nextEntry
	      while (entry != null) {
	        val outFile = File(baseDir, entry.name)
	        if (!isInsideDir(baseDir, outFile)) {
	          return@withContext Result.failure(SecurityException("Path traversal in archive: ${entry.name}"))
	        }
	
	        if (entry.isDirectory) {
	          if (!outFile.mkdirs() && !outFile.exists()) {
	            return@withContext Result.failure(Exception("Failed to create directory: ${entry.name}"))
          }
        } else {
          outFile.parentFile?.let { parent ->
            if (!parent.mkdirs() && !parent.exists()) {
              return@withContext Result.failure(Exception("Failed to create parent directory for: ${entry.name}"))
            }
          }
	          FileOutputStream(outFile).use { output ->
	            tar.copyTo(output)
	          }
        }

        entry = tar.nextEntry
      }
    }

    archiveFile.delete()

    val missing = archive.requiredFiles.filter { !File(baseDir, it).exists() }
    if (missing.isNotEmpty()) {
      return@withContext Result.failure(Exception("Missing files after extraction: $missing"))
    }

    BaoLog.i(TAG, "${archive.modelId} extracted successfully")
    Result.success(Unit)
  }

  private suspend fun downloadSingleFile(
    context: Context,
    file: FileSpec,
    modelId: String,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    if (isFileDownloaded(context, file)) {
      BaoLog.i(TAG, "${file.modelId} already downloaded, skipping")
      return@withContext Result.success(Unit)
    }

    val targetFile = File(getSherpaOnnxDir(context), file.fileName)
    targetFile.parentFile?.mkdirs()

    val result = downloadFileWithProgress(
      context,
      file.downloadUrl, targetFile, file.sizeBytes,
    ) { downloaded, total ->
      val progress = if (total > 0) downloaded.toFloat() / total else 0f
      updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
    }

    if (result.isFailure) {
      targetFile.delete()
    }
    result
  }

  private suspend fun downloadWhisperModel(
    context: Context,
    modelId: String,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    val modelDir = getWhisperModelDir(context)
    val encoderFile = File(modelDir, "base-encoder.int8.onnx")
    val decoderFile = File(modelDir, "base-decoder.int8.onnx")
    val tokensFile = File(modelDir, "base-tokens.txt")

    if (encoderFile.exists() && encoderFile.length() > 0 &&
        decoderFile.exists() && decoderFile.length() > 0 &&
        tokensFile.exists() && tokensFile.length() > 0) {
      return@withContext Result.success(Unit)
    }

    modelDir.mkdirs()

    val whisperArchiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
    val archiveFile = File(modelDir.parentFile, "sherpa-onnx-whisper-base.tar.bz2")

    val downloadResult = downloadFileWithProgress(
      context,
      whisperArchiveUrl, archiveFile, 148_000_000L, 148_000_000L,
    ) { downloaded, total ->
      val progress = if (total > 0) downloaded.toFloat() / total else 0f
      updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
    }

    if (downloadResult.isFailure) {
      archiveFile.delete()
      return@withContext downloadResult
    }

    updateStatus(modelId, ModelStatus.Extracting)

    val baseDir = getSherpaOnnxDir(context)
    if (hasSymlinks(baseDir)) {
      return@withContext Result.failure(SecurityException("Symlinks detected in extraction directory"))
    }

    TarArchiveInputStream(
      BZip2CompressorInputStream(
        BufferedInputStream(archiveFile.inputStream())
      )
    ).use { tar ->
	      var entry = tar.nextEntry
	      while (entry != null) {
	        val outFile = File(baseDir, entry.name)
	        if (!isInsideDir(baseDir, outFile)) {
	          return@withContext Result.failure(SecurityException("Path traversal: ${entry.name}"))
	        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { output -> tar.copyTo(output) }
        }
        entry = tar.nextEntry
      }
    }

    File(modelDir, "base-encoder.onnx").delete()
    File(modelDir, "base-decoder.onnx").delete()
    archiveFile.delete()

    if (encoderFile.exists() && decoderFile.exists() && tokensFile.exists()) {
      Result.success(Unit)
    } else {
      Result.failure(Exception("Whisper model files not found after extraction"))
    }
  }

  private suspend fun downloadTranslationModel(
    context: Context,
    modelId: String,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    if (isTranslationModelDownloaded(context, modelId)) {
      return@withContext Result.success(Unit)
    }

    val modelDir = getTranslationModelDir(context, modelId)
    modelDir.mkdirs()

    val spec = translationSpec(modelId)
    val targetFile = File(modelDir, spec.fileName)

    val downloadResult = downloadFileWithProgress(
      context,
      spec.downloadUrl, targetFile, spec.sizeBytes,
    ) { downloaded, total ->
      val progress = if (total > 0) downloaded.toFloat() / total else 0f
      updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
    }

    if (downloadResult.isFailure) {
      targetFile.delete()
      return@withContext downloadResult
    }

    if (targetFile.length() < spec.sizeBytes) {
      val actualSize = targetFile.length()
      targetFile.delete()
      return@withContext Result.failure(
        Exception(context.getString(R.string.bao_error_incomplete_download, actualSize, spec.sizeBytes))
      )
    }

    Result.success(Unit)
  }

  private suspend fun downloadOpenVoiceModels(
    context: Context,
    modelId: String,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    if (isOpenVoiceCloneAvailable(context)) {
      BaoLog.i(TAG, "$modelId already downloaded, skipping")
      return@withContext Result.success(Unit)
    }
    getOpenVoiceDir(context).mkdirs()

    // Combined progress across both files (converter ~128 MB + ref encoder ~3 MB) so the UI shows
    // one monotonic bar for the logical "voice cloning" model.
    val totalBytes = OPENVOICE_FILES.sumOf { it.sizeBytes }
    var completedBytes = 0L
    for (spec in OPENVOICE_FILES) {
      val target = File(getOpenVoiceDir(context), spec.targetName)
      val result = downloadFileWithProgress(
        context, spec.downloadUrl, target, spec.sizeBytes,
      ) { downloaded, _ ->
        val overall = completedBytes + downloaded
        val progress = if (totalBytes > 0) overall.toFloat() / totalBytes else 0f
        updateStatus(modelId, ModelStatus.Downloading(progress, overall, totalBytes))
      }
      if (result.isFailure) {
        target.delete()
        return@withContext result
      }
      // Reject a short file (200-with-wrong-length / silent truncation) so a corrupt ONNX never
      // reaches the runtime; sizeBytes is the pinned HF LFS blob size.
      if (target.length() < spec.sizeBytes) {
        val actual = target.length()
        target.delete()
        return@withContext Result.failure(
          Exception(context.getString(R.string.bao_error_incomplete_download, actual, spec.sizeBytes))
        )
      }
      completedBytes += spec.sizeBytes
    }
    Result.success(Unit)
  }

  private class AutoDisconnectConnection(private val connection: HttpURLConnection) : AutoCloseable {
    val inputStream get() = connection.inputStream
    val responseCode get() = connection.responseCode
    val contentLengthLong get() = connection.contentLengthLong

    override fun close() {
      connection.disconnect()
    }
  }

  private suspend fun downloadFileWithProgress(
    context: Context,
    url: String,
    targetFile: File,
    expectedSize: Long,
    reserveExtraBytes: Long = 0L,
    onProgress: (downloaded: Long, total: Long) -> Unit,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    BaoLog.i(TAG, "Downloading $url")
    val parentDir = targetFile.parentFile
    parentDir?.mkdirs()

    var resumeFrom = 0L
    if (targetFile.exists() && targetFile.length() > 0) {
      resumeFrom = targetFile.length()
      BaoLog.i(TAG, "Resuming download from $resumeFrom bytes")
    }

    val usableSpace = parentDir?.usableSpace ?: targetFile.usableSpace
    val remainingBytes = (expectedSize - resumeFrom.coerceAtMost(expectedSize)).coerceAtLeast(0L)
    val minimumSpace = remainingBytes + reserveExtraBytes
    if (minimumSpace > 0 && usableSpace in 1 until minimumSpace) {
      return@withContext Result.failure(
        Exception(context.getString(R.string.bao_translate_error_storage_required, minimumSpace / (1024 * 1024)))
      )
    }

    val parsedUrl = URL(url)
    if (parsedUrl.protocol != "https" && parsedUrl.protocol != "http") {
      return@withContext Result.failure(IllegalArgumentException("Invalid URL scheme: ${parsedUrl.protocol}"))
    }

    val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnectionWithHeaders(
      url = parsedUrl,
      headers = if (resumeFrom > 0) mapOf("Range" to "bytes=$resumeFrom-") else emptyMap(),
    )
    connection.instanceFollowRedirects = true

    connection.connect()

    AutoDisconnectConnection(connection).use { conn ->
      val responseCode = conn.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
        val errorMsg = when (responseCode) {
          401, 403 -> context.getString(R.string.bao_error_license_required)
          404 -> context.getString(R.string.bao_error_model_not_found, url)
          else -> "HTTP $responseCode for $url"
        }
        return@withContext Result.failure(Exception(errorMsg))
      }

      val supportsRange = responseCode == HttpURLConnection.HTTP_PARTIAL
      if (resumeFrom > 0 && !supportsRange) {
        targetFile.delete()
        resumeFrom = 0L
        BaoLog.i(TAG, "Server does not support Range; restarting download")
      }

      val totalSize = conn.contentLengthLong.takeIf { it > 0 }?.let {
        if (supportsRange) it + resumeFrom else it
      } ?: expectedSize
      var downloaded = if (supportsRange) resumeFrom else 0L
      var lastEmitTime = System.currentTimeMillis()
      var lastEmittedProgress = -1f

      conn.inputStream.use { input ->
        FileOutputStream(targetFile, supportsRange).use { output ->
          val buffer = ByteArray(8192)
          var bytesRead: Int

          while (input.read(buffer).also { bytesRead = it } != -1) {
            // Cooperative cancellation: a delete-during-download cancels this coroutine; check each
            // chunk so cancel()/join() returns promptly instead of blocking on the full transfer.
            coroutineContext.ensureActive()
            output.write(buffer, 0, bytesRead)
            downloaded += bytesRead
            if (totalSize > 0) {
              val progress = downloaded.toFloat() / totalSize
              val now = System.currentTimeMillis()
              if (now - lastEmitTime >= 100 || progress - lastEmittedProgress >= 0.01f) {
                onProgress(downloaded, totalSize)
                lastEmitTime = now
                lastEmittedProgress = progress
              }
            }
          }
        }
      }

      if (totalSize > 0 && downloaded < totalSize) {
        return@withContext Result.failure(Exception(context.getString(R.string.bao_error_incomplete_download, downloaded, totalSize)))
      }

      BaoLog.i(TAG, "Downloaded: $downloaded bytes -> ${targetFile.absolutePath}")
      Result.success(Unit)
    }
  }

  private fun updateStatus(modelId: String, status: ModelStatus) {
    _modelStatuses.update { it + (modelId to status) }
  }

  // Like runCatching, but never swallows CancellationException — rethrowing it preserves structured
  // concurrency so a cancelled (e.g. delete-triggered) download unwinds instead of being captured as
  // a Result.failure and mislabeled an Error.
  private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }

  private fun translationSpec(modelId: String): TranslationModelSpec =
    TRANSLATION_MODELS.first { it.modelId == modelId }

  private fun isTranslationModelDownloaded(context: Context, modelId: String): Boolean {
    val dir = getTranslationModelDir(context, modelId)
    val spec = translationSpec(modelId)
    val builtIn = File(dir, spec.fileName)
    val builtInReady = builtIn.exists() && builtIn.length() >= spec.sizeBytes
    val customTaskReady = dir
      .listFiles { file -> file.extension == "task" && file.length() > 0 }
      ?.isNotEmpty() == true

    return builtInReady || customTaskReady
  }

	  private fun dirSize(dir: File): Long {
	    if (!dir.exists()) return 0L
	    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
	  }
	
  private fun isInsideDir(baseDir: File, candidate: File): Boolean {
    val basePath = baseDir.canonicalFile.toPath()
    val candidatePath = candidate.canonicalFile.toPath()
    return candidatePath.startsWith(basePath)
  }

  private fun hasSymlinks(dir: File): Boolean {
    if (!dir.exists()) return false
    return dir.walkTopDown().any { java.nio.file.Files.isSymbolicLink(it.toPath()) }
  }
	
	  private fun isWifiConnected(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
  }
}
