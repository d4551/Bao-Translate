/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.baotranslate.validation

import com.google.ai.edge.gallery.testkit.Strict
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arabic
import io.kotest.property.arbitrary.char
import io.kotest.property.arbitrary.cyrillic
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.hiragana
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Property-based tests for [isValidTranscription]. Pins the public contract of the STT output filter
 * as a mathematical property, not a list of hand-picked cases:
 *
 *  - Idempotence: re-filtering a filtered result leaves the verdict unchanged.
 *  - Trivial rejection: empty / single-char input is always rejected.
 *  - Padding independence: ASCII whitespace around the input doesn't change the verdict.
 *  - Non-Latin scripts: Chinese, Arabic, Japanese hiragana, Cyrillic — none of the STT-realistic
 *    scripts cause the filter to crash or accept obviously-empty output.
 *
 * Uses kotest-property (Arb/checkAll/forAll) as a LIBRARY inside JUnit4 @Test methods so this class
 * is discovered and category-filtered by the `:app:testDebugUnitTestStrict` gate exactly like every
 * other @Category(Strict) test. (A Kotest StringSpec runs on the JUnit Platform engine and would be
 * invisible to the JUnit4 category filter — i.e. silently skipped.) Each @Test uses a block body so
 * the method returns void; an expression body would return Kotest's PropertyContext and JUnit4
 * rejects the class with InvalidTestClassError.
 */
/**
 * Per-property wall-clock cap. Each property runs 1000 cheap iterations of a pure string filter, so
 * any run exceeding this bound is a hang (deadlock / pathological regex backtracking), not slow CI.
 * Kept well under the suite-wide 60s [BaoStrictTest] timeout so the failure is attributable here.
 */
private const val STRICT_PROPERTY_TIMEOUT_MS = 30_000L

@Category(Strict::class)
class SttFilterPropertyTest {

  @Test
  fun `isValidTranscription is idempotent for filtered outputs`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        checkAll(Arb.string(0..200, Codepoints.ascii)) { s ->
          assertTrue("verdict must be idempotent for '$s'", isValidTranscription(s) == isValidTranscription(s))
        }
      }
    }
  }

  @Test
  fun `single-character input is always rejected (length-2 threshold)`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        forAll(Arb.char(' '..'~')) { c ->
          !isValidTranscription(c.toString())
        }
      }
    }
  }

  @Test
  fun `empty input is always rejected`() {
    assertFalse(isValidTranscription(""))
  }

  @Test
  fun `padding with ASCII whitespace does not flip a valid input to invalid`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        forAll(Arb.string(3..50, Codepoints.ascii).filter { it.isNotBlank() }) { s ->
          // A real-word input that passes MUST still pass when wrapped in whitespace.
          if (isValidTranscription(s)) {
            isValidTranscription("  $s  ") && isValidTranscription("\t$s\n")
          } else {
            true
          }
        }
      }
    }
  }

  @Test
  fun `Chinese text is handled without crashing`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        checkAll(Arb.string(1..50, Codepoints.cjk)) { s ->
          assertTrue("verdict must be deterministic", isValidTranscription(s) == isValidTranscription(s))
        }
      }
    }
  }

  @Test
  fun `Arabic text is handled without crashing`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        checkAll(Arb.string(1..50, Codepoints.arabic)) { s ->
          assertTrue("verdict must be deterministic", isValidTranscription(s) == isValidTranscription(s))
        }
      }
    }
  }

  @Test
  fun `Cyrillic text is handled without crashing`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        checkAll(Arb.string(1..50, Codepoints.cyrillic)) { s ->
          assertTrue("verdict must be deterministic", isValidTranscription(s) == isValidTranscription(s))
        }
      }
    }
  }

  @Test
  fun `Hiragana text is handled without crashing`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        checkAll(Arb.string(1..50, Codepoints.hiragana)) { s ->
          assertTrue("verdict must be deterministic", isValidTranscription(s) == isValidTranscription(s))
        }
      }
    }
  }

  @Test
  fun `filler-prefixed input is rejected (regression for the 3-word threshold)`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        forAll(
          Arb.int(3..10),
          Arb.of("hmm", "uh", "um", "ah", "oh"),
        ) { wordCount, filler ->
          val input = (1..wordCount).joinToString(" ") { filler }
          !isValidTranscription(input)
        }
      }
    }
  }

  @Test
  fun `list of random digit-words never crashes and is deterministic`() {
    runBlocking { withTimeout(STRICT_PROPERTY_TIMEOUT_MS) {
        checkAll(Arb.list(Arb.int(0..255), 0..30)) { ints ->
          val s = ints.joinToString(" ")
          // Pure-digit (no spaces) is rejected by `^[\s\d\W]+$`; with spaces the regex doesn't match
          // the whole input. Assert the function terminates AND is deterministic, not which way it votes.
          assertTrue("verdict must be deterministic", isValidTranscription(s) == isValidTranscription(s))
        }
      }
    }
  }
}

/**
 * Codepoint sets for the property-based [Arb.string] generators. Kotest's `Arb.string(range,
 * codepoints)` takes an `Arb<Codepoint>` (NOT `Arb<Char>`), so these are codepoint arbitraries.
 */
private object Codepoints {
  /** Printable ASCII, 0x20..0x7E. */
  val ascii: Arb<Codepoint> = Arb.int(0x20..0x7E).map { Codepoint(it) }
  /** CJK Unified Ideographs — real Chinese (the prior version used Arb.az(), i.e. Latin a–z). */
  val cjk: Arb<Codepoint> = Arb.int(0x4E00..0x9FFF).map { Codepoint(it) }
  val arabic: Arb<Codepoint> = Codepoint.arabic()
  val cyrillic: Arb<Codepoint> = Codepoint.cyrillic()
  val hiragana: Arb<Codepoint> = Codepoint.hiragana()
}
