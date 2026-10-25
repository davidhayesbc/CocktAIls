package com.davidhayesbc.cocktails.android.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

internal data class LumaFrame(val pixels: ByteArray, val width: Int, val height: Int)

/** Copies the cropped Y plane to tightly packed luminance bytes, honoring CameraX row/pixel strides. */
internal fun copyLumaPlane(
    buffer: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = imageWidth,
    cropHeight: Int = imageHeight,
): LumaFrame {
    require(imageWidth > 0 && imageHeight > 0 && imageWidth.toLong() * imageHeight <= MAX_FRAME_PIXELS)
    require(rowStride > 0 && pixelStride > 0)
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
    require(cropLeft + cropWidth <= imageWidth && cropTop + cropHeight <= imageHeight)

    val view = buffer.duplicate()
    val bufferStart = view.position()
    val pixels = ByteArray(cropWidth * cropHeight)
    for (row in 0 until cropHeight) {
        for (column in 0 until cropWidth) {
            val index = bufferStart + (cropTop + row) * rowStride + (cropLeft + column) * pixelStride
            require(index < view.limit()) { "Camera luminance plane is shorter than its declared stride." }
            pixels[row * cropWidth + column] = view.get(index)
        }
    }
    return LumaFrame(pixels, cropWidth, cropHeight)
}

/** Rotates packed luminance bytes clockwise to the target orientation reported by ImageInfo. */
internal fun rotateLumaFrame(frame: LumaFrame, rotationDegrees: Int): LumaFrame {
    require(rotationDegrees in setOf(0, 90, 180, 270)) { "Camera rotation must be 0, 90, 180, or 270 degrees." }
    if (rotationDegrees == 0) return frame
    val outWidth = if (rotationDegrees == 180) frame.width else frame.height
    val outHeight = if (rotationDegrees == 180) frame.height else frame.width
    val result = ByteArray(outWidth * outHeight)
    for (y in 0 until frame.height) {
        for (x in 0 until frame.width) {
            val target = when (rotationDegrees) {
                90 -> x * outWidth + (frame.height - y - 1)
                180 -> (frame.height - y - 1) * outWidth + (frame.width - x - 1)
                else -> (frame.width - x - 1) * outWidth + y
            }
            result[target] = frame.pixels[y * frame.width + x]
        }
    }
    return LumaFrame(result, outWidth, outHeight)
}

internal fun decodeBarcodeFrame(reader: MultiFormatReader, frame: LumaFrame, rotationDegrees: Int): String {
    val upright = rotateLumaFrame(frame, rotationDegrees)
    val source = PlanarYUVLuminanceSource(
        upright.pixels,
        upright.width,
        upright.height,
        0,
        0,
        upright.width,
        upright.height,
        false,
    )
    return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
}

private const val MAX_FRAME_PIXELS = 4_000_000L
