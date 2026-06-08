# BaoTranslate unit test pyramid

This directory hosts the **strict** JVM test pyramid for BaoTranslate. Tests here run in plain
JVM (no Android device required) and gate every commit via `./gradlew :app:testDebugUnitTest`.

## Layers

| Layer | Marker | Purpose | Gating |
|---|---|---|---|
| Strict (CI) | `@Category(Strict::class)` | Production-path verification, no skip-if-no-hardware, no `Thread.sleep` padding, deterministic outcomes | `:app:testDebugUnitTestStrict` |
| Soft (legacy) | _(none)_ | The original test set kept for backward compatibility | `:app:testDebugUnitTest` |

The strict subset is the release gate. New tests must be `@Category(Strict::class)`.

## Shared test harness

- `com.google.ai.edge.gallery.testkit.BaoStrictTest` — base class with a 60s/test
  timeout. Subclass to attach it.
- `com.google.ai.edge.gallery.testkit.BaoStrictRules` — assertion helpers:
  `assertFiniteFloats`, `assertNoEmptyAfterNormalize`, `assertNoBrokenSurrogates`,
  `assertCodepointCount`.
- `com.google.ai.edge.gallery.testkit.CorpusFixture` — golden corpora: CJK, emoji,
  Cyrillic homoglyphs, NBSP, malformed JSON, NaN/Inf float arrays. Reuse instead of
  inlining edge cases.
- `com.google.ai.edge.gallery.testkit.Strict` — JUnit4 `@Category` annotation
  marking a test as part of the strict subset.

## Rules of the road

1. **Never skip a case the production code must handle.** No `Assume.assumeTrue` to dodge
   missing hardware or empty fixtures — the production code must handle them, the test
   must assert that.
2. **No fake success toasts as a substitute for real assertions.** Each test must check
   actual production behavior, not "did the test code run to the end".
3. **No `Thread.sleep` for synchronization.** Use `composeRule.waitUntil { ... }` for
   poll-based waits, or proper `kotlinx.coroutines` mechanisms.
4. **No `try/catch` in tests** to "make them pass" — if a test catches a known exception,
   it must re-throw or document the gap explicitly.
5. **Run a property-based generator at least once per release.** The
   `SttFilterPropertyTest.kt` uses Kotest to fuzz the STT filter — extend the property
   surface when adding new filters.
6. **Use shared corpora.** New edge cases go in `CorpusFixture.kt`, not inlined. The
   corpus is auditable in one place.
7. **Document gaps, don't paper over them.** If production has a real bug, add a
   test that demonstrates it AND file an entry in `out_of_scope_findings.txt`. The
   test will fail; that is the correct outcome.

## Adding a new strict test

```kotlin
package com.google.ai.edge.gallery.your.pkg

import com.google.ai.edge.gallery.testkit.BaoStrictTest
import com.google.ai.edge.gallery.testkit.CorpusFixture
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Test

@Strict
class YourNewTest : BaoStrictTest() {
  @Test
  fun yourNewCase() {
    // ... real assertions, no try/catch, no skip
  }
}
```

The new test runs in both `:app:testDebugUnitTest` and `:app:testDebugUnitTestStrict`.

## Running

```bash
# All unit tests (slow)
./gradlew :app:testDebugUnitTest

# Strict subset only (release gate, faster)
./gradlew :app:testDebugUnitTestStrict

# Single test
./gradlew :app:testDebugUnitTest --tests "*BaoLogTest.normalize*"
```
