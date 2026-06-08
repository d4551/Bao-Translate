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
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies the [BaoStrictTest] harness is wired correctly. If this test class fails to
 * compile or its @Rule is missing, every other strict test inherits the same breakage —
 * fail fast here.
 */
@Strict
class BaoStrictTest {

  @Test
  fun corpus_cjk_isNotEmpty_andHasNoSurrogates() {
    assertFalse(CorpusFixture.cjk.isEmpty())
    BaoStrictRules.assertNoBrokenSurrogates(CorpusFixture.cjk, "cjk")
  }

  @Test
  fun corpus_emoji_surrogatePair_preserved() {
    val s = CorpusFixture.emoji.repeat(20)
    // 20 codepoints × 2 UTF-16 units = 40 .length
    assertEquals(40, s.length)
    // But only 20 codepoints
    assertEquals(20, s.codePointCount(0, s.length))
    BaoStrictRules.assertNoBrokenSurrogates(s, "emoji x20")
  }

  @Test
  fun corpus_cyrillicFiller_notEqualToAsciiFiller() {
    // The whole point: regex like `^hmm$` won't catch this.
    assertNotNull(CorpusFixture.cyrillicFiller)
    assertFalse(CorpusFixture.cyrillicFiller == "hmm")
  }

  @Test
  fun corpus_utf8Hostile_roundTrips() {
    val s = CorpusFixture.utf8Hostile
    assertTrue(s.contains("Hello"))
    assertTrue(s.contains("Bonjour"))
    assertTrue(s.contains("дравствуй"))
    assertTrue(s.contains("хмм"))
  }

  @Test
  fun assertFiniteFloats_passesOnReal() {
    val ok = floatArrayOf(0f, 1f, -1f, 1e-6f, 1e6f, Float.MIN_VALUE, Float.MAX_VALUE)
    BaoStrictRules.assertFiniteFloats(ok, "realistic")
  }

  @Test
  fun assertFiniteFloats_failsOnNaN() {
    val bad = floatArrayOf(0f, 1f, Float.NaN)
    try {
      BaoStrictRules.assertFiniteFloats(bad, "nan-test")
      fail("expected AssertionError on NaN")
    } catch (e: AssertionError) {
      assertTrue(e.message!!.contains("index 2"))
    }
  }

  @Test
  fun assertFiniteFloats_failsOnInf() {
    val bad = floatArrayOf(0f, Float.POSITIVE_INFINITY, 1f)
    try {
      BaoStrictRules.assertFiniteFloats(bad, "inf-test")
      fail("expected AssertionError on Inf")
    } catch (e: AssertionError) {
      assertTrue(e.message!!.contains("index 1"))
    }
  }

  @Test
  fun assertNoEmptyAfterNormalize_passesOnRealText() {
    BaoStrictRules.assertNoEmptyAfterNormalize("Hello, world.")
  }

  @Test
  fun assertNoEmptyAfterNormalize_failsOnWhitespaceAfterStrip() {
    try {
      BaoStrictRules.assertNoEmptyAfterNormalize("$nbsp$nbsp")
      fail("expected AssertionError on NBSP-only input")
    } catch (e: AssertionError) {
      assertTrue(e.message!!.contains("collapsed"))
    }
  }

  @Test
  fun assertNoBrokenSurrogates_passesOnHealthyString() {
    BaoStrictRules.assertNoBrokenSurrogates("Hello 🎉 中文", "healthy")
  }

  @Test
  fun whitespaceTorture_doesNotPanic_onAnyEntry() {
    CorpusFixture.whitespaceTorture.forEach { s ->
      // Document current contract: codePointCount must not throw, .trim() must not throw
      s.codePointCount(0, s.length)
      s.trim()
    }
  }
}
