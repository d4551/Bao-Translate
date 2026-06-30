/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class DateTimeValidationTest {

  @Test
  fun `valid datetime with seconds parses correctly`() {
    val result = requireNotNull(parseDateTime("2026-06-24T10:30:00"))
    assertEquals(2026, result.year)
    assertEquals(6, result.monthValue)
    assertEquals(24, result.dayOfMonth)
    assertEquals(10, result.hour)
    assertEquals(30, result.minute)
    assertEquals(0, result.second)
  }

  @Test
  fun `valid datetime without seconds parses correctly`() {
    val result = requireNotNull(parseDateTime("2026-12-31T23:59"))
    assertEquals(23, result.hour)
    assertEquals(59, result.minute)
  }

  @Test
  fun `invalid month 13 returns null`() {
    assertNull(parseDateTime("2026-13-01T10:00:00"))
  }

  @Test
  fun `invalid month 0 returns null`() {
    assertNull(parseDateTime("2026-00-15T10:00:00"))
  }

  @Test
  fun `invalid day 32 returns null`() {
    assertNull(parseDateTime("2026-01-32T10:00:00"))
  }

  @Test
  fun `invalid calendar date returns null`() {
    assertNull(parseDateTime("2026-02-31T10:00:00"))
  }

  @Test
  fun `invalid day 0 returns null`() {
    assertNull(parseDateTime("2026-01-00T10:00:00"))
  }

  @Test
  fun `invalid hour 24 returns null`() {
    assertNull(parseDateTime("2026-06-24T24:00:00"))
  }

  @Test
  fun `invalid minute 60 returns null`() {
    assertNull(parseDateTime("2026-06-24T10:60:00"))
  }

  @Test
  fun `invalid second 60 returns null`() {
    assertNull(parseDateTime("2026-06-24T10:30:60"))
  }

  @Test
  fun `non-matching format returns null`() {
    assertNull(parseDateTime("not-a-date"))
  }

  @Test
  fun `empty string returns null`() {
    assertNull(parseDateTime(""))
  }

  @Test
  fun `missing time component returns null`() {
    assertNull(parseDateTime("2026-06-24"))
  }
}
