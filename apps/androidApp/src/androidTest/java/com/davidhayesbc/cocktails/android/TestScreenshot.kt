package com.davidhayesbc.cocktails.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/** Saves emulator screenshots app-privately; CI copies them with run-as while the test app is installed. */
internal fun saveTestScreenshot(instrumentation: Instrumentation, fileName: String) {
    val safeName = fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val context = instrumentation.targetContext
    val localFile = File(File(context.filesDir, "test-screenshots"), safeName)
    check(localFile.parentFile?.mkdirs() == true || localFile.parentFile?.isDirectory == true) {
        "Could not create private test screenshot directory"
    }
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    try {
        val written = localFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(written) { "Could not encode the test screenshot" }
    } finally {
        bitmap.recycle()
    }
    Log.i(SCREENSHOT_LOG_TAG, "captured=$safeName")
}

private const val SCREENSHOT_LOG_TAG = "CocktailsTestScreenshot"
