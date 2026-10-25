package com.davidhayesbc.cocktails.android.product

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsLookupTest {
    @Test
    fun parsesV36ProductNameBrandAndQuantityAndPreservesCodesAndWarnings() {
        val result = parseOpenFoodFactsResponse(
            httpStatus = 200,
            body = successResponse,
            requestedBarcode = "036000291452",
            fetchedAtMillis = 100,
        )
        val found = assertIs<OpenFoodFactsLookupResult.Found>(result)
        assertEquals(false, found.fromCache)
        assertEquals("036000291452", found.suggestion.requestedBarcode)
        assertEquals("036000291452", found.suggestion.requestCode)
        assertEquals("0036000291452", found.suggestion.canonicalCode)
        assertEquals("Example Gin", found.suggestion.productName)
        assertEquals("Example Brand", found.suggestion.brands)
        assertEquals("750 ml", found.suggestion.quantity)
        assertEquals(1_700_000_000L, found.suggestion.lastModifiedSeconds)
        assertEquals("3.6", found.suggestion.schemaVersion)
        assertEquals(listOf("product_image_missing"), found.suggestion.warningIds)
    }

    @Test
    fun invalidCodeErrorTakesPrecedenceOverGenericNotFound() {
        val result = parseOpenFoodFactsResponse(
            404,
            """{"status":"failure","result":{"id":"product_not_found"},"errors":[{"message":{"id":"invalid_code"},"impact":{"id":"failure"}}]}""",
            "036000291452",
            100,
        )
        assertIs<OpenFoodFactsLookupResult.InvalidBarcode>(result)
    }

    @Test
    fun classifiesMissingAndDifferentProductTypeResponses() {
        val missing = parseOpenFoodFactsResponse(
            404,
            """{"status":"failure","result":{"id":"product_not_found"},"errors":[{"message":{"id":"product_not_found"},"impact":{"id":"failure"}}]}""",
            "036000291452",
            100,
        )
        assertIs<OpenFoodFactsLookupResult.NotFound>(missing)

        val wrongType = parseOpenFoodFactsResponse(
            404,
            """{"status":"failure","result":{"id":"product_found_with_a_different_product_type"}}""",
            "036000291452",
            100,
        )
        assertIs<OpenFoodFactsLookupResult.DifferentProductType>(wrongType)
    }

    @Test
    fun rejectsMalformedFailureImpactAndMissingCanonicalCodeResponses() {
        assertIs<OpenFoodFactsLookupResult.InvalidResponse>(parseOpenFoodFactsResponse(200, "<html>proxy error</html>", "036000291452", 100))
        assertIs<OpenFoodFactsLookupResult.Unavailable>(parseOpenFoodFactsResponse(
            200,
            """{"status":"success_with_errors","result":{"id":"product_found"},"errors":[{"message":{"id":"upstream_failure"},"impact":{"id":"failure"}}],"product":{"code":"0036000291452"}}""",
            "036000291452",
            100,
        ))
        assertIs<OpenFoodFactsLookupResult.InvalidResponse>(parseOpenFoodFactsResponse(
            200,
            successResponse.replace("\"code\":\"0036000291452\",", ""),
            "036000291452",
            100,
        ))
    }

    @Test
    fun quantityFallsBackToStructuredAmountAndUnitWithoutGuessingAbvOrCategory() {
        val response = successResponse
            .replace("\"quantity\":\"750 ml\",", "\"quantity\":\"\",")
        val found = assertIs<OpenFoodFactsLookupResult.Found>(parseOpenFoodFactsResponse(200, response, "036000291452", 100))
        assertEquals("750 ml", found.suggestion.quantity)
    }

    @Test
    fun cacheRetainsAttributionAndFindsOnlyReturnedOrRequestedCodes() {
        val storage = MemoryLookupStorage()
        var now = 100_000L
        val cache = OpenFoodFactsLocalCache(storage) { now }
        val suggestion = (parseOpenFoodFactsResponse(200, successResponse, "036000291452", now) as OpenFoodFactsLookupResult.Found).suggestion
        cache.store(suggestion)

        assertEquals(suggestion, cache.find("036000291452"))
        assertEquals(suggestion, cache.find("0036000291452"))
        assertNull(cache.find("003600291452"))
        assertTrue(storage.read("product_suggestions")!!.contains("\"provider\":\"Open Food Facts\""))
        assertTrue(storage.read("product_suggestions")!!.contains("\"databaseLicense\":\"ODbL-1.0\""))

        now += 31L * 24 * 60 * 60 * 1000
        assertNull(cache.find("036000291452"))
    }

    @Test
    fun requestUsesExactScannedDigitsWithoutPaddingAndSendsOnlyRequiredFieldsAndAppUserAgent() {
        val storage = MemoryLookupStorage()
        val cache = OpenFoodFactsLocalCache(storage) { 100_000L }
        lateinit var openedUrl: String
        lateinit var connection: FakeConnection
        val client = OpenFoodFactsLookupClient(cache, { 100_000L }) { value ->
            openedUrl = value
            connection = FakeConnection(URL(value), 200, successResponse)
            connection
        }

        val found = assertIs<OpenFoodFactsLookupResult.Found>(client.lookupBlocking("036000291452", "0.1.0-preview.2+3473bfa"))
        assertEquals("0036000291452", found.suggestion.canonicalCode)
        assertTrue(openedUrl.startsWith("https://world.openfoodfacts.org/api/v3.6/product/036000291452.json?fields="))
        assertTrue(openedUrl.contains("code,product_name,brands,quantity,product_quantity,product_quantity_unit,last_modified_t,schema_version"))
        assertTrue(!openedUrl.contains("image"))
        assertEquals("GET", connection.requestMethod)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(5_000, connection.readTimeout)
        assertEquals(false, connection.instanceFollowRedirects)
        assertEquals("application/json", connection.capturedHeaders["Accept"])
        assertEquals("CocktAIls/0.1.0-preview.2+3473bfa (+https://github.com/davidhayesbc/CocktAIls)", connection.capturedHeaders["User-Agent"])

        val cached = assertIs<OpenFoodFactsLookupResult.Found>(client.lookupBlocking("036000291452", "0.1.0-preview.2+3473bfa"))
        assertTrue(cached.fromCache)
    }

    @Test
    fun invalidBarcodeUsesNoNetworkAndHttp429IsNotRetried() {
        var requestCount = 0
        var now = 100_000L
        var responseCode = 429
        val cache = OpenFoodFactsLocalCache(MemoryLookupStorage()) { now }
        val client = OpenFoodFactsLookupClient(cache, { now }) { value ->
            requestCount++
            FakeConnection(URL(value), responseCode, "")
        }
        assertIs<OpenFoodFactsLookupResult.InvalidBarcode>(client.lookupBlocking("036000291453", "test"))
        assertEquals(0, requestCount)
        assertIs<OpenFoodFactsLookupResult.RateLimited>(client.lookupBlocking("036000291452", "test"))
        assertEquals(1, requestCount)
        now += 3_001L
        responseCode = 503
        assertIs<OpenFoodFactsLookupResult.Unavailable>(client.lookupBlocking("036000291452", "test"))
        assertEquals(2, requestCount)
    }

    @Test
    fun completedLookupIsShownOnlyForTheBarcodeItWasRequestedFor() {
        assertTrue(lookupResultMatchesCurrentBarcode("036000291452", "036000291452"))
        assertTrue(lookupResultMatchesCurrentBarcode("036000291452", "0036000291452"))
        assertEquals(false, lookupResultMatchesCurrentBarcode("036000291452", "0036000291453"))
        assertEquals(false, lookupResultMatchesCurrentBarcode("036000291452", ""))
    }

    @Test
    fun cacheReturnsBeforeNetworkAndRateLimiterBoundsRepeatedRequests() {
        var now = 100_000L
        val cache = OpenFoodFactsLocalCache(MemoryLookupStorage()) { now }
        val suggestion = (parseOpenFoodFactsResponse(200, successResponse, "036000291452", now) as OpenFoodFactsLookupResult.Found).suggestion
        cache.store(suggestion)
        val cachedClient = OpenFoodFactsLookupClient(cache, { now }) { error("Cache hit must not access network") }
        assertTrue(assertIs<OpenFoodFactsLookupResult.Found>(cachedClient.lookupBlocking("036000291452", "test")).fromCache)

        val throttledCache = OpenFoodFactsLocalCache(MemoryLookupStorage()) { now }
        repeat(5) { index ->
            assertTrue(throttledCache.reserveRequest())
            now += 3_001L
        }
        assertEquals(false, throttledCache.reserveRequest())
    }

    private class MemoryLookupStorage : LocalLookupStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) { values[key] = value }
    }

    private inline fun <reified T> assertIs(actual: Any): T {
        assertTrue("Expected ${T::class.simpleName}, got ${actual::class.simpleName}", actual is T)
        return actual as T
    }

    private class FakeConnection(url: URL, private val code: Int, private val responseBody: String) : HttpURLConnection(url) {
        val capturedHeaders = mutableMapOf<String, String>()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream() = ByteArrayInputStream(responseBody.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(responseBody.toByteArray())
        override fun setRequestProperty(key: String, value: String) { capturedHeaders[key] = value }
    }

    private companion object {
        const val successResponse = """{"status":"success_with_warnings","schema_version":"3.6","result":{"id":"product_found"},"warnings":[{"message":{"id":"product_image_missing"}}],"product":{"code":"0036000291452","product_name":"Example Gin","brands":"Example Brand","quantity":"750 ml","product_quantity":750,"product_quantity_unit":"ml","last_modified_t":1700000000,"schema_version":"3.6","unknown_field":"ignored"}}"""
    }
}
