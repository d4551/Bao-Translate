# Engineering Plan And Status

This document tracks stabilization work for Bao Translate. It is intentionally scoped to decisions,
verified fixes, remaining follow-ups, and the commands needed to reproduce the current state.

## Current Direction

Bao Translate is being hardened around four priorities:

1. Preserve the privacy guarantee: speech, translation, TTS, and voice conversion remain on device.
2. Keep the audio pipeline reliable across microphone, speaker, Bluetooth, and Nearby conversation flows.
3. Maintain professional documentation and comments so the codebase is easy to audit.
4. Verify changes with the narrowest meaningful gate first, then broaden to device E2E when hardware is required.

## Completed Stabilization Work

| Area | Status | Notes |
| --- | --- | --- |
| Download pipeline | Complete | Streams and HTTP connections are closed deterministically on failure. |
| Recording lifecycle | Complete | Recording cancellation now stops the blocking loop and releases resources. |
| Skill imports | Complete | Partial imports are cleaned up and reported as validation failures. |
| Agent tools | Complete | Tool execution returns typed failure envelopes instead of throwing through the bridge. |
| Notification and deep-link safety | Complete | Schemes are allowlisted and sensitive values are not logged. |
| WorkManager observers | Complete | `observeForever` callbacks self-remove on terminal state. |
| User-facing strings | Complete | Previously hardcoded app text was moved to Android resources where appropriate. |
| Camera and download executors | Complete | Executor lifetimes were made explicit and leak-resistant. |
| Sample-rate constants | Complete | STT and TTS sample rates now come from `PipelineConfig`. |
| DataStore writes | Complete | Write paths are suspend-friendly and called from coroutine scopes. |
| DataStore reads | Partially complete | Startup-gate reads remain synchronous by design; VM-internal reads can continue moving to suspend or Flow. |

## Bao Translate Device Verification Summary

The following flows have been verified in prior hardware-backed passes:

- Required model downloads, extraction, and ready-state transitions.
- Translate screen rendering, language selection, voice enrollment entry point, audio device controls, and recording controls.
- Microphone capture engagement with real PCM input.
- Text translation through the LiteRT-LM pipeline.
- TTS playback through the production audio route.
- OpenVoice cloning and peer-voice attribution in dedicated device tests.
- Two-device Nearby conversation flow when both physical devices are available.

Hardware-specific verification should be repeated when code touches microphone capture, audio routing,
Bluetooth behavior, model provisioning, OpenVoice, or Nearby Connections.

## Build And Test Commands

```bash
cd Android/src
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:smokeE2e
```

Use `assembleDebug` as the fast compile gate. Use unit tests for JVM behavior. Use connected Android tests
for microphone, Bluetooth, model inference, screenshots, and Nearby flows.

## Remaining Follow-Ups

| ID | Item | Rationale | Recommended path |
| --- | --- | --- | --- |
| Q1 | Complete `Outcome` / `AppError` adoption | Error-shape changes affect downstream `when` handling. | Migrate one file at a time and compile after each file. |
| Q2 | Split large Compose and ViewModel files | Several files exceed 1,000 lines and are harder to review safely. | Extract cohesive sections behind existing state boundaries. |
| Q3 | Consolidate camera bind-and-log helpers | Similar logic exists in camera-enabled UI paths. | Extract only after confirming identical lifecycle requirements. |
| Q4 | Moshi KSP migration | Remaining build-tooling warning. | Treat as a focused dependency/build-system change. |
| Q5 | ADRs for prompt and media-routing decisions | Some upstream follow-ups are product or architecture decisions. | Capture decision context before code changes. |

## Documentation Standard

Project documentation should be professional, reproducible, and honest about verification. Prefer:

- A short plain-language explanation before deep technical detail.
- Commands that can be copied and run from the documented working directory.
- Explicit hardware, storage, network, and model prerequisites.
- Clear separation between verified behavior, deferred work, and future decisions.
- Comments that explain non-obvious intent rather than narrating the code.
