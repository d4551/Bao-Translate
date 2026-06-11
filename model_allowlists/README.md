# Model Allowlists

This directory contains versioned model allowlists used by Bao Translate and the underlying AI Edge Gallery model-management flows. The root [model_allowlist.json](../model_allowlist.json) is the active default allowlist; files in this directory preserve release-specific snapshots.

## Purpose

Allowlists define which model entries the app can display, download, and configure. They help keep the model picker predictable, ensure curated metadata ships with each app version, and make release-to-release model changes auditable.

## File Naming

| Pattern | Meaning |
| --- | --- |
| `1_0_15.json` | Android app release allowlist snapshot. |
| `ios_1_0_0.json` | iOS-specific allowlist snapshot. |
| `README.md` | This guide. |

## Maintenance Guidelines

- Keep release snapshots immutable after publishing.
- Add new snapshots instead of rewriting old release files.
- Validate JSON syntax before shipping.
- Keep model identifiers, download URLs, sizes, and default runtime settings aligned with the app code that consumes them.
- Review any gated, private, or license-sensitive model entry before exposing it in the app.

## Verification

After changing the active allowlist, build and run the model-management flow:

```bash
cd ../Android/src
./gradlew :app:assembleDebug
```

For model download or runtime changes, verify on a physical device with the target model selected.
