package com.davidhayesbc.cocktails.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UuidV7Test {
    @Test
    fun formatsCanonicalUuidV7WithRfcVariant() {
        repeat(256) { seed ->
            val value = newUuidV7(1_766_668_800_123, Random(seed))
            assertTrue(CANONICAL_UUID.matches(value), "Not a canonical UUID: $value")
            val parsedTimestamp = parseCanonicalUuidV7Timestamp(value)
            assertEquals(1_766_668_800_123L, parsedTimestamp)
        }
    }

    @Test
    fun parserRejectsTheMalformedVariantGroupEmittedByThePreviousGenerator() {
        assertNull(parseCanonicalUuidV7Timestamp("00000000-0000-7000--800-000000000000"))
    }

    @Test
    fun timestampSortsBeforeRandomBits() {
        val earlier = newUuidV7(10, Random(1))
        val later = newUuidV7(11, Random(0))
        assertTrue(earlier < later)
    }

    @Test
    fun rejectsNegativeEpoch() {
        assertFailsWith<IllegalArgumentException> { newUuidV7(-1, Random(0)) }
    }

    @Test
    fun timestampUsesTheUuidV7FortyEightBitField() {
        val epoch = 0x1_2345_6789_ABCDL
        val value = newUuidV7(epoch, Random(0))
        assertEquals((epoch and 0xFFFFFFFFFFFFL).toString(16).padStart(12, '0'), value.take(8) + value.substring(9, 13))
    }
}

private val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

private fun parseCanonicalUuidV7Timestamp(value: String): Long? {
    if (!CANONICAL_UUID.matches(value) || value[14] != '7' || value[19] !in "89ab") return null
    return runCatching { (value.substring(0, 8) + value.substring(9, 13)).toLong(16) }.getOrNull()
}
