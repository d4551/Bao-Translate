/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Strict::class)
class PiiRedactionTest {

  @Test
  fun `maskEmail redacts local part keeping first char and domain`() {
    assertEquals("j***@example.com", maskEmail("john@example.com"))
  }

  @Test
  fun `maskEmail single char local returns stars with domain`() {
    assertEquals("***@x.com", maskEmail("a@x.com"))
  }

  @Test
  fun `maskEmail no at sign returns stars`() {
    assertEquals("***", maskEmail("invalidemail"))
  }

  @Test
  fun `maskEmail at sign at start returns stars`() {
    assertEquals("***", maskEmail("@domain.com"))
  }

  @Test
  fun `maskEmail empty string returns stars`() {
    assertEquals("***", maskEmail(""))
  }

  @Test
  fun `maskEmail two char local keeps first char`() {
    assertEquals("a***@b.co", maskEmail("ab@b.co"))
  }

  @Test
  fun `maskPhone masks middle digits keeping first two and last two`() {
    assertEquals("55***44", maskPhone("5551234544"))
  }

  @Test
  fun `maskPhone strips non-digit characters`() {
    assertEquals("15***99", maskPhone("+1 (555) 123-4599"))
  }

  @Test
  fun `maskPhone fewer than four digits returns stars`() {
    assertEquals("***", maskPhone("123"))
  }

  @Test
  fun `maskPhone empty string returns stars`() {
    assertEquals("***", maskPhone(""))
  }

  @Test
  fun `maskPhone exactly four digits keeps all`() {
    assertEquals("12***34", maskPhone("1234"))
  }

  @Test
  fun `maskPhone no digits returns stars`() {
    assertEquals("***", maskPhone("abc-def"))
  }
}
