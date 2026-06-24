/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import android.net.Uri
import com.google.ai.edge.gallery.testkit.Strict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Category(Strict::class)
class CaptureCoordinatorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun mockContext(): Context {
    val ctx = mock<Context>()
    whenever(ctx.cacheDir).thenReturn(tempFolder.root)
    whenever(ctx.packageName).thenReturn("com.test")
    return ctx
  }

  private fun fakeUriProvider(uri: Uri): (Context, String, File) -> Uri = { _, _, _ -> uri }

  private fun armCoordinator(
    ctx: Context,
    fakeUri: Uri,
    action: CapturePhotoAction = CapturePhotoAction(resolutionHint = "12mp", mode = "main"),
  ): CaptureCoordinator {
    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(fakeUri))
    coordinator.arm(action)
    return coordinator
  }

  @Test
  fun `arm returns empty string`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(fakeUri))
    val result = coordinator.arm(CapturePhotoAction(resolutionHint = "12mp", mode = "main"))
    assertEquals("", result)
  }

  @Test
  fun `arm sets outputUri and captureRequest`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    val action = CapturePhotoAction(resolutionHint = "50mp", mode = "tele")
    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(fakeUri))
    coordinator.arm(action)
    assertEquals(fakeUri, coordinator.outputUri.value)
    assertEquals(action, coordinator.captureRequest.value)
  }

  @Test
  fun `arm creates capture subdirectory`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    armCoordinator(ctx, fakeUri)
    assertTrue(File(tempFolder.root, CAPTURE_SUBDIR).exists())
  }

  @Test
  fun `onResult success appends pending capture and clears armed state`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    whenever(fakeUri.lastPathSegment).thenReturn("capture-test.jpg")
    val coordinator = armCoordinator(ctx, fakeUri)

    coordinator.onResult(success = true)

    val captures = coordinator.captures.value
    assertEquals(1, captures.size)
    assertEquals(fakeUri, captures[0].uri)
    assertEquals("capture-test.jpg", captures[0].displayName)
    assertEquals("12mp", captures[0].resolutionHint)
    assertEquals("main", captures[0].mode)
    assertTrue(captures[0].capturedAtMs > 0)
    assertNull(coordinator.captureRequest.value)
    assertNull(coordinator.outputUri.value)
  }

  @Test
  fun `onResult failure does not append capture but clears armed state`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    val coordinator = armCoordinator(ctx, fakeUri)

    coordinator.onResult(success = false)

    assertTrue(coordinator.captures.value.isEmpty())
    assertNull(coordinator.captureRequest.value)
    assertNull(coordinator.outputUri.value)
  }

  @Test
  fun `onResult without prior arm is a no-op`() {
    val ctx = mockContext()
    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(mock()))
    coordinator.onResult(success = true)
    assertTrue(coordinator.captures.value.isEmpty())
  }

  @Test
  fun `consume removes matching capture`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    whenever(fakeUri.lastPathSegment).thenReturn("capture-test.jpg")
    val coordinator = armCoordinator(ctx, fakeUri)
    coordinator.onResult(success = true)
    assertEquals(1, coordinator.captures.value.size)

    coordinator.consume(fakeUri)

    assertTrue(coordinator.captures.value.isEmpty())
  }

  @Test
  fun `consume with non-matching uri leaves captures unchanged`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    whenever(fakeUri.lastPathSegment).thenReturn("capture-test.jpg")
    val coordinator = armCoordinator(ctx, fakeUri)
    coordinator.onResult(success = true)

    coordinator.consume(mock())

    assertEquals(1, coordinator.captures.value.size)
  }

  @Test
  fun `reset clears all state`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    whenever(fakeUri.lastPathSegment).thenReturn("capture-test.jpg")
    val coordinator = armCoordinator(ctx, fakeUri)
    coordinator.onResult(success = true)

    coordinator.reset()

    assertNull(coordinator.captureRequest.value)
    assertNull(coordinator.outputUri.value)
    assertTrue(coordinator.captures.value.isEmpty())
  }

  @Test
  fun `reset on fresh coordinator is safe`() {
    val ctx = mockContext()
    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(mock()))
    coordinator.reset()
    assertNull(coordinator.captureRequest.value)
    assertNull(coordinator.outputUri.value)
    assertTrue(coordinator.captures.value.isEmpty())
  }

  @Test
  fun `multiple successful captures accumulate`() {
    val ctx = mockContext()
    val uri1 = mock<Uri>()
    whenever(uri1.lastPathSegment).thenReturn("capture-1.jpg")
    val uri2 = mock<Uri>()
    whenever(uri2.lastPathSegment).thenReturn("capture-2.jpg")

    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(uri1))
    coordinator.arm(CapturePhotoAction(resolutionHint = "12mp", mode = "main"))
    coordinator.onResult(success = true)

    val coordinator2 = CaptureCoordinator(ctx, fakeUriProvider(uri2))
    coordinator2.arm(CapturePhotoAction(resolutionHint = "50mp", mode = "tele"))
    coordinator2.onResult(success = true)

    assertEquals(1, coordinator.captures.value.size)
    assertEquals("capture-1.jpg", coordinator.captures.value[0].displayName)
    assertEquals(1, coordinator2.captures.value.size)
    assertEquals("50mp", coordinator2.captures.value[0].resolutionHint)
  }

  @Test
  fun `arm replaces prior pending request`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    val coordinator = CaptureCoordinator(ctx, fakeUriProvider(fakeUri))
    val action1 = CapturePhotoAction(resolutionHint = "12mp", mode = "main")
    val action2 = CapturePhotoAction(resolutionHint = "50mp", mode = "tele")
    coordinator.arm(action1)
    coordinator.arm(action2)
    assertEquals(action2, coordinator.captureRequest.value)
  }

  @Test
  fun `onResult success with null lastPathSegment uses fallback name`() {
    val ctx = mockContext()
    val fakeUri = mock<Uri>()
    whenever(fakeUri.lastPathSegment).thenReturn(null)
    val coordinator = armCoordinator(ctx, fakeUri)

    coordinator.onResult(success = true)

    val captures = coordinator.captures.value
    assertEquals(1, captures.size)
    assertTrue(captures[0].displayName.startsWith("capture-"))
    assertTrue(captures[0].displayName.endsWith(".jpg"))
  }
}
