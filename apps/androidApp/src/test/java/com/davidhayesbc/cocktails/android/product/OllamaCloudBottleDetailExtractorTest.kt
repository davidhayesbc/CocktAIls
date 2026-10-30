package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun optInCaptureStoresCloudValidatorInputAndModelContentWithoutHttpEnvelope() = runBlocking {
        val root = Files.createTempDirectory("ollama-capture").toFile()
        val store = DetailedBottleLookupCaptureStore(File(root, "capture.json"), { 100L }, processSessionId = "test")
        val token = "00000000-0000-0000-0000-000000000077"
        store.arm("draft-cloud", token, "preview", 100L)
        val capture = store.claim("draft-cloud", token, "", "preview.1", 1, "qwen3:8b-cloud", 100L)!!
        val reply = """{"facts":[{"field":"product_name","value":"Example Gin","evidence":"Example Gin"}]}"""
        val provider = OllamaCloudBottleDetailExtractor(
            { "synthetic-ollama-key" },
            FakeTransport(OllamaCloudHttpResponse.Success(response(reply))),
        )

        provider.extractDetailed(source, "qwen3:8b-cloud", null, capture)
        capture.finish("COMPLETE")
        val preview = store.previewForDraft("draft-cloud", 100L)!!

        assertTrue(preview.contains("Example Gin is a London dry gin"))
        assertTrue(preview.contains(reply.replace("\"", "\\\"")))
        assertFalse(preview.contains("synthetic-ollama-key"))
        assertFalse(preview.contains("messages"))
        root.deleteRecursively()
        Unit
    }

    @Test
    fun sendsOnlyBoundedUntrustedPageTextToTheSelectedModelAndReturnsEvidence() = runBlocking {
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"product_name","value":"Example Gin","evidence":"Example Gin"},{"field":"brand","value":"Example Gin","evidence":"Example Gin is a London dry gin"},{"field":"quantity","value":"700 mL","evidence":"700 mL"},{"field":"abv_percent","value":"40%","evidence":"40% ABV"}]}""",
        )))
        val provider = OllamaCloudBottleDetailExtractor({ "synthetic-ollama-key" }, transport)

        val result = provider.extract(source, "qwen3:8b-cloud")
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals(3, suggestions.facts.size)
        assertTrue(BottleFactField.BRAND in suggestions.unresolvedFields)
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
    fun structuredBarcodeBindingUsesProductTitleButDoesNotSendBarcodeToCloudModel() = runBlocking {
        val identity = StructuredBottleIdentity(
            "5011333150725",
            "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            title = "Auchentoshan Three Wood Single Malt Scotch Whisky | Canadian Liquor Store",
            sourceUrl = "https://example.org/auchentoshan-three-wood",
            markdown = "Auchentoshan Three Wood bottle size: 750 mL. Auchentoshan Three Wood is 43% ABV.",
            provider = "Firecrawl",
            structuredBottleIdentities = listOf(identity),
        )
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"quantity","value":"750 mL","evidence":"Auchentoshan Three Wood bottle size: 750 mL"},{"field":"abv_percent","value":"43%","evidence":"Auchentoshan Three Wood is 43% ABV"}]}""",
        )))
        val result = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)
            .extract(page, "qwen3:8b-cloud", "5011333150725")

        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertEquals(identity.evidence, suggestions.identityEvidence)
        assertEquals("Auchentoshan Three Wood", suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertTrue(transport.lastBody.contains(identity.productName))
        assertFalse(transport.lastBody.contains(identity.barcode))
        val request = kotlinx.serialization.json.Json.parseToJsonElement(transport.lastBody).jsonObject
        val messages = request.getValue("messages").jsonArray
        val system = messages[0].jsonObject.getValue("content").jsonPrimitive.content
        val user = messages[1].jsonObject.getValue("content").jsonPrimitive.content
        assertFalse(system.contains(identity.productName))
        assertTrue(user.contains("TARGET PRODUCT NAME (untrusted metadata"))
        assertFalse(user.contains(identity.barcode))
    }

    @Test
    fun structuredIdentityDoesNotAuthorizePageWideFactsFromARecommendedProduct() = runBlocking {
        val identity = StructuredBottleIdentity(
            "5011333150725",
            "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            title = "Auchentoshan Three Wood Single Malt Scotch Whisky | Canadian Liquor Store",
            sourceUrl = "https://example.org/auchentoshan-three-wood",
            markdown = "Auchentoshan Three Wood. Recommended alternative: Other Gin 700 mL, 40% ABV.",
            provider = "Firecrawl",
            structuredBottleIdentities = listOf(identity),
        )
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"quantity","value":"700 mL","evidence":"Recommended alternative: Other Gin 700 mL, 40% ABV"},{"field":"abv_percent","value":"40%","evidence":"Recommended alternative: Other Gin 700 mL, 40% ABV"}]}""",
        )))

        val result = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)
            .extract(page, "qwen3:8b-cloud", identity.barcode)

        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals(listOf(BottleFactField.PRODUCT_NAME), suggestions.facts.map(BottleFactEvidence::field))
        assertTrue(BottleFactField.QUANTITY in suggestions.unresolvedFields)
        assertTrue(BottleFactField.ABV_PERCENT in suggestions.unresolvedFields)
    }

    @Test
    fun pageBarcodeIdentityDoesNotAuthorizeFactsQuotedOnlyForAnotherProduct() = runBlocking {
        val page = WebPageText(
            title = "Auchentoshan Three Wood Single Malt Scotch Whisky",
            sourceUrl = "https://example.org/auchentoshan-three-wood",
            markdown = "UPC 5011333150725 applies to Auchentoshan Three Wood. Related whisky: Other Gin 40% ABV.",
            provider = "Firecrawl",
        )
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"product_name","value":"Auchentoshan Three Wood","evidence":"Auchentoshan Three Wood"},{"field":"abv_percent","value":"40%","evidence":"Related whisky: Other Gin 40% ABV"}]}""",
        )))

        val result = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)
            .extract(page, "qwen3:8b-cloud", "5011333150725")

        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertEquals(listOf(BottleFactField.PRODUCT_NAME), suggestions.facts.map(BottleFactEvidence::field))
        assertTrue(BottleFactField.ABV_PERCENT in suggestions.unresolvedFields)
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
        val page = source.copy(markdown = "UPC 5011333150725 applies to Example Gin. Example Gin is 40% ABV.")
        val content = """{"facts":[{"field":"product_name","value":"Example Gin","evidence":"Example Gin"},{"field":"abv_percent","value":"40%","evidence":"Example Gin is 40% ABV"}]}"""
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(content)))
        val provider = OllamaCloudBottleDetailExtractor({ "synthetic-key" }, transport)

        val result = provider.extract(page, "qwen3:8b-cloud", "5011333150725")

        assertTrue(result is BottleDetailExtractionResult.Found)
        assertEquals("5011333150725", (result as BottleDetailExtractionResult.Found).suggestions.targetBarcode)
        assertTrue((result as BottleDetailExtractionResult.Found).suggestions.identityEvidence.orEmpty().contains("UPC 5011333150725"))
        assertFalse(transport.lastBody.contains("\"identity\""))
        assertFalse(transport.lastBody.contains("5011333150725"))
        assertTrue(transport.lastBody.contains("[barcode omitted]"))
    }

    @Test
    fun stripsLinksUrlsQueryStringsHtmlAndControlCharactersBeforeCloudRequest() = runBlocking {
        val pageText = "Gin brand [official page](https://example.org/gin?token=private123)\nRaw https://shop.example/item?email=person%40example.org\u0001"
        val transport = FakeTransport(OllamaCloudHttpResponse.Success(response(
            """{"facts":[{"field":"product_name","value":"Gin brand","evidence":"Gin brand"}]}""",
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

    @Test
    fun parserClassifiesValidJsonWithWrongNestedTypesAsMalformedInsteadOfNetworkFailure() {
        val malformedEnvelopes = listOf(
            """{"done":{},"message":{"content":"{}"}}""",
            """{"done":true,"message":"wrong-shape"}""",
            """{"done":true,"message":{"content":{}}}""",
        )
        malformedEnvelopes.forEach { raw ->
            assertEquals(
                BottleDetailExtractionResult.Rejected(BottleExtractionRejectionReason.MALFORMED_MODEL_RESPONSE),
                parseOllamaExtractionResponse(raw, "model", source.sourceUrl, source.markdown),
            )
        }
        assertEquals(
            BottleDetailExtractionResult.Rejected(BottleExtractionRejectionReason.INVALID_FACT_SHAPE),
            parseBottleFactCandidate("""{"facts":{}}""", "Ollama Cloud", "model", source.sourceUrl, source.markdown),
        )
        assertEquals(
            BottleDetailExtractionResult.Rejected(BottleExtractionRejectionReason.INVALID_FACT_SHAPE),
            parseBottleFactCandidate("""{"facts":[{"field":{},"value":"Gin","evidence":"Gin"}]}""", "Ollama Cloud", "model", source.sourceUrl, source.markdown),
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
