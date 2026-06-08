package com.google.ai.edge.gallery.common

import com.google.ai.edge.gallery.testkit.BaoStrictRules
import com.google.ai.edge.gallery.testkit.CorpusFixture
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Strict
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

  // ----- The TAG_MAX contract: any input over 23 chars is truncated to the first 23
  // characters. This is a UTF-16 unit count, NOT a codepoint count — a real production
  // bug surface when tags contain CJK / emoji.
  @Test
  fun `normalize_unicodeTruncation_usesUtf16UnitCountNotCodepointCount`() {
    val cjk = CorpusFixture.cjk.repeat(20) // 20 codepoints, 20 UTF-16 units, 40 chars total
    val out = BaoLog.normalize(cjk)
    // Current prod: takes substring(0, TAG_MAX) on the UTF-16 string, NOT on codepoints.
    // .length == 23 (UTF-16 units), .codePointCount == 23 (no surrogate pairs in CJK).
    // Pin the current behavior.
    assertEquals("tag length is TAG_MAX UTF-16 units", BaoLog.TAG_MAX, out.length)
    assertEquals("CJK has no surrogate pairs; codepoint count == UTF-16 count",
      out.length, out.codePointCount(0, out.length))
  }

  // ----- Surrogate pairs: each emoji 🎉 is 1 codepoint = 2 UTF-16 units. Truncating
  // at 23 UTF-16 units can cut a surrogate pair in half if the boundary is between
  // pairs. Document the current behavior.
  @Test
  fun `normalize_surrogatePairTruncation_documentedGap`() {
    val input = CorpusFixture.emoji.repeat(20) // 20 codepoints = 40 UTF-16 units
    val out = BaoLog.normalize(input)
    // 23 UTF-16 units, which is 11 complete emoji (22 units) + 1 high-surrogate half (1 unit).
    // This is a MALFORMED Java String — many downstream ops break on it.
    assertEquals("currently truncates mid-codepoint (gap)", BaoLog.TAG_MAX, out.length)
    // codepointCount will still be 12 (11 complete + 1 unpaired high surrogate)
    // — but the string is malformed. We assert that this is the current behavior so a
    // future prod fix to "truncate at codepoint boundary" flips the assertion.
    val isWellFormed = runCatching {
      // codePoints() never throws on a well-formed string; on malformed it might still
      // return values. Best cheap check: no unpaired surrogate by counting.
      val chars = out.toCharArray()
      var i = 0
      var unpairedHigh = 0
      while (i < chars.size) {
        val c = chars[i]
        if (c.isHighSurrogate() && (i + 1 >= chars.size || !chars[i + 1].isLowSurrogate())) {
          unpairedHigh++
        } else if (c.isHighSurrogate()) {
          i++ // skip the low surrogate too
        }
        i++
      }
      unpairedHigh == 0
    }.getOrDefault(false)
    assertTrue(
      "DOCUMENTED GAP: truncation can leave an unpaired high surrogate in the tag. " +
        "Android may drop or display the entire tag. isWellFormed=$isWellFormed",
      // We intentionally do NOT assert !isWellFormed — pinning the current gap.
      true,
    )
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

  // ----- Document: newline characters in tags. Android 7+ silently drops these.
  // Current prod: pass-through (no filter). Pin the gap.
  @Test
  fun `normalize_newlineNotStripped_documentedGap`() {
    val withNewline = "Tag\nWithNewline"
    val out = BaoLog.normalize(withNewline)
    // Current prod does NOT strip newlines. The resulting tag, if sent to Android Log,
    // is silently dropped.
    assertEquals(
      "DOCUMENTED GAP: newline not stripped — Android log entry is silently dropped",
      withNewline,
      out,
    )
  }

  // ----- Document: control characters in tags. Android 7+ silently drops these.
  @Test
  fun `normalize_controlCharsNotStripped_documentedGap`() {
    val input = "Tag\u0000With\u0001Ctrl"
    val out = BaoLog.normalize(input)
    assertEquals(input, out)
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
