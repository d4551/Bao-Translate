# Bao Translate — Android app

The Android client for **Bao Translate**: a fully on-device, real-time speech translator that can
speak translations in your own cloned voice and relay a live conversation to a paired phone over
Bluetooth LE. Everything — voice activity detection, speech-to-text, translation, text-to-speech,
and voice cloning — runs locally on the device. No network, no accounts, nothing leaves the phone.

For the product overview, screenshots, and the model stack, see the [root README](../README.md).

## What the app does

* **Live speech translation** — a continuous `mic → VAD → STT → translation → TTS → speaker`
  pipeline that translates speech as it is spoken.
* **Cross-lingual voice cloning** — enroll your voice once; translations into any supported language
  are re-spoken in your own timbre (Kokoro pronunciation + OpenVoice tone-color conversion, on ONNX
  Runtime), computed entirely on-device.
* **Conversation mode** — two phones pair over Bluetooth LE; each person speaks their own language
  and hears the other in theirs, attributed per speaker.
* **Per-speaker language + audio routing** — choose each side's source/target language and pick the
  headset mic / output device in-app.
* **On-device model management** — download the curated model stack from Hugging Face on first run;
  resumable, integrity-checked downloads.

## Module layout

Gradle project rooted at [`src/`](src). The Bao Translate feature lives under
`app/src/main/java/com/google/ai/edge/gallery/customtasks/baotranslate/`:

| Area | Key files |
| --- | --- |
| Feature entry / DI | `BaoTranslateTaskModule.kt` (registers the task), `BaoTranslateViewModel.kt` |
| Capture & live windowing | `RecordingController.kt` |
| Pipelines | `stt/WhisperPipeline.kt`, `translate/TranslationPipeline.kt`, `tts/KokoroTtsPipeline.kt`, `tts/PlatformTtsPipeline.kt`, `tts/OpenVoiceVoiceConverter.kt`, `stt/VadProcessor.kt` |
| Lifecycle / models | `PipelineLifecycleManager.kt`, `BaoTranslateModelManager.kt`, `ModelDownloadCoordinator.kt` |
| Audio routing | `audio/AudioRouter.kt`, `audio/AudioPlayback.kt` |
| Conversation mesh | `bluetooth/BleConversationManager.kt` |
| UI | `BaoTranslateScreen.kt`, `ConversationModeScreen.kt`, `BaoTranslateSettings.kt`, `VoiceEnrollmentSheet.kt` |

The feature plugs into the host app's custom-task system (`customtasks/common/CustomTask.kt`); it is
registered via Hilt `@IntoSet` and runs standalone within the app shell.

## Build, test, run

```bash
cd src

# Use Android Studio's bundled JBR (JDK 21) — matches Gradle 8.10.2 / AGP 8.8.2.
# The system JDK (26) breaks the Gradle build.
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

./gradlew :app:assembleDebug              # build the debug APK
./gradlew :app:testDebugUnitTest          # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # on-device E2E (translation, live-mic, cloning, languages)

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

* `applicationId = com.bao.translate`; `compileSdk = 35`, `minSdk = 31` (Android 12).
* `app/libs/` vendors the `sherpa-onnx` AAR. It and `onnxruntime-android` both ship
  `libonnxruntime.so` (the same ORT 1.24.3 build); the duplicate is resolved at packaging via
  `jniLibs.pickFirsts`. ORT 1.24.3 is pinned because sherpa's JNI binds the ELF-versioned
  `OrtGetApiBase@VERS_1.24.3` symbol.

## On-device E2E

Instrumented tests under `app/src/androidTest/` drive the real pipeline on a connected device
(`BaoTranslate*E2eTest`): the language matrix, live-mic translation, spoken translation, and
OpenVoice voice cloning. The model stack must be provisioned on the device first (downloaded in-app
or pushed via `adb`); `adb uninstall` clears provisioned models, but `install -r` preserves them.

## License

Apache-2.0. Bao Translate is a derivative of [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery);
upstream copyright and the Apache-2.0 license are retained. See [`../LICENSE`](../LICENSE).
