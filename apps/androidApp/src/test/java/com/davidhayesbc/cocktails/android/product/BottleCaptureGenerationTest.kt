package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottleCaptureGenerationTest {
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
}
