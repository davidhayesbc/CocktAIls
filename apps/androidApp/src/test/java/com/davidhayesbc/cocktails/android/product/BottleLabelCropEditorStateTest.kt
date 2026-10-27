package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Test

class BottleLabelCropEditorStateTest {
    @Test
    fun rotationChangesCropCoordinatesAndResetRestoresSuggestionAndZeroRotation() {
        val suggested = NormalizedLabelCrop(0.2f, 0.25f, 0.8f, 0.75f)
        val rotated = BottleLabelCropEditorState(suggested).rotateClockwise()

        assertEquals(90, rotated.rotationDegrees)
        assertEquals(NormalizedLabelCrop(0.25f, 0.2f, 0.75f, 0.8f), rotated.crop)

        assertEquals(BottleLabelCropEditorState(suggested), rotated.reset(suggested))
    }

    @Test
    fun resetWithoutSuggestionReturnsWholeUnrotatedPhoto() {
        val rotated = BottleLabelCropEditorState(NormalizedLabelCrop(0.1f, 0.1f, 0.9f, 0.9f), 180)

        assertEquals(BottleLabelCropEditorState(NormalizedLabelCrop.FULL_IMAGE), rotated.reset(null))
    }

    @Test
    fun successiveDragUpdatesAccumulateFromLatestCropState() {
        val initial = BottleLabelCropEditorState(NormalizedLabelCrop(0.2f, 0.2f, 0.8f, 0.8f))
        val first = initial.move(handle = 4, dx = 0.05f, dy = 0f)
        val second = first.move(handle = 4, dx = 0.05f, dy = 0f)

        assertEquals(0.3f, second.crop.left, 0.0001f)
        assertEquals(0.9f, second.crop.right, 0.0001f)
    }
}
