# Bao Translate Android App

The Android client for **Bao Translate**: a fully on-device, real-time speech translator with live
streaming captions that can speak translations in your own cloned voice and relay a live
conversation to a paired phone over Nearby Connections (Bluetooth LE + Wi-Fi). Everything from voice
activity detection, streaming captions, speech-to-text, translation, text-to-speech, and voice
cloning runs locally on the device after the model stack has been downloaded. No account is required
for normal use, and conversation audio stays on the phone.

For the product overview, architecture charts, and the model stack, see the [root README](../README.md).

## What the app does

- **Live speech translation:** a continuous `mic -> VAD -> STT -> translation -> TTS -> speaker`
  pipeline that translates speech as it is spoken.
- **Multilingual streaming captions:** token-by-token recognition as you speak, in every language:
  a sherpa-onnx streaming zipformer **transducer** (English) and **Vosk** (10 more), lazily
  provisioned per language; runs in both face-to-face and single-speaker continuous mode.
- **On-device speech for every language:** Kokoro voices 7 languages, the supplemental **Supertonic**
  TTS voices de/ja/ko/ru/ar, so no language falls back to the platform text-to-speech engine.
- **Cross-lingual voice cloning:** enroll your voice once; translations into any supported language
  are re-spoken in your own timbre (Kokoro/Supertonic pronunciation + OpenVoice tone-color conversion,
  on ONNX Runtime), computed entirely on-device.
- **Conversation mode:** two phones pair over Nearby Connections (Bluetooth LE + Wi-Fi); each person
  speaks their own language and hears the other in theirs, attributed per speaker.
- **Per-speaker language and audio routing:** choose each side's source/target language and pick the
  headset mic / output device in-app.
- **On-device model management:** download the curated model stack from Hugging Face on first run;
  resumable, integrity-checked downloads.

## Module layout

Gradle project rooted at [`src/`](src). The Bao Translate feature lives under
`app/src/main/java/com/google/ai/edge/gallery/customtasks/baotranslate/`:

| Area | Key files |
| --- | --- |
| Feature entry / DI | `BaoTranslateTaskModule.kt` (registers the task), `BaoTranslateViewModel.kt` |
| Capture & live windowing | `RecordingController.kt` (mic read loop, turn endpointing, streaming-caption feed) |
| STT & streaming captions | `stt/WhisperPipeline.kt`, `stt/VadProcessor.kt`, `stt/StreamingCaptioner.kt`, `stt/StreamingSttPipeline.kt` (sherpa transducer), `stt/VoskStreamingPipeline.kt` (Vosk) |
| Translation | `translate/TranslationPipeline.kt` |
| TTS & cloning | `tts/KokoroTtsPipeline.kt`, `tts/SupertonicTtsPipeline.kt`, `tts/TtsRouter.kt`, `tts/PlatformTtsPipeline.kt`, `tts/OpenVoiceVoiceConverter.kt` |
| Lifecycle / models | `PipelineLifecycleManager.kt`, `BaoTranslateModelManager.kt` (caption-model registry + lazy provisioning), `ModelDownloadCoordinator.kt` |
| Audio routing | `audio/AudioRouter.kt`, `audio/AudioPlayback.kt` |
| Conversation mesh | `bluetooth/BleConversationManager.kt` (Google Nearby Connections) |
| UI | `BaoTranslateScreen.kt`, `ConversationModeScreen.kt`, `BaoTranslateSettings.kt`, `VoiceEnrollmentSheet.kt` |

The feature plugs into the host app's custom-task system (`customtasks/common/CustomTask.kt`); it is
registered via Hilt `@IntoSet` and runs standalone within the app shell.

## Build, test, run

```bash
cd src

# Toolchain: Gradle 9.5.1 + AGP 9.2.1 + Kotlin 2.4.0 + compileSdk 37.
# Gradle toolchains provision JDK 26 for compilation and emit Java 17 bytecode.
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

./gradlew :app:assembleDebug              # build the debug APK
./gradlew :app:testDebugUnitTest          # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # on-device E2E (streaming captions, all-language audio,
                                          # translation, cloning, two-device Nearby conversation)
./gradlew :app:smokeE2e                    # fast smoke gate (skill WebView + UI navigation)

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

* `applicationId = com.bao.translate`; `compileSdk = 37`, `targetSdk = 35`, `minSdk = 31` (Android 12).
* `app/libs/` vendors the `sherpa-onnx` AAR. It and `onnxruntime-android` both ship
  `libonnxruntime.so` (the same ORT 1.24.3 build); the duplicate is resolved at packaging via
  `jniLibs.pickFirsts`. ORT 1.24.3 is pinned because sherpa's JNI binds the ELF-versioned
  `OrtGetApiBase@VERS_1.24.3` symbol.

## On-device E2E

Instrumented tests under `app/src/androidTest/` drive the real pipeline on a connected device
(`BaoTranslate*E2eTest`): the live translation loop (streaming captions through the production read
loop, both face-to-face and continuous), every caption language end-to-end
(`AllLanguagesCaption`, `VoskCaption`, `StreamingAsr`), all-language on-device audio
(`KokoroAudio`, `SupertonicAudio`), the translation matrix, spoken translation, OpenVoice cloning,
the agent skill-call path, and the **two-device Nearby conversation** round-trip (`MultiDevice`, run
the receiver method on one device and the sender on another). Bundled speech prompts make these
device-independent (no reliance on a platform-TTS engine). The model stack must be provisioned on the
device first (downloaded in-app or pushed via `adb`); `adb uninstall` clears provisioned models, but
`install -r` preserves them.

## License

Apache-2.0. Bao Translate is a derivative of [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery);
upstream copyright and the Apache-2.0 license are retained. See [`../LICENSE`](../LICENSE).
