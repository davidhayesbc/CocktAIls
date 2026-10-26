package com.davidhayesbc.cocktails.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.File

/** Persists failure screenshots outside app-private cache so Gradle uninstall cannot erase them. */
internal fun saveTestScreenshot(instrumentation: Instrumentation, fileName: String): String {
    val safeName = fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val context = instrumentation.targetContext
    val localFile = File(requireNotNull(context.externalCacheDir), safeName)
    localFile.parentFile?.mkdirs()
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    try {
        localFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
        bitmap.recycle()
    }

    val sharedDirectory = "/sdcard/Download/CocktAIlsDiagnostics"
    val command = "mkdir -p $sharedDirectory && cp ${localFile.absolutePath} $sharedDirectory/$safeName && echo copied"
    val output = runCatching {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }
    }.getOrDefault("")
    return if ("copied" in output) {
        "$sharedDirectory/$safeName"
    } else {
        val bytes = localFile.readBytes()
        if (bytes.size <= MAX_SCREENSHOT_LOG_BYTES) {
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val chunks = encoded.chunked(SCREENSHOT_LOG_CHUNK_CHARS)
            chunks.forEachIndexed { index, chunk ->
                Log.e(SCREENSHOT_LOG_TAG, "$safeName|${index + 1}/${chunks.size}|$chunk")
            }
        } else {
            Log.e(SCREENSHOT_LOG_TAG, "$safeName|omitted|${bytes.size} bytes exceeds diagnostic log cap")
        }
        "${localFile.absolutePath} (shared copy unavailable; see $SCREENSHOT_LOG_TAG log chunks)"
    }
}

private const val SCREENSHOT_LOG_TAG = "CocktailsTestScreenshot"
private const val SCREENSHOT_LOG_CHUNK_CHARS = 2_800
private const val MAX_SCREENSHOT_LOG_BYTES = 1024 * 1024
