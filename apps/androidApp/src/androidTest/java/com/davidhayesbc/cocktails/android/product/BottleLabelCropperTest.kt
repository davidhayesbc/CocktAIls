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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BottleLabelCropperTest {
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
        return output.toByteArray()
    }
}
