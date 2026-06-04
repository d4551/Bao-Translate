<p align="center">
  <img src="bao-translate/appstore.png" alt="Bao Translate" width="128" height="128" />
</p>

<h1 align="center">Bao Translate</h1>

<p align="center"><strong>Private, on-device real-time speech translation and AI chat for Android — fully offline.</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" /></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84.svg" />
  <a href="https://ai.google.dev/edge"><img alt="Built on Google AI Edge" src="https://img.shields.io/badge/built%20on-Google%20AI%20Edge%20%7C%20LiteRT-4285F4.svg" /></a>
  <a href="https://github.com/d4551/bao-translate/releases"><img alt="Release" src="https://img.shields.io/github/v/release/d4551/bao-translate?include_prereleases" /></a>
</p>

---

Bao Translate runs a complete speech-translation pipeline **entirely on your device** — voice activity detection, speech-to-text, machine translation, and text-to-speech — with no servers and nothing leaving your phone. On top of translation it ships a full on-device AI workspace: chat with agent skills, tools, MCP servers, and function calling, plus model management and hardware benchmarking.

It is built on the open-source [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) foundation (Apache-2.0) and the [LiteRT](https://ai.google.dev/edge/litert) runtime — see [Built on](#-built-on).

## How it works

Audio flows through a fully local pipeline; the live **Conversation mode** then relays each translated turn to a paired phone over Bluetooth LE and speaks it aloud, so both speakers hear the other side in their own language:

```
mic ─▶ VAD (Silero) ─▶ STT (Whisper · Sherpa-ONNX) ─▶ Translation (Qwen2.5-1.5B · LiteRT-LM) ─▶ TTS (Kokoro) ─▶ speaker
                                                                                                  └─▶ BLE peer ─▶ speaker
```

## Screenshots

<p align="center">
  <img src="docs/screenshots/01-translate-home.png" alt="Bao Translate home — on-device model stack and Bluetooth audio routing" width="280" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/02-benchmark.png" alt="On-device model benchmarking — GPU/CPU, prefill/decode tokens" width="280" />
</p>

<p align="center"><em>Left: translation home with the on-device model stack and Bluetooth audio routing. Right: on-device model benchmarking.</em></p>

## ✨ Features

* **Real-time speech translation** — a complete on-device VAD → STT → translation → TTS pipeline.
* **Live Conversation mode** — translated turns sync to a peer device over Bluetooth LE and play aloud on both ends.
* **100% on-device & private** — all inference runs on your hardware; no internet required, nothing uploaded.
* **AI Chat** — multi-turn chat with **agent skills**, tool use, **MCP server** integration (see [`mcp/`](mcp/)), and **function calling** (see [Function_Calling_Guide.md](Function_Calling_Guide.md)).
* **Model management & benchmarking** — download curated models, load your own, and benchmark them on GPU/CPU with configurable prefill/decode tokens.
* **Bluetooth audio routing** — pick headset mic and output devices directly in-app.
* **Plus the AI Edge Gallery toolset** — Ask Image, Prompt Lab, Audio Scribe, and more, inherited from the upstream foundation.

## On-device models

Downloaded in-app from Hugging Face on first run:

| Role | Model | Size |
| --- | --- | --- |
| Voice activity detection | Silero VAD | 2 MB |
| Speech-to-text | Whisper Base (Sherpa-ONNX) | 148 MB |
| Translation | Qwen2.5 1.5B (LiteRT-LM compact) | 1523 MB |
| Text-to-speech | Kokoro Multi-Lang | 142 MB |

## 🏁 Getting started

**Requirements:** Android 12 (API 31) or newer.

1. Install the latest APK from [**Releases**](https://github.com/d4551/bao-translate/releases), or build from source (below).
2. On first launch, tap **Download All Models** (or download individually) to fetch the on-device model stack.
3. Open **Conversation mode**, pick your audio devices, and start translating.

## 🛠️ Build from source

This is a standard Gradle Android project rooted at [`Android/src`](Android/src).

```bash
cd Android/src

# Use Android Studio's bundled JBR (JDK 21) — matches Gradle 8.10.2 / AGP 8.8.2
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

./gradlew :app:assembleDebug          # compile + build the debug APK
./gradlew :app:testDebugUnitTest      # run unit tests

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.bao.translate -c android.intent.category.LAUNCHER 1
```

* SDK: `compileSdk = 35`, `minSdk = 31`; `applicationId = com.bao.translate`.
* See [DEVELOPMENT.md](DEVELOPMENT.md) for more.

## 📁 Project structure

| Path | What |
| --- | --- |
| [`Android/`](Android/) | The Android app (Gradle project under `Android/src`) |
| [`bao-translate/`](bao-translate/) | Brand assets and app-icon sets |
| [`mcp/`](mcp/) | Model Context Protocol server integration and docs |
| [`skills/`](skills/) | Agent skill packs |
| [`model_allowlists/`](model_allowlists/), [`model_allowlist.json`](model_allowlist.json) | Curated model allowlist |
| [`docs/`](docs/) | Screenshots and documentation |

## 🧩 Built on

Bao Translate stands on excellent open-source work — thank you to:

* [**Google AI Edge Gallery**](https://github.com/google-ai-edge/gallery) — the app foundation (Apache-2.0)
* [**LiteRT** / **LiteRT-LM**](https://github.com/google-ai-edge/LiteRT-LM) — on-device model runtime
* [**sherpa-onnx**](https://github.com/k2-fsa/sherpa-onnx) — on-device STT/TTS engine
* [**Whisper**](https://github.com/openai/whisper) — speech recognition
* [**Qwen2.5**](https://github.com/QwenLM/Qwen2.5) — translation model
* [**Kokoro**](https://huggingface.co/hexgrad/Kokoro-82M) — multilingual TTS
* [**Silero VAD**](https://github.com/snakers4/silero-vad) — voice activity detection
* [**Hugging Face**](https://huggingface.co/litert-community) — model hosting

## 🤝 Feedback & contributing

* 🐞 **Found a bug?** [Report it](https://github.com/d4551/bao-translate/issues/new?template=bug_report.md) (see also the [Bug Reporting Guide](Bug_Reporting_Guide.md)).
* 💡 **Have an idea?** [Suggest a feature](https://github.com/d4551/bao-translate/issues/new?template=feature_request.md).
* See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE). Bao Translate is a derivative of Google AI Edge Gallery; upstream copyright and the Apache-2.0 license are retained.
