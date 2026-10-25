package com.davidhayesbc.cocktails.core.model

import kotlin.random.Random

/** Creates a sortable, server-independent RFC 9562 UUIDv7 string. */
fun newUuidV7(epochMillis: Long, random: Random = Random.Default): String {
    require(epochMillis >= 0) { "epochMillis must be non-negative" }
    val timestamp = (epochMillis and TIMESTAMP_MASK).toString(16).padStart(12, '0')
    val randomA = random.nextInt(RANDOM_A_BOUND).toString(16).padStart(3, '0')
    val randomB = (random.nextLong() and RANDOM_B_MASK or Long.MIN_VALUE).toString(16)

    return buildString(UUID_LENGTH) {
        append(timestamp, 0, 8)
        append('-')
        append(timestamp, 8, 12)
        append("-7")
        append(randomA)
        append('-')
        append(randomB, 0, 4)
        append('-')
        append(randomB, 4, 16)
    }
}

private const val TIMESTAMP_MASK = 0xFFFFFFFFFFFFL
private const val RANDOM_A_BOUND = 0x1000
private const val RANDOM_B_MASK = 0x3FFFFFFFFFFFFFFFL
private const val UUID_LENGTH = 36
