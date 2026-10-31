package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BottleSourceLinesTest {
    private fun parse(source: String, output: String) = parseBottleFactCandidate(
        BottleSourceLines(source).resolveEvidence(output), "Fixture", "Fixture", "https://example.org/item", source,
    )

    @Test fun resolvesExactLinesWithoutInventingQuotes() {
        val source = "Polar River Gin\nBrand: Northbank\nBottle size: 700 mL\nAlcohol: 41%"
        assertEquals("L1: Polar River Gin\nL2: Brand: Northbank\nL3: Bottle size: 700 mL\nL4: Alcohol: 41%",
            BottleSourceLines(source).promptText)
        val result = parse(source, """{"facts":[{"field":"brand","value":"Northbank","evidence":"L2"},{"field":"quantity","value":"700 mL","evidence":"L3"},{"field":"abv_percent","value":"41%","evidence":"L4"}]}""")
            as BottleDetailExtractionResult.Found
        assertEquals(listOf("Brand: Northbank", "Bottle size: 700 mL", "Alcohol: 41%"), result.suggestions.facts.map { it.quote })
    }

    @Test fun rejectsUnknownMalformedOrGeneratedEvidenceReferences() {
        for (reference in listOf("L0", "L99", "L01", "L1-L2", "L1\nL2", "exact short quote from source", "Brand: Northbank")) {
            val raw = kotlinx.serialization.json.JsonPrimitive(reference).toString()
            assertTrue(reference, parse("Brand: Northbank",
                """{"facts":[{"field":"brand","value":"Northbank","evidence":$raw}]}""") is BottleDetailExtractionResult.Rejected)
        }
        assertTrue(parse("Brand: Northbank", """{"facts":[{"field":"brand","value":"Northbank","evidence":1}]}""") is BottleDetailExtractionResult.Rejected)
    }

    @Test fun referencesDoNotBypassValueUnitOrBrandValidation() {
        val source = "Polar River Gin | Northbank Store\nBottle size: 700 mL\nDiscount: 41%\nBrand: Northbank"
        for (fact in listOf(
            """{"field":"brand","value":"Northbank Store","evidence":"L1"}""",
            """{"field":"brand","value":"Polar River","evidence":"L1"}""",
            """{"field":"quantity","value":"700","evidence":"L2"}""",
            """{"field":"quantity","value":"1 L","evidence":"L2"}""",
            """{"field":"abv_percent","value":"41%","evidence":"L3"}""",
            """{"field":"brand","value":"Wrong Brand","evidence":"L4"}""",
        )) {
            val result = parse(source, """{"facts":[$fact]}""")
            assertTrue(fact, result !is BottleDetailExtractionResult.Found || result.suggestions.facts.isEmpty())
        }
    }

    @Test fun abstentionMalformedJsonAndLongEvidenceRemainConservative() {
        assertTrue(parse("Brand: Northbank", """{"facts":[]}""") is BottleDetailExtractionResult.Rejected)
        assertTrue(parse("Brand: Northbank", "not json") is BottleDetailExtractionResult.Rejected)
        assertTrue(parse("Brand: Northbank " + "x".repeat(400),
            """{"facts":[{"field":"brand","value":"Northbank","evidence":"L1"}]}""") is BottleDetailExtractionResult.Rejected)
    }

    @Test fun localExperimentUsesNumberedInputAndPreservesWrongProductBinding() = runBlocking {
        var prompt = ""
        val page = WebPageText("Polar River Gin", "https://example.org/item",
            "Polar River Gin 700 mL UPC 080686032069\nOther Product 1 L Alcohol: 45%", "Fixture")
        val result = LocalBottleDetailExtractor({ "/fixture.gguf" }, LocalInferenceRunner { _, _, user, _ ->
            prompt = user
            """{"facts":[{"field":"product_name","value":"Polar River Gin 700 mL","evidence":"L1"},{"field":"abv_percent","value":"45%","evidence":"L2"}]}"""
        }, promptConfiguration = LocalBottleExtractionPromptConfiguration.SOURCE_LINES)
            .extract(page, LOCAL_MODEL_LABEL, "080686032069")
        assertTrue(prompt.contains("L1: Polar River Gin 700 mL UPC [barcode omitted]"))
        assertTrue(result is BottleDetailExtractionResult.Found)
        assertFalse((result as BottleDetailExtractionResult.Found).suggestions.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertFalse(LocalBottleExtractionPromptConfiguration.DEFAULT.usesSourceLineIds)
    }
}
