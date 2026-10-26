package com.davidhayesbc.cocktails.android.product

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirecrawlSearchProviderTest {
    @Test
    fun searchIsBoundedExplicitAndDoesNotPlaceTheKeyInTheRequestBody() = runBlocking {
        val transport = FakeTransport(FirecrawlHttpResponse.Success("""{"success":true,"data":{"web":[
            {"title":"Gin producer","url":"https://example.com/gin","description":"Facts"},
            {"title":"Private target","url":"http://127.0.0.1/admin"},
            {"title":"Duplicate","url":"https://example.com/gin"}
        ],"images":[{"title":"Ignored image result","url":"https://example.com/image"}]}}"""))
        val provider = FirecrawlSearchProvider({ "private-key-canary" }, transport)

        val searchResult = provider.search("Example gin", 4)
        assertTrue(searchResult is WebSearchResult.Found)
        val result = searchResult as WebSearchResult.Found
        assertEquals(1, result.hits.size)
        assertEquals("https://example.com/gin", result.hits.single().url)
        assertEquals(1, transport.calls)
        assertTrue(transport.lastPath == "/v2/search")
        assertTrue(transport.lastBody.contains("Example gin"))
        assertFalse(transport.lastBody.contains("private-key-canary"))
        assertTrue(provider.search(" ") is WebSearchResult.InvalidQuery)
        assertEquals(1, transport.calls)
    }

    @Test
    fun searchRequiresConfigurationAndRejectsOversizedInputWithoutCallingTransport() = runBlocking {
        val transport = FakeTransport(FirecrawlHttpResponse.Success("{}"))
        val provider = FirecrawlSearchProvider({ null }, transport)
        assertTrue(provider.search("Gin") is WebSearchResult.SetupRequired)
        assertTrue(provider.search("g".repeat(257)) is WebSearchResult.InvalidQuery)
        assertEquals(0, transport.calls)
    }

    @Test
    fun fetchValidatesPublicHttpsUrlAndHandlesRateLimitWithoutRetry() = runBlocking {
        val transport = FakeTransport(FirecrawlHttpResponse.Failure(429))
        val provider = FirecrawlSearchProvider({ "key-not-recorded" }, transport)
        assertTrue(provider.fetch("http://example.com") is WebPageResult.InvalidUrl)
        assertTrue(provider.fetch("https://user:pass@example.com") is WebPageResult.InvalidUrl)
        assertTrue(provider.fetch("https://192.168.1.1/private") is WebPageResult.InvalidUrl)
        assertTrue(provider.fetch("https://127.1/admin") is WebPageResult.InvalidUrl)
        assertTrue(provider.fetch("https://example.com/product") is WebPageResult.RateLimited)
        assertEquals(1, transport.calls)
        assertEquals("/v2/scrape", transport.lastPath)
    }

    @Test
    fun providerAuthenticationQuotaRateAndOfflineFailuresAreDistinctAndDoNotRetry() = runBlocking {
        val cases = listOf(
            401 to WebSearchResult.AuthenticationRequired,
            402 to WebSearchResult.QuotaExceeded,
            429 to WebSearchResult.RateLimited,
            503 to WebSearchResult.Unavailable,
            0 to WebSearchResult.Unavailable,
            404 to WebSearchResult.InvalidResponse,
        )
        cases.forEach { (status, expected) ->
            val transport = FakeTransport(FirecrawlHttpResponse.Failure(status))
            val provider = FirecrawlSearchProvider({ "synthetic-key" }, transport)
            assertEquals(expected, provider.search("Example gin"))
            assertEquals(1, transport.calls)
        }
    }

    @Test
    fun scrapeResponsePreservesSourceAndCapsReturnedText() {
        val text = "x".repeat(30_000)
        val response = parseFirecrawlPageResponse(
            """{"success":true,"data":{"markdown":"$text","metadata":{"title":"Source","sourceURL":"https://example.com/item"}}}""",
        )
        assertTrue(response is WebPageResult.Found)
        val page = (response as WebPageResult.Found).page
        assertEquals("https://example.com/item", page.sourceUrl)
        assertEquals("Firecrawl", page.provider)
        assertEquals(24_000, page.markdown.length)
    }

    @Test
    fun unsuccessfulAndMalformedProviderBodiesFailClosed() {
        assertTrue(parseFirecrawlSearchResponse("""{"success":false,"data":[]}""") is WebSearchResult.InvalidResponse)
        assertTrue(parseFirecrawlSearchResponse("""{"success":true,"data":[]}""") is WebSearchResult.InvalidResponse)
        assertTrue(parseFirecrawlSearchResponse("not-json") is WebSearchResult.InvalidResponse)
        assertTrue(parseFirecrawlPageResponse("""{"success":true,"data":{"metadata":{}}}""") is WebPageResult.InvalidResponse)
    }

    @Test
    fun publicUrlValidatorRejectsLoopbackPrivateAndLocalNames() {
        listOf(
            "http://example.com",
            "https://localhost/admin",
            "https://printer.local/label",
            "https://10.1.2.3/",
            "https://172.20.0.1/",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/",
            "https://198.18.0.1/",
            "https://224.0.0.1/",
            "https://example.com:8443/",
        ).forEach { assertEquals(null, normalizePublicHttpsUrl(it)) }
        assertEquals("https://example.com/product", normalizePublicHttpsUrl("https://Example.com/product"))
    }

    private class FakeTransport(private val response: FirecrawlHttpResponse) : FirecrawlTransport {
        var calls: Int = 0
        var lastPath: String = ""
        var lastBody: String = ""
        override suspend fun post(path: String, apiKey: String, body: String, maxResponseBytes: Int): FirecrawlHttpResponse {
            calls += 1
            lastPath = path
            lastBody = body
            return response
        }
    }
}
