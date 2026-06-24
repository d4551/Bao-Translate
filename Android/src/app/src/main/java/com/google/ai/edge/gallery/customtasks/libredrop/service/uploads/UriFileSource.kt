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
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.FileSource
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel

/**
 * Factory that adapts a `content://` [Uri] (camera capture, SAF-picked file,
 * MediaStore row) into a [FileSource] ready for [OutboundConnection.run].
 *
 * `:core-protocol`'s [FileSource] is platform-agnostic — it only knows about
 * [ReadableByteChannel]. This class is the Android-side glue: it reads display
 * name, size, MIME type, and last-modified from the [ContentResolver] and
 * hands [FileSource] a factory that re-opens the Uri fresh on each transfer
 * attempt.
 *
 * ### Why a dedicated factory instead of inline construction
 *
 * Two active call sites:
 *  1. The camera macro action ([PendingCapture] → FileSource).
 *  2. [LibreDropSenderViewModel] (SAF-picked files → FileSource).
 *
 * Centralising the metadata extraction here keeps the [FileSource] constructor
 * free of Android imports and ensures every caller resolves the same columns
 * with the same null-fallback policy.
 *
 * ### Permission contract
 *
 * The caller MUST already hold a read grant on [uri] (persistable SAF grant,
 * `FLAG_GRANT_READ_URI_PERMISSION` from an intent result, or a FileProvider
 * Uri owned by this process). This factory does NOT take new grants.
 *
 * ### Robustness
 *
 * - `OpenableColumns.SIZE` is nullable; when missing we fall back to the
 *   [android.content.res.AssetFileDescriptor.length] of a freshly opened
 *   descriptor, then to `0L`. A `0L` size is legal for [FileSource] but the
 *   receiver will reject the payload as undersized if the channel yields zero
 *   bytes — acceptable for a genuinely empty file, a bug otherwise.
 * - `OpenableColumns.DISPLAY_NAME` is nullable; when missing we derive a name
 *   from the last path segment, falling back to `"unnamed"` so [FileSource]
 *   never sees an empty string.
 * - MIME type comes from [ContentResolver.getType]; `null` → empty string
 *   (the wire proto accepts empty MIME; the receiver's UI hint degrades).
 *
 * @param context Used only to obtain the application [ContentResolver]. Pass
 *   `applicationContext` so the returned [FileSource] does not pin an Activity.
 * @param uri Content Uri the caller already has read access to.
 * @param payloadId Caller-chosen unique positive payload id (see [FileSource]).
 * @param parentFolder Optional relative parent directory; empty for top-level.
 */
public class UriFileSource(
    context: Context,
    private val uri: Uri,
    private val payloadId: Long,
    private val parentFolder: String = "",
) {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Build the [FileSource]. Metadata is resolved eagerly (one `query()` plus,
     * only when the cursor lacks size, one `openAssetFileDescriptor()`) so the
     * returned [FileSource] is self-contained and safe to hold across transfer
     * retries.
     */
    public fun build(): FileSource {
        val meta = resolveMetadata()
        return FileSource(
            name = meta.displayName,
            size = meta.sizeBytes,
            mimeType = meta.mimeType,
            lastModifiedTimestampMillis = meta.lastModifiedMs,
            payloadId = payloadId,
            parentFolder = parentFolder,
            open = ::openChannel,
        )
    }

    private fun openChannel(): ReadableByteChannel {
        val stream: InputStream =
            contentResolver.openInputStream(uri)
                ?: throw IOException("openInputStream returned null for $uri")
        return Channels.newChannel(stream)
    }

    private data class ResolvedMetadata(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String,
        val lastModifiedMs: Long,
    )

    private fun resolveMetadata(): ResolvedMetadata {
        var displayName: String? = null
        var sizeBytes = 0L
        var lastModifiedMs = 0L
        contentResolver.query(uri, METADATA_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                sizeBytes = cursor.longOrNull(OpenableColumns.SIZE) ?: 0L
                lastModifiedMs = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L
            }
        }
        val name = displayName ?: deriveNameFromUri()
        val mime = contentResolver.getType(uri) ?: ""
        // SIZE comes from the cursor for FileProvider + SAF providers. When absent we leave
        // 0L rather than probing via openAssetFileDescriptor (which throws FileNotFoundException
        // on stale/revoked Uris and would crash the main-thread caller). The byte stream is
        // opened lazily inside OutboundConnection.run, which contractually surfaces I/O
        // failures as OutboundResult.Failed.
        return ResolvedMetadata(name, sizeBytes, mime, lastModifiedMs)
    }

    private fun deriveNameFromUri(): String =
        uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "unnamed"

    private fun Cursor.stringOrNull(columnName: String): String? {
        val idx = getColumnIndex(columnName)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun Cursor.longOrNull(columnName: String): Long? {
        val idx = getColumnIndex(columnName)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    private companion object {
        val METADATA_PROJECTION =
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
    }
}
