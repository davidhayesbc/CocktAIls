package com.davidhayesbc.cocktails.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.core.interchange.isProductPhotoJpeg
import com.davidhayesbc.cocktails.android.product.stripJpegMetadata
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
import com.davidhayesbc.cocktails.android.product.OcrState
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
        var stage = "IMAGE"
        var workerState = "ABSENT"
        try {
            val bitmap = Bitmap.createBitmap(1400, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
            canvas.drawText("EXAMPLE LABEL", 100f, 180f, paint)
            canvas.drawText("40% ALC/VOL", 100f, 350f, paint)
            canvas.drawText("750 mL", 100f, 520f, paint)
            val encoded = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            bitmap.recycle()
            stage = "IMAGE_VALIDATION"
            // Match retained production label photos: Android's encoder can add APP metadata,
            // which the repository deliberately rejects. Keep the strict validator intact.
            val bytes = checkNotNull(stripJpegMetadata(encoded))
            assertTrue("Synthetic label must satisfy the retained-photo contract", isProductPhotoJpeg(bytes))
            stage = "DIRECT_RECOGNITION"
            val text = kotlinx.coroutines.withTimeout(30_000) { recognizeBottleLabel(bytes) }
            stage = "DIRECT_FACTS"
            val facts = independentBottleFacts(text, explicitLabelCandidates(text)).facts
            stage = "DIRECT_QUANTITY"
            assertEquals("Bundled offline OCR must read exactly 750 mL", "750", facts.singleOrNull { it.field == BottleFactField.QUANTITY }?.let(::bottleFactFingerprint))
            stage = "DIRECT_ABV"
            assertEquals("Bundled offline OCR must read exactly 40%", "40", facts.singleOrNull { it.field == BottleFactField.ABV_PERCENT }?.let(::bottleFactFingerprint))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = context.getSharedPreferences("cocktails_privacy_settings", android.content.Context.MODE_PRIVATE)
            val aiWasEnabled = prefs.getBoolean("local_ai_enabled", false)
            prefs.edit().putBoolean("local_ai_enabled", false).commit()
            try {
                stage = "OPEN_REPOSITORY"
                BottleScanEnrichmentRuntime(context).openRepository().use { runtime ->
                    val repo = runtime.repository
                    val home = repo.locations().first().id
                    stage = "CREATE_SESSION"
                    val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, System.currentTimeMillis())
                    stage = "CREATE_DRAFT"
                    val draft = checkNotNull(repo.addBottleIntakeDraft(session.id, "Synthetic OCR test", "Spirit", home,
                        InventoryLevel.FULL, System.currentTimeMillis(), photoJpeg = bytes, drinkCategory = "Gin"))
                    try {
                        val correlation = OcrCorrelation.of(draft, null)
                        val journal = BottleOcrJournal.forContext(context)
                        stage = "ENQUEUE"
                        BottleLabelOcrScheduler.enqueue(context, draft, null)
                        stage = "WAIT_WORKER"
                        val completed = kotlinx.coroutines.withTimeout(45_000) {
                            var record: JsonObject
                            while (true) {
                                record = journal.snapshot(listOf(correlation), draft.id)["attempts"]!!.jsonArray.lastOrNull()?.jsonObject ?: JsonObject(emptyMap())
                                workerState = OcrState.entries.firstOrNull { it.name == record["state"]?.jsonPrimitive?.content }?.name ?: "ABSENT"
                                if (record["state"]?.jsonPrimitive?.content in setOf("COMPLETED", "FAILED", "CANCELLED", "STALE")) break
                                kotlinx.coroutines.delay(100)
                            }
                            record
                        }
                        stage = "WORKER_STATE"
                        assertEquals("COMPLETED", completed["state"]!!.jsonPrimitive.content)
                        stage = "RECOGNITION_STATE"
                        assertEquals("COMPLETED", completed["recognitionState"]!!.jsonPrimitive.content)
                        stage = "TEXT_FOUND"
                        assertTrue(completed["textFound"]!!.jsonPrimitive.boolean)
                        stage = "RECOGNITION_TIME"
                        assertTrue(completed["recognitionMs"]!!.jsonPrimitive.long >= 0)
                        stage = "AI_STATE"
                        assertEquals("SKIPPED", completed["labelAiState"]!!.jsonPrimitive.content)
                        stage = "AI_REASON"
                        assertEquals("LOCAL_AI_DISABLED", completed["labelAiReason"]!!.jsonPrimitive.content)
                        stage = "SUPPORTED_FIELDS"
                        assertEquals(setOf("quantity", "abv_percent"), completed["supportedFields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
                        stage = "PRIVACY"
                        val exported = journal.snapshot(listOf(correlation), draft.id).toString()
                        assertFalse(exported.contains("EXAMPLE LABEL"))
                        assertFalse(exported.contains("750 mL"))
                        assertFalse(exported.contains("40%"))
                        stage = "CATEGORY"
                        assertEquals("Gin", repo.openBottleIntakeSession()!!.drafts.single { it.id == draft.id }.drinkCategory)
                    } finally {
                        BottleLabelOcrScheduler.cancel(context, draft.id)
                        repo.removeBottleIntakeDraft(session.id, draft.id)
                    }
                }
            } finally { prefs.edit().putBoolean("local_ai_enabled", aiWasEnabled).commit() }

        } catch (failure: Throwable) {
            val kind = when (failure) {
                is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
                is AssertionError -> "ASSERTION"
                is IllegalStateException -> "ILLEGAL_STATE"
                is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
                else -> "OTHER"
            }
            // Fixed fixture codes only. Never copy throwable messages, OCR text, or assertion values.
            throw AssertionError("Synthetic OCR diagnostic: stage=$stage; failure=$kind; worker=$workerState").also { it.initCause(failure) }
        } finally {
            shell("svc wifi ${if (wifiEnabled) "enable" else "disable"}")
            shell("svc data ${if (dataEnabled) "enable" else "disable"}")
        }
    }
}
