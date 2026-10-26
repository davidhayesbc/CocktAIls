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

        assertEquals(BottleDetailExtractionResult.InvalidResponse, result)
    }
}
