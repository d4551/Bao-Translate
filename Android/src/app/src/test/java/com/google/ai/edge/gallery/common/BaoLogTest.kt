package com.google.ai.edge.gallery.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
