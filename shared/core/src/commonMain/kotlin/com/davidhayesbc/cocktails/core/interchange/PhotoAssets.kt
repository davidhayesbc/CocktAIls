package com.davidhayesbc.cocktails.core.interchange

const val MAX_PRODUCT_PHOTO_BYTES = 1024 * 1024
const val MAX_BUNDLE_PHOTO_BYTES = 6 * 1024 * 1024

fun encodePhotoBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index < bytes.size) {
        val a = bytes[index].toInt() and 0xff
        val b = bytes.getOrNull(index + 1)?.toInt()?.and(0xff)
        val c = bytes.getOrNull(index + 2)?.toInt()?.and(0xff)
        output.append(alphabet[a ushr 2])
        output.append(alphabet[((a and 0x03) shl 4) or ((b ?: 0) ushr 4)])
        output.append(if (b == null) '=' else alphabet[((b and 0x0f) shl 2) or ((c ?: 0) ushr 6)])
        output.append(if (c == null) '=' else alphabet[c and 0x3f])
        index += 3
    }
    return output.toString()
}

/** Strict canonical RFC 4648 base64 decoder; invalid padding and nonzero pad bits are rejected. */
fun decodePhotoBase64(encoded: String): ByteArray? {
    if (encoded.length % 4 != 0) return null
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = ByteArray((encoded.length / 4) * 3 - when {
        encoded.endsWith("==") -> 2
        encoded.endsWith("=") -> 1
        else -> 0
    })
    var outputIndex = 0
    for (offset in encoded.indices step 4) {
        val last = offset + 4 == encoded.length
        val c0 = alphabet.indexOf(encoded[offset])
        val c1 = alphabet.indexOf(encoded[offset + 1])
        val c2 = if (encoded[offset + 2] == '=' && last) 0 else alphabet.indexOf(encoded[offset + 2])
        val c3 = if (encoded[offset + 3] == '=' && last) 0 else alphabet.indexOf(encoded[offset + 3])
        if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null
        val pad2 = last && encoded[offset + 2] == '='
        val pad1 = last && encoded[offset + 3] == '='
        if ((pad2 && !pad1) || (!last && (encoded[offset + 2] == '=' || encoded[offset + 3] == '='))) return null
        if (pad2 && (c1 and 0x0f) != 0) return null
        if (pad1 && !pad2 && (c2 and 0x03) != 0) return null
        val bits = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
        if (outputIndex < output.size) output[outputIndex++] = (bits ushr 16).toByte()
        if (outputIndex < output.size) output[outputIndex++] = (bits ushr 8).toByte()
        if (outputIndex < output.size) output[outputIndex++] = bits.toByte()
    }
    return output
}

/** SHA-256 used to detect corruption in optional, user-supplied household photo assets. */
fun photoSha256(bytes: ByteArray): String {
    val constants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1,
        0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(),
        0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(),
        0xd5a79147.toInt(), 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(), 0xa2bfe8a1.toInt(), 0xa81a664b.toInt(),
        0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(),
        0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
        0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(),
        0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
    val state = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
    val paddedLength = ((bytes.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedLength)
    bytes.copyInto(padded)
    padded[bytes.size] = 0x80.toByte()
    val bitLength = bytes.size.toLong() * 8L
    for (byteIndex in 0 until 8) padded[paddedLength - 1 - byteIndex] = (bitLength ushr (byteIndex * 8)).toByte()

    fun rotateRight(value: Int, amount: Int): Int = (value ushr amount) or (value shl (32 - amount))
    for (chunk in 0 until paddedLength step 64) {
        val words = IntArray(64)
        for (index in 0 until 16) {
            val offset = chunk + index * 4
            words[index] = ((padded[offset].toInt() and 0xff) shl 24) or
                ((padded[offset + 1].toInt() and 0xff) shl 16) or
                ((padded[offset + 2].toInt() and 0xff) shl 8) or
                (padded[offset + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val x = words[index - 15]
            val y = words[index - 2]
            val sigma0 = rotateRight(x, 7) xor rotateRight(x, 18) xor (x ushr 3)
            val sigma1 = rotateRight(y, 17) xor rotateRight(y, 19) xor (y ushr 10)
            words[index] = words[index - 16] + sigma0 + words[index - 7] + sigma1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (index in 0 until 64) {
            val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choice = (e and f) xor (e.inv() and g)
            val temp1 = h + sum1 + choice + constants[index] + words[index]
            val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = sum0 + majority
            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }
    return state.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}

fun isProductPhotoJpeg(bytes: ByteArray): Boolean {
    if (bytes.size < 4 || bytes.size > MAX_PRODUCT_PHOTO_BYTES ||
        bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
        bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()
    ) return false
    // Walk JPEG marker segments rather than searching arbitrary bytes: this
    // rejects truncated/fake JPEG headers and metadata while allowing marker
    // byte patterns inside length-delimited segments and entropy-coded data.
    var offset = 2
    var inScan = false
    var sawFrame = false
    var sawScan = false
    while (offset < bytes.size) {
        var marker: Int
        if (inScan) {
            while (offset < bytes.size && bytes[offset] != 0xff.toByte()) offset++
            if (offset >= bytes.size) return false
            while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
            if (offset >= bytes.size) return false
            marker = bytes[offset++].toInt() and 0xff
            if (marker == 0x00 || marker in 0xd0..0xd7) continue
            inScan = false
        } else {
            if (bytes[offset] != 0xff.toByte()) return false
            while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
            if (offset >= bytes.size) return false
            marker = bytes[offset++].toInt() and 0xff
        }
        if (marker == 0xd9) return sawFrame && sawScan && offset == bytes.size
        if (marker == 0xd8 || marker == 0x00) return false
        if (marker in 0xe1..0xef || marker == 0xfe) return false
        if (marker == 0x01 || marker in 0xd0..0xd7) continue
        if (offset + 2 > bytes.size) return false
        val segmentLength = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        if (segmentLength < 2 || offset + segmentLength > bytes.size) return false
        val isFrame = marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)
        if (isFrame) sawFrame = true
        if (marker == 0xda) {
            if (!sawFrame) return false
            sawScan = true
            inScan = true
        }
        offset += segmentLength
    }
    return false
}
