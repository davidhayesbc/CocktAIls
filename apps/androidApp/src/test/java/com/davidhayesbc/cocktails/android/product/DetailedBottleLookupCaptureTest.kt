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
    fun nextLookupDebugModeIsClaimedByExactlyOneEligibleOnDeviceAttempt() {
        val root = Files.createTempDirectory("detailed-capture-next-lookup").toFile()
        val file = File(root, "capture.json")
        val store = DetailedBottleLookupCaptureStore(file, { 1_000L }, processSessionId = "test")
        assertEquals(DetailedCaptureArmResult.ARMED, store.armNextLookup("preview", 1_000L))
        assertTrue(store.currentStatus(1_000L)?.nextLookupArmed == true)

        assertNull(store.claim("cloud-draft", "00000000-0000-0000-0000-000000000091", "5011333150725", "preview", 1, "Ollama", 1_000L))
        val first = store.claim("local-draft-a", "00000000-0000-0000-0000-000000000092", "5011333150725", "preview", 1, "Qwen", 1_000L, allowNextLookup = true)
        assertNotNull(first)
        assertNull(store.claim("local-draft-b", "00000000-0000-0000-0000-000000000093", "088352109084", "preview", 1, "Qwen", 1_000L, allowNextLookup = true))
        assertEquals("local-draft-a", store.currentStatus(1_000L)?.draftId)
        first!!.recordModelResponse("On-device Qwen3", "Qwen", "partial response", generationFinishReason = "TOKEN_LIMIT", outputTokenLimitReached = true)
        first.finish("FAILED")

        val preview = store.previewForDraft("local-draft-a", 1_000L)!!
        assertTrue(preview.contains("partial response"))
        assertTrue(preview.contains("\"generationFinishReason\": \"TOKEN_LIMIT\""))
        assertTrue(preview.contains("\"outputTokenLimitReached\": true"))
        assertFalse(store.currentStatus(1_000L)?.nextLookupArmed == true)
        root.deleteRecursively()
    }

    @Test
    fun nextLookupDebugModeCanBeCancelledAndUnusedArmExpires() {
        val root = Files.createTempDirectory("detailed-capture-next-expiry").toFile()
        var now = 5_000L
        val file = File(root, "capture.json")
        val store = DetailedBottleLookupCaptureStore(file, { now }, processSessionId = "test")
        assertEquals(DetailedCaptureArmResult.ARMED, store.armNextLookup("preview", now))
        assertTrue(store.cancelNextLookup())
        assertFalse(file.exists())
        assertNull(store.currentStatus(now))

        assertEquals(DetailedCaptureArmResult.ARMED, store.armNextLookup("preview", now))
        now += DetailedBottleLookupCaptureStore.RETENTION_MILLIS + 1
        assertNull(store.currentStatus(now))
        assertFalse(file.exists())
        root.deleteRecursively()
    }

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

        val reviewedShare = store.exportReviewedCopyForDraft("draft-a", reviewedPreview, File(root, "cache"), now)!!
        assertEquals(reviewedPreview, reviewedShare.readText())
        assertFalse(reviewedShare.readText().contains("modelResponse"))

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
        val shareExpiry = Regex("^lookup-[0-9a-f]{12}-(\\d+)-[0-9a-fA-F-]{36}\\.json$")
            .matchEntire(share.name)?.groupValues?.get(1)?.toLong()
        assertEquals(now + 60 * 60 * 1000L, shareExpiry)
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

    @Test
    fun deletedOrExpiredCaptureCannotReuseAnAlreadyOpenPreviewForCopyOrShare() {
        val root = Files.createTempDirectory("detailed-capture-revocation").toFile()
        var now = 50_000L
        val file = File(root, "capture.json")
        val cache = File(root, "cache")
        val store = DetailedBottleLookupCaptureStore(file, { now }, shareCacheDirectory = cache, processSessionId = "test")
        val token = "00000000-0000-0000-0000-000000000040"
        store.arm("draft-a", token, "preview", now)
        store.claim("draft-a", token, "5011333150725", "preview", 22, "Qwen", now)!!.finish("COMPLETE")
        val reviewed = store.previewForDraft("draft-a", now)!!
        assertTrue(store.isReviewedPreviewCurrent("draft-a", reviewed, now))

        now += DetailedBottleLookupCaptureStore.RETENTION_MILLIS + 1
        var copied = false
        assertFalse(store.isReviewedPreviewCurrent("draft-a", reviewed, now))
        assertNull(store.previewForDraft("draft-a", now))
        assertFalse(store.copyReviewedPreviewForDraft("draft-a", reviewed, now) { copied = true })
        assertFalse(copied)
        assertNull(store.exportReviewedCopyForDraft("draft-a", reviewed, cache, now))
        assertFalse(file.exists())
        root.deleteRecursively()
    }

    @Test
    fun deletingDraftRemovesItsShareAndTemporaryFilesAndReportsDeletionFailures() {
        val root = Files.createTempDirectory("detailed-capture-delete-files").toFile()
        var failedShareName: String? = null
        val file = File(root, "capture.json")
        val cache = File(root, "cache")
        val store = DetailedBottleLookupCaptureStore(
            file,
            { 80_000L },
            deleteFile = { target -> if (target.name == failedShareName) false else target.delete() },
            shareCacheDirectory = cache,
            processSessionId = "test",
        )
        val token = "00000000-0000-0000-0000-000000000041"
        store.arm("draft-a", token, "preview", 80_000L)
        store.claim("draft-a", token, "5011333150725", "preview", 22, "Qwen", 80_000L)!!.finish("COMPLETE")
        val preview = store.previewForDraft("draft-a", 80_000L)!!
        val share = store.exportReviewedCopyForDraft("draft-a", preview, cache, 80_000L)!!
        val otherShare = store.exportReviewedCopyForDraft("draft-a", preview, cache, 80_000L)!!
        failedShareName = share.name
        val temporary = File(root, "capture.json.tmp")
        assertTrue(temporary.writeText("partial sensitive bytes").let { temporary.exists() })
        assertFalse(store.deleteForDraft("draft-a"))
        assertFalse(file.exists())
        assertFalse(temporary.exists())
        assertTrue(share.exists())
        assertFalse("deletion continues after one share file cannot be removed", otherShare.exists())

        failedShareName = null
        assertTrue(store.deleteForDraft("draft-a"))
        assertFalse(share.exists())
        assertFalse(store.isReviewedPreviewCurrent("draft-a", preview, 80_000L))
        root.deleteRecursively()
    }

    @Test
    fun cleanupExpiredRemovesExpiredCaptureTemporaryAndShareFilesAndReportsFailures() {
        val root = Files.createTempDirectory("detailed-capture-expired-cleanup").toFile()
        var now = 100_000L
        var failNextDelete = false
        var failedTarget: File? = null
        val file = File(root, "capture.json")
        val cache = File(root, "cache")
        val store = DetailedBottleLookupCaptureStore(
            file,
            { now },
            deleteFile = { target ->
                if (failNextDelete && target == failedTarget) false else target.delete()
            },
            shareCacheDirectory = cache,
            processSessionId = "test",
        )
        val token = "00000000-0000-0000-0000-000000000042"
        store.arm("draft-a", token, "preview", now)
        store.claim("draft-a", token, "5011333150725", "preview", 22, "Qwen", now)!!.finish("COMPLETE")
        val preview = store.previewForDraft("draft-a", now)!!
        val share = store.exportReviewedCopyForDraft("draft-a", preview, cache, now)!!
        val temporary = File(root, "capture.json.tmp").apply { writeText("partial capture") }
        failedTarget = share
        failNextDelete = true

        now += DetailedBottleLookupCaptureStore.RETENTION_MILLIS + 1
        assertFalse(store.cleanupExpired(now))
        assertFalse(file.exists())
        assertFalse(temporary.exists())
        assertTrue("a failed share deletion remains available for a later cleanup", share.exists())
        assertNull(store.previewForDraft("draft-a", now))

        failNextDelete = false
        assertTrue(store.cleanupExpired(now))
        assertFalse(share.exists())
        root.deleteRecursively()
    }

    @Test
    fun deleteDraftCleansItsTemporaryCaptureWhenPrimaryIsAlreadyMissing() {
        val root = Files.createTempDirectory("detailed-capture-missing-primary").toFile()
        val file = File(root, "capture.json")
        val store = DetailedBottleLookupCaptureStore(file, { 200_000L }, processSessionId = "test")
        val token = "00000000-0000-0000-0000-000000000043"
        store.arm("draft-a", token, "preview", 200_000L)
        store.claim("draft-a", token, "5011333150725", "preview", 22, "Qwen", 200_000L)!!.finish("COMPLETE")
        val temporary = File(root, "capture.json.tmp").apply {
            writeText(file.readText())
        }
        assertTrue(file.delete())

        assertTrue(store.deleteForDraft("draft-a"))
        assertFalse(temporary.exists())
        root.deleteRecursively()
    }

    @Test
    fun deletingAnUnreadablePrimaryCaptureDoesNotClaimItWasDeleted() {
        val root = Files.createTempDirectory("detailed-capture-unreadable-primary").toFile()
        val file = File(root, "capture.json")
        val store = DetailedBottleLookupCaptureStore(file, { 300_000L }, processSessionId = "test")
        file.writeText("not a valid capture")

        assertFalse(store.deleteForDraft("draft-a"))
        assertTrue(file.exists())
        assertTrue(store.deleteAll())
        assertFalse(file.exists())
        root.deleteRecursively()
    }
}
