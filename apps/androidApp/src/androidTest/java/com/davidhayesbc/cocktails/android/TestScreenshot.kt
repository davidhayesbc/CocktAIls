package com.davidhayesbc.cocktails.android

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Saves emulator screenshots app-privately; CI copies them with run-as while the test app is installed. */
internal fun saveTestScreenshot(instrumentation: Instrumentation, fileName: String): String {
    val safeName = fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val context = instrumentation.targetContext
    val localFile = File(File(context.filesDir, "test-screenshots"), safeName)
    val temporaryFile = File(localFile.parentFile, "$safeName.tmp")
    check(localFile.parentFile?.mkdirs() == true || localFile.parentFile?.isDirectory == true) {
        "Could not create private test screenshot directory"
    }
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(500, 5_000)
    // Accessibility can become idle just before Compose draws an image update. Let one
    // presentation interval pass before taking the full-screen review artifact.
    SystemClock.sleep(100)
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    try {
        check(bitmap.width >= MIN_TEST_SCREENSHOT_WIDTH && bitmap.height >= MIN_TEST_SCREENSHOT_HEIGHT) {
            "Screenshot surface was too small to review: ${bitmap.width}x${bitmap.height}"
        }
        FileOutputStream(temporaryFile).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode the test screenshot" }
            output.fd.sync()
        }
    } finally {
        bitmap.recycle()
    }

    val decoded = BitmapFactory.decodeFile(temporaryFile.absolutePath)
        ?: run { temporaryFile.delete(); error("Captured screenshot was not a complete readable PNG") }
    val dimensions = "${decoded.width}x${decoded.height}"
    val pixels = decoded.width.toLong() * decoded.height.toLong()
    decoded.recycle()
    check(pixels >= MIN_TEST_SCREENSHOT_WIDTH.toLong() * MIN_TEST_SCREENSHOT_HEIGHT) {
        temporaryFile.delete()
        "Decoded screenshot surface was too small: $dimensions"
    }
    check(temporaryFile.renameTo(localFile)) {
        temporaryFile.delete()
        "Could not atomically publish the complete screenshot into app-private test storage"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(localFile.readBytes()).joinToString("") { "%02x".format(it) }
    Log.i(SCREENSHOT_LOG_TAG, "captured=$safeName dimensions=$dimensions sha256=$digest")
    return digest
}

private const val SCREENSHOT_LOG_TAG = "CocktailsTestScreenshot"
private const val MIN_TEST_SCREENSHOT_WIDTH = 320
private const val MIN_TEST_SCREENSHOT_HEIGHT = 480
