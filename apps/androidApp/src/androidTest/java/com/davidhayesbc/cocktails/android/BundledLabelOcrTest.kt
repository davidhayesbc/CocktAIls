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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.serialization.json.*
import com.davidhayesbc.cocktails.android.product.BottleOcrJournal
import com.davidhayesbc.cocktails.android.product.OcrCorrelation
import com.davidhayesbc.cocktails.android.product.BottleLabelOcrScheduler
import com.davidhayesbc.cocktails.android.product.BottleScanEnrichmentRuntime
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.android.product.bottleFactFingerprint
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
            assertEquals("Bundled offline OCR must read exactly 750 mL", "750", facts.singleOrNull { it.field == BottleFactField.QUANTITY }?.let(::bottleFactFingerprint))
            assertEquals("Bundled offline OCR must read exactly 40%", "40", facts.singleOrNull { it.field == BottleFactField.ABV_PERCENT }?.let(::bottleFactFingerprint))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = context.getSharedPreferences("cocktails_privacy_settings", android.content.Context.MODE_PRIVATE)
            val aiWasEnabled = prefs.getBoolean("local_ai_enabled", false)
            prefs.edit().putBoolean("local_ai_enabled", false).commit()
            try {
                BottleScanEnrichmentRuntime(context).openRepository().use { runtime ->
                    val repo = runtime.repository
                    val home = repo.locations().first().id
                    val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, System.currentTimeMillis())
                    val draft = checkNotNull(repo.addBottleIntakeDraft(session.id, "Synthetic OCR test", "Spirit", home,
                        InventoryLevel.FULL, System.currentTimeMillis(), photoJpeg = bytes, drinkCategory = "Gin"))
                    try {
                        val correlation = OcrCorrelation.of(draft, null)
                        val journal = BottleOcrJournal.forContext(context)
                        BottleLabelOcrScheduler.enqueue(context, draft, null)
                        val completed = kotlinx.coroutines.withTimeout(45_000) {
                            var record: JsonObject
                            while (true) {
                                record = journal.snapshot(listOf(correlation), draft.id)["attempts"]!!.jsonArray.lastOrNull()?.jsonObject ?: JsonObject(emptyMap())
                                if (record["state"]?.jsonPrimitive?.content in setOf("COMPLETED", "FAILED", "CANCELLED", "STALE")) break
                                kotlinx.coroutines.delay(100)
                            }
                            record
                        }
                        assertEquals("COMPLETED", completed["state"]!!.jsonPrimitive.content)
                        assertEquals("COMPLETED", completed["recognitionState"]!!.jsonPrimitive.content)
                        assertTrue(completed["textFound"]!!.jsonPrimitive.boolean)
                        assertTrue(completed["recognitionMs"]!!.jsonPrimitive.long >= 0)
                        assertEquals("SKIPPED", completed["labelAiState"]!!.jsonPrimitive.content)
                        assertEquals("LOCAL_AI_DISABLED", completed["labelAiReason"]!!.jsonPrimitive.content)
                        assertEquals(setOf("quantity", "abv_percent"), completed["supportedFields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
                        val exported = journal.snapshot(listOf(correlation), draft.id).toString()
                        assertFalse(exported.contains("EXAMPLE LABEL"))
                        assertFalse(exported.contains("750 mL"))
                        assertFalse(exported.contains("40%"))
                        assertEquals("Gin", repo.openBottleIntakeSession()!!.drafts.single { it.id == draft.id }.drinkCategory)
                    } finally {
                        BottleLabelOcrScheduler.cancel(context, draft.id)
                        repo.removeBottleIntakeDraft(session.id, draft.id)
                    }
                }
            } finally { prefs.edit().putBoolean("local_ai_enabled", aiWasEnabled).commit() }

        } finally {
            shell("svc wifi ${if (wifiEnabled) "enable" else "disable"}")
            shell("svc data ${if (dataEnabled) "enable" else "disable"}")
        }
    }
}
