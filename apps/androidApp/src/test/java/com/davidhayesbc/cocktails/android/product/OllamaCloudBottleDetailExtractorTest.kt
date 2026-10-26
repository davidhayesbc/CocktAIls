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
                BottleDetailExtractionResult.InvalidResponse,
        )
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
                BottleDetailExtractionResult.InvalidResponse,
        )
        assertTrue(
            parseOllamaExtractionResponse("malformed", "model", source.sourceUrl, source.markdown) is
                BottleDetailExtractionResult.InvalidResponse,
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
