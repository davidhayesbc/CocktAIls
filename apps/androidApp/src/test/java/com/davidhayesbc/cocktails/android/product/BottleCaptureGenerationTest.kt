package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertFalse
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
}
