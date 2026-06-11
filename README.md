<p align="center">
  <img src="bao-translate/appstore.png" alt="Bao Translate" width="128" height="128" />
</p>

<h1 align="center">Bao Translate</h1>

<p align="center"><strong>Private, on-device live translation that can speak in your own voice.</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License: Apache 2.0" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" /></a>
  <img alt="Platform: Android 12+" src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84.svg" />
  <img alt="Privacy: fully on-device" src="https://img.shields.io/badge/privacy-fully%20on--device-success.svg" />
  <img alt="Supported languages: 12" src="https://img.shields.io/badge/languages-12-orange.svg" />
  <img alt="Build: Gradle" src="https://img.shields.io/badge/build-Gradle-02303A.svg" />
  <img alt="Docs: ELI5 first" src="https://img.shields.io/badge/docs-ELI5%20first-purple.svg" />
  <img alt="Runtime: LiteRT and ONNX Runtime" src="https://img.shields.io/badge/runtime-LiteRT%20%7C%20ONNX%20Runtime-4285F4.svg" />
  <a href="https://github.com/d4551/bao-translate/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/d4551/bao-translate?include_prereleases" /></a>
</p>

## ELI5

Bao Translate is like having a tiny interpreter inside your phone. You speak, another person speaks, and the app says each side's words out loud in the other person's language. If you record a short voice sample, the translated speech can sound like you. It works offline, without accounts, and without uploading your voice or conversation.

## What Makes It Different

- **Your voice, across languages.** Enroll once and translations can be spoken with your timbre through on-device cross-lingual voice conversion.
- **Live captions while speech is still happening.** Streaming recognizers show words as they arrive instead of waiting for the entire sentence.
- **Speech output for every supported language.** Kokoro covers en/es/fr/hi/it/pt/zh, and the on-device Supertonic engine covers de/ja/ko/ru/ar.
- **A private local pipeline.** Voice activity detection, speech recognition, translation, TTS, and voice conversion all run on the device.
- **Conversation mode.** Two phones can pair over Nearby Connections so each person speaks their own language and hears the other in theirs.

## Product Tour

Bao Translate turns speech into translated speech with a local pipeline:

```text
                    live streaming caption
                    sherpa transducer (English) or Vosk (multilingual)
                                  ^
                                  |
microphone -> VAD -> STT -> translation -> TTS or voice conversion -> speaker
            Silero  Whisper   Qwen2.5       Kokoro, Supertonic, OpenVoice
                                                   |
                                                   v
                                            Nearby peer speaker
```

The live caption path is optimized for responsiveness. The Whisper pass produces the final transcript that drives translation quality. Voice cloning uses language-appropriate TTS for pronunciation, then OpenVoice converts the result into the enrolled speaker timbre.

## Supported Languages

English, Spanish, French, German, Italian, Portuguese, Russian, Chinese, Japanese, Korean, Arabic, and Hindi.

| Capability | Coverage |
| --- | --- |
| Translation | All 12 languages |
| Live captions | English via sherpa-onnx transducer; es/fr/de/zh/ja/ko/pt/it/ru/hi via Vosk; Arabic via Whisper fallback |
| Spoken output | Kokoro for en/es/fr/hi/it/pt/zh; Supertonic for de/ja/ko/ru/ar |
| Voice cloning | Any supported target language through OpenVoice tone-color conversion |

## Screenshots

<p align="center">
  <img src="docs/screenshots/01-translate-home.png" alt="Bao Translate home with model stack and Bluetooth audio routing" width="280" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/02-benchmark.png" alt="On-device model benchmarking screen" width="280" />
</p>

<p align="center"><em>Left: translation home with model status and audio routing. Right: on-device model benchmarking.</em></p>

## Features

- **Real-time speech translation** with a full on-device `VAD -> STT -> translation -> TTS` pipeline.
- **Multilingual streaming captions** in face-to-face and continuous translation modes.
- **Cross-lingual voice cloning** from a short enrolled sample, computed locally.
- **Live conversation relay** over Nearby Connections with speaker attribution.
- **Per-speaker language selection** for accurate source-language recognition.
- **Bluetooth audio routing** for headset microphones and output devices.
- **Model management** with curated downloads, resumable transfers, integrity checks, and benchmarking.
- **On-device LLM tools** inherited from the AI Edge Gallery foundation: AI Chat, Prompt Lab, Ask Image, Audio Scribe, Agent Skills, MCP integration, function calling, Mobile Actions, and Tiny Garden.

## Model Stack

Models are downloaded in-app from Hugging Face on first use.

| Role | Model | Approximate size |
| --- | --- | --- |
| Voice activity detection | Silero VAD | 2 MB |
| Speech-to-text | Whisper Base through sherpa-onnx | 148 MB |
| Streaming captions, English | Streaming Zipformer transducer through sherpa-onnx | 44 MB |
| Streaming captions, other languages | Vosk small models, one per language | 30-86 MB each |
| Translation | Qwen2.5 1.5B through LiteRT-LM, with Gemma optional | 1.5 GB |
| Text-to-speech | Kokoro Multi-Lang | 142 MB |
| Supplemental TTS | Supertonic TTS through sherpa-onnx | 80 MB |
| Voice conversion | OpenVoice tone converter and reference encoder through ONNX | 131 MB |

The English caption model and core translation stack are fetched up front. Non-English Vosk caption models are provisioned lazily the first time each language is used.

## Getting Started

Requirements:

- Android 12 / API 31 or newer.
- Enough local storage for the selected model stack.
- Optional Bluetooth headset or second Android device for advanced conversation testing.

Steps:

1. Install the latest APK from [Releases](https://github.com/d4551/bao-translate/releases), or build from source.
2. Launch the app and download the required models.
3. Optional: enroll your voice in settings.
4. Open Bao Translate, choose source and target languages, select audio devices, and start translating.

## Build From Source

The Android project is rooted at [Android/src](Android/src).

```bash
cd Android/src

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:smokeE2e

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Build notes:

- `applicationId = com.bao.translate`.
- `compileSdk = 37`; `minSdk = 31`; `targetSdk = 35`.
- The vendored `sherpa-onnx` AAR lives under `Android/src/app/libs/`.
- `sherpa-onnx` and `onnxruntime-android` both ship `libonnxruntime.so`; packaging de-duplicates the shared object with `jniLibs.pickFirsts`.
- See [DEVELOPMENT.md](DEVELOPMENT.md) for local setup, Hugging Face OAuth configuration, and verification commands.

## Repository Map

| Path | Purpose |
| --- | --- |
| [Android/](Android/) | Android application and Gradle project |
| [bao-translate/](bao-translate/) | Brand assets and app icon resources |
| [docs/](docs/) | Architecture decisions, screenshots, and supporting documentation |
| [mcp/](mcp/) | Model Context Protocol integration guide |
| [skills/](skills/) | Agent skill documentation and examples |
| [model_allowlists/](model_allowlists/) and [model_allowlist.json](model_allowlist.json) | Curated model allowlists |
| [Function_Calling_Guide.md](Function_Calling_Guide.md) | Guide for adding custom mobile actions |
| [Bug_Reporting_Guide.md](Bug_Reporting_Guide.md) | Android bug report capture guide |

## Documentation

- [Android app guide](Android/README.md)
- [Development setup](DEVELOPMENT.md)
- [Contribution policy](CONTRIBUTING.md)
- [Bug reporting](Bug_Reporting_Guide.md)
- [Function calling](Function_Calling_Guide.md)
- [MCP integration](mcp/README.md)
- [Agent skills](skills/README.md)
- [Model allowlists](model_allowlists/README.md)

## Quality And Verification

Recommended local gates before shipping changes:

```bash
cd Android/src
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:smokeE2e
```

Use a physical device for full translation, Bluetooth audio routing, voice cloning, and Nearby conversation validation. Emulator coverage is useful for compile, unit, and focused UI checks, but it does not replace hardware verification for the audio pipeline.

## Built On

Bao Translate builds on excellent open-source projects:

- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) for the app foundation.
- [LiteRT and LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) for local model execution.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) for on-device STT, streaming ASR, and TTS.
- [Vosk](https://github.com/alphacep/vosk-api) for multilingual streaming recognition.
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) for voice-conversion graphs.
- [Whisper](https://github.com/openai/whisper) for speech recognition.
- [Qwen2.5](https://github.com/QwenLM/Qwen2.5) for translation.
- [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) for multilingual TTS.
- [OpenVoice](https://github.com/myshell-ai/OpenVoice) for cross-lingual tone-color conversion.
- [Silero VAD](https://github.com/snakers4/silero-vad) for voice activity detection.
- [Hugging Face](https://huggingface.co/litert-community) for model hosting.

## Support And Contributing

- Found a bug? Open an issue with the [bug report template](https://github.com/d4551/bao-translate/issues/new?template=bug_report.md) and include the details from the [Bug Reporting Guide](Bug_Reporting_Guide.md).
- Have an idea? Use the [feature request template](https://github.com/d4551/bao-translate/issues/new?template=feature_request.md).
- Planning a code change? Read [CONTRIBUTING.md](CONTRIBUTING.md) first so expectations are clear.

## License

Bao Translate is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE). This project is derived from Google AI Edge Gallery; upstream copyright notices and Apache-2.0 licensing are retained.
