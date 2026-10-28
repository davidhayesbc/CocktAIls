package com.davidhayesbc.cocktails.android.product

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/** Enforces the short share-copy deadline when a recipient accesses its content URI. */
class DetailedLookupCaptureFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        enforceShareCopyAccess(uri, metadataRequest = false)
        return super.openFile(uri, mode)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        enforceShareCopyAccess(uri, metadataRequest = true)
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
        enforceShareCopyAccess(uri, metadataRequest = true)
        return super.query(uri, projection, queryArgs, cancellationSignal)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor? {
        enforceShareCopyAccess(uri, metadataRequest = true)
        return super.query(uri, projection, selection, selectionArgs, sortOrder, cancellationSignal)
    }

    override fun getType(uri: Uri): String? {
        enforceShareCopyAccess(uri, metadataRequest = true)
        return super.getType(uri)
    }

    private fun enforceShareCopyAccess(uri: Uri, metadataRequest: Boolean) {
        val segments = uri.pathSegments
        val shareDirectoryIndex = segments.indexOf(SHARE_DIRECTORY)
        if (shareDirectoryIndex < 0) return
        val filename = segments.getOrNull(shareDirectoryIndex + 1)
            ?: unavailable(metadataRequest)
        if (shareDirectoryIndex != segments.lastIndex - 1) {
            unavailable(metadataRequest)
        }
        val expiresAt = SHARE_FILENAME.matchEntire(filename)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: unavailable(metadataRequest)
        if (System.currentTimeMillis() >= expiresAt) {
            unavailable(metadataRequest)
        }
    }

    private fun unavailable(metadataRequest: Boolean): Nothing = if (metadataRequest) {
        throw IllegalArgumentException("Shared detailed capture is unavailable.")
    } else {
        throw FileNotFoundException("Shared detailed capture is unavailable.")
    }

    private companion object {
        const val SHARE_DIRECTORY = "detailed-lookup-share"
        val SHARE_FILENAME = Regex("^lookup-[0-9a-f]{12}-(\\d+)-[0-9a-fA-F-]{36}\\.json$")
    }
}
