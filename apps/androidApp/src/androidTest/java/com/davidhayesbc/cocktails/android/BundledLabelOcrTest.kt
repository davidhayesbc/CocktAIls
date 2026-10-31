package com.davidhayesbc.cocktails.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.product.recognizeBottleLabel
import com.davidhayesbc.cocktails.android.product.explicitLabelCandidates
import com.davidhayesbc.cocktails.android.product.independentBottleFacts
import com.davidhayesbc.cocktails.android.product.BottleFactField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BundledLabelOcrTest {
    @Test fun bundledRecognizerReadsSyntheticLabelWithoutNetworkOrDownloadedLanguageModel() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val wifiEnabled = shell("settings get global wifi_on") == "1"
        val dataEnabled = shell("settings get global mobile_data") == "1"
        // CI installs the APK afresh. Recognition must not depend on Play Services model downloads.
        shell("svc wifi disable")
        shell("svc data disable")
        try {
            val bitmap = Bitmap.createBitmap(1400, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
            canvas.drawText("EXAMPLE LABEL", 100f, 180f, paint)
            canvas.drawText("40% ALC/VOL", 100f, 350f, paint)
            canvas.drawText("750 mL", 100f, 520f, paint)
            val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            bitmap.recycle()
            val text = kotlinx.coroutines.withTimeout(30_000) { recognizeBottleLabel(bytes) }
            val facts = independentBottleFacts(text, explicitLabelCandidates(text)).facts
            assertTrue("Bundled offline OCR must read the synthetic bottle size", facts.any { it.field == BottleFactField.QUANTITY })
            assertTrue("Bundled offline OCR must read the synthetic alcohol strength", facts.any { it.field == BottleFactField.ABV_PERCENT })
        } finally {
            shell("svc wifi ${if (wifiEnabled) "enable" else "disable"}")
            shell("svc data ${if (dataEnabled) "enable" else "disable"}")
        }
    }
}
