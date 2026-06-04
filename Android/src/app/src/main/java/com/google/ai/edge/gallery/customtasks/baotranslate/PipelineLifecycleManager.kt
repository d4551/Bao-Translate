package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadProcessor
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.WhisperPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.VoiceClonePipeline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PipelineLifecycleManager {
  val pipelineMutex = Mutex()

  @Volatile var whisperPipeline: WhisperPipeline? = null
  @Volatile var translationPipeline: TranslationPipeline? = null
  @Volatile var kokoroTts: KokoroTtsPipeline? = null
  @Volatile var voiceCloneTts: VoiceClonePipeline? = null
  @Volatile var vadProcessor: VadProcessor? = null

  suspend fun initializePipelines(app: Application, translationModel: String) {
    pipelineMutex.withLock {
      cleanupPipelinesLocked()
      initializeAllPipelines(app, translationModel)
    }
  }

  private suspend fun initializeAllPipelines(app: Application, translationModel: String) {
    val modelManager = BaoTranslateModelManager

    val whisperDir = modelManager.getWhisperModelDir(app)
    if (whisperDir.exists()) {
      val whisper = WhisperPipeline(app)
      if (whisper.initialize(whisperDir.absolutePath)) {
        whisperPipeline = whisper
      }
    }

    val transDir = modelManager.getTranslationModelDir(app, translationModel)
    val litertlmFiles = transDir.listFiles { f -> f.extension == "litertlm" }
    if (litertlmFiles != null && litertlmFiles.isNotEmpty()) {
      val translation = TranslationPipeline(app)
      if (translation.initialize(litertlmFiles[0].absolutePath)) {
        translationPipeline = translation
      }
    }

    val kokoroDir = modelManager.getKokoroModelDir(app)
    if (kokoroDir.exists()) {
      val kokoro = KokoroTtsPipeline(app)
      if (kokoro.initialize(kokoroDir.absolutePath)) {
        kokoroTts = kokoro
      }
    }

    val pocketDir = modelManager.getPocketTtsModelDir(app)
    if (pocketDir.exists()) {
      val clone = VoiceClonePipeline(app)
      if (clone.initialize(pocketDir.absolutePath)) {
        voiceCloneTts = clone
      }
    }

    val vad = VadProcessor(app)
    if (vad.initialize()) {
      vadProcessor = vad
    }
  }

  private suspend fun initializeComponent(app: Application, component: String, translationModel: String) {
    val modelManager = BaoTranslateModelManager

    when (component) {
      "stt" -> {
        val whisperDir = modelManager.getWhisperModelDir(app)
        if (whisperDir.exists()) {
          val whisper = WhisperPipeline(app)
          if (whisper.initialize(whisperDir.absolutePath)) {
            whisperPipeline = whisper
          }
        }

        val vad = VadProcessor(app)
        if (vad.initialize()) {
          vadProcessor = vad
        }
      }
      "translation" -> {
        val transDir = modelManager.getTranslationModelDir(app, translationModel)
        val litertlmFiles = transDir.listFiles { f -> f.extension == "litertlm" }
        if (litertlmFiles != null && litertlmFiles.isNotEmpty()) {
          val translation = TranslationPipeline(app)
          if (translation.initialize(litertlmFiles[0].absolutePath)) {
            translationPipeline = translation
          }
        }
      }
      "tts" -> {
        val kokoroDir = modelManager.getKokoroModelDir(app)
        if (kokoroDir.exists()) {
          val kokoro = KokoroTtsPipeline(app)
          if (kokoro.initialize(kokoroDir.absolutePath)) {
            kokoroTts = kokoro
          }
        }

        val pocketDir = modelManager.getPocketTtsModelDir(app)
        if (pocketDir.exists()) {
          val clone = VoiceClonePipeline(app)
          if (clone.initialize(pocketDir.absolutePath)) {
            voiceCloneTts = clone
          }
        }
      }
    }
  }

  suspend fun cleanupPipelines() {
    pipelineMutex.withLock {
      cleanupPipelinesLocked()
    }
  }

  fun cleanupPipelinesLocked() {
    whisperPipeline?.cleanup()
    whisperPipeline = null
    translationPipeline?.cleanup()
    translationPipeline = null
    kokoroTts?.cleanup()
    kokoroTts = null
    voiceCloneTts?.cleanup()
    voiceCloneTts = null
    vadProcessor?.cleanup()
    vadProcessor = null
  }

  suspend fun reinitializePipeline(app: Application, component: String, translationModel: String) {
    pipelineMutex.withLock {
      when (component) {
        "stt" -> {
          whisperPipeline?.cleanup()
          whisperPipeline = null
          vadProcessor?.cleanup()
          vadProcessor = null
        }
        "translation" -> {
          translationPipeline?.cleanup()
          translationPipeline = null
        }
        "tts" -> {
          kokoroTts?.cleanup()
          kokoroTts = null
          voiceCloneTts?.cleanup()
          voiceCloneTts = null
        }
      }

      initializeComponent(app, component, translationModel)
    }
  }

  fun requiredPipelinesReady(): Boolean =
    whisperPipeline != null &&
      translationPipeline != null &&
      kokoroTts != null &&
      vadProcessor != null

  fun missingPipelineComponents(app: Application): List<String> = buildList {
    if (whisperPipeline == null) add(app.getString(R.string.bao_component_whisper_stt))
    if (translationPipeline == null) add(app.getString(R.string.bao_component_translation))
    if (kokoroTts == null) add(app.getString(R.string.bao_component_kokoro_tts))
    if (vadProcessor == null) add(app.getString(R.string.bao_component_silero_vad))
  }
}
