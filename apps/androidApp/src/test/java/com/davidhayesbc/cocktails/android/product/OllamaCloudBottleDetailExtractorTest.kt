package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaCloudBottleDetailExtractorTest {
    private val source = WebPageText(
        title = "Example gin",
        sourceUrl = "https://example.org/gin",
        markdown = "Example Gin is a London dry gin. The bottle contains 700 mL and is 40% ABV.",
        provider = "Firecrawl",
    )

    @Test
    fun sendsOnlyBoundedUntrustedPageTextToTheSelectedModelAndReturnsEvidence() = runBlocking {
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"product_name","value":"Example Gin","evidence":"Example Gin"},{"field":"brand","value":"Example Gin","evidence":"Example Gin is a London dry gin"},{"field":"quantity","value":"700 mL","evidence":"700 mL"},{"field":"abv_percent","value":"40%","evidence":"40% ABV"}]}""",
        )))
        val provider = OllamaCloudBottleDetailExtractor({ "synthetic-ollama-key" }, transport)

        val result = provider.extract(source, "qwen3:8b-cloud")
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals(4, suggestions.facts.size)
        assertEquals("https://example.org/gin", suggestions.sourceUrl)
        assertEquals("qwen3:8b-cloud", transport.lastModel)
        assertEquals("synthetic-ollama-key", transport.lastKey)
        assertTrue(transport.lastBody.contains(source.markdown))
        assertFalse(transport.lastBody.contains("synthetic-ollama-key"))
        assertTrue(transport.lastBody.contains("\"stream\":false"))
        assertFalse(transport.lastBody.contains("\"format\""))
        assertTrue(transport.lastBody.contains("untrusted data"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun rejectsFactsWithoutExactSourceEvidenceOrValidAbvUnits() {
        val raw = response(
            """{"facts":[{"field":"product_name","value":"Imaginary Reserve","evidence":"Example Gin"},{"field":"brand","value":"Fabricated Brand","evidence":"Example Gin is a London dry gin"},{"field":"quantity","value":"900 mL","evidence":"700 mL"},{"field":"abv_percent","value":"120%","evidence":"40% ABV"}]}""",
        )
        assertTrue(
            parseOllamaExtractionResponse(raw, "qwen3:8b-cloud", source.sourceUrl, source.markdown) is
                BottleDetailExtractionResult.Rejected,
        )
    }

    @Test
    fun rejectsNumericSubstringAbvQuantityCrossFieldAndConflictingValues() {
        val invalidCandidates = listOf(
            """{"facts":[{"field":"abv_percent","value":"0%","evidence":"40% ABV"}]}""",
            """{"facts":[{"field":"abv_percent","value":"5%","evidence":"15% ABV"}]}""",
            """{"facts":[{"field":"quantity","value":"40%","evidence":"40% ABV"}]}""",
        )
        invalidCandidates.forEach { content ->
            assertTrue(
                parseOllamaExtractionResponse(response(content), "model", source.sourceUrl, source.markdown) is
                    BottleDetailExtractionResult.Rejected,
            )
        }

        val conflictingSource = "Example Gin is 40% ABV. The retailer says 50% ABV."
        val conflictingContent = """{"facts":[{"field":"abv_percent","value":"40%","evidence":"40% ABV"},{"field":"abv_percent","value":"50%","evidence":"50% ABV"}]}"""
        assertTrue(
            parseOllamaExtractionResponse(response(conflictingContent), "model", source.sourceUrl, conflictingSource) is
                BottleDetailExtractionResult.Rejected,
        )
    }

    @Test
    fun conflictingBottleSizesRemainUnresolvedInsteadOfSelectingOne() {
        val sourceText = "UPC 5011333150725. Three Wood is listed in 700 mL and 750 mL bottles."
        val candidate = """{"facts":[{"field":"product_name","value":"Three Wood","evidence":"Three Wood"},{"field":"quantity","value":"750 mL","evidence":"750 mL bottles"}]}"""

        val result = parseBottleFactCandidate(candidate, "Ollama Cloud", "model", "https://shop.example/item", sourceText)

        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertTrue(BottleFactField.QUANTITY in suggestions.unresolvedFields)
        assertTrue(suggestions.facts.none { it.field == BottleFactField.QUANTITY })
        assertTrue(suggestions.facts.any { it.field == BottleFactField.PRODUCT_NAME })
    }

    @Test
    fun conflictingSourceAbvValuesRemainUnresolvedInsteadOfSelectingOne() {
        val sourceText = "Example Gin 40% ABV. A related product is 50% ABV."
        val candidate = """{"facts":[{"field":"product_name","value":"Example Gin","evidence":"Example Gin 40% ABV"},{"field":"abv_percent","value":"40%","evidence":"Example Gin 40% ABV"}]}"""

        val result = parseBottleFactCandidate(candidate, "Ollama Cloud", "model", "https://shop.example/item", sourceText)

        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertTrue(BottleFactField.ABV_PERCENT in suggestions.unresolvedFields)
        assertTrue(suggestions.facts.none { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(suggestions.facts.any { it.field == BottleFactField.PRODUCT_NAME })
    }

    @Test
    fun automaticExtractionRequiresIdentityEvidenceLinkingScannedCodeAndProductName() {
        val sourceText = "UPC 5011333150725 applies to Auchentoshan Three Wood. Related whisky: Other Reserve; Brand: WrongBrand."
        val wrongProduct = """{"identity":{"barcode":"5011333150725","evidence":"UPC 5011333150725 applies to Auchentoshan Three Wood"},"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Related whisky: Other Reserve"},{"field":"brand","value":"WrongBrand","evidence":"Brand: WrongBrand"}]}"""

        assertTrue(
            (parseBottleFactCandidate(wrongProduct, "Ollama Cloud", "model", "https://shop.example/item", sourceText, "5011333150725") as? BottleDetailExtractionResult.Rejected)?.reason ==
                BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE,
        )

        val linkedProduct = """{"identity":{"barcode":"5011333150725","evidence":"UPC 5011333150725 applies to Auchentoshan Three Wood"},"facts":[{"field":"product_name","value":"Auchentoshan Three Wood","evidence":"UPC 5011333150725 applies to Auchentoshan Three Wood"}]}"""
        val result = parseBottleFactCandidate(linkedProduct, "Ollama Cloud", "model", "https://shop.example/item", sourceText, "5011333150725")
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertTrue(suggestions.identityEvidence.orEmpty().contains("Auchentoshan Three Wood"))
    }

    @Test
    fun identityEvidenceCannotJoinScannedBottleAndNearbyRelatedProduct() {
        val barcode = "5011333150725"
        val mixedPassage = "UPC 5011333150725 applies to Auchentoshan Three Wood. Related whisky: Other Reserve."
        val mixedCandidate = """{"identity":{"barcode":"$barcode","evidence":"$mixedPassage"},"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Related whisky: Other Reserve"}]}"""
        assertTrue(
            (parseBottleFactCandidate(mixedCandidate, "Ollama Cloud", "model", "https://shop.example/item", mixedPassage, barcode) as? BottleDetailExtractionResult.Rejected)?.reason ==
                BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE,
        )

        val scopedText = "UPC 5011333150725 applies to Auchentoshan Three Wood.\n\nRelated whisky: Other Reserve."
        val scopedQuote = "UPC 5011333150725 applies to Auchentoshan Three Wood"
        val scopedCandidate = """{"identity":{"barcode":"$barcode","evidence":"$scopedQuote"},"facts":[{"field":"product_name","value":"Auchentoshan Three Wood","evidence":"$scopedQuote"}]}"""
        val scopedResult = parseBottleFactCandidate(scopedCandidate, "Ollama Cloud", "model", "https://shop.example/item", scopedText, barcode)
        assertTrue(scopedResult is BottleDetailExtractionResult.Found)

        val competingBarcodeText = "UPC 5011333150725 applies to Auchentoshan Three Wood. UPC 088352109084 applies to St Remy VSOP."
        val competingCandidate = """{"identity":{"barcode":"$barcode","evidence":"UPC 5011333150725 applies to Auchentoshan Three Wood"},"facts":[{"field":"product_name","value":"Auchentoshan Three Wood","evidence":"UPC 5011333150725 applies to Auchentoshan Three Wood"}]}"""
        assertTrue(
            (parseBottleFactCandidate(competingCandidate, "Ollama Cloud", "model", "https://shop.example/item", competingBarcodeText, barcode) as? BottleDetailExtractionResult.Rejected)?.reason ==
                BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE,
        )
    }

    @Test
    fun cloudRequestForScannedBottleRequiresExactCodeAndProductIdentityEvidence() = runBlocking {
        val page = source.copy(markdown = "UPC 5011333150725 applies to Example Gin 40% ABV.")
        val content = """{"identity":{"barcode":"5011333150725","evidence":"UPC 5011333150725 applies to Example Gin"},"facts":[{"field":"product_name","value":"Example Gin","evidence":"UPC 5011333150725 applies to Example Gin"},{"field":"abv_percent","value":"40%","evidence":"Example Gin 40% ABV"}]}"""
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(content)))
        val provider = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)

        val result = provider.extract(page, "qwen3:8b-cloud", "5011333150725")

        assertTrue(result is BottleDetailExtractionResult.Found)
        assertTrue(transport.lastBody.contains("identity"))
        assertTrue(transport.lastBody.contains("5011333150725"))
    }

    @Test
    fun stripsLinksUrlsQueryStringsHtmlAndControlCharactersBeforeCloudRequest() = runBlocking {
        val pageText = "Gin brand [official page](https://example.org/gin?token=private123)\nRaw https://shop.example/item?email=person%40example.org\u0001"
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"brand","value":"Gin brand","evidence":"Gin brand"}]}""",
        )))
        val provider = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)

        assertTrue(provider.extract(source.copy(markdown = pageText), "qwen3:8b-cloud") is BottleDetailExtractionResult.Found)
        assertTrue(transport.lastBody.contains("official page"))
        assertFalse(transport.lastBody.contains("https://"))
        assertFalse(transport.lastBody.contains("private123"))
        assertFalse(transport.lastBody.contains("person%40example.org"))
        assertFalse(transport.lastBody.contains("\u0001"))
    }

    @Test
    fun missingKeyInvalidModelUnsafeSourceAndFailedResponsesNeverCallTransport() = runBlocking {
        val transport = FakeTransport(OllamaCloudHttpResponse.Success("{}"))
        val missingKey = OllamaCloudBottleDetailExtractor({ null }, transport)
        assertTrue(missingKey.extract(source, "qwen3:8b-cloud") is BottleDetailExtractionResult.SetupRequired)
        val configured = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)
        assertTrue(configured.extract(source, "bad model/from-url") is BottleDetailExtractionResult.InvalidModel)
        assertTrue(configured.extract(source.copy(sourceUrl = "http://localhost/private"), "qwen3:8b-cloud") is BottleDetailExtractionResult.InvalidResponse)
        assertEquals(0, transport.calls)
    }

    @Test
    fun providerErrorsAreDistinctAndDoNotRetry() = runBlocking {
        val cases = listOf(
            401 to BottleDetailExtractionResult.AuthenticationRequired,
            402 to BottleDetailExtractionResult.QuotaOrRateLimited,
            429 to BottleDetailExtractionResult.QuotaOrRateLimited,
            503 to BottleDetailExtractionResult.Unavailable,
            0 to BottleDetailExtractionResult.Unavailable,
        )
        cases.forEach { (status, expected) ->
            val transport = FakeTransport(OllamaCloudHttpResponse.Failure(status))
            val provider = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)
            assertEquals(expected, provider.extract(source, "qwen3:8b-cloud"))
            assertEquals(1, transport.calls)
        }
    }

    @Test
    fun parserRejectsMalformedAndMarkdownWrappedModelOutput() {
        assertTrue(
            parseOllamaExtractionResponse(response("not-json"), "model", source.sourceUrl, source.markdown) is
                BottleDetailExtractionResult.Rejected,
        )
        assertTrue(
            parseOllamaExtractionResponse("malformed", "model", source.sourceUrl, source.markdown) is
                BottleDetailExtractionResult.Rejected,
        )
    }

    private fun response(content: String): String =
        """{"done":true,"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}"""

    private class FakeTransport(private val response: OllamaCloudHttpResponse) : OllamaCloudTransport {
        var calls: Int = 0
        var lastModel: String = ""
        var lastBody: String = ""
        var lastKey: String = ""
        override suspend fun chat(model: String, apiKey: String, requestBody: String): OllamaCloudHttpResponse {
            calls += 1
            lastModel = model
            lastBody = requestBody
            lastKey = apiKey
            return response
        }
    }
}
