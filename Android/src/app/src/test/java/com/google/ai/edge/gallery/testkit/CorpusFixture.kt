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
package com.google.ai.edge.gallery.testkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Golden corpora and strict-test rules shared across the BaoTranslate test suite.
 *
 * Purpose: strict tests should pull edge cases from here instead of inlining them. This keeps the
 * corpus auditable in one place and prevents each test from defining whitespace or hostile inputs
 * differently.
 */
object CorpusFixture {
  /** CJK ideographs (no surrogate pair, 3 bytes UTF-8, 1 UTF-16 unit). */
  val cjk: String = "中文"

  /** BMP+1 emoji (uses surrogate pair, 2 UTF-16 units, 1 codepoint). */
  val emoji: String = "🎉"

  /** Cyrillic homoglyph filler that fools ASCII-only regex filters. */
  val cyrillicFiller: String = "хмм"

  /** Latin-1 supplement characters, including NBSP and various dashes. */
  val nbsp: String = "\u00A0"
  val variousWhitespace: List<String> = listOf(" ", "\t", "\n", "\r", "\r\n", nbsp, "\u2003", "\u2009")

  /** A hostile UTF-8 text: CJK + emoji + accented Latin + Cyrillic. */
  val utf8Hostile: String =
    "Hello, ${cjk}! ${emoji} Bonjour. ¿Qué tal? ${cyrillicFiller} дравствуй"

  /** A JSON-shaped string that passes naive substring validators but isn't valid JSON. */
  val jsonPassesSubstringGateBroken: String =
    "{\"text\" \"senderId\" \"senderName\" \"sourceLanguage\" \"targetLanguage\"}"

  /** A JSON string with a trailing comma (invalid JSON). */
  val jsonTrailingComma: String =
    "{\"text\":\"hi\",\"senderId\":\"a\",\"senderName\":\"b\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"es\",}"

  /** A short-array of NaN/Inf floats, the kind a malformed BLE peer could send. */
  val nanFloats256: List<Float> = List(256) { Float.NaN }
  val infFloats256: List<Float> = List(256) { Float.POSITIVE_INFINITY }

  /** All ASCII letters — used as a fuzz seed for filter-property tests. */
  val asciiLetters: CharRange = 'A'..'z'

  /** A non-empty list of whitespace-torture strings for normalization edge cases. */
  val whitespaceTorture: List<String> = listOf(
    "",
    " ",
    "  ",
    "\t\t",
    "\n",
    "\r\n",
    nbsp,
    "$nbsp$nbsp",
    "  $nbsp  ",
    "\u2003", // EM SPACE
    "\u2009", // THIN SPACE
    "​", // ZERO WIDTH SPACE
  )
}

/**
 * JUnit4 rules for the strict test pyramid. Subclass [BaoStrictTest] to attach them
 * automatically; inline-instantiate [strictTimeout] in any class that can't extend.
 *
 *  - [strictTimeout] caps every test at 60s wall. Any silent stall past that is a test
 *    bug, not a slow CI runner — fail loudly.
 *  - [assertFiniteFloats] catches NaN/Inf propagation in audio / DSP code paths.
 *  - [assertNoEmptyAfterNormalize] asserts a non-blank input is still non-blank after
 *    trim/normalize, catching "looks like content but isn't" regressions.
 */
class BaoStrictRules {
  @JvmField
  val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  companion object {
    /**
     * Assert every element of [arr] is finite (not NaN, not Inf). On failure, includes
     * the index of the first non-finite element for actionable diagnostics.
     */
    fun assertFiniteFloats(arr: FloatArray, label: String) {
      for (i in arr.indices) {
        val v = arr[i]
        if (v.isNaN() || v.isInfinite()) {
          throw AssertionError("$label: non-finite at index $i (value=$v)")
        }
      }
    }

    /**
     * Assert [s] is non-blank AFTER normalize-style operations (trim + NBSP strip).
     * Catches "filter accepts '   ' as valid content" type regressions where
     * Unicode whitespace bypasses ASCII trim().
     */
    fun assertNoEmptyAfterNormalize(s: String) {
      val stripped = s.replace(CorpusFixture.nbsp, "").trim()
      assertNotNull("input was null", s)
      assertFalse("normalize collapsed '$s' to empty", stripped.isEmpty())
    }

    /**
     * Cross-version JDK codepoint count: handles surrogate pairs correctly.
     * `String.length` is UTF-16 unit count, not codepoint count — using the wrong one
     * is a real production bug surface (BaoLog truncation, JSON serialization, etc).
     */
    fun assertCodepointCount(s: String, expected: Int, label: String) {
      val got = s.codePointCount(0, s.length)
      assertEquals("$label: codepoint count", expected, got)
    }

    /**
     * Assert no broken surrogate halves: a malformed string where the high or low
     * surrogate is missing produces undefined behavior in many JVM string operations.
     */
    fun assertNoBrokenSurrogates(s: String, label: String) {
      val it = s.codePointCount(0, s.length)
      val cp = IntArray(it)
      var i = 0
      s.codePoints().toArray().forEachIndexed { idx, c -> cp[idx] = c }
      assertEquals("$label: codepoint array length", it, cp.size)
      // Every codepoint must be a valid Unicode scalar value.
      for (c in cp) {
        assertTrue(
          "$label: invalid Unicode codepoint U+${Integer.toHexString(c)}",
          c in 0..0x10FFFF && (c < 0xD800 || c > 0xDFFF),
        )
      }
    }
  }
}

/**
 * Base class for the strict JVM test pyramid. Sets a 60-second per-test timeout so a
 * regression that hangs the test loop (poll deadlock, missing resume, blocked I/O) fails
 * in CI rather than blocking the runner indefinitely.
 */
abstract class BaoStrictTest {
  @org.junit.Rule
  @JvmField
  val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout(60, java.util.concurrent.TimeUnit.SECONDS)
}
