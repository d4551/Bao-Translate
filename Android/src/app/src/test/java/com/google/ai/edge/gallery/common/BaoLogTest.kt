package com.google.ai.edge.gallery.common

import com.google.ai.edge.gallery.testkit.BaoStrictRules
import com.google.ai.edge.gallery.testkit.CorpusFixture
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Category(Strict::class)
class BaoLogTest {

  @Test
  fun `normalize keeps short tags unchanged`() {
    assertEquals("BaoTranslateVM", BaoLog.normalize("BaoTranslateVM"))
    assertEquals("A", BaoLog.normalize("A"))
  }

  @Test
  fun `normalize truncates tags longer than TAG_MAX`() {
    val long = "X".repeat(50)
    val out = BaoLog.normalize(long)
    assertEquals(BaoLog.TAG_MAX, out.length)
    assertTrue(out.all { it == 'X' })
  }

  @Test
  fun `normalize preserves boundary at TAG_MAX`() {
    val exact = "B".repeat(BaoLog.TAG_MAX)
    assertEquals(exact, BaoLog.normalize(exact))
  }

  @Test
  fun `TAG_MAX is 23 to match Android API 24 limit`() {
    // Android 7.0+ silent log drop threshold.
    assertEquals(23, BaoLog.TAG_MAX)
  }

  // ----- BRUTALISATION -----

  // ----- Codepoint-aware truncation: CJK has no surrogate pairs, so length == codepoints.
  @Test
  fun `normalize_unicodeTruncation_usesCodepointCount`() {
    val cjk = CorpusFixture.cjk.repeat(20) // 20 codepoints, 20 UTF-16 units, 40 chars total
    val out = BaoLog.normalize(cjk)
    // Production now truncates at codepoint boundary, not UTF-16 unit boundary.
    assertEquals("tag length is TAG_MAX codepoints", BaoLog.TAG_MAX, out.codePointCount(0, out.length))
    assertEquals("CJK has no surrogate pairs; codepoint count == UTF-16 count",
      out.length, out.codePointCount(0, out.length))
  }

  // ----- Surrogate pairs: truncation must never split a pair.
  @Test
  fun `normalize_surrogatePairTruncation_noBrokenSurrogates`() {
    val input = CorpusFixture.emoji.repeat(30) // 30 codepoints — must truncate to TAG_MAX
    val out = BaoLog.normalize(input)
    BaoStrictRules.assertNoBrokenSurrogates(out, "normalized tag")
    assertEquals("truncated to TAG_MAX codepoints", BaoLog.TAG_MAX, out.codePointCount(0, out.length))
  }

  // ----- Edge: empty string input.
  @Test
  fun `normalize_emptyString_returnsEmpty`() {
    assertEquals("", BaoLog.normalize(""))
  }

  // ----- Edge: single char.
  @Test
  fun `normalize_singleChar_unchanged`() {
    assertEquals("X", BaoLog.normalize("X"))
  }

  // ----- Edge: TAG_MAX - 1 (one below the cutoff).
  @Test
  fun `normalize_justBelowMax_unchanged`() {
    val input = "A".repeat(BaoLog.TAG_MAX - 1)
    assertEquals(input, BaoLog.normalize(input))
  }

  // ----- Edge: TAG_MAX + 1 (one over the cutoff).
  @Test
  fun `normalize_justOverMax_truncated()`() {
    val input = "A".repeat(BaoLog.TAG_MAX + 1)
    val out = BaoLog.normalize(input)
    assertEquals(BaoLog.TAG_MAX, out.length)
    assertTrue(out.all { it == 'A' })
  }

  // ----- Newline characters in tags are replaced with '_' so Android 7+ does not drop them.
  @Test
  fun `normalize_newlineStripped`() {
    val withNewline = "Tag\nWithNewline"
    val out = BaoLog.normalize(withNewline)
    assertEquals("Tag_WithNewline", out)
  }

  // ----- Control characters in tags are replaced with '_'.
  @Test
  fun `normalize_controlCharsStripped`() {
    val input = "Tag\u0000With\u0001Ctrl"
    val out = BaoLog.normalize(input)
    assertEquals("Tag_With_Ctrl", out)
  }

  // ----- Determinism: normalize is a pure function. Call twice, get same result.
  @Test
  fun `normalize_deterministic`() {
    val input = "SomeVeryLongTagNameThatExceedsTheLimitForSure"
    assertEquals(BaoLog.normalize(input), BaoLog.normalize(input))
  }

  // ----- Boundary around codepoint-pair safety: emoji at exact positions.
  @Test
  fun `normalize_emojiAtBoundary_documentedBehavior`() {
    // 11 emoji = 22 UTF-16 units, +1 char to push to 23. The 23rd char is a high
    // surrogate alone (since each emoji is 2 units and TAG_MAX=23 is odd).
    val input = CorpusFixture.emoji.repeat(11) + "X"
    val out = BaoLog.normalize(input)
    assertEquals(BaoLog.TAG_MAX, out.length)
    // First 22 chars: 11 complete emoji (22 units). 23rd char: 'X'.
    // Verify the trailing char.
    assertEquals("trailing single char preserved", 'X', out[22])
    // First 22 chars are 11 complete emoji pairs — verify no half-surrogate.
    var i = 0
    while (i < 22) {
      assertTrue("emoji pair at i=$i is a high surrogate", out[i].isHighSurrogate())
      assertTrue("emoji pair at i=${i + 1} is a low surrogate", out[i + 1].isLowSurrogate())
      i += 2
    }
  }

  // ----- BaoStrictRules sanity check: every helper compiles and runs against BaoLog.
  @Test
  fun `strictHelpers_compileAndRun`() {
    // Sanity: the cross-version codepoint-count helper matches `String.length` for ASCII.
    BaoStrictRules.assertCodepointCount("Hello", 5, "ascii-hello")
    // And matches for non-ASCII when surrogate-free.
    BaoStrictRules.assertCodepointCount("中文", 2, "cjk")
    // And differs when surrogates are present.
    BaoStrictRules.assertCodepointCount("🎉", 1, "emoji-one-codepoint-2-units")
    // The string `🎉` has length 2 but codepoint count 1.
    assertEquals(2, "🎉".length)
    assertEquals(1, "🎉".codePointCount(0, "🎉".length))
  }
}
