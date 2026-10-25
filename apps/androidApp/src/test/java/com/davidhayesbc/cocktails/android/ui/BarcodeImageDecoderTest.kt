package com.davidhayesbc.cocktails.android.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BarcodeImageDecoderTest {
    @Test
    fun decodesUpcAFromEveryCameraRotation() {
        val upright = barcodeFrame("036000291452")
        for (rotation in listOf(0, 90, 180, 270)) {
            val cameraOrientation = rotateLumaFrame(upright, (360 - rotation) % 360)
            assertEquals("rotation=$rotation", "036000291452", decodeBarcodeFrame(reader(), cameraOrientation, rotation))
        }
    }

    @Test
    fun copiesCroppedLumaUsingNontrivialRowAndPixelStrides() {
        val expected = barcodeFrame("036000291452")
        val cropLeft = 3
        val cropTop = 2
        val imageWidth = expected.width + cropLeft + 4
        val imageHeight = expected.height + cropTop + 5
        val pixelStride = 2
        val rowStride = imageWidth * pixelStride + 7
        val start = 5
        val buffer = ByteBuffer.allocate(start + rowStride * imageHeight + 1)
        buffer.position(start)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                buffer.put(start + (cropTop + y) * rowStride + (cropLeft + x) * pixelStride, expected.pixels[y * expected.width + x])
            }
        }

        val copied = copyLumaPlane(
            buffer = buffer,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            rowStride = rowStride,
            pixelStride = pixelStride,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropWidth = expected.width,
            cropHeight = expected.height,
        )
        assertEquals(expected.width, copied.width)
        assertEquals(expected.height, copied.height)
        assertArrayEquals(expected.pixels, copied.pixels)
        assertEquals("036000291452", decodeBarcodeFrame(reader(), copied, 0))
    }

    @Test
    fun rejectsUnsupportedCameraRotationInsteadOfSilentlyMisdecoding() {
        assertThrows(IllegalArgumentException::class.java) { rotateLumaFrame(barcodeFrame("036000291452"), 45) }
    }

    @Test
    fun zxingPlanarYuvSourceThrowsWhenAskedToRotate() {
        val source = PlanarYUVLuminanceSource(byteArrayOf(0, 0, 0, 0), 2, 2, 0, 0, 2, 2, false)
        assertThrows(UnsupportedOperationException::class.java) { source.rotateCounterClockwise() }
    }

    private fun barcodeFrame(contents: String): LumaFrame {
        val matrix = MultiFormatWriter().encode(contents, BarcodeFormat.UPC_A, 640, 240)
        val pixels = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) 0 else 0xff.toByte()
            }
        }
        return LumaFrame(pixels, matrix.width, matrix.height)
    }

    private fun reader() = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.UPC_A, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13),
            DecodeHintType.TRY_HARDER to true,
        ))
    }
}
