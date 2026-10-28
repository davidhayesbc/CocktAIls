package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BottleCaptureGenerationTest {
    @Test
    fun changingQueueLabelOwnerCannotReuseThePreviousBottlesPreview() {
        val firstPhoto = byteArrayOf(1, 2, 3)
        val firstCrop = NormalizedLabelCrop(0.1f, 0.1f, 0.9f, 0.9f)

        assertNull(retainPendingBottleValueForOwner("draft-a", "draft-b", firstPhoto))
        assertNull(retainPendingBottleValueForOwner("draft-a", "draft-b", firstCrop))
        assertArrayEquals(firstPhoto, retainPendingBottleValueForOwner("draft-a", "draft-a", firstPhoto))
    }

    @Test
    fun delayedPrivatePhotoRestoreCannotPopulateADifferentDraft() {
        assertFalse(bottleLabelRestoreBelongsToCurrentDraft("draft-a", "draft-b"))
        assertFalse(bottleLabelRestoreBelongsToCurrentDraft("draft-a", null))
        assertTrue(bottleLabelRestoreBelongsToCurrentDraft("draft-b", "draft-b"))
    }

    @Test
    fun lateCropFromSkippedBottleCannotAttachToTheNextBottle() {
        val first = BottleCaptureToken(10, "draft-first")
        val second = BottleCaptureToken(12, "draft-second")

        assertFalse(isCurrentBottleCapture(first, second.generation, second.draftId))
        assertTrue(isCurrentBottleCapture(second, second.generation, second.draftId))
    }

    @Test
    fun retakeInvalidatesPreviousDecodeEvenForSameDraft() {
        val oldCapture = BottleCaptureToken(4, "draft")
        val retake = BottleCaptureToken(5, "draft")

        assertFalse(isCurrentBottleCapture(oldCapture, retake.generation, retake.draftId))
        assertTrue(isCurrentBottleCapture(retake, retake.generation, retake.draftId))
    }

    @Test
    fun discardInvalidatesCaptureWithNoCurrentDraft() {
        val capture = BottleCaptureToken(7, "draft")

        assertFalse(isCurrentBottleCapture(capture, 8, null))
    }

    @Test
    fun everyRetakeGetsASeparateTemporaryFileForTheSameBottle() {
        val first = BottleCaptureToken(20, "draft")
        val retake = BottleCaptureToken(21, "draft")

        assertTrue(bottleCaptureTempFileName(first) != bottleCaptureTempFileName(retake))
        assertTrue(bottleCaptureTempFileName(first).endsWith("-draft.jpg"))
    }

    @Test
    fun delayedCompletionCannotChangeAReplacedPendingDraft() {
        val firstBottle = BottleCaptureToken(31, "draft-first")
        val secondBottle = BottleCaptureToken(32, "draft-second")

        assertFalse(isCurrentBottleCapture(firstBottle, secondBottle.generation, secondBottle.draftId))
    }

    @Test
    fun externalCameraLaunchTokenCanBeRestoredAcrossActivityRecreation() {
        val restored = restoreBottleCaptureToken(
            launchPending = true,
            generation = 42,
            draftId = "draft-pending",
            manualCapture = false,
            fileName = "capture-42-draft-pending.jpg",
        )

        assertEquals(BottleCaptureToken(42, "draft-pending"), restored)
        assertTrue(bottleCaptureDraftIsStillOpen(restored!!, setOf("draft-pending")))
        assertFalse(bottleCaptureDraftIsStillOpen(restored, emptySet()))
    }

    @Test
    fun invalidOrMissingCameraLaunchStateIsNotRestoredAndManualCaptureHasNoDraft() {
        assertNull(restoreBottleCaptureToken(false, 42, "draft", false, "capture-42-draft.jpg"))
        assertNull(restoreBottleCaptureToken(true, 0, "draft", false, "capture-0-draft.jpg"))
        assertNull(restoreBottleCaptureToken(true, 42, "bad draft", false, "capture-42-bad draft.jpg"))
        assertNull(restoreBottleCaptureToken(true, 42, "draft", false, "capture-99-draft.jpg"))
        assertEquals(BottleCaptureToken(42, null), restoreBottleCaptureToken(true, 42, null, true, "capture-42-manual.jpg"))
    }

    @Test
    fun staleDecodeCompletionCannotClearANewerCaptureOperation() {
        val old = BottleCaptureToken(50, "draft-old")
        val current = BottleCaptureToken(51, "draft-new")

        assertEquals(current, processingBottleCaptureAfterCompletion(current, old))
        assertNull(processingBottleCaptureAfterCompletion(old, old))
    }

    @Test
    fun delayedCameraCallbackKeepsTheTokenThatCreatedIt() {
        val first = BottleCaptureToken(60, "draft-first")
        val second = BottleCaptureToken(61, "draft-second")
        val current = second
        var callbackToken: BottleCaptureToken? = null
        val callback = bindBottleCaptureCallback(first) { callbackToken = it }

        callback()

        assertEquals(first, callbackToken)
        assertEquals("A late first result must not take ownership of the second capture", second, current)
        assertFalse(isCurrentBottleCapture(first, current.generation, current.draftId))
    }

    @Test
    fun failedRetakeKeepsThePreviouslyReviewablePhotoAndCrop() {
        val previous = PendingBottlePhoto(
            selected = byteArrayOf(1, 2, 3),
            original = byteArrayOf(4, 5, 6),
            suggestedCrop = NormalizedLabelCrop(0.2f, 0.2f, 0.8f, 0.8f),
        )

        val afterFailure = pendingBottlePhotoAfterCapture(previous, decodedPhoto = null, suggestedCrop = null)
        assertArrayEquals(previous.selected, afterFailure.selected)
        assertArrayEquals(previous.original, afterFailure.original)
        assertEquals(previous.suggestedCrop, afterFailure.suggestedCrop)

        val replacement = byteArrayOf(9, 8, 7)
        val afterSuccess = pendingBottlePhotoAfterCapture(previous, replacement, suggestedCrop = null)
        assertArrayEquals(replacement, afterSuccess.selected)
        assertArrayEquals(replacement, afterSuccess.original)
        assertNull(afterSuccess.suggestedCrop)
    }

    @Test
    fun oldFrameAndOutlineCannotCrossAViewportGenerationChange() {
        assertFalse(cameraFrameBelongsToGeometry(frameGeneration = 10, currentGeneration = 11))
        assertTrue(cameraFrameBelongsToGeometry(frameGeneration = 11, currentGeneration = 11))
        assertFalse(stableBoundaryCanSeedCapture(isStable = true, boundaryGeneration = 10, currentGeneration = 11))
        assertFalse(stableBoundaryCanSeedCapture(isStable = false, boundaryGeneration = 11, currentGeneration = 11))
        assertTrue(stableBoundaryCanSeedCapture(isStable = true, boundaryGeneration = 11, currentGeneration = 11))
    }

    @Test
    fun sameSizeDisplayRotationStillRequiresCameraViewportRebind() {
        assertFalse(cameraGeometryChanged(Pair(480, 440), Pair(480, 440), 0, 0))
        assertTrue(cameraGeometryChanged(Pair(480, 440), Pair(480, 440), 0, 2))
        assertTrue(cameraGeometryChanged(Pair(480, 440), Pair(440, 480), 0, 1))
        assertTrue(cameraGeometryChanged(null, Pair(480, 440), null, 0))
    }
}
