package com.davidhayesbc.cocktails.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
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
    return if ("copied" in output) "$sharedDirectory/$safeName" else "${localFile.absolutePath} (shared copy unavailable)"
}
