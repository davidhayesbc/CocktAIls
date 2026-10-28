package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailedBottleLookupCaptureTest {
    @Test
    fun withoutExplicitArmNoAttemptContentIsWritten() {
        val root = Files.createTempDirectory("detailed-capture-disabled").toFile()
        val file = File(root, "capture.json")
        val store = DetailedBottleLookupCaptureStore(file, { 100L }, processSessionId = "test")

        assertNull(store.claim("draft-a", "00000000-0000-0000-0000-000000000001", "5011333150725", "preview", 19, "Qwen"))
        assertFalse(file.exists())
        root.deleteRecursively()
    }

    @Test
    fun captureIsConsumedForOneDraftAndAttemptAndPersistsBoundedReplayInputs() {
        val root = Files.createTempDirectory("detailed-capture").toFile()
        var now = 1_000L
        val captureFile = File(root, "no-backup/capture.json")
        val store = DetailedBottleLookupCaptureStore(captureFile, { now }, processSessionId = "process-a")
        val attempt = "00000000-0000-0000-0000-000000000001"
        assertEquals(DetailedCaptureArmResult.ARMED, store.arm("draft-a", null, "pending", now))
        assertTrue(store.bindArmedAttempt("draft-a", attempt, now))
        assertNull(store.claim("draft-b", attempt, "5011333150725", "preview", 19, "Qwen", now))
        assertNull(store.claim("draft-a", "00000000-0000-0000-0000-000000000002", "5011333150725", "preview", 19, "Qwen", now))

        val capture = store.claim("draft-a", attempt, "5011333150725", "0.1.0-preview.19+abcdef0", 19, "Qwen3-0.6B-Q8_0", now)
        assertNotNull(capture)
        assertNull(store.claim("draft-a", attempt, "5011333150725", "preview", 19, "Qwen", now))
        capture!!.recordSearchSelection(
            listOf(WebSearchHit("Auchentoshan Three Wood", "https://shop.example/product?id=secret", "UPC 5011333150725")),
            selectedIndex = 0,
            decision = "SELECTED_EXACT_MATCH",
        )
        val identity = StructuredBottleIdentity(
            "5011333150725",
            "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        capture.recordFetchedPage(0, "https://shop.example/product?id=secret", "Auchentoshan Three Wood", "STRUCTURED_PRODUCT_MATCH", listOf(identity), StructuredIdentityStatus.VERIFIED)
        capture.recordValidatorInput(
            provider = "On-device Qwen3",
            barcode = "5011333150725",
            sourceText = "Auchentoshan Three Wood 750 mL. Secret Gin 40% ABV.",
            pageTitle = "Auchentoshan Three Wood",
            structuredIdentities = listOf(identity),
            structuredIdentityStatus = StructuredIdentityStatus.VERIFIED,
            selectedStructuredIdentity = identity,
        )
        val reviewedPreview = store.previewForDraft("draft-a", now)!!
        capture.recordModelResponse("On-device Qwen3", "Qwen3-0.6B-Q8_0", "{\"facts\":[],\"note\":\"api_key=superSecretValue123\"}")
        capture.finish("COMPLETE")

        val reopened = DetailedBottleLookupCaptureStore(captureFile, { now }, processSessionId = "process-b")
        val preview = reopened.previewForDraft("draft-a", now)!!
        assertTrue(preview.contains("0.1.0-preview.19+abcdef0"))
        assertTrue(preview.contains("\"appVersionCode\": 19"))
        assertTrue(preview.contains(attempt))
        assertTrue(preview.contains("Auchentoshan Three Wood 750 mL."))
        assertTrue(preview.contains("STRUCTURED_IDENTITY_VERIFIED"))
        assertTrue(preview.contains("selectedStructuredIdentity"))
        assertTrue(preview.contains("Secret Gin 40% ABV"))
        assertTrue(preview.contains("responseRedacted"))
        assertFalse(preview.contains("superSecretValue123"))
        assertFalse(preview.contains("id=secret"))
        assertFalse(preview.contains("user=private"))
        assertEquals("COMPLETE", reopened.statusForDraft("draft-a", now)?.state)
        assertNull(reopened.statusForDraft("draft-b", now))
        val reviewedShare = reopened.exportReviewedCopyForDraft("draft-a", reviewedPreview, File(root, "cache"), now)!!
        assertEquals(reviewedPreview, reviewedShare.readText())
        assertFalse(reviewedShare.readText().contains("modelResponse"))
        root.deleteRecursively()
    }

    @Test
    fun interruptedCaptureIsRecoverableButCannotBeReclaimedAfterRestart() {
        val root = Files.createTempDirectory("detailed-capture-restart").toFile()
        var now = 5_000L
        val file = File(root, "capture.json")
        val firstProcess = DetailedBottleLookupCaptureStore(file, { now }, processSessionId = "first")
        val token = "00000000-0000-0000-0000-000000000010"
        firstProcess.arm("draft-a", token, "preview")
        val recorder = firstProcess.claim("draft-a", token, "5011333150725", "preview", 19, "Qwen")!!
        recorder.recordValidatorInput("On-device Qwen3", "5011333150725", "validator text", "Product", emptyList(), StructuredIdentityStatus.STRUCTURED_DATA_MISSING)

        now += 100
        val afterRestart = DetailedBottleLookupCaptureStore(file, { now }, processSessionId = "second")
        assertEquals("INTERRUPTED", afterRestart.statusForDraft("draft-a")?.state)
        assertNull(afterRestart.claim("draft-a", token, "5011333150725", "preview", 19, "Qwen"))
        assertTrue(afterRestart.previewForDraft("draft-a")!!.contains("validator text"))
        root.deleteRecursively()
    }

    @Test
    fun captureExpiresAndExplicitShareCopyIsPrivateShortLivedAndDeletedWithCapture() {
        val root = Files.createTempDirectory("detailed-capture-expiry").toFile()
        var now = 10_000L
        val file = File(root, "no-backup/capture.json")
        val cache = File(root, "cache")
        val store = DetailedBottleLookupCaptureStore(file, { now }, shareCacheDirectory = cache, processSessionId = "test")
        val token = "00000000-0000-0000-0000-000000000020"
        store.arm("draft-a", token, "preview", now)
        store.claim("draft-a", token, "5011333150725", "preview", 20, "Qwen", now)!!.finish("COMPLETE")
        assertTrue(file.exists())
        val share = store.exportCopyForDraft("draft-a", cache, now)!!
        assertTrue(share.exists())
        assertTrue(share.readText().contains("draft-a"))
        assertTrue(share.setLastModified(1L))
        now = 3_700_000L
        DetailedBottleLookupCaptureStore(file, { now }, shareCacheDirectory = cache, processSessionId = "cleanup")
        assertFalse(share.exists())
        val freshShare = store.exportCopyForDraft("draft-a", cache, now)!!
        assertTrue(store.deleteForDraft("draft-a"))
        assertFalse(file.exists())
        assertFalse(freshShare.exists())

        store.arm("draft-a", token, "preview", now)
        now += DetailedBottleLookupCaptureStore.RETENTION_MILLIS + 1
        assertNull(store.statusForDraft("draft-a", now))
        assertFalse(file.exists())
        root.deleteRecursively()
    }

    @Test
    fun oneGlobalCaptureCannotBeOverwrittenByAnotherBottleUntilDeleted() {
        val root = Files.createTempDirectory("detailed-capture-singleton").toFile()
        val store = DetailedBottleLookupCaptureStore(File(root, "capture.json"), { 1_000L }, processSessionId = "test")
        assertEquals(DetailedCaptureArmResult.ARMED, store.arm("draft-a", null, "preview", 1_000L))
        assertEquals(DetailedCaptureArmResult.ALREADY_ARMED, store.arm("draft-a", null, "preview", 1_001L))
        assertEquals(DetailedCaptureArmResult.CAPTURE_EXISTS, store.arm("draft-b", null, "preview", 1_001L))
        assertTrue(store.deleteForDraft("draft-a"))
        assertEquals(DetailedCaptureArmResult.ARMED, store.arm("draft-b", null, "preview", 1_002L))
        root.deleteRecursively()
    }

    @Test
    fun explicitBoundsExposeTruncationAndCandidateOverflow() {
        val root = Files.createTempDirectory("detailed-capture-bounds").toFile()
        val file = File(root, "capture.json")
        val store = DetailedBottleLookupCaptureStore(file, { 1_000L }, processSessionId = "test")
        val token = "00000000-0000-0000-0000-000000000030"
        store.arm("draft-a", token, "preview", 1_000L)
        val capture = store.claim("draft-a", token, "5011333150725", "preview", 21, "Qwen", 1_000L)!!
        val hit = WebSearchHit("Title".repeat(100), "https://shop.example/product", "Snippet".repeat(100))
        capture.recordSearchSelection(List(6) { hit }, 0, "SELECTED_EXACT_MATCH")
        capture.recordValidatorInput(
            "On-device Qwen3", "5011333150725", "Source ".repeat(DetailedBottleLookupCaptureStore.MAX_SOURCE_CHARS / 7 + 2),
            "Title", emptyList(), StructuredIdentityStatus.STRUCTURED_DATA_MISSING,
        )
        capture.recordModelResponse("On-device Qwen3", "Qwen", "Reply ".repeat(DetailedBottleLookupCaptureStore.MAX_MODEL_RESPONSE_CHARS / 6 + 2))
        capture.finish("COMPLETE")

        val preview = store.previewForDraft("draft-a", 1_000L)!!
        assertTrue(preview.contains("\"candidatesTruncated\": true"))
        assertTrue(preview.contains("\"titleTruncated\": true"))
        assertTrue(preview.contains("\"snippetTruncated\": true"))
        assertTrue(preview.contains("\"sourceTextTruncated\": true"))
        assertTrue(preview.contains("\"responseTruncated\": true"))
        assertTrue(file.length() <= DetailedBottleLookupCaptureStore.MAX_CAPTURE_BYTES)
        root.deleteRecursively()
    }
}
