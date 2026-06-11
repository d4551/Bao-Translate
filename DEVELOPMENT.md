# Development Guide

This guide covers the local setup required to build, install, and verify Bao Translate from source.

## Prerequisites

- Android Studio with its bundled JBR / JDK 21.
- Android SDK platform tools on `PATH`.
- An Android 12 / API 31 or newer test device for end-to-end validation.
- A Hugging Face developer application for model download authentication.

## Configure Hugging Face OAuth

Model downloads require a Hugging Face OAuth application. Create one from the [Hugging Face OAuth documentation](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app), then update the Android project:

1. In [ProjectConfig.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt), replace the sample `clientId` and `redirectUri` values with the values from your Hugging Face application.
2. In [app/build.gradle.kts](Android/src/app/build.gradle.kts), set `manifestPlaceholders["appAuthRedirectScheme"]` to the redirect scheme configured for the same application.

Keep personal client IDs and secrets out of commits.

## Build Environment

```bash
cd Android/src

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
```

The Android Studio JBR is recommended because it matches the Gradle and Android Gradle Plugin expectations for this project. If your shell resolves a newer system JDK first, Gradle may fail before compilation starts.

## Common Commands

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:smokeE2e
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `assembleDebug` for a fast compile gate. Use unit tests for JVM-level behavior. Use connected Android tests and smoke tests for app flows, WebView skills, model provisioning, audio routing, voice cloning, and Nearby Connections.

## Verification Notes

- Full Bao Translate validation requires hardware because microphone capture, Bluetooth routing, and Nearby Connections cannot be fully represented by a software emulator.
- Model download tests need network access and enough device storage.
- Reinstalling with `adb install -r` preserves app data. Uninstalling the app clears provisioned models.
- If a test depends on previously downloaded models, document that precondition in the test or make the test self-provisioning.

## Documentation Standard

When changing docs, keep the top-level README approachable and detailed, keep Android-specific build details in [Android/README.md](Android/README.md), and keep operational guides focused on reproducible steps. This note captures the current project preference: first-class documentation, professional comments, clear badges, and an ELI5 introduction at the top of the README.
