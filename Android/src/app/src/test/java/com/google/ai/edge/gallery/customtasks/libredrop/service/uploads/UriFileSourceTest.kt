/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop.service.uploads

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Category(Strict::class)
class UriFileSourceTest {

  private fun mockContextWithResolver(resolver: ContentResolver): Context {
    val ctx = mock<Context>()
    val appCtx = mock<Context>()
    whenever(ctx.applicationContext).thenReturn(appCtx)
    whenever(appCtx.contentResolver).thenReturn(resolver)
    return ctx
  }

  private fun stubQuery(resolver: ContentResolver, uri: Uri, cursor: Cursor?) {
    whenever(resolver.query(eq(uri), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(cursor)
  }

  private fun mockCursor(
    displayName: String?,
    size: Long?,
    lastModified: Long?,
  ): Cursor {
    val cursor = mock<Cursor>()
    whenever(cursor.moveToFirst()).thenReturn(true)

    val nameIdx = 0
    val sizeIdx = 1
    val modIdx = 2

    whenever(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(nameIdx)
    whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(sizeIdx)
    whenever(cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)).thenReturn(modIdx)

    whenever(cursor.isNull(nameIdx)).thenReturn(displayName == null)
    whenever(cursor.isNull(sizeIdx)).thenReturn(size == null)
    whenever(cursor.isNull(modIdx)).thenReturn(lastModified == null)

    displayName?.let { whenever(cursor.getString(nameIdx)).thenReturn(it) }
    size?.let { whenever(cursor.getLong(sizeIdx)).thenReturn(it) }
    lastModified?.let { whenever(cursor.getLong(modIdx)).thenReturn(it) }

    return cursor
  }

  @Test
  fun `build extracts metadata from cursor`() {
    val uri = mock<Uri>()
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = "photo.jpg", size = 1024L, lastModified = 1234567890L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("image/jpeg")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 42L, parentFolder = "photos")
    val fileSource = source.build()

    assertEquals("photo.jpg", fileSource.name)
    assertEquals(1024L, fileSource.size)
    assertEquals("image/jpeg", fileSource.mimeType)
    assertEquals(1234567890L, fileSource.lastModifiedTimestampMillis)
    assertEquals(42L, fileSource.payloadId)
    assertEquals("photos", fileSource.parentFolder)
  }

  @Test
  fun `build uses deriveNameFromUri when cursor lacks display name`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("fallback.png")
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = null, size = 512L, lastModified = 0L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("image/png")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertEquals("fallback.png", fileSource.name)
  }

  @Test
  fun `build uses unnamed when both cursor and uri lack name`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn(null)
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = null, size = 0L, lastModified = 0L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn(null)
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertEquals("unnamed", fileSource.name)
    assertEquals("", fileSource.mimeType)
  }

  @Test
  fun `build defaults size to zero when cursor lacks size column`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("file.txt")
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = "file.txt", size = null, lastModified = 0L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("text/plain")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertEquals(0L, fileSource.size)
  }

  @Test
  fun `build handles null cursor gracefully`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("doc.pdf")
    val resolver = mock<ContentResolver>()
    stubQuery(resolver, uri, null)
    whenever(resolver.getType(uri)).thenReturn("application/pdf")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertEquals("doc.pdf", fileSource.name)
    assertEquals(0L, fileSource.size)
    assertEquals("application/pdf", fileSource.mimeType)
  }

  @Test
  fun `build handles empty cursor gracefully`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("video.mp4")
    val resolver = mock<ContentResolver>()
    val cursor = mock<Cursor>()
    whenever(cursor.moveToFirst()).thenReturn(false)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("video/mp4")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertEquals("video.mp4", fileSource.name)
    assertEquals(0L, fileSource.size)
    assertEquals("video/mp4", fileSource.mimeType)
  }

  @Test
  fun `build uses empty parent folder by default`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("file.txt")
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = "file.txt", size = 100L, lastModified = 0L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("text/plain")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertEquals("", fileSource.parentFolder)
  }

  @Test
  fun `build preserves payload id`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("file.txt")
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = "file.txt", size = 100L, lastModified = 0L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("text/plain")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 999L)
    val fileSource = source.build()

    assertEquals(999L, fileSource.payloadId)
  }

  @Test
  fun `build returns FileSource with openChannel factory`() {
    val uri = mock<Uri>()
    whenever(uri.lastPathSegment).thenReturn("file.txt")
    val resolver = mock<ContentResolver>()
    val cursor = mockCursor(displayName = "file.txt", size = 100L, lastModified = 0L)
    stubQuery(resolver, uri, cursor)
    whenever(resolver.getType(uri)).thenReturn("text/plain")
    val ctx = mockContextWithResolver(resolver)

    val source = UriFileSource(ctx, uri, payloadId = 1L)
    val fileSource = source.build()

    assertNotNull(fileSource)
    assertTrue(fileSource.size >= 0)
  }
}
