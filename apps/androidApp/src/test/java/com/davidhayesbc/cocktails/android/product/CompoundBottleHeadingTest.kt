package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompoundBottleHeadingTest {
    @Test fun siteSuffixKeepsModelNameAndSizeWithoutInventingBrand() = runBlocking {
        val page = WebPageText("Example Syrup | Example Site", "https://example.org/syrup",
            "Example Syrup | Example Site\nBottles available in: 1 L\nAlcohol Grade: Non-alcoholic\nProduct Code: S123", "Synthetic")
        var prompt = ""
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, user, _ ->
            prompt = user
            """{"facts":[{"field":"product_name","value":"Example Syrup","evidence":"Example Syrup | Example Site"},{"field":"quantity","value":"1 L","evidence":"Bottles available in: 1 L"},{"field":"brand","value":"Example Site","evidence":"Example Syrup | Example Site"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL) as BottleDetailExtractionResult.Found
        assertEquals(mapOf(BottleFactField.PRODUCT_NAME to "Example Syrup", BottleFactField.QUANTITY to "1 L"),
            result.suggestions.facts.associate { it.field to it.value })
        assertTrue(prompt.contains("quantity"))
        assertNull(result.suggestions.targetBarcode)
        assertTrue(result.suggestions.facts.all { it.quote in page.markdown })
        assertTrue(result.suggestions.fieldRejections.any { it.field == BottleFactField.BRAND })
    }

    @Test fun compoundHeadingsNeverCreateDeterministicIdentityOrStripVariants() = runBlocking {
        for (heading in listOf("Example Gin | Other Gin", "Example Gin | Extra Dry", "Example Gin | 1 L", "Example Gin | Site | Other Gin")) {
            val page = WebPageText(heading, "https://example.org/item", "$heading\nUPC: 080686032069\nSize: 700 mL\nABV: 27.5%", "Synthetic")
            assertNull(heading, labeledBottleSpecifications(page))
            assertNull(heading, labeledBottleSpecifications(page, "080686032069"))
            val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ -> """{"facts":[]}""" })
                .extract(page, LOCAL_MODEL_LABEL)
            assertTrue(heading, (result as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty().isEmpty())
        }
    }

    @Test fun packageConflictAndMissingEvidenceStillBlockModelClaims() = runBlocking {
        val page = WebPageText("Example Gin 1 L | Site", "https://example.org/item",
            "Example Gin 1 L | Site\nBottles available in: 700 mL", "Synthetic")
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Other Gin","evidence":"Other Gin"},{"field":"quantity","value":"700 mL","evidence":"Bottles available in: 700 mL"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL)
        assertTrue((result as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty().isEmpty())
    }
}
