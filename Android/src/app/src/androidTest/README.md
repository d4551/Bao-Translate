# BaoTranslate androidTest (E2E) suite

This directory hosts the **end-to-end** test pyramid for BaoTranslate. Tests here require a
connected Android device or emulator and run via `./gradlew :app:connectedDebugAndroidTest`
(or `:app:smokeE2e` for the smoke subset).

## Hardware matrix

| Capability | Required for | Fallback |
|---|---|---|
| Android 13+ (API 33) | All tests | API 31+ for most |
| Bluetooth LE / SCO / A2DP | `BaoTranslateBluetoothAudioRoutingTest` | Tests skip with `Assume.assumeTrue` until the hardware matrix is enforced in CI. |
| Spanish TTS voice (es) | `BaoTranslateLanguageMatrixE2eTest`, `BaoTranslateSpokenTranslationE2eTest`, `BaoTranslateOpenVoiceCloneE2eTest` | Tests skip if device has no voice |
| German / Korean / Russian / Arabic TTS | `nonKokoroLanguages_useDevicePlatformTtsFallback` | Tests skip per-language |
| Microphone | `BaoTranslateLiveMicTranslationE2eTest` (also `BaoTranslateLivePipelineE2eTest`) | Test asserts on silence if mic is muted |
| Real Bluetooth headset | `BaoTranslateBluetoothAudioRoutingTest` | Skips when hardware is absent; still asserts graceful empty-list handling |
| ov_target_ref.wav (256-d human ref) | `openVoiceClonesToReferenceVoiceOnDeviceOrt` | Skip if not pushed to `filesDir/ov_target_ref.wav` |
| ONNX Runtime 1.24.3 (Android) | All OpenVoice tests | Tests fail clearly if runtime missing |

## Shared test harness

- `com.google.ai.edge.gallery.E2eStrictBase`: base class for androidTest E2E. Provides:
  - `RetryOnFlakeRule`: re-runs a test on `AssumptionViolatedException` only (real
    `AssertionError`s fail immediately).
  - `pollUntil(timeoutMs, intervalMs, block)`: replaces scattered
    `while(deadline) { Thread.sleep(500) }` loops.
  - `assertScreenshotValid(path, minBytes)`: replaces 3+ hand-rolled shell+ls blocks.
  - `parseRecorderPorts(dump)`: parses `dumpsys audio` output into a structured map.

## Rules of the road

1. **No skip-as-success.** Every test must assert real production behavior. Hardware
   fallbacks are allowed only when the production code itself handles the absence
   (e.g. `VadProcessor` falls back to energy-based segmentation when the model is
   missing).
2. **No `Thread.sleep` for synchronization.** Use `pollUntil { ... }` (UiAutomator tests) or
   `composeRule.waitUntil { ... }` (Compose tests).
   **`createAndroidComposeRule` requires freezing the animation clock on this app.** The screens run
   infinite "breathing"/pulse animations (`rememberInfiniteTransition`); with the default
   `autoAdvance = true`, `waitForIdle()` advances the clock until idle and an `infiniteRepeatable`
   never idles, so the test hangs. Two complementary guards now exist:
     - Production: every infinite animation is gated behind `isReducedMotion`
       (`LocalAnimationScale == 0f`), so with `animator_duration_scale=0` they don't register.
     - Test: add `@Before { composeRule.mainClock.autoAdvance = false }`; then `waitForIdle()` only
       waits on idling resources, not the clock, while state-driven `waitUntil(...)` still advances on
       real time. See `BaoTranslateLiveMicTranslationE2eTest` / `BaoTranslateScreenshotHarnessTest`.
   UiAutomator (`am start` + `device.wait(Until.hasObject(...))` + `BaoTranslateViewModel.testInstance`)
   remains the alternative for flows that do not need Compose semantics; see `BaoTranslatePeerVoiceE2eTest`.
3. **Round-trip tests must be stronger than "any overlap".** Use 50% of expected content,
   or 5% cosine margin. Avoid accepting a match on only one word.
4. **Real model downloads, not cached state.** The tests self-provision missing
   models via `BaoTranslateModelManager.downloadModel(...)`; this exercises the
   production download path.
5. **Clean up between tests.** `pipeline.cleanup()` belongs in a `finally` block. If a test
   uses a resource, release it so later tests start from a known state.
6. **Real Android permissions, not mocked.** Use `instrumentation.uiAutomation
   .grantRuntimePermission(packageName, ...)` in a `Rule.before()`. Mocks are for unit
   tests only.

## Running

```bash
# All E2E tests (slow; requires a connected device)
./gradlew :app:connectedDebugAndroidTest

# Smoke subset (faster; runs only SmokeE2eTest, not the full E2E matrix)
./gradlew :app:smokeE2e

# Single test
./gradlew :app:connectedDebugAndroidTest --tests "*BaoTranslateSpokenTranslation*"
```

## Adding a new E2E test

```kotlin
package com.google.ai.edge.gallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YourNewE2eTest {
  @Test
  fun yourNewCase() {
    // ... real assertions, real device, real model downloads
  }
}
```

Add a permission grant rule if your test needs new permissions.
