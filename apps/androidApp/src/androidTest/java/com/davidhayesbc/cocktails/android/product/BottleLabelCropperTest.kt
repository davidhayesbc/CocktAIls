package com.davidhayesbc.cocktails.android.product

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.davidhayesbc.cocktails.core.interchange.isProductPhotoJpeg
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BottleLabelCropperTest {
    @Test
    fun liveEdgeDetectorFindsOnlyClearRectangularCandidates() {
        assertTrue("OpenCV should be available in the installed test APK", org.opencv.android.OpenCVLoader.initLocal())
        val width = 200
        val height = 200
        val luma = ByteArray(width * height) { 32 }
        for (y in 55 until 145) for (x in 65 until 135) luma[y * width + x] = 230.toByte()

        val candidate = LiveBottleLabelEdgeDetector.suggestLuma(width, height, luma)
        assertNotNull("A clear rectangular label should produce a conservative live candidate", candidate)
        assertTrue(candidate!!.left in 0.25f..0.38f)
        assertTrue(candidate.top in 0.20f..0.34f)
        assertTrue(candidate.right in 0.62f..0.75f)
        assertTrue(candidate.bottom in 0.66f..0.80f)
        assertNull("Uniform frames must not invent an outline", LiveBottleLabelEdgeDetector.suggestLuma(width, height, ByteArray(width * height) { 90 }))
    }

    @Test
    fun liveBoundaryRequiresStableFramesAndClearsAfterLostDetection() {
        val crop = NormalizedLabelCrop(0.2f, 0.25f, 0.8f, 0.75f)
        val stabilizer = LiveBottleLabelEdgeStabilizer(requiredStableFrames = 4)
        repeat(3) { assertFalse(stabilizer.update(crop)!!.stable) }
        assertTrue(stabilizer.update(crop)!!.stable)
        assertNull(stabilizer.update(null))
        assertFalse(stabilizer.update(crop)!!.stable)
    }

    @Test
    fun liveCropMappingAndCameraLumaRespectRotationCropAndStrides() {
        val source = NormalizedLabelCrop(0.2f, 0.3f, 0.6f, 0.8f)
        assertEquals(NormalizedLabelCrop(0.2f, 0.2f, 0.7f, 0.6f), rotateNormalizedCrop(source, 90))
        assertEquals(NormalizedLabelCrop(0.4f, 0.2f, 0.8f, 0.7f), rotateNormalizedCrop(source, 180))
        assertEquals(NormalizedLabelCrop(0.3f, 0.4f, 0.8f, 0.8f), rotateNormalizedCrop(source, 270))
        assertEquals(source, mapFillCenterCropToView(source, 400, 800, 400, 800))
        val centerCropped = mapFillCenterCropToView(source, 400, 800, 800, 400)
        assertNotNull(centerCropped)
        assertTrue(centerCropped!!.left in 0f..1f && centerCropped.right in 0f..1f)
        assertTrue(centerCropped.top in 0f..1f && centerCropped.bottom in 0f..1f)

        val width = 64
        val height = 64
        val rowStride = 128
        val pixelStride = 2
        val plane = ByteArray(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) plane[y * rowStride + x * pixelStride] = (y + x).toByte()
        val downsampled = downsampleCameraLuma(plane, width, height, rowStride, pixelStride, 4, 6, 32, 32, maxDimension = 32)
        assertNotNull(downsampled)
        assertEquals(32, downsampled!!.width)
        assertEquals((6 + 4).toByte(), downsampled.bytes[0])
        assertEquals((6 + 31 + 4 + 31).toByte(), downsampled.bytes.last())
        assertNull(downsampleCameraLuma(plane, width, height, rowStride, pixelStride, 60, 60, 32, 32, maxDimension = 32))
    }

    @Test
    fun suggestsAndCropsAConservativeRectangularLabelOnDevice() {
        val original = syntheticBottlePhoto(labelColor = Color.WHITE)
        assertTrue("The synthetic source must be a valid metadata-free JPEG", isProductPhotoJpeg(original))
        val suggestion = BottleLabelCropper.suggest(original)

        assertNotNull("The high-contrast centered label should be suggested", suggestion)
        val crop = suggestion!!
        assertTrue(crop.left in 0.15f..0.35f)
        assertTrue(crop.top in 0.20f..0.40f)
        assertTrue(crop.right in 0.65f..0.85f)
        assertTrue(crop.bottom in 0.60f..0.80f)

        val selected = BottleLabelCropper.crop(original, crop)
        assertNotNull(selected)
        assertTrue(isProductPhotoJpeg(selected!!))
        assertTrue(selected.size <= 1024 * 1024)
        assertTrue(selected.size < original.size)
    }

    @Test
    fun doesNotInventCropForUniformImageAndAllowsManualFullImage() {
        val uniform = syntheticBottlePhoto(labelColor = Color.DKGRAY, addLabel = false)

        assertTrue("The synthetic source must be a valid metadata-free JPEG", isProductPhotoJpeg(uniform))
        assertNull(BottleLabelCropper.suggest(uniform))
        val manual = BottleLabelCropper.crop(uniform, NormalizedLabelCrop.FULL_IMAGE)
        assertNotNull(manual)
        assertTrue(isProductPhotoJpeg(manual!!))
    }

    @Test
    fun removesMetadataSegmentsFromReencodedJpegWithoutChangingImageData() {
        val jpeg = syntheticBottlePhoto(labelColor = Color.WHITE)
        val scanStart = jpeg.indices.firstOrNull { index ->
            index + 1 < jpeg.size && jpeg[index] == 0xff.toByte() && jpeg[index + 1] == 0xda.toByte()
        } ?: error("Synthetic JPEG has no start-of-scan marker")
        // APP2 with a small ICC-profile-like payload, inserted before SOS.
        val app2 = byteArrayOf(0xff.toByte(), 0xe2.toByte(), 0x00, 0x06, 'I'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(), 0)
        val withMetadata = jpeg.copyOfRange(0, scanStart) + app2 + jpeg.copyOfRange(scanStart, jpeg.size)

        assertTrue(isProductPhotoJpeg(jpeg))
        assertFalse(isProductPhotoJpeg(withMetadata))
        val sanitized = stripJpegMetadata(withMetadata)
        assertNotNull(sanitized)
        assertTrue(isProductPhotoJpeg(sanitized!!))
    }

    private fun syntheticBottlePhoto(labelColor: Int, addLabel: Boolean = true): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(45, 50, 55))
        if (addLabel) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = labelColor }
            canvas.drawRect(230f, 300f, 570f, 700f, paint)
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        bitmap.recycle()
        return stripJpegMetadata(output.toByteArray()) ?: error("Synthetic JPEG metadata could not be removed")
    }
}
