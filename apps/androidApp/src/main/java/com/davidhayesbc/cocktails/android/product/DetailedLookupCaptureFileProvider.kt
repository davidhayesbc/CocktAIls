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
        val shareRootIndex = segments.indexOf(SHARE_ROOT_NAME)
        if (shareRootIndex < 0) return
        if (shareRootIndex != 0 || segments.size != 2) unavailable(metadataRequest)
        val filename = segments.getOrNull(1)
            ?: unavailable(metadataRequest)
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
        // FileProvider URIs use the <cache-path name="..."> alias, not the directory name.
        const val SHARE_ROOT_NAME = "detailed_lookup_share"
        val SHARE_FILENAME = Regex("^lookup-[0-9a-f]{12}-(\\d+)-[0-9a-fA-F-]{36}\\.json$")
    }
}
