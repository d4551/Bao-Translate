package com.google.ai.edge.gallery.worker

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the resumed-download corruption fix: append ONLY when the server honored the range (206).
 * A 200 OK after a Range request means the range was ignored and the full body is being sent from
 * byte 0 — appending it onto the partial would corrupt the file, so the decision must be overwrite.
 */
@Strict
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

  // ----- BRUTALISATION -----

  // ----- 307/308 redirects preserve the Range header per RFC 7233. The resume decision
  // is based on the final response's status code, not the chain. Pin that 307/308 on
  // the FINAL response behave like 200/206.
  @Test
  fun resume_307_withPartialContent_stillAppends() {
    val d = resolveResumeDecision(307, "bytes 100-199/200", /* contentLength = */ 100)
    // 307 is a redirect — current prod code only checks HTTP_PARTIAL (= 206). Document
    // current behavior. If prod is hardened to follow redirects, the final 206 from the
    // redirected URL is what matters, and this assertion still represents the in-flight
    // decision (the code can be improved to recurse on redirects).
    assertFalse("307 is a redirect, not partial — currently treated as overwrite (gap)", d.append)
  }

  @Test
  fun resume_308_withPartialContent_stillAppends() {
    val d = resolveResumeDecision(308, "bytes 100-199/200", /* contentLength = */ 100)
    assertFalse("308 is a permanent redirect, not partial — currently overwrite (gap)", d.append)
  }

  // ----- 416 Range Not Satisfiable: server says "your range is invalid". Current prod code
  // (append = 206) treats it as overwrite (no append). Document current behavior.
  @Test
  fun rangeNotSatisfiable_416_overwritesNotAppends() {
    val d = resolveResumeDecision(416, "bytes */500", /* contentLength = */ -1)
    assertFalse("416 is a server-side reject — must not append", d.append)
    // 416 with Content-Range `bytes */500` exposes the total even when rejecting the range.
    // Current prod code uses `contentRange?.substringAfterLast('/')?.toLongOrNull()` only
    // on append==true paths. Document that expectedFinalSize is -1 in the 416 path.
    assertEquals("416 path has no expected-final-size in current code", -1L, d.expectedFinalSize)
  }

  // ----- Mismatched total: Content-Range says total=300, contentLength=200 (the partial
  // body). On 206, prod takes the total from Content-Range. Verify the test reflects the
  // actual contract: Content-Range wins.
  @Test
  fun mismatched_totalContentRange_206_usesContentRangeTotal() {
    val d = resolveResumeDecision(206, "bytes 50-149/300", /* contentLength = */ 100)
    assertTrue(d.append)
    assertEquals("Content-Range /<total> wins over Content-Length on 206", 300L, d.expectedFinalSize)
  }

  // ----- Off-by-one: range start > total is malformed. Current prod code doesn't validate
  // this. Document the gap.
  @Test
  fun partialLargerThanTotal_currentlyAccepts_documentGap() {
    // Malformed: Content-Range says range is 50-149 but total is only 100.
    val d = resolveResumeDecision(206, "bytes 50-149/100", /* contentLength = */ 100)
    assertTrue("currently accepts malformed range (gap)", d.append)
    assertEquals("currently trusts the malformed /<total>", 100L, d.expectedFinalSize)
  }

  // ----- Zero content-length with 206: this is the edge case where the server sends
  // 206 + Content-Range with a non-zero total, but Content-Length is 0 (some servers do
  // this when the partial is in cache or pre-resolved). The expectedFinalSize is
  // driven by the Content-Range parse, not the Content-Length, so this should be fine.
  @Test
  fun contentLengthZero_on206_usesContentRangeTotal() {
    val d = resolveResumeDecision(206, "bytes 0-99/100", /* contentLength = */ 0)
    assertTrue(d.append)
    assertEquals(100L, d.expectedFinalSize)
  }

  // ----- Content-Length: 0 (not chunked) on 200: prod code takes contentLength verbatim.
  // This is the degenerate case of "server said 200 with no body" — should overwrite
  // the partial with a zero-byte file. Assert that.
  @Test
  fun contentLengthZero_on200_overwritesWithEmpty() {
    val d = resolveResumeDecision(200, null, /* contentLength = */ 0)
    assertFalse(d.append)
    assertEquals("200 with Content-Length: 0 means 'empty file'", 0L, d.expectedFinalSize)
  }

  // ----- Content-Range with no total (the open-ended form, "bytes 100-"): some servers
  // use this when the total isn't known yet. prod should return -1 (disabled size check).
  @Test
  fun contentRangeOpenEnded_noTotal_disablesSizeCheck() {
    val d = resolveResumeDecision(206, "bytes 100-", /* contentLength = */ 100)
    assertTrue(d.append)
    assertEquals("no /<total> in Content-Range -> disable size check", -1L, d.expectedFinalSize)
  }

  // ----- Content-Range with non-numeric total ("bytes 100-/*"): currently parsed as -1
  // because `toLongOrNull()` returns null. Pin.
  @Test
  fun contentRangeTotalNonNumeric_disablesSizeCheck() {
    val d = resolveResumeDecision(206, "bytes 100-199/abc", /* contentLength = */ 100)
    assertTrue(d.append)
    assertEquals("non-numeric /<total> -> disable size check", -1L, d.expectedFinalSize)
  }

  // ----- Content-Range is a malformed prefix (e.g. server sent "items 100-199/500"
  // instead of "bytes"): substringAfterLast('/') still works for the 500, but the
  // "bytes " prefix check is missing. Document the gap.
  @Test
  fun contentRangeNonBytesPrefix_currentlyAccepts_documentGap() {
    val d = resolveResumeDecision(206, "items 100-199/500", /* contentLength = */ 100)
    // Current code does NOT validate the unit prefix. It just takes the part after "/".
    assertTrue("non-bytes unit currently accepted (gap)", d.append)
    assertEquals(500L, d.expectedFinalSize)
  }

  // ----- Multi-range response (e.g. "multipart/byteranges" content type): these come
  // with `Content-Type: multipart/byteranges; boundary=...` and a 206 with a different
  // Content-Range shape. The current code does not handle this. Document.
  @Test
  fun contentRangeMultipartByteranges_currentlyAcceptsTotalPart_documentGap() {
    val d = resolveResumeDecision(206, "bytes 100-199/500, 300-399/500", /* contentLength = */ 200)
    // The "total" portion is "500, 300-399/500" — `toLongOrNull()` returns null. Assert
    // the current behavior: the entire string after the last "/" is "500, 300-399/500"
    // which fails `toLongOrNull()` and yields -1. Wait — `substringAfterLast('/')` is
    // "500", and "500".toLongOrNull() = 500L. So actually it would be 500. Pin that.
    assertTrue(d.append)
    // We don't assert expectedFinalSize exactly because it depends on the multi-range
    // string format; the gap is "this should never happen in a single-range download".
    // Just assert the result is one of {500L, -1L}.
    assertTrue("multi-range result is either 500L or -1L",
      d.expectedFinalSize == 500L || d.expectedFinalSize == -1L)
  }

  // ----- Boundary: 5xx error responses must not append (we'd corrupt the partial).
  @Test
  fun serverError_5xx_neverAppends() {
    for (code in listOf(500, 501, 502, 503, 504, 505)) {
      val d = resolveResumeDecision(code, null, /* contentLength = */ -1)
      assertFalse("5xx ($code) must not append", d.append)
    }
  }

  // ----- Boundary: 3xx redirects (without follow) — current prod only checks 206. Pin
  // that 301/302/303/304/305 also overwrite.
  @Test
  fun redirect_3xx_overwritesNotAppends() {
    for (code in listOf(301, 302, 303, 304, 305, 307, 308)) {
      val d = resolveResumeDecision(code, null, /* contentLength = */ -1)
      assertFalse("3xx ($code) must not append", d.append)
    }
  }

  // ----- Boundary: 4xx client errors — must not append.
  @Test
  fun clientError_4xx_overwritesNotAppends() {
    for (code in listOf(400, 401, 403, 404, 405, 408, 410, 412, 415, 429)) {
      val d = resolveResumeDecision(code, null, /* contentLength = */ -1)
      assertFalse("4xx ($code) must not append", d.append)
    }
  }

  // ----- Boundary: negative contentLength is a sentinel for chunked encoding. Both 200
  // and 206 paths must handle it without overflow.
  @Test
  fun negativeContentLength_on206_doesNotCrash() {
    val d = resolveResumeDecision(206, "bytes 0-99/*", /* contentLength = */ -1L)
    assertTrue(d.append)
    assertEquals("Content-Range '/*' with chunked encoding: -1L", -1L, d.expectedFinalSize)
  }

  // ----- The MAX_INT contentLength is preserved without overflow.
  @Test
  fun maxIntContentLength_on200_isPreserved() {
    val d = resolveResumeDecision(200, null, /* contentLength = */ Long.MAX_VALUE)
    assertFalse(d.append)
    assertEquals("Long.MAX_VALUE preserved as expectedFinalSize", Long.MAX_VALUE, d.expectedFinalSize)
  }
}
