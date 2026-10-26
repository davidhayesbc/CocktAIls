package com.davidhayesbc.cocktails.android.product

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray

internal const val FIRECRAWL_API_ORIGIN = "https://api.firecrawl.dev"
private const val FIRECRAWL_SEARCH_PATH = "/v2/search"
private const val FIRECRAWL_SCRAPE_PATH = "/v2/scrape"
private const val MAX_QUERY_CHARS = 256
private const val MAX_SEARCH_RESULTS = 5
private const val MAX_SEARCH_RESPONSE_BYTES = 512 * 1024
private const val MAX_SCRAPE_RESPONSE_BYTES = 1024 * 1024
private const val MAX_STRUCTURED_HTML_CHARS = 512 * 1024
private const val MAX_RESULT_TITLE_CHARS = 240
private const val MAX_RESULT_SNIPPET_CHARS = 600
private const val MAX_PAGE_TEXT_CHARS = 24_000

/** A single provider-neutral search result. Text remains source attributed and is never catalog data. */
internal data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String?,
)

internal data class WebPageText(
    val title: String?,
    val sourceUrl: String,
    val markdown: String,
    val provider: String,
    /** Parsed barcode/name pairs only; raw HTML is never retained beyond the bounded parser. */
    val structuredBottleIdentities: List<StructuredBottleIdentity> = emptyList(),
)

internal data class StructuredBottleIdentity(
    val barcode: String,
    val productName: String,
    /** A compact normalized record of the exact structured fields, not a page-text quote. */
    val evidence: String,
)

internal sealed interface WebSearchResult {
    data class Found(val hits: List<WebSearchHit>) : WebSearchResult
    data object SetupRequired : WebSearchResult
    data object AuthenticationRequired : WebSearchResult
    data object InvalidQuery : WebSearchResult
    data object QuotaExceeded : WebSearchResult
    data object RateLimited : WebSearchResult
    data object Unavailable : WebSearchResult
    data object InvalidResponse : WebSearchResult
}

internal sealed interface WebPageResult {
    data class Found(val page: WebPageText) : WebPageResult
    data object SetupRequired : WebPageResult
    data object AuthenticationRequired : WebPageResult
    data object InvalidUrl : WebPageResult
    data object QuotaExceeded : WebPageResult
    data object RateLimited : WebPageResult
    data object Unavailable : WebPageResult
    data object InvalidResponse : WebPageResult
}

/** Provider-neutral boundaries keep search discovery and page retrieval independently replaceable. */
internal interface WebSearchProvider {
    suspend fun search(query: String, limit: Int = 5): WebSearchResult
}

internal interface WebPageContentProvider {
    suspend fun fetch(url: String): WebPageResult
}

/**
 * The API key must come from a user-approved secure key provider. This class never persists or logs it.
 * Calls are user initiated, one request each, and have no automatic retry behavior.
 */
internal class FirecrawlSearchProvider(
    private val keyProvider: () -> String?,
    private val transport: FirecrawlTransport = UrlConnectionFirecrawlTransport(),
) : WebSearchProvider, WebPageContentProvider {
    override suspend fun search(query: String, limit: Int): WebSearchResult = searchDetailed(query, limit).result

    suspend fun searchDetailed(query: String, limit: Int = 5): BottleProviderCall<WebSearchResult> {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isEmpty() || cleanedQuery.length > MAX_QUERY_CHARS || limit !in 1..MAX_SEARCH_RESULTS) {
            return BottleProviderCall(WebSearchResult.InvalidQuery)
        }
        val key = keyProvider()?.trim()?.takeIf(String::isNotEmpty) ?: return BottleProviderCall(WebSearchResult.SetupRequired)
        val body = buildJsonObject {
            put("query", cleanedQuery)
            put("limit", limit)
            put("sources", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("web")) })
        }.toString()
        return when (val response = transport.post(FIRECRAWL_SEARCH_PATH, key, body, MAX_SEARCH_RESPONSE_BYTES)) {
            is FirecrawlHttpResponse.Failure -> BottleProviderCall(response.status.toWebSearchFailure(), response.status.takeIf { it in 100..599 })
            is FirecrawlHttpResponse.Success -> BottleProviderCall(parseFirecrawlSearchResponse(response.body, limit), response.status.takeIf { it in 100..599 })
        }
    }

    override suspend fun fetch(url: String): WebPageResult = fetchDetailed(url).result

    suspend fun fetchDetailed(url: String): BottleProviderCall<WebPageResult> {
        val safeUrl = normalizePublicHttpsUrl(url) ?: return BottleProviderCall(WebPageResult.InvalidUrl)
        val key = keyProvider()?.trim()?.takeIf(String::isNotEmpty) ?: return BottleProviderCall(WebPageResult.SetupRequired)
        val body = buildJsonObject {
            put("url", safeUrl)
            put("formats", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("markdown"))
                add(kotlinx.serialization.json.JsonPrimitive("html"))
            })
        }.toString()
        return when (val response = transport.post(FIRECRAWL_SCRAPE_PATH, key, body, MAX_SCRAPE_RESPONSE_BYTES)) {
            is FirecrawlHttpResponse.Failure -> BottleProviderCall(response.status.toWebPageFailure(), response.status.takeIf { it in 100..599 })
            is FirecrawlHttpResponse.Success -> BottleProviderCall(parseFirecrawlPageResponse(response.body), response.status.takeIf { it in 100..599 })
        }
    }
}

internal sealed interface FirecrawlHttpResponse {
    data class Success(val body: String, val status: Int = 200) : FirecrawlHttpResponse
    data class Failure(val status: Int) : FirecrawlHttpResponse
}

internal interface FirecrawlTransport {
    suspend fun post(path: String, apiKey: String, body: String, maxResponseBytes: Int): FirecrawlHttpResponse
}

/** Uses fixed API paths, HTTPS, bounded responses, finite timeouts, no redirects and no retry loop. */
internal class UrlConnectionFirecrawlTransport : FirecrawlTransport {
    override suspend fun post(path: String, apiKey: String, body: String, maxResponseBytes: Int): FirecrawlHttpResponse = suspendCancellableCoroutine { continuation ->
        require(path == FIRECRAWL_SEARCH_PATH || path == FIRECRAWL_SCRAPE_PATH)
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val requestJob = CoroutineScope(Dispatchers.IO).launch {
            val connection = try {
                (URL("$FIRECRAWL_API_ORIGIN$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "CocktAIls Android")
                }
            } catch (_: Exception) {
                if (continuation.isActive) runCatching { continuation.resume(FirecrawlHttpResponse.Failure(0)) }
                return@launch
            }
            connectionRef.set(connection)
            if (!continuation.isActive) {
                connection.disconnect()
                return@launch
            }
            val result = try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    FirecrawlHttpResponse.Failure(status)
                } else {
                    val buffer = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val chunk = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            total += count
                            if (total > maxResponseBytes) return@use FirecrawlHttpResponse.Failure(413)
                            buffer.write(chunk, 0, count)
                        }
                    FirecrawlHttpResponse.Success(buffer.toString(Charsets.UTF_8.name()), status)
                    }
                }
            } catch (_: Exception) {
                FirecrawlHttpResponse.Failure(0)
            } finally {
                connection.disconnect()
                connectionRef.set(null)
            }
            if (continuation.isActive) runCatching { continuation.resume(result) }
        }
        continuation.invokeOnCancellation {
            requestJob.cancel()
            connectionRef.getAndSet(null)?.disconnect()
        }
    }
}

internal fun parseFirecrawlSearchResponse(raw: String, limit: Int = MAX_SEARCH_RESULTS): WebSearchResult = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return WebSearchResult.InvalidResponse
    val data = (root["data"] as? JsonObject)?.get("web") as? JsonArray ?: return WebSearchResult.InvalidResponse
    val hits = data.mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        val title = item.string("title")?.trim()?.take(MAX_RESULT_TITLE_CHARS)?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val url = normalizePublicHttpsUrl(item.string("url") ?: return@mapNotNull null) ?: return@mapNotNull null
        val snippet = (item.string("description") ?: item.string("markdown"))?.trim()?.take(MAX_RESULT_SNIPPET_CHARS)
        WebSearchHit(title, url, snippet)
    }.distinctBy { it.url }.take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
    WebSearchResult.Found(hits)
}.getOrDefault(WebSearchResult.InvalidResponse)

internal fun parseFirecrawlPageResponse(raw: String): WebPageResult = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return WebPageResult.InvalidResponse
    val data = root["data"] as? JsonObject ?: return WebPageResult.InvalidResponse
    val markdown = data.string("markdown")?.take(MAX_PAGE_TEXT_CHARS)?.trim()?.takeIf(String::isNotEmpty)
        ?: return WebPageResult.InvalidResponse
    val metadata = data["metadata"] as? JsonObject
    val html = data.string("html")?.take(MAX_STRUCTURED_HTML_CHARS).orEmpty()
    val source = metadata?.string("sourceURL")?.let(::normalizePublicHttpsUrl)
        ?: metadata?.string("url")?.let(::normalizePublicHttpsUrl)
        ?: return WebPageResult.InvalidResponse
    WebPageResult.Found(
        WebPageText(
            title = metadata?.string("title")?.take(MAX_RESULT_TITLE_CHARS),
            sourceUrl = source,
            markdown = markdown,
            provider = "Firecrawl",
            structuredBottleIdentities = parseStructuredBottleIdentities(html),
        ),
    )
}.getOrDefault(WebPageResult.InvalidResponse)

/** Reads bounded JSON-LD/application-json scripts and retains only exact barcode/product pairs. */
internal fun parseStructuredBottleIdentities(html: String): List<StructuredBottleIdentity> {
    if (html.isBlank()) return emptyList()
    val scriptRegex = Regex(
        """(?is)<script\b[^>]*type\s*=\s*["'](?:application/ld\+json|application/json)["'][^>]*>(.*?)</script\s*>""",
    )
    val found = linkedSetOf<StructuredBottleIdentity>()
    var visitedNodes = 0

    fun walk(element: kotlinx.serialization.json.JsonElement, inheritedName: String?, depth: Int) {
        if (depth > 16 || visitedNodes >= 5_000 || found.size >= 32) return
        visitedNodes++
        when (element) {
            is JsonObject -> {
                val isProduct = element["@type"]?.let { type ->
                    when (type) {
                        is JsonPrimitive -> type.contentOrNull.equals("Product", ignoreCase = true)
                        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull.equals("Product", ignoreCase = true) }
                        else -> false
                    }
                } == true || element["variants"] is JsonArray
                val ownName = element.string("name")?.trim()?.takeIf(String::isNotEmpty)
                    ?: element.string("productName")?.trim()?.takeIf(String::isNotEmpty)
                    ?: element.string("title")?.trim()?.takeIf(String::isNotEmpty)?.takeIf { isProduct }
                val productName = ownName ?: inheritedName
                val barcode = listOf("gtin8", "gtin12", "gtin13", "gtin14", "gtin", "barcode")
                    .firstNotNullOfOrNull { key -> element.string(key)?.let(::normalizeStructuredBarcode) }
                val name = productName?.take(180)?.takeIf(String::isNotBlank)
                if (barcode != null && name != null) {
                    found += StructuredBottleIdentity(
                        barcode = barcode,
                        productName = name,
                        evidence = "Structured product fields: barcode $barcode; product $name",
                    )
                }
                element.values.forEach { walk(it, productName, depth + 1) }
            }
            is JsonArray -> element.forEach { walk(it, inheritedName, depth + 1) }
            else -> Unit
        }
    }

    scriptRegex.findAll(html).take(32).forEach { match ->
        val content = match.groupValues.getOrNull(1)?.take(128 * 1024) ?: return@forEach
        runCatching { Json.parseToJsonElement(content) }.getOrNull()?.let { walk(it, null, 0) }
    }
    return found.toList().take(32)
}

private fun normalizeStructuredBarcode(raw: String): String? {
    val digits = raw.trim()
    if (!digits.matches(Regex("\\d{8}|\\d{12}|\\d{13}|\\d{14}"))) return null
    return com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup(digits)
}

/** Restricts page retrieval to public HTTPS hostnames; local, literal-IP, credential and odd-port URLs are rejected. */
internal fun normalizePublicHttpsUrl(raw: String): String? = runCatching {
    val uri = URI(raw.trim())
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port !in setOf(-1, 443) || uri.rawFragment != null) return null
    val host = uri.host?.let { IDN.toASCII(it) }?.lowercase() ?: return null
    if (!host.contains('.') || host.endsWith('.') || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
    if (host.contains(':') || isNonPublicIpv4(host)) return null
    URI("https", null, host, -1, uri.path?.ifEmpty { "/" } ?: "/", uri.query, null).toASCIIString()
}.getOrNull()

private fun isNonPublicIpv4(host: String): Boolean {
    val octets = host.split('.')
    if (host.all { it.isDigit() || it == '.' }) {
        if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return true
    } else return false
    val (a, b) = octets[0].toInt() to octets[1].toInt()
    return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
        (a == 192 && b in setOf(0, 2, 168)) || (a == 100 && b in 64..127) ||
        (a == 198 && b in setOf(18, 19, 51)) || (a == 203 && b == 0 && octets[2] == "113") || a >= 224
}

private fun JsonObject.string(field: String): String? = this[field]?.jsonPrimitive?.contentOrNull

private fun Int.toWebSearchFailure(): WebSearchResult = when (this) {
    401, 403 -> WebSearchResult.AuthenticationRequired
    402 -> WebSearchResult.QuotaExceeded
    429 -> WebSearchResult.RateLimited
    in 500..599, 0 -> WebSearchResult.Unavailable
    else -> WebSearchResult.InvalidResponse
}

private fun Int.toWebPageFailure(): WebPageResult = when (this) {
    401, 403 -> WebPageResult.AuthenticationRequired
    402 -> WebPageResult.QuotaExceeded
    429 -> WebPageResult.RateLimited
    in 500..599, 0 -> WebPageResult.Unavailable
    else -> WebPageResult.InvalidResponse
}
