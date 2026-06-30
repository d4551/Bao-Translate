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
class LaunchIntentOrErrorTest {

  @Test
  fun `returns empty string and starts activity when resolvable`() {
    var started = false

    val result =
      launchIntentOrError(
        isResolvable = true,
        startActivity = { started = true },
        noActivityMessage = { "No app can handle this action" },
        actionName = "test action",
      )

    assertEquals("", result)
    assertEquals(true, started)
  }

  @Test
  fun `returns error string and skips startActivity when not resolvable`() {
    var started = false

    val result =
      launchIntentOrError(
        isResolvable = false,
        startActivity = { started = true },
        noActivityMessage = { "No app can handle this action" },
        actionName = "test action",
      )

    assertEquals("No app can handle this action", result)
    assertEquals(false, started)
  }
}
