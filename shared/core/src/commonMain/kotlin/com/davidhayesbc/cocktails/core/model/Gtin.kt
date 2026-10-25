package com.davidhayesbc.cocktails.core.model

/** Returns a UPC/EAN lookup form after checking the GTIN check digit. Twelve-digit UPC-A is padded to GTIN-13. */
fun normalizeGtinForLookup(raw: String): String? {
    val digits = buildString {
        raw.trim().forEach { character ->
            when {
                character in '0'..'9' -> append(character)
                character == ' ' || character == '-' -> Unit
                else -> return null
            }
        }
    }
    if (digits.length !in setOf(8, 12, 13, 14)) return null

    val expectedCheckDigit = digits.dropLast(1).reversed().mapIndexed { index, digit ->
        digit.digitToInt() * if (index % 2 == 0) 3 else 1
    }.sum().let { (10 - it % 10) % 10 }
    if (expectedCheckDigit != digits.last().digitToInt()) return null

    return if (digits.length == 12) "0$digits" else digits
}
