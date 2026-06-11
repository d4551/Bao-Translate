# Contributing To Bao Translate

Thank you for wanting to improve Bao Translate. The repository is currently in a maintainer-led stabilization phase, so broad public code contributions are not open yet. Issues, diagnostics, documentation corrections, and focused discussion are still welcome.

## Current Contribution Status

| Contribution type | Status | Notes |
| --- | --- | --- |
| Bug reports | Open | Use the issue template and include device details. |
| Feature requests | Open | Explain the user problem, not only the proposed UI. |
| Documentation fixes | Welcome | Small corrections are the easiest contributions to review. |
| Code pull requests | By coordination | Please open an issue or discussion before investing in a large patch. |
| Security reports | Coordinate privately | Do not publish exploit details in a public issue. |

## Before Opening An Issue

1. Search existing issues and discussions.
2. Confirm the behavior on the latest available release or current source build.
3. Capture logs or a full Android bug report when the issue involves crashes, audio routing, model downloads, Bluetooth, or on-device inference.
4. Remove private information from screenshots and logs before posting publicly.

The [Bug Reporting Guide](Bug_Reporting_Guide.md) explains how to capture a complete Android bug report.

## Development Expectations

The Android project lives in [Android/src](Android/src). Local changes should be validated with:

```bash
cd Android/src
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

For work that touches audio, translation, model loading, voice cloning, or Nearby Connections, also run device-backed validation:

```bash
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:smokeE2e
```

## Code Quality Bar

Changes should be small, reviewable, and consistent with the existing app architecture.

- Keep user-facing text in Android string resources.
- Preserve on-device privacy guarantees.
- Avoid logging secrets, prompts, voice data, deep-link values, or raw model output unless explicitly required for a diagnostic path.
- Prefer typed results and explicit validation at app boundaries.
- Keep model paths, archive extraction, and file imports guarded against traversal and symlink issues.
- Include tests or a clear manual verification note for behavior changes.

## Pull Request Notes

When pull requests are open for an area, include:

- A short description of the problem and solution.
- Screenshots or screen recordings for visible UI changes.
- Device model and Android version for hardware-tested changes.
- Commands run, including failures or skipped tests.
- Any follow-up work intentionally left out of scope.

## Documentation Tone

Project documentation should be clear, professional, and specific. Prefer concrete behavior, commands, and verification steps over broad claims. Keep the ELI5 explanation approachable, and keep technical sections precise enough for maintainers to reproduce your setup.
