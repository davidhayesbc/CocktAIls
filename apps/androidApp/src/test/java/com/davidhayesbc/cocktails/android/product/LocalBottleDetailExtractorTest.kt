package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBottleDetailExtractorTest {
    @Test
    fun usesLocalRunnerAndAcceptsOnlyQuotedFieldsFromSelectedPage() = runBlocking {
        val page = WebPageText("Example Gin", "https://example.org/gin", "<p>North Star Gin 40% ABV</p><a href=\"https://tracker.example/?user=secret\">Buy</a>", "Firecrawl")
        var receivedPrompt = ""
        val extractor = LocalBottleDetailExtractor({ "/private/model.gguf" }, LocalInferenceRunner { _, system, prompt, tokens ->
            assertTrue(system.contains("untrusted data"))
            assertEquals(256, tokens)
            receivedPrompt = prompt
            """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin 40% ABV"},{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV"}]}"""
        })

        val result = extractor.extract(page, LOCAL_MODEL_LABEL)

        assertTrue(result is BottleDetailExtractionResult.Found)
        result as BottleDetailExtractionResult.Found
        assertEquals("On-device Qwen3", result.suggestions.provider)
        assertEquals(2, result.suggestions.facts.size)
        assertFalse(receivedPrompt.contains("tracker.example"))
        assertFalse(receivedPrompt.contains("user=secret"))
    }

    @Test
    fun doesNotRunWhenModelMissingAndRejectsUnsupportedModel() = runBlocking {
        var invoked = false
        val runner = LocalInferenceRunner { _, _, _, _ -> invoked = true; "{}" }
        val page = WebPageText("Gin", "https://example.org/gin", "Gin 40% ABV", "Firecrawl")

        assertEquals(BottleDetailExtractionResult.SetupRequired, LocalBottleDetailExtractor({ null }, runner).extract(page, LOCAL_MODEL_LABEL))
        assertEquals(BottleDetailExtractionResult.InvalidModel, LocalBottleDetailExtractor({ "/model" }, runner).extract(page, "other-model"))
        assertFalse(invoked)
    }

    @Test
    fun rejectsLocalCandidateWithoutExactSourceEvidence() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"brand","value":"Invented Brand","evidence":"Invented Brand"}]}""",
            provider = "On-device Qwen3",
            model = LOCAL_MODEL_LABEL,
            sourceUrl = "https://example.org/gin",
            sourceText = "North Star Gin 40% ABV",
        )

        assertTrue(result is BottleDetailExtractionResult.Rejected)
    }

    @Test
    fun acceptsExactStructuredVariantIdentityAndRejectsModelNameFromAnotherProduct() = runBlocking {
        val identity = StructuredBottleIdentity(
            "5011333150725",
            "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            "Auchentoshan Three Wood Single Malt Scotch Whisky | Canadian Liquor Store",
            "https://shop.example/auchentoshan-three-wood",
            "Auchentoshan Three Wood bottle size: 750 mL. Auchentoshan Three Wood is 43% ABV. Alternative: Other Reserve 700 mL.",
            "Firecrawl",
            listOf(identity),
        )
        val output = """{"facts":[{"field":"quantity","value":"750 mL","evidence":"Auchentoshan Three Wood bottle size: 750 mL"},{"field":"abv_percent","value":"43%","evidence":"Auchentoshan Three Wood is 43% ABV"}]}"""
        var prompt = ""
        val extractor = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, _ ->
            prompt = userPrompt
            output
        })

        val result = extractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725")
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertEquals(identity.evidence, suggestions.identityEvidence)
        assertEquals("Auchentoshan Three Wood", suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertTrue(prompt.contains(identity.productName))
        assertFalse(prompt.contains(identity.barcode))

        val wrongNameExtractor = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Other Reserve 700 mL"}]}"""
        })
        assertTrue(wrongNameExtractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725") is BottleDetailExtractionResult.Rejected)
    }

    @Test
    fun scannedBarcodeIsBoundLocallyAndRedactedFromInferencePrompt() = runBlocking {
        val page = WebPageText(
            "North Star Gin",
            "https://shop.example/north-star",
            "UPC 5011333150725 belongs to North Star Gin, 40% ABV.",
            "Firecrawl",
        )
        var prompt = ""
        val extractor = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, _ ->
            prompt = userPrompt
            """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin, 40% ABV"}]}"""
        })

        val result = extractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725")
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertTrue(prompt.contains("[barcode omitted]"))
        assertFalse(prompt.contains("5011333150725"))
    }
}
