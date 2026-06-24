/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import android.net.Uri
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.testkit.Strict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Category(Strict::class)
class CaptureCoordinatorMkdirsTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `arm returns error when mkdirs fails`() {
    val ctx = mock<Context>()
    val readOnlyDir = File(tempFolder.root, "readonly")
    readOnlyDir.mkdirs()
    readOnlyDir.setWritable(false)
    whenever(ctx.cacheDir).thenReturn(readOnlyDir)
    whenever(ctx.packageName).thenReturn("com.test")
    whenever(ctx.getString(eq(R.string.mobile_actions_capture_dir_error)))
      .thenReturn("Failed to create capture directory")

    val coordinator = CaptureCoordinator(ctx) { _, _, _ -> mock<Uri>() }
    val result = coordinator.arm(CapturePhotoAction(resolutionHint = "12mp", mode = "main"))

    assertEquals("Failed to create capture directory", result)
    readOnlyDir.setWritable(true)
  }
}
