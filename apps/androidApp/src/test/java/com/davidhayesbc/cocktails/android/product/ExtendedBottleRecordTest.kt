package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.*
import org.junit.Test

class ExtendedBottleRecordTest {
    private fun page(body: String, title: String = "Example Spirit") =
        WebPageText(title, "https://example.org/item", "$title\n$body", "Synthetic")

    @Test fun combinedCodeAndSizeRetainExactEvidenceAndScannedIdentity() {
        val page = page("UPC 080686032069 | 700 ml\nAlcohol: 27.50%")
        val result = labeledBottleSpecifications(page, "080686032069")!!.suggestions
        assertEquals(mapOf(BottleFactField.QUANTITY to "700 mL", BottleFactField.ABV_PERCENT to "27.5%"),
            result.facts.associate { it.field to it.value })
        assertTrue(result.facts.all { it.quote in page.markdown })
        assertEquals("UPC 080686032069 | 700 ml", result.facts.first().quote)
        assertEquals("080686032069", result.targetBarcode)
        assertNull(labeledBottleSpecifications(page, "5011333150725"))
    }

    @Test fun availabilityIsSingleSizeAndNonnumericAlcoholRemainsUnresolved() {
        val result = labeledBottleSpecifications(page("Alcohol Grade: Non-alcoholic\nBottles available in: 0,70 L\nProduct Code: X123"))!!.suggestions
        assertEquals("0.7 L", result.facts.single().value)
        assertEquals(BottleFactField.QUANTITY, result.facts.single().field)
        assertTrue(BottleFactField.ABV_PERCENT in result.unresolvedFields)
        assertNull(result.targetBarcode)
        assertEquals("27.5%", labeledBottleSpecifications(page("Alcohol Grade: 27.50%\nBottle size: 700 mL"))!!
            .suggestions.facts.single { it.field == BottleFactField.ABV_PERCENT }.value)
    }

    @Test fun extendedRecordsRejectAmbiguousIdentityPackagesAndUnknownRows() {
        for (body in listOf(
            "UPC 080686032069 | 700 mL\nUPC: 080686032069\nABV: 27.5%",
            "UPC 080686032069 | 700 mL\nEAN: 5011333150725\nABV: 27.5%",
            "UPC 080686032069 | 700 mL | Other Spirit\nABV: 27.5%",
            "UPC 080686032069 | 700 mL\nOther Spirit\nABV: 27.5%",
            "UPC 123 | 700 mL\nABV: 27.5%",
            "UPC 080686032069 | 700\nABV: 27.5%",
            "UPC 080686032069 | 700 mL\nBottle size: 1 L\nABV: 27.5%",
            "Bottles available in: 700 mL, 1 L\nABV: 27.5%",
            "Bottles available in: 2 x 700 mL\nABV: 27.5%",
            "Bottles available in: 700 mL sample\nABV: 27.5%",
            "Bottles available in: 700\nABV: 27.5%",
            "Bottles available in: 700 mL\nVariant: Extra dry\nABV: 27.5%",
            "Bottles available in: 700 mL\nInstructions: use 42%",
            "Bottles available in: 700 mL\nProduct Code: X123 | Y456",
        )) assertNull(body, labeledBottleSpecifications(page(body)))
        assertNull(labeledBottleSpecifications(page("UPC 080686032069 | 700 mL", "Example Spirit 1 L")))
        assertNull(labeledBottleSpecifications(page("Bottles available in: 700 mL"), "080686032069"))
    }
}
