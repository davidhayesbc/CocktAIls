package com.davidhayesbc.cocktails.android.product

import android.content.Context
import android.content.SharedPreferences
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val OPEN_FOOD_FACTS_HOME = "https://world.openfoodfacts.org/"
internal const val OPEN_FOOD_FACTS_LICENSE = "Open Food Facts · ODbL 1.0 / DbCL"
internal const val ODBL_LICENSE_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
internal const val DBCL_LICENSE_URL = "https://opendatacommons.org/licenses/dbcl/1-0/"
private const val OPEN_FOOD_FACTS_ENDPOINT = "https://world.openfoodfacts.org/api/v3.6/product/"
private const val CACHE_KEY = "product_suggestions"
private const val REQUEST_TIMES_KEY = "request_times"
private const val CACHE_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val REQUEST_WINDOW_MILLIS = 60_000L
private const val MIN_REQUEST_INTERVAL_MILLIS = 3_000L
private const val MAX_REQUESTS_PER_WINDOW = 5
private const val MAX_RESPONSE_BYTES = 256 * 1024
private const val MAX_CACHE_ENTRIES = 40
private const val REQUEST_FIELDS = "code,product_name,brands,quantity,product_quantity,product_quantity_unit,last_modified_t,schema_version"

internal data class OpenFoodFactsSuggestion(
    val requestedBarcode: String,
    val requestCode: String,
    val canonicalCode: String,
    val productName: String?,
    val brands: String?,
    val quantity: String?,
    val lastModifiedSeconds: Long?,
    val schemaVersion: String?,
    val fetchedAtMillis: Long,
    val warningIds: List<String>,
)

internal sealed interface OpenFoodFactsLookupResult {
    data class Found(val suggestion: OpenFoodFactsSuggestion, val fromCache: Boolean) : OpenFoodFactsLookupResult
    data object InvalidBarcode : OpenFoodFactsLookupResult
    data object NotFound : OpenFoodFactsLookupResult
    data object DifferentProductType : OpenFoodFactsLookupResult
    data object LocallyThrottled : OpenFoodFactsLookupResult
    data object ProviderRateLimited : OpenFoodFactsLookupResult
    data object Unavailable : OpenFoodFactsLookupResult
    data object InvalidResponse : OpenFoodFactsLookupResult
}

internal fun lookupResultMatchesActiveRequest(
    requestedBarcode: String,
    currentBarcode: String,
    requestToken: Long,
    activeToken: Long,
): Boolean {
    if (requestToken != activeToken) return false
    val requested = normalizeGtinForLookup(requestedBarcode) ?: return false
    return requested == normalizeGtinForLookup(currentBarcode)
}

internal interface LocalLookupStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

internal class SharedPreferencesLookupStorage(context: Context) : LocalLookupStorage {
    private val preferences: SharedPreferences = context.getSharedPreferences("open_food_facts_local_cache", Context.MODE_PRIVATE)
    override fun read(key: String): String? = preferences.getString(key, null)
    override fun write(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

/** Provider suggestions are stored in a private preferences file, separate from household records and exports. */
internal class OpenFoodFactsLocalCache(
    private val storage: LocalLookupStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun find(barcode: String): OpenFoodFactsSuggestion? {
        val now = nowMillis()
        val existing = readEntries()
        val entries = existing.filter { now >= it.fetchedAtMillis && now - it.fetchedAtMillis <= CACHE_TTL_MILLIS }
        if (entries.size != existing.size) writeEntries(entries)
        return entries.firstOrNull { it.requestedBarcode == barcode || it.requestCode == barcode || it.canonicalCode == barcode }
    }

    @Synchronized
    fun store(suggestion: OpenFoodFactsSuggestion) {
        val retained = readEntries()
            .filterNot { it.requestedBarcode == suggestion.requestedBarcode || it.canonicalCode == suggestion.canonicalCode }
            .filterNot { it.requestCode == suggestion.requestCode }
            .plus(suggestion)
            .sortedByDescending { it.fetchedAtMillis }
            .take(MAX_CACHE_ENTRIES)
        writeEntries(retained)
    }

    /** Limits this installation to five user-triggered requests per minute and prevents rapid duplicate taps. */
    @Synchronized
    fun reserveRequest(): Boolean {
        val now = nowMillis()
        val recent = readRequestTimes().filter { it <= now && now - it < REQUEST_WINDOW_MILLIS }
        if (recent.size >= MAX_REQUESTS_PER_WINDOW || recent.lastOrNull()?.let { now - it < MIN_REQUEST_INTERVAL_MILLIS } == true) {
            writeRequestTimes(recent)
            return false
        }
        writeRequestTimes(recent + now)
        return true
    }

    private fun readEntries(): List<OpenFoodFactsSuggestion> = runCatching {
        Json.parseToJsonElement(storage.read(CACHE_KEY) ?: return emptyList()).jsonArray.mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val request = item.string("requestedBarcode") ?: return@mapNotNull null
            val canonical = item.string("canonicalCode") ?: return@mapNotNull null
            val fetchedAt = item.number("fetchedAtMillis") ?: return@mapNotNull null
            OpenFoodFactsSuggestion(
                requestedBarcode = request,
                requestCode = item.string("requestCode") ?: request,
                canonicalCode = canonical,
                productName = item.string("productName"),
                brands = item.string("brands"),
                quantity = item.string("quantity"),
                lastModifiedSeconds = item.number("lastModifiedSeconds"),
                schemaVersion = item.string("schemaVersion"),
                fetchedAtMillis = fetchedAt,
                warningIds = item.arrayStrings("warningIds"),
            )
        }
    }.getOrDefault(emptyList())

    private fun readRequestTimes(): List<Long> = runCatching {
        Json.parseToJsonElement(storage.read(REQUEST_TIMES_KEY) ?: return emptyList()).jsonArray.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }.getOrDefault(emptyList())

    private fun writeEntries(entries: List<OpenFoodFactsSuggestion>) {
        storage.write(CACHE_KEY, buildJsonArray {
            entries.forEach { item ->
                add(buildJsonObject {
                    put("provider", "Open Food Facts")
                    put("databaseLicense", "ODbL-1.0")
                    put("contentsLicense", "DbCL")
                    put("requestedBarcode", item.requestedBarcode)
                    put("requestCode", item.requestCode)
                    put("canonicalCode", item.canonicalCode)
                    putNullable("productName", item.productName)
                    putNullable("brands", item.brands)
                    putNullable("quantity", item.quantity)
                    putNullable("lastModifiedSeconds", item.lastModifiedSeconds)
                    putNullable("schemaVersion", item.schemaVersion)
                    put("fetchedAtMillis", item.fetchedAtMillis)
                    put("warningIds", buildJsonArray { item.warningIds.forEach { add(JsonPrimitive(it)) } })
                })
            }
        }.toString())
    }

    private fun writeRequestTimes(times: List<Long>) {
        storage.write(REQUEST_TIMES_KEY, buildJsonArray { times.forEach { add(JsonPrimitive(it)) } }.toString())
    }
}

internal class OpenFoodFactsLookupClient(
    private val cache: OpenFoodFactsLocalCache,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    suspend fun lookup(
        barcode: String,
        versionName: String,
        onHttpStatus: (Int?) -> Unit = {},
    ): OpenFoodFactsLookupResult = withContext(Dispatchers.IO) {
        lookupBlocking(barcode, versionName, onHttpStatus)
    }

    internal fun lookupBlocking(
        barcode: String,
        versionName: String,
        onHttpStatus: (Int?) -> Unit = {},
    ): OpenFoodFactsLookupResult {
        val originalCode = barcode.trim()
        if (normalizeGtinForLookup(originalCode) == null) return OpenFoodFactsLookupResult.InvalidBarcode
        val requestCode = originalCode.filter { it in '0'..'9' }
        cache.find(requestCode)?.let { return OpenFoodFactsLookupResult.Found(it, fromCache = true) }
        if (!cache.reserveRequest()) return OpenFoodFactsLookupResult.LocallyThrottled

        val safeVersion = versionName.filter { it.isLetterOrDigit() || it in ".-_+" }.take(80).ifBlank { "unknown" }
        val requestUrl = "$OPEN_FOOD_FACTS_ENDPOINT$requestCode.json?fields=$REQUEST_FIELDS"
        val connection = try {
            openConnection(requestUrl).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CocktAIls/$safeVersion (+https://github.com/davidhayesbc/CocktAIls)")
            }
        } catch (_: Exception) {
            return OpenFoodFactsLookupResult.Unavailable
        }

        return try {
            val status = connection.responseCode
            runCatching { onHttpStatus(status.takeIf { it in 100..599 }) }
            if (status == 429) return OpenFoodFactsLookupResult.ProviderRateLimited
            if (status !in 200..299 && status != 404) return OpenFoodFactsLookupResult.Unavailable
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use(::readBoundedUtf8)
                ?: return OpenFoodFactsLookupResult.InvalidResponse
            when (val parsed = parseOpenFoodFactsResponse(status, body, originalCode, nowMillis())) {
                is OpenFoodFactsLookupResult.Found -> {
                    cache.store(parsed.suggestion)
                    parsed
                }
                else -> parsed
            }
        } catch (_: IOException) {
            OpenFoodFactsLookupResult.Unavailable
        } catch (_: Exception) {
            OpenFoodFactsLookupResult.InvalidResponse
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseOpenFoodFactsResponse(
    httpStatus: Int,
    body: String,
    requestedBarcode: String,
    fetchedAtMillis: Long,
): OpenFoodFactsLookupResult {
    val normalizedRequestedCode = normalizeGtinForLookup(requestedBarcode)
        ?: return OpenFoodFactsLookupResult.InvalidBarcode
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return OpenFoodFactsLookupResult.InvalidResponse
    val status = root.string("status") ?: return OpenFoodFactsLookupResult.InvalidResponse
    if (status !in setOf("success", "success_with_warnings", "success_with_errors", "failure")) return OpenFoodFactsLookupResult.InvalidResponse

    val errors = root.objectArray("errors")
    val errorIds = errors.mapNotNull { it.objectAt("message")?.string("id") }
    if ("invalid_code" in errorIds) return OpenFoodFactsLookupResult.InvalidBarcode

    val resultId = root.objectAt("result")?.string("id")
    if (resultId == "product_found_with_a_different_product_type") return OpenFoodFactsLookupResult.DifferentProductType
    if (resultId == "product_not_found" || "product_not_found" in errorIds) return OpenFoodFactsLookupResult.NotFound
    if (httpStatus == 404) return OpenFoodFactsLookupResult.NotFound
    if (httpStatus !in 200..299) return OpenFoodFactsLookupResult.Unavailable
    if (errors.any { it.objectAt("impact")?.string("id") == "failure" }) return OpenFoodFactsLookupResult.Unavailable
    if (status == "failure" || resultId != "product_found") return OpenFoodFactsLookupResult.InvalidResponse

    val product = root.objectAt("product") ?: return OpenFoodFactsLookupResult.InvalidResponse
    val canonicalCode = product.string("code")?.takeIf {
        it.isNotBlank() && it.length <= 32 && it.all(Char::isDigit) && normalizeGtinForLookup(it) != null
    }
        ?: return OpenFoodFactsLookupResult.InvalidResponse
    if (normalizeGtinForLookup(canonicalCode) != normalizedRequestedCode) {
        return OpenFoodFactsLookupResult.InvalidResponse
    }
    val quantity = product.string("quantity")?.takeIf(String::isNotBlank) ?: run {
        val amount = product.scalarString("product_quantity")
        val unit = product.scalarString("product_quantity_unit")
        amount?.let { if (unit.isNullOrBlank()) it else "$it $unit" }
    }
    val warningIds = root.objectArray("warnings").mapNotNull { it.objectAt("message")?.string("id") }
    return OpenFoodFactsLookupResult.Found(
        OpenFoodFactsSuggestion(
            requestedBarcode = requestedBarcode,
            requestCode = requestedBarcode.filter { it in '0'..'9' },
            canonicalCode = canonicalCode,
            productName = product.string("product_name").cleanField(200),
            brands = product.string("brands").cleanField(200),
            quantity = quantity.cleanField(100),
            lastModifiedSeconds = product.number("last_modified_t"),
            schemaVersion = (root.scalarString("schema_version") ?: product.scalarString("schema_version"))?.cleanField(40),
            fetchedAtMillis = fetchedAtMillis,
            warningIds = warningIds.mapNotNull { it.cleanField(100) },
        ),
        fromCache = false,
    )
}

private fun readBoundedUtf8(input: java.io.InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_RESPONSE_BYTES) throw IOException("Response exceeded limit")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun String?.cleanField(maxLength: Int): String? = this
    ?.filterNot { it.isISOControl() }
    ?.trim()
    ?.take(maxLength)
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.contentOrNull

private fun JsonObject.scalarString(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString || it.longOrNull != null || it.doubleOrNull != null }
    ?.contentOrNull

private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.objectArray(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.arrayStrings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}
