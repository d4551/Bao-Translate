/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import com.google.ai.edge.gallery.testkit.Strict
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class DateTimeValidationTest {

  private fun parse(datetime: String): LocalDateTime? {
    val pattern = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?")
    if (!pattern.matches(datetime)) return null
    val parts = datetime.split("T")
    val dateParts = parts[0].split("-")
    val month = dateParts[1].toIntOrNull()
    val day = dateParts[2].toIntOrNull()
    if (month == null || month !in 1..12 || day == null || day !in 1..31) return null
    val timeParts = parts[1].split(":")
    val hour = timeParts[0].toIntOrNull()
    val minute = timeParts[1].toIntOrNull()
    val second = if (timeParts.size > 2) timeParts[2].toIntOrNull() else 0
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    return LocalDateTime.parse(datetime, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
  }

  @Test
  fun `valid datetime with seconds parses correctly`() {
    val result = parse("2026-06-24T10:30:00")
    assertNotNull(result)
    assertEquals(2026, result!!.year)
    assertEquals(6, result.monthValue)
    assertEquals(24, result.dayOfMonth)
    assertEquals(10, result.hour)
    assertEquals(30, result.minute)
    assertEquals(0, result.second)
  }

  @Test
  fun `valid datetime without seconds parses correctly`() {
    val result = parse("2026-12-31T23:59")
    assertNotNull(result)
    assertEquals(23, result!!.hour)
    assertEquals(59, result.minute)
  }

  @Test
  fun `invalid month 13 returns null`() {
    assertNull(parse("2026-13-01T10:00:00"))
  }

  @Test
  fun `invalid month 0 returns null`() {
    assertNull(parse("2026-00-15T10:00:00"))
  }

  @Test
  fun `invalid day 32 returns null`() {
    assertNull(parse("2026-01-32T10:00:00"))
  }

  @Test
  fun `invalid day 0 returns null`() {
    assertNull(parse("2026-01-00T10:00:00"))
  }

  @Test
  fun `invalid hour 24 returns null`() {
    assertNull(parse("2026-06-24T24:00:00"))
  }

  @Test
  fun `invalid minute 60 returns null`() {
    assertNull(parse("2026-06-24T10:60:00"))
  }

  @Test
  fun `invalid second 60 returns null`() {
    assertNull(parse("2026-06-24T10:30:60"))
  }

  @Test
  fun `non-matching format returns null`() {
    assertNull(parse("not-a-date"))
  }

  @Test
  fun `empty string returns null`() {
    assertNull(parse(""))
  }

  @Test
  fun `missing time component returns null`() {
    assertNull(parse("2026-06-24"))
  }
}
