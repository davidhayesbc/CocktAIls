package com.davidhayesbc.cocktails.android.product

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

internal data class LiveBottleLabelBoundary(
    val crop: NormalizedLabelCrop,
    val stable: Boolean,
)

internal data class DownsampledLuma(val width: Int, val height: Int, val bytes: ByteArray)

/** Detects only conservative rectangular candidates from a downsampled luminance frame. */
internal object LiveBottleLabelEdgeDetector {
    fun suggestLuma(width: Int, height: Int, luma: ByteArray): NormalizedLabelCrop? {
        if (width < 32 || height < 32 || luma.size != width * height) return null
        val gray = Mat(height, width, CvType.CV_8UC1)
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            gray.put(0, 0, luma)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 48.0, 132.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val imageArea = width.toDouble() * height
            val candidates = contours.asSequence().take(MAX_CONTOURS_PER_FRAME).mapNotNull { contour ->
                val points = MatOfPoint2f(*contour.toArray())
                try {
                    val perimeter = Imgproc.arcLength(points, true)
                    if (perimeter < 1.0) return@mapNotNull null
                    val approx = MatOfPoint2f()
                    try {
                        Imgproc.approxPolyDP(points, approx, perimeter * 0.018, true)
                        if (approx.total() != 4L) return@mapNotNull null
                        val polygon = MatOfPoint(*approx.toArray())
                        try {
                            if (!Imgproc.isContourConvex(polygon)) return@mapNotNull null
                            val rect = Imgproc.boundingRect(polygon)
                            val areaFraction = rect.area() / imageArea
                            val aspect = rect.width.toDouble() / rect.height.coerceAtLeast(1)
                            if (areaFraction !in 0.025..0.58 || aspect !in 0.36..2.7) return@mapNotNull null
                            val margin = minOf(
                                rect.x.toDouble() / width,
                                rect.y.toDouble() / height,
                                (width - rect.x - rect.width).toDouble() / width,
                                (height - rect.y - rect.height).toDouble() / height,
                            )
                            if (margin < 0.008) return@mapNotNull null
                            val rectangularity = Imgproc.contourArea(polygon) / rect.area().coerceAtLeast(1.0)
                            if (rectangularity < 0.70) return@mapNotNull null
                            val padX = (rect.width * 0.035).toInt()
                            val padY = (rect.height * 0.035).toInt()
                            val crop = runCatching { NormalizedLabelCrop(
                                left = (rect.x - padX).coerceAtLeast(0).toFloat() / width,
                                top = (rect.y - padY).coerceAtLeast(0).toFloat() / height,
                                right = (rect.x + rect.width + padX).coerceAtMost(width).toFloat() / width,
                                bottom = (rect.y + rect.height + padY).coerceAtMost(height).toFloat() / height,
                            ) }.getOrNull() ?: return@mapNotNull null
                            val expectedArea = 0.20
                            val areaScore = 1.0 - (abs(areaFraction - expectedArea) / 0.58).coerceIn(0.0, 1.0)
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
            }.toList()
            val best = candidates.maxByOrNull(Candidate::score) ?: return null
            return best.crop.takeIf { best.score >= MIN_SCORE }
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            edges.release()
            gray.release()
        }
    }

    private data class Candidate(val crop: NormalizedLabelCrop, val score: Double)
    private const val MIN_SCORE = 0.68
    private const val MAX_CONTOURS_PER_FRAME = 384
}

/** Requires repeated nearby detections before drawing the outline as stable. */
internal class LiveBottleLabelEdgeStabilizer(
    private val requiredStableFrames: Int = 4,
    private val tolerance: Float = 0.035f,
) {
    private val recent = ArrayDeque<NormalizedLabelCrop>()

    init {
        require(requiredStableFrames >= 2)
        require(tolerance > 0f && tolerance < 0.2f)
    }

    fun update(candidate: NormalizedLabelCrop?): LiveBottleLabelBoundary? {
        if (candidate == null) {
            recent.clear()
            return null
        }
        recent.addLast(candidate)
        while (recent.size > requiredStableFrames) recent.removeFirst()
        val average = NormalizedLabelCrop(
            recent.map { it.left }.average().toFloat(),
            recent.map { it.top }.average().toFloat(),
            recent.map { it.right }.average().toFloat(),
            recent.map { it.bottom }.average().toFloat(),
        )
        val stable = recent.size == requiredStableFrames && recent.all { close(it, average) }
        return LiveBottleLabelBoundary(average, stable)
    }

    private fun close(first: NormalizedLabelCrop, second: NormalizedLabelCrop): Boolean =
        abs(first.left - second.left) <= tolerance && abs(first.top - second.top) <= tolerance &&
            abs(first.right - second.right) <= tolerance && abs(first.bottom - second.bottom) <= tolerance
}

/** Camera analysis frames are often rotated relative to the portrait preview. */
internal fun rotateNormalizedCrop(crop: NormalizedLabelCrop, rotationDegrees: Int): NormalizedLabelCrop = when (rotationDegrees) {
    0 -> crop
    90 -> NormalizedLabelCrop(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
    180 -> NormalizedLabelCrop(1f - crop.right, 1f - crop.bottom, 1f - crop.left, 1f - crop.top)
    270 -> NormalizedLabelCrop(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left)
    else -> error("Camera rotation must be 0, 90, 180 or 270 degrees.")
}

/** Maps a rotated normalized box through PreviewView's center-crop geometry. */
internal fun mapFillCenterCropToView(
    crop: NormalizedLabelCrop,
    sourceWidth: Int,
    sourceHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
): NormalizedLabelCrop? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
    val scale = max(viewWidth.toFloat() / sourceWidth, viewHeight.toFloat() / sourceHeight)
    val renderedWidth = sourceWidth * scale
    val renderedHeight = sourceHeight * scale
    val offsetX = (viewWidth - renderedWidth) / 2f
    val offsetY = (viewHeight - renderedHeight) / 2f
    fun x(value: Float) = ((value * renderedWidth + offsetX) / viewWidth).coerceIn(0f, 1f)
    fun y(value: Float) = ((value * renderedHeight + offsetY) / viewHeight).coerceIn(0f, 1f)
    val left = x(crop.left)
    val top = y(crop.top)
    val right = x(crop.right)
    val bottom = y(crop.bottom)
    if (right - left < NormalizedLabelCrop.MIN_CROP_SIDE || bottom - top < NormalizedLabelCrop.MIN_CROP_SIDE) return null
    return NormalizedLabelCrop(left, top, right, bottom)
}

/** Copies and downsamples only the Y plane while honoring CameraX strides and crop bounds. */
internal fun downsampleCameraLuma(
    plane: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    maxDimension: Int = 480,
): DownsampledLuma? {
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0 || cropWidth < 32 || cropHeight < 32) return null
    val scale = maxOf(1, kotlin.math.ceil(maxOf(cropWidth, cropHeight).toDouble() / maxDimension).toInt())
    val outWidth = cropWidth / scale
    val outHeight = cropHeight / scale
    if (outWidth < 32 || outHeight < 32) return null
    val output = ByteArray(outWidth * outHeight)
    for (y in 0 until outHeight) {
        val sourceY = cropTop + y * scale
        for (x in 0 until outWidth) {
            val sourceX = cropLeft + x * scale
            val index = sourceY * rowStride + sourceX * pixelStride
            if (sourceX !in 0 until width || sourceY !in 0 until height || index !in plane.indices) return null
            output[y * outWidth + x] = plane[index]
        }
    }
    return DownsampledLuma(outWidth, outHeight, output)
}
