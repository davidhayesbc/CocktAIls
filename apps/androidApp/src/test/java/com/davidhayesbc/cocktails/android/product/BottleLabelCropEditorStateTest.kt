package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun draggingEachCornerToImageEdgesKeepsCropValidAndRotatable() {
        val starting = BottleLabelCropEditorState(NormalizedLabelCrop.FULL_IMAGE)
        val edgeCrops = listOf(
            starting.move(0, 10f, 10f),
            starting.move(1, -10f, 10f),
            starting.move(2, -10f, -10f),
            starting.move(3, 10f, -10f),
        )

        edgeCrops.forEach { state ->
            assertTrue(state.crop.left in 0f..1f && state.crop.right in 0f..1f)
            assertTrue(state.crop.top in 0f..1f && state.crop.bottom in 0f..1f)
            assertTrue(state.crop.right - state.crop.left >= NormalizedLabelCrop.MIN_CROP_SIDE - 0.000001f)
            assertTrue(state.crop.bottom - state.crop.top >= NormalizedLabelCrop.MIN_CROP_SIDE - 0.000001f)
            var rotated = state
            repeat(4) {
                rotated = rotated.rotateClockwise()
                assertTrue(rotated.crop.right > rotated.crop.left)
                assertTrue(rotated.crop.bottom > rotated.crop.top)
            }
            assertEquals(0, rotated.rotationDegrees)
            assertTrue(rotated.crop.right > rotated.crop.left)
            assertTrue(rotated.crop.bottom > rotated.crop.top)
            assertEquals(0, rotated.reset(null).rotationDegrees)
        }
    }
}
