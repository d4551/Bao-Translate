package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadProcessor
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.WhisperPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.PlatformTtsPipeline
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PipelineLifecycleManager {
  val pipelineMutex = Mutex()

  @Volatile var whisperPipeline: WhisperPipeline? = null
  @Volatile var translationPipeline: TranslationPipeline? = null
  @Volatile var kokoroTts: KokoroTtsPipeline? = null
  // Fallback TTS for languages Kokoro can't voice (de/ko/ru/ar/...). Lazily initializes the device
  // engine on first use; created alongside Kokoro so the synth path can route non-native languages.
  @Volatile var platformTts: PlatformTtsPipeline? = null
  @Volatile var vadProcessor: VadProcessor? = null

  // OpenVoice cross-lingual clone: converts Kokoro output into the enrolled user's timbre.
  @Volatile var openVoiceConverter: OpenVoiceVoiceConverter? = null
  @Volatile var openVoiceTargetSe: FloatArray? = null

  private fun initOpenVoiceLocked(app: Application) {
    if (!BaoTranslateModelManager.isOpenVoiceCloneAvailable(app)) return
    val conv = OpenVoiceVoiceConverter(app)
    if (!conv.initialize(
        BaoTranslateModelManager.getOpenVoiceConverterFile(app),
        BaoTranslateModelManager.getOpenVoiceRefEncFile(app),
      )
    ) {
      return
    }
    openVoiceConverter = conv
    val vpm = VoiceProfileManager(app)
    var se = vpm.loadSpeakerEmbedding()
    if (se == null) {
      // Migrate: derive the embedding from an existing enrollment clip and cache it.
      val profile = vpm.loadProfile()
      if (profile != null) {
        val bytes = File(profile.wavPath).takeIf { it.exists() }?.readBytes()
        if (bytes != null && WavUtils.isValidWav(bytes)) {
          val rate = WavUtils.extractSampleRateFromWav(bytes) ?: 16000
          se = conv.computeSpeakerEmbedding(WavUtils.extractSamplesFromWav(bytes), rate)
            ?.also { vpm.saveSpeakerEmbedding(it) }
        }
      }
    }
    openVoiceTargetSe = se
  }

  // [sttLanguage] is the Whisper decode language ("" = auto-detect). Forcing the user's chosen
  // source language is essential: Whisper-base auto-detect mis-identifies several languages (e.g.
  // Spanish/Italian/Portuguese -> "Latin"), which corrupts the transcript. Honoring the selected
  // language fixes recognition. Verified on device in BaoTranslateLanguageMatrixE2eTest.
  suspend fun initializePipelines(app: Application, translationModel: String, sttLanguage: String = "") {
    pipelineMutex.withLock {
      cleanupPipelinesLocked()
      initializeAllPipelines(app, translationModel, sttLanguage)
    }
  }

  private suspend fun initializeAllPipelines(app: Application, translationModel: String, sttLanguage: String) {
    val modelManager = BaoTranslateModelManager

    val whisperDir = modelManager.getWhisperModelDir(app)
    if (whisperDir.exists()) {
      val whisper = WhisperPipeline(app)
      if (whisper.initialize(whisperDir.absolutePath, sttLanguage)) {
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
    platformTts = PlatformTtsPipeline(app)

    val vad = VadProcessor(app)
    if (vad.initialize()) {
      vadProcessor = vad
    }

    initOpenVoiceLocked(app)
  }

  private suspend fun initializeComponent(app: Application, component: String, translationModel: String, sttLanguage: String) {
    val modelManager = BaoTranslateModelManager

    when (component) {
      "stt" -> {
        val whisperDir = modelManager.getWhisperModelDir(app)
        if (whisperDir.exists()) {
          val whisper = WhisperPipeline(app)
          if (whisper.initialize(whisperDir.absolutePath, sttLanguage)) {
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
        platformTts = PlatformTtsPipeline(app)
        initOpenVoiceLocked(app)
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
    platformTts?.cleanup()
    platformTts = null
    openVoiceConverter?.cleanup()
    openVoiceConverter = null
    openVoiceTargetSe = null
    vadProcessor?.cleanup()
    vadProcessor = null
  }

  suspend fun reinitializePipeline(app: Application, component: String, translationModel: String, sttLanguage: String = "") {
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
          platformTts?.cleanup()
          platformTts = null
          openVoiceConverter?.cleanup()
          openVoiceConverter = null
        }
      }

      initializeComponent(app, component, translationModel, sttLanguage)
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
