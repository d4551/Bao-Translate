# ADR-001 — TinyGarden vendored compiled bundle

- Status: Accepted
- Date: 2026-06-09
- Owner: app maintainers (`Android/src/app/src/main/assets/tinygarden/`)
- Sunset: replaced or rebuilt from source before the next major release; tracked as a release blocker if the bundle ever needs a code change.

## Context

`Android/src/app/src/main/assets/tinygarden/main-K5DSW5YL.js` is a compiled, minified
Angular application shipped as a static asset. Its source tree is not in this
repository, so the artifact is not auditable or patchable line-by-line. A
security scan flagged three findings at the artifact level:

1. "Unsanitized HTML sink" — single `innerHTML` occurrence.
2. "`http://` resource load."
3. "`Math.random()` in id context."

## Analysis (2026-06-09)

All three findings were re-verified against the artifact:

- Every `http://` string in the bundle is either the Apache license header URL
  or a W3C XML namespace constant (`w3.org/1999/xhtml`, `2000/svg`, `1999/xlink`,
  `1998/Math/MathML`, `2000/xmlns/`, `XML/1998/namespace`). These are namespace
  identifiers used by Angular's renderer, never fetched as network resources.
- The single `innerHTML` occurrence is inside Angular's framework renderer,
  which routes application values through Angular's sanitizer. There is no
  application-level sink reachable with external input.
- `Math.random()` appears in framework-internal id generation, not in a
  security or token context.

Host-side mitigations in `TinyGardenScreen.kt`:

- `allowFileAccess = false`.
- All requests are intercepted and served only through `WebViewAssetLoader`
  (`AssetsPathHandler`) from APK-local assets; nothing loads from the network.
- No `@JavascriptInterface` bridge is exposed to this WebView.

## Decision

Keep the compiled bundle vendored, with this ADR as the governance exception
record ("canonical component used; no forked anatomy" gate). The artifact is
treated as read-only: no line-level fixes are applied to minified output.

## Consequences

- Any functional or security change to TinyGarden requires rebuilding from the
  upstream Angular source and replacing the whole artifact (filename hash
  changes with the build).
- If the upstream source becomes unavailable, this ADR's sunset clause makes
  removal or reimplementation of TinyGarden a release blocker.
- Scanners must classify this path as a compiled artifact (single
  "fix upstream" finding), not a line-level finding source.
