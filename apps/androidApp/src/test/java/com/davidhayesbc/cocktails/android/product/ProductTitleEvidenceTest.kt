package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.*
import org.junit.Test

class ProductTitleEvidenceTest {
    @Test fun removesOnlyExplicitTrailingSingleBottleSizes() {
        for (suffix in listOf("750ml", "0.75 L", "750 mL bottle", "(750 mL)", "- 750 mL")) {
            assertEquals("Polar River 101", productNameWithoutPackageSuffix("Polar River 101 $suffix"))
            assertTrue(structuredProductNamesAgree("Polar River 101", "Polar River 101 $suffix"))
        }
        for (name in listOf("Polar River 101", "Polar River 12 Year", "Polar River 100 Proof", "Polar River Reserve",
            "Polar River 6 x 750 mL", "Polar River pack of 6 750 mL", "Polar River 750 mL Reserve")) {
            assertEquals(name, productNameWithoutPackageSuffix(name))
        }
    }

    @Test fun packagingNormalizationDoesNotCollapseDifferentProductsOrSizes() {
        for ((a,b) in listOf(
            "Polar River 101" to "Polar River 81 750 mL",
            "Polar River Reserve" to "Polar River Classic 750 mL",
            "Polar River Dry Gin" to "Polar River Sweet Gin 750 mL",
            "Polar River 750 mL" to "Polar River 1 L",
            "Polar River 6 x 750 mL" to "Polar River 750 mL",
        )) assertFalse("$a / $b", structuredProductNamesAgree(a,b))
    }

    @Test fun normalizedNameStillCarriesPackagingForIdentityValidation() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"product_name","value":"Polar River 750 mL","evidence":"Polar River 750 mL"}]}""",
            "Fixture", "Fixture", "https://example.org/product", "Polar River 750 mL",
        ) as BottleDetailExtractionResult.Found
        assertEquals("Polar River", result.suggestions.facts.single().value)
        assertEquals("Polar River 750 mL", result.suggestions.facts.single().originalProductName)
        val bound = bindPageBarcodeIdentity(result, "080686032069", WebPageText(
            "Polar River 1 L", "https://example.org/product", "Polar River 750 mL UPC 080686032069", "Fixture",
        ))
        assertTrue(bound is BottleDetailExtractionResult.Rejected)
    }

    @Test fun rejectsShortenedTitleBrandQuotesAndKeepsNameEvidence() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"product_name","value":"Polar River 101 750ml","evidence":"Polar River 101 750ml"},{"field":"brand","value":"Polar River","evidence":"Polar River"}]}""",
            "Fixture", "Fixture", "https://example.org/product", "Polar River 101 750ml\nABV: 50.5%",
        ) as BottleDetailExtractionResult.Found
        assertEquals("Polar River 101", result.suggestions.facts.single().value)
        assertEquals("Polar River 101 750ml", result.suggestions.facts.single().quote)
        assertTrue(BottleFactField.BRAND in result.suggestions.unresolvedFields)
    }

    @Test fun brandOnlyTitleFragmentIsNotEvidenceButExplicitBrandIs() {
        val titleOnly = parseBottleFactCandidate(
            """{"facts":[{"field":"brand","value":"Polar River","evidence":"Polar River"}]}""",
            "Fixture", "Fixture", "https://example.org/product", "Polar River Reserve 750ml",
        )
        assertTrue(titleOnly is BottleDetailExtractionResult.Rejected)
        val labeled = parseBottleFactCandidate(
            """{"facts":[{"field":"brand","value":"Polar River","evidence":"Brand: Polar River"}]}""",
            "Fixture", "Fixture", "https://example.org/product", "Polar River Reserve 750ml\nBrand: Polar River",
        ) as BottleDetailExtractionResult.Found
        assertEquals(BottleFactField.BRAND, labeled.suggestions.facts.single().field)
    }
}
