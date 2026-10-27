package com.davidhayesbc.cocktails.android.product

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Paint
import android.util.Size
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.camera.view.TransformExperimental
import androidx.annotation.OptIn
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
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [TransformExperimental::class])
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
        val analysisTransform = OutputTransform(Matrix().apply { setScale(400f, 300f) }, Size(400, 300))
        val previewTransform = OutputTransform(Matrix().apply { setScale(800f, 600f) }, Size(800, 600))
        val mapped = mapAnalysisCropToPreview(
            crop = source,
            analysisCropWidth = 400,
            analysisCropHeight = 300,
            sourceTransform = analysisTransform,
            previewTransform = previewTransform,
            viewWidth = 800,
            viewHeight = 600,
        )
        assertNotNull("Different analysis and preview resolutions must map through CameraX coordinates", mapped)
        assertCropClose(source, mapped!!)

        val portraitPreviewTransform = OutputTransform(Matrix().apply { setScale(600f, 800f) }, Size(600, 800))
        val portraitMapped = mapAnalysisCropToPreview(
            crop = source,
            analysisCropWidth = 400,
            analysisCropHeight = 300,
            sourceTransform = analysisTransform,
            previewTransform = portraitPreviewTransform,
            viewWidth = 600,
            viewHeight = 800,
        )
        assertNotNull("Preview rotation and scaling must preserve normalized crop bounds", portraitMapped)
        assertCropClose(source, portraitMapped!!)

        val squareIdentity = OutputTransform(Matrix(), Size(100, 100))
        val rotatedPreview = OutputTransform(Matrix().apply { setRotate(90f, 50f, 50f) }, Size(100, 100))
        val rotated = mapAnalysisCropToPreview(
            crop = source,
            analysisCropWidth = 100,
            analysisCropHeight = 100,
            sourceTransform = squareIdentity,
            previewTransform = rotatedPreview,
            viewWidth = 100,
            viewHeight = 100,
        )
        assertNotNull("A quarter-turn must be mapped by the camera transform", rotated)
        assertCropClose(NormalizedLabelCrop(0.2f, 0.2f, 0.7f, 0.6f), rotated!!)

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
    fun rawLumaCoordinatesUseUnrotatedImageProxyTransformForAsymmetric90And270Frames() {
        val cropRect = Rect(80, 20, 560, 460)
        val rawFactory = rawAnalysisTransformFactory()
        val previewTransform = normalizedViewportToPixels(cropRect.width(), cropRect.height())
        val detected = NormalizedLabelCrop(0.13f, 0.21f, 0.61f, 0.76f)
        val mapped = listOf(0, 90, 270).map { rotation ->
            val imageProxy = fakeImageProxy(640, 480, cropRect, rotation)
            try {
                val sourceTransform = rawFactory.getOutputTransform(imageProxy)
                mapAnalysisCropToPreview(
                    crop = detected,
                    analysisCropWidth = cropRect.width(),
                    analysisCropHeight = cropRect.height(),
                    sourceTransform = sourceTransform,
                    previewTransform = previewTransform,
                    viewWidth = cropRect.width(),
                    viewHeight = cropRect.height(),
                )
            } finally {
                imageProxy.close()
            }
        }
        mapped.forEach { result -> assertNotNull("The raw Y-plane rectangle should map in each display rotation", result) }
        assertCropClose(mapped[0]!!, mapped[1]!!)
        assertCropClose(mapped[0]!!, mapped[2]!!)

        // Guard against accidentally enabling CameraX rotation on coordinates that were
        // detected directly in the unrotated cropRect/Y-plane coordinate system.
        val rotatingFactory = ImageProxyTransformFactory().apply {
            setUsingCropRect(true)
            setUsingRotationDegrees(true)
        }
        val rotatedProxy = fakeImageProxy(640, 480, cropRect, 90)
        val rotateAgain = try {
            mapAnalysisCropToPreview(
                detected,
                cropRect.width(),
                cropRect.height(),
                rotatingFactory.getOutputTransform(rotatedProxy),
                previewTransform,
                cropRect.width(),
                cropRect.height(),
            )
        } finally {
            rotatedProxy.close()
        }
        assertNotNull(rotateAgain)
        assertTrue(
            "Applying ImageProxy rotation to the raw crop should visibly change this asymmetric rectangle",
            kotlin.math.abs(rotateAgain!!.left - mapped[0]!!.left) > 0.05f ||
                kotlin.math.abs(rotateAgain.top - mapped[0]!!.top) > 0.05f,
        )
    }

    private fun fakeImageProxy(width: Int, height: Int, crop: Rect, rotationDegrees: Int): ImageProxy {
        val imageInfo = Proxy.newProxyInstance(
            ImageInfo::class.java.classLoader,
            arrayOf(ImageInfo::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRotationDegrees" -> rotationDegrees
                "getTimestamp" -> 1L
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as ImageInfo
        return Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader,
            arrayOf(ImageProxy::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getFormat" -> android.graphics.ImageFormat.YUV_420_888
                "getCropRect" -> Rect(crop)
                "getImageInfo" -> imageInfo
                "getPlanes" -> emptyArray<ImageProxy.PlaneProxy>()
                "close" -> null
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as ImageProxy
    }

    /** CameraX OutputTransforms map normalized viewport coordinates to output pixels. */
    private fun normalizedViewportToPixels(width: Int, height: Int): OutputTransform = OutputTransform(
        Matrix().apply {
            setValues(
                floatArrayOf(
                    width / 2f, 0f, width / 2f,
                    0f, height / 2f, height / 2f,
                    0f, 0f, 1f,
                ),
            )
        },
        Size(width, height),
    )

    private fun assertCropClose(expected: NormalizedLabelCrop, actual: NormalizedLabelCrop, tolerance: Float = 0.00001f) {
        assertTrue("left ${actual.left} != ${expected.left}", kotlin.math.abs(actual.left - expected.left) <= tolerance)
        assertTrue("top ${actual.top} != ${expected.top}", kotlin.math.abs(actual.top - expected.top) <= tolerance)
        assertTrue("right ${actual.right} != ${expected.right}", kotlin.math.abs(actual.right - expected.right) <= tolerance)
        assertTrue("bottom ${actual.bottom} != ${expected.bottom}", kotlin.math.abs(actual.bottom - expected.bottom) <= tolerance)
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
