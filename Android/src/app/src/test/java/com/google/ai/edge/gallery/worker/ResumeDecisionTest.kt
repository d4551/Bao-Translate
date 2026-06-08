package com.google.ai.edge.gallery.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the resumed-download corruption fix: append ONLY when the server honored the range (206).
 * A 200 OK after a Range request means the range was ignored and the full body is being sent from
 * byte 0 — appending it onto the partial would corrupt the file, so the decision must be overwrite.
 */
class ResumeDecisionTest {

  @Test
  fun resume_appendsAndTakesTotalFromContentRange_on206() {
    val d = resolveResumeDecision(206, "bytes 100-499/500", /* contentLength = */ 400)
    assertTrue("206 honored the range -> append", d.append)
    assertEquals("expected size is the /<total> of Content-Range", 500L, d.expectedFinalSize)
  }

  @Test
  fun rangeIgnored_overwritesNotAppends_on200() {
    // The corruption case: a partial existed (Range sent) but the server replied 200 with the FULL
    // body from byte 0. Must NOT append.
    val d = resolveResumeDecision(200, /* contentRange = */ null, /* contentLength = */ 500)
    assertFalse("200 ignored the range -> overwrite", d.append)
    assertEquals("expected size is Content-Length of the full body", 500L, d.expectedFinalSize)
  }

  @Test
  fun sizeCheckDisabled_whenContentRangeTotalUnknown_on206() {
    val d = resolveResumeDecision(206, "bytes 0-99/*", /* contentLength = */ 100)
    assertTrue(d.append)
    assertEquals("unknown total ('*') disables the size check", -1L, d.expectedFinalSize)
  }

  @Test
  fun sizeCheckDisabled_whenChunked_on200() {
    val d = resolveResumeDecision(200, null, /* contentLength = */ -1)
    assertFalse(d.append)
    assertEquals("chunked / unknown length disables the size check", -1L, d.expectedFinalSize)
  }
}
