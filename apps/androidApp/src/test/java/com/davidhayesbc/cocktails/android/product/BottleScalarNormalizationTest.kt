package com.davidhayesbc.cocktails.android.product

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BottleScalarNormalizationTest {
    private fun parse(field: String, value: String, quote: String, source: String = quote): BottleDetailExtractionResult {
        val raw = buildJsonObject { putJsonArray("facts") { add(buildJsonObject {
            put("field", field); put("value", value); put("evidence", quote)
        }) } }.toString()
        return parseBottleFactCandidate(raw, "Test", "Test", "https://example.org/item", source)
    }

    @Test fun canonicalizesOnlyAcceptedScalarsAndPreservesQuotes() {
        for ((field, value, quote, expected) in listOf(
            listOf("abv_percent", "27,50% Alc./Vol.", "27,50% Alc./Vol.", "27.5%"),
            listOf("abv_percent", "0.00", "Alcohol: 0.00%", "0%"),
            listOf("quantity", "0,70 l", "Bottle size: 0,70 l", "0.7 L"),
            listOf("quantity", "700.00ml", "Size: 700.00ml", "700 mL"),
        )) {
            val fact = (parse(field, value, quote) as BottleDetailExtractionResult.Found).suggestions.facts.single()
            assertEquals(expected, fact.value)
            assertEquals(quote, fact.quote)
        }
    }

    @Test fun normalizationCannotRepairUnsupportedOrAmbiguousEvidence() {
        for ((field, value, quote) in listOf(
            listOf("abv_percent", "27.5%", "Save 27.5%"),
            listOf("abv_percent", "27.5%", "ABV: 37.5%"),
            listOf("abv_percent", "127.5%", "ABV: 127.5%"),
            listOf("quantity", "700", "Size: 700 mL"),
            listOf("quantity", "0.7 L", "Size: 700 mL"),
            listOf("quantity", "2 x 700 mL", "Size: 2 x 700 mL"),
        )) assertTrue(parse(field, value, quote) is BottleDetailExtractionResult.Rejected)
        assertTrue(parse("abv_percent", "27.50%", "ABV: 27.50%", "Other source") is BottleDetailExtractionResult.Rejected)
        val conflicting = parse("quantity", "700.00ml", "Size: 700.00ml", "Size: 700.00ml\nOther bottle: 1 L")
            as BottleDetailExtractionResult.Found
        assertTrue(conflicting.suggestions.facts.isEmpty())
        assertTrue(BottleFactField.QUANTITY in conflicting.suggestions.unresolvedFields)
    }

    @Test fun fallbackRecordEligibilityRemainsConservative() {
        for (body in listOf(
            "UPC 080686032069 | 700 mL\nAlcohol: 27.5%",
            "Alcohol Grade: Non-Alcoholic\nBottles available in: 1 L\nProduct Code: A123",
            "Bottles available in: 700 mL, 1 L",
            "Other bottle\nSize: 700 mL\nABV: 27.5%",
        )) assertNull(labeledBottleSpecifications(WebPageText("Example", "https://example.org/item", "Example\n$body", "Test")))
    }
}
