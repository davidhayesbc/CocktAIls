package com.davidhayesbc.cocktails.core.interchange

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoAssetsTest {
    @Test
    fun sha256MatchesStandardVectorAndBase64RoundTrips() {
        val bytes = "abc".encodeToByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", photoSha256(bytes))
        assertEquals("aGVsbG8=", encodePhotoBase64("hello".encodeToByteArray()))
        assertContentEquals("hello".encodeToByteArray(), decodePhotoBase64("aGVsbG8="))
        assertContentEquals(ByteArray(0), decodePhotoBase64(""))
    }

    @Test
    fun rejectsMalformedBase64AndImagesContainingPrivateMetadataSegments() {
        assertNull(decodePhotoBase64("aGVsbG8"))
        assertNull(decodePhotoBase64("aG=sbG8="))
        assertNull(decodePhotoBase64("aGVsbG9=")) // non-canonical pad bits
        val plainJpeg = minimalJpeg()
        val exifJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x00, 0x08, 1, 2, 3, 4) + plainJpeg.copyOfRange(2, plainJpeg.size)
        assertTrue(isProductPhotoJpeg(plainJpeg))
        assertFalse(isProductPhotoJpeg(exifJpeg))
        assertFalse(isProductPhotoJpeg(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0xff.toByte(), 0xd9.toByte())))
        assertFalse(isProductPhotoJpeg(plainJpeg.copyOf(plainJpeg.size - 2) + byteArrayOf(0, 0)))
        assertFalse(isProductPhotoJpeg(plainJpeg + ByteArray(MAX_PRODUCT_PHOTO_BYTES)))
    }

    private fun minimalJpeg() = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), // SOI
        0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
        0xff.toByte(), 0xda.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00,
        0x00,
        0xff.toByte(), 0xd9.toByte(), // EOI
    )
}
