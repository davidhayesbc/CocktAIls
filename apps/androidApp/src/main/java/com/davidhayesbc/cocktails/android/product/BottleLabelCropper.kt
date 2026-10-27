package com.davidhayesbc.cocktails.android.product

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.davidhayesbc.cocktails.core.interchange.isProductPhotoJpeg
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

/** Normalized rectangular label crop. Kept separate from the original JPEG until review is accepted. */
internal data class NormalizedLabelCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right - left >= MIN_CROP_SIDE && bottom - top >= MIN_CROP_SIDE)
    }

    companion object {
        const val MIN_CROP_SIDE = 0.08f
        val FULL_IMAGE = NormalizedLabelCrop(0f, 0f, 1f, 1f)
    }
}

/** Conservative on-device edge suggestion. It never changes a photo without user confirmation. */
internal object BottleLabelCropper {
    fun suggest(jpeg: ByteArray): NormalizedLabelCrop? {
        if (jpeg.isEmpty() || !OpenCVLoader.initLocal()) return null
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val analysisScale = minOf(1.0, MAX_ANALYSIS_DIMENSION.toDouble() / maxOf(decoded.width, decoded.height))
        val bitmap = if (analysisScale < 1.0) Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * analysisScale).toInt().coerceAtLeast(1),
            (decoded.height * analysisScale).toInt().coerceAtLeast(1),
            true,
        ) else decoded
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 45.0, 135.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val imageArea = (bitmap.width.toDouble() * bitmap.height).coerceAtLeast(1.0)
            val candidates = contours.mapNotNull { contour -> candidate(contour, imageArea, bitmap.width, bitmap.height) }
            val winner = candidates.sortedByDescending { it.score }.firstOrNull() ?: return null
            if (winner.score < MIN_CONFIDENCE) return null
            return winner.crop
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            edges.release()
            gray.release()
            rgba.release()
            bitmap.recycle()
            if (bitmap !== decoded) decoded.recycle()
        }
    }

    fun crop(jpeg: ByteArray, crop: NormalizedLabelCrop, rotationDegrees: Int = 0): ByteArray? {
        if (jpeg.isEmpty() || rotationDegrees !in setOf(0, 90, 180, 270)) return null
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val transform = Matrix().apply { if (rotationDegrees != 0) setRotate(rotationDegrees.toFloat()) }
        val rotated = if (rotationDegrees == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
        if (rotated !== decoded) decoded.recycle()
        val left = (crop.left * rotated.width).toInt().coerceIn(0, rotated.width - 1)
        val top = (crop.top * rotated.height).toInt().coerceIn(0, rotated.height - 1)
        val right = (crop.right * rotated.width).toInt().coerceIn(left + 1, rotated.width)
        val bottom = (crop.bottom * rotated.height).toInt().coerceIn(top + 1, rotated.height)
        val cropped = Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
        if (cropped !== rotated) rotated.recycle()
        val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(cropped.width, cropped.height))
        val finalBitmap = if (scale < 1.0) Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt().coerceAtLeast(1),
            (cropped.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== cropped) cropped.recycle() } else cropped
        try {
            for (quality in listOf(84, 76, 68, 60, 52)) {
                val output = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_BYTES && isProductPhotoJpeg(bytes)) {
                    return bytes
                }
            }
            return null
        } finally {
            finalBitmap.recycle()
        }
    }

    private fun candidate(contour: MatOfPoint, imageArea: Double, imageWidth: Int, imageHeight: Int): Candidate? {
        val points = MatOfPoint2f(*contour.toArray())
        return try {
            val perimeter = Imgproc.arcLength(points, true)
            if (perimeter <= 0.0) return null
            val approx = MatOfPoint2f()
            try {
                Imgproc.approxPolyDP(points, approx, perimeter * APPROXIMATION_RATIO, true)
                if (approx.total() != 4L) return null
                val polygon = MatOfPoint(*approx.toArray())
                try {
                    if (!Imgproc.isContourConvex(polygon)) return null
                    val rect = Imgproc.boundingRect(polygon)
                    val areaFraction = rect.area() / imageArea
                    val aspect = rect.width.toDouble() / rect.height.coerceAtLeast(1)
                    if (areaFraction !in MIN_AREA_FRACTION..MAX_AREA_FRACTION || aspect !in MIN_ASPECT..MAX_ASPECT) return null
                    val marginX = rect.x.toDouble() / imageWidth
                    val marginY = rect.y.toDouble() / imageHeight
                    val rightMargin = (imageWidth - rect.x - rect.width).toDouble() / imageWidth
                    val bottomMargin = (imageHeight - rect.y - rect.height).toDouble() / imageHeight
                    if (minOf(marginX, marginY, rightMargin, bottomMargin) < EDGE_MARGIN) return null
                    val polygonArea = Imgproc.contourArea(polygon)
                    val rectangularity = polygonArea / rect.area().coerceAtLeast(1.0)
                    if (rectangularity < MIN_RECTANGULARITY) return null
                    val paddingX = (rect.width * CROP_PADDING).toInt()
                    val paddingY = (rect.height * CROP_PADDING).toInt()
                    val left = (rect.x - paddingX).coerceAtLeast(0).toFloat() / imageWidth
                    val top = (rect.y - paddingY).coerceAtLeast(0).toFloat() / imageHeight
                    val right = (rect.x + rect.width + paddingX).coerceAtMost(imageWidth).toFloat() / imageWidth
                    val bottom = (rect.y + rect.height + paddingY).coerceAtMost(imageHeight).toFloat() / imageHeight
                    val crop = runCatching { NormalizedLabelCrop(left, top, right, bottom) }.getOrNull() ?: return null
                    val areaScore = (1.0 - kotlin.math.abs(areaFraction - TARGET_AREA_FRACTION)).coerceIn(0.0, 1.0)
                    Candidate(crop, rectangularity * (0.72 + 0.28 * areaScore))
                } finally {
                    polygon.release()
                }
            } finally {
                approx.release()
            }
        } finally {
            points.release()
        }
    }

    private data class Candidate(val crop: NormalizedLabelCrop, val score: Double)

    private const val APPROXIMATION_RATIO = 0.02
    private const val MIN_AREA_FRACTION = 0.035
    private const val MAX_AREA_FRACTION = 0.62
    private const val TARGET_AREA_FRACTION = 0.18
    private const val MIN_ASPECT = 0.38
    private const val MAX_ASPECT = 2.65
    private const val EDGE_MARGIN = 0.025
    private const val MIN_RECTANGULARITY = 0.76
    private const val MIN_CONFIDENCE = 0.72
    private const val CROP_PADDING = 0.025
    private const val MAX_DIMENSION = 1600
    private const val MAX_ANALYSIS_DIMENSION = 960
    private const val MAX_BYTES = 1024 * 1024
}
