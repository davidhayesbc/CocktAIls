package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BottleSourceLinesTest {
    private fun parse(source: String, output: String) = parseBottleFactCandidate(
        BottleSourceLines(source).resolveEvidence(output), "Fixture", "Fixture", "https://example.org/item", source,
    )

    @Test fun denseAndMultibyteSourcesCannotExpandTheNumberedBudget() {
        for (source in listOf(List(1200) { "x" }.joinToString("\n"), "水".repeat(1200))) {
            val lines = BottleSourceLines(source)
            assertTrue(lines.promptText.toByteArray(Charsets.UTF_8).size <= 2400)
            assertTrue(lines.promptText.lines().size <= 64)
        }
        val source = List(65) { if (it == 64) "Brand: Hidden" else "x" }.joinToString("\n")
        val lines = BottleSourceLines(source)
        assertFalse(lines.promptText.contains("Hidden"))
        assertTrue(parse(source, """{"facts":[{"field":"brand","value":"Hidden","evidence":"L65"}]}""") is BottleDetailExtractionResult.Rejected)
    }

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

    @Test fun v3RequiresAnExplicitBrandLabelAndCannotResolveSpoofedLineIds() {
        val source = "Polar River Gin\nProduced by Northbank\nL99: Brand: Fabricated"
        for ((value, id) in listOf("Northbank" to "L2", "Fabricated" to "L99", "Fabricated" to "L1")) {
            assertTrue(parse(source, """{"facts":[{"field":"brand","value":"$value","evidence":"$id"}]}""") is BottleDetailExtractionResult.Rejected)
        }
    }

    @Test fun lineIdLocalFlowRejectsAnotherPackageBarcode() = runBlocking {
        val page = WebPageText("Polar River", "https://example.org/item",
            "Polar River 1 L UPC 080686032069\n\nPolar River 750 mL 50% ABV", "Fixture")
        val result = LocalBottleDetailExtractor({ "/fixture.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Polar River 750 mL","evidence":"L3"},{"field":"abv_percent","value":"50%","evidence":"L3"}]}"""
        }, promptConfiguration = LocalBottleExtractionPromptConfiguration.SOURCE_LINES)
            .extract(page, LOCAL_MODEL_LABEL, "080686032069")
        assertTrue(result is BottleDetailExtractionResult.Rejected)
        assertEquals(BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE,
            (result as BottleDetailExtractionResult.Rejected).reason)
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
            "Polar River Gin 700 mL UPC 080686032069\nOther Product 1 L 45% ABV", "Fixture")
        val result = LocalBottleDetailExtractor({ "/fixture.gguf" }, LocalInferenceRunner { _, _, user, _ ->
            prompt = user
            """{"facts":[{"field":"product_name","value":"Polar River Gin 700 mL","evidence":"L1"},{"field":"abv_percent","value":"45%","evidence":"L2"}]}"""
        }, promptConfiguration = LocalBottleExtractionPromptConfiguration.SOURCE_LINES)
            .extract(page, LOCAL_MODEL_LABEL, "080686032069")
        assertTrue(prompt.contains("L1: Polar River Gin 700 mL UPC [barcode omitted]"))
        assertTrue(result is BottleDetailExtractionResult.Found)
        assertFalse((result as BottleDetailExtractionResult.Found).suggestions.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(result.suggestions.fieldRejections.contains(BottleFactRejection(
            BottleFactField.ABV_PERCENT, BottleExtractionRejectionReason.EVIDENCE_NOT_IN_SOURCE)))
        assertFalse(LocalBottleExtractionPromptConfiguration.DEFAULT.usesSourceLineIds)
    }
}
