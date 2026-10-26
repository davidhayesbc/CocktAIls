package com.davidhayesbc.cocktails.android.product

import java.net.IDN
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class BottleLookupDebugStage {
    LOCAL_MATCH,
    OPEN_FOOD_FACTS,
    FIRECRAWL_SEARCH,
    SOURCE_SELECTION,
    FIRECRAWL_FETCH,
    BARCODE_MATCH,
    DETAIL_EXTRACTION,
    COMPLETE,
}

internal data class BottleProviderCall<T>(
    val result: T,
    val httpStatus: Int? = null,
    val durationMillis: Long = 0,
)

/** Stable, non-free-form diagnostic event. These values must never contain provider response text. */
internal data class BottleLookupDebugEvent(
    val stage: BottleLookupDebugStage,
    val provider: String,
    val result: String,
    val durationMillis: Long = 0,
    val httpStatus: Int? = null,
    val resultCount: Int? = null,
    val sourceUrls: List<String> = emptyList(),
    val reasonCodes: List<String> = emptyList(),
    val factFields: List<String> = emptyList(),
)

/**
 * A single local-only report for the most recent barcode lookup. All external strings pass through
 * strict allowlists before serialization; page text, prompts, response bodies, exception messages,
 * entered bottle names, and credentials are never accepted by this type.
 */
internal class BottleLookupDebugReport(
    val createdAtMillis: Long,
    versionName: String,
    versionCode: Long,
    originalBarcode: String,
    normalizedBarcode: String?,
    val openFoodFactsEnabled: Boolean,
    val openFoodFactsConfigured: Boolean,
    val firecrawlEnabled: Boolean,
    val firecrawlConfigured: Boolean,
    extractorProvider: String?,
    modelIdentifier: String?,
    query: String?,
    val onDeviceAiEnabled: Boolean = false,
    val onDeviceModelInstalled: Boolean = false,
    val ollamaCloudEnabled: Boolean = false,
    val ollamaCloudConfigured: Boolean = false,
    ollamaCloudModelIdentifier: String? = null,
    localModelIdentifier: String? = null,
    private val reportId: String = UUID.randomUUID().toString(),
) {
    val versionName: String = versionName.safeVersion()
    val versionCode: Long = versionCode.coerceAtLeast(0)
    val originalBarcode: String = originalBarcode.filter(Char::isDigit).take(18)
    val normalizedBarcode: String? = normalizedBarcode?.filter(Char::isDigit)?.take(18)
    val extractorProvider: String? = extractorProvider?.safeProvider()
    val modelIdentifier: String? = modelIdentifier?.safeModel()
    val ollamaCloudModelIdentifier: String? = ollamaCloudModelIdentifier?.safeModel()
    val localModelIdentifier: String? = localModelIdentifier?.safeModel()
    val query: String? = query?.safeQuery()

    private val lock = Any()
    private val events = mutableListOf<BottleLookupDebugEvent>()
    private var outcome: String = "RUNNING"

    fun record(event: BottleLookupDebugEvent) = synchronized(lock) {
        events += sanitizeEvent(event)
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    fun finish(result: String) = synchronized(lock) {
        outcome = result.safeCode(OUTCOMES, "UNKNOWN")
        events += BottleLookupDebugEvent(BottleLookupDebugStage.COMPLETE, "APP", outcome)
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    fun toJson(): String = synchronized(lock) {
        val root = buildJsonObject {
            put("reportVersion", 1)
            put("reportId", reportId)
            put("createdAtMillis", createdAtMillis.coerceAtLeast(0))
            put("app", buildJsonObject {
                put("versionName", versionName)
                put("versionCode", versionCode)
            })
            put("barcode", buildJsonObject {
                put("originalDigits", originalBarcode)
                put("normalized", normalizedBarcode ?: "unknown")
            })
            put("providers", buildJsonObject {
                put("openFoodFacts", providerState(openFoodFactsEnabled, openFoodFactsConfigured))
                put("firecrawl", providerState(firecrawlEnabled, firecrawlConfigured))
                put("onDeviceAi", buildJsonObject {
                    put("enabled", onDeviceAiEnabled)
                    put("modelInstalled", onDeviceModelInstalled)
                    put("model", localModelIdentifier ?: "none")
                })
                put("ollamaCloud", buildJsonObject {
                    put("enabled", ollamaCloudEnabled)
                    put("configured", ollamaCloudConfigured)
                    put("model", ollamaCloudModelIdentifier ?: "none")
                })
                put("extractor", buildJsonObject {
                    put("provider", extractorProvider ?: "disabled")
                    put("selected", extractorProvider != null)
                    put("model", modelIdentifier ?: "none")
                })
            })
            put("searchQuery", query ?: "not constructed")
            put("outcome", outcome)
            put("events", buildJsonArray { events.forEach { add(it.toJson()) } })
            put("privacy", "No keys, headers, response bodies, page text, prompts, photos, notes, or household records are included.")
        }
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    private fun providerState(enabled: Boolean, configured: Boolean) = buildJsonObject {
        put("enabled", enabled)
        put("configured", configured)
    }

    private fun sanitizeEvent(event: BottleLookupDebugEvent) = event.copy(
        provider = event.provider.safeProvider(),
        result = event.result.safeCode(RESULTS, "UNKNOWN"),
        durationMillis = event.durationMillis.coerceIn(0, MAX_DURATION_MS),
        httpStatus = event.httpStatus?.takeIf { it in 100..599 },
        resultCount = event.resultCount?.coerceIn(0, MAX_RESULTS),
        sourceUrls = event.sourceUrls.mapNotNull(::safeDiagnosticUrl).distinct().take(MAX_RESULTS),
        reasonCodes = event.reasonCodes.map { it.safeCode(REASONS, "OTHER") }.distinct().take(MAX_REASONS),
        factFields = event.factFields.map { it.safeCode(FACT_FIELDS, "OTHER") }.distinct().take(MAX_FACT_FIELDS),
    )

    private fun BottleLookupDebugEvent.toJson() = buildJsonObject {
        put("stage", stage.name)
        put("provider", provider)
        put("result", result)
        put("durationMillis", durationMillis)
        httpStatus?.let { put("httpStatus", it) }
        resultCount?.let { put("resultCount", it) }
        put("sourceUrls", buildJsonArray { sourceUrls.forEach { add(JsonPrimitive(it)) } })
        put("reasonCodes", buildJsonArray { reasonCodes.forEach { add(JsonPrimitive(it)) } })
        put("factFields", buildJsonArray { factFields.forEach { add(JsonPrimitive(it)) } })
    }

    private fun String.safeVersion() = filter { it.isLetterOrDigit() || it in ".-_+" }.take(80).ifBlank { "unknown" }
    private fun String.safeProvider() = takeIf(PROVIDERS::contains) ?: "unknown"
    private fun String.safeModel() = takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")) } ?: "unknown"
    private fun String.safeQuery() = filter { it.isLetterOrDigit() || it in " ._:-\"" }.take(256)
        .ifBlank { "not constructed" }
    private fun String.safeCode(allowed: Set<String>, fallback: String) = uppercase()
        .filter { it.isLetterOrDigit() || it == '_' }.take(48).takeIf(allowed::contains) ?: fallback

    companion object {
        const val MAX_EVENTS = 16
        const val MAX_RESULTS = 5
        const val MAX_DURATION_MS = 10 * 60 * 1000L
        const val MAX_REASONS = 12
        const val MAX_FACT_FIELDS = 4

        private val OUTCOMES = setOf("RUNNING", "SUCCESS", "PARTIAL", "NOT_FOUND", "FAILED", "CANCELLED", "DISABLED")
        private val RESULTS = setOf(
            "RUNNING", "FOUND", "CACHE_HIT", "MATCH", "NO_MATCH", "NOT_FOUND", "DIFFERENT_PRODUCT_TYPE",
            "DISABLED", "MISSING_KEY", "MISSING_MODEL", "INVALID_BARCODE", "INVALID_QUERY", "LOCALLY_THROTTLED",
            "RATE_LIMITED", "AUTHENTICATION", "QUOTA", "NETWORK_ERROR", "INVALID_RESPONSE", "NO_RESULTS",
            "SELECTED_EXACT_MATCH", "SELECTED_FALLBACK", "NO_USABLE_SOURCE", "FETCHED", "BARCODE_VERIFIED",
            "BARCODE_MISSING", "BARCODE_MISMATCH", "AMBIGUOUS_BARCODE", "CANDIDATES_READY", "CANDIDATES_REJECTED",
            "CANCELLED", "UNEXPECTED_ERROR", "SKIPPED", "UNKNOWN",
        )
        private val REASONS = setOf(
            "HIT_TEXT_MISSING_BARCODE", "GENERIC_BARCODE_LOOKUP", "NO_EXACT_CODE_IN_PAGE", "TARGET_BARCODE_MISMATCH",
            "MULTIPLE_BARCODES_IN_PAGE", "IDENTITY_EVIDENCE_MISSING", "PRODUCT_NAME_NOT_TIED_TO_BARCODE",
            "EVIDENCE_NOT_IN_SOURCE", "VALUE_NOT_IN_EVIDENCE", "CONFLICTING_FIELD_VALUES", "INVALID_FACT_SHAPE",
            "UNSUPPORTED_FIELD", "INVALID_ABV_EVIDENCE", "INVALID_QUANTITY_EVIDENCE", "MALFORMED_MODEL_RESPONSE",
            "NO_SUPPORTED_FIELDS", "REQUEST_CANCELLED", "UNEXPECTED_EXCEPTION", "OTHER",
            "INVALID_BARCODE", "BARCODE_EVIDENCE_MISSING",
            "NO_SEARCH_RESULTS", "MODEL_NOT_CONFIGURED", "PRODUCT_NOT_FOUND", "BRAND_UNRESOLVED",
            "PRODUCT_NAME_UNRESOLVED", "QUANTITY_UNRESOLVED", "ABV_PERCENT_UNRESOLVED",
            "PRODUCT_NAME_MISSING", "INVALID_TARGET_BARCODE",
        )
        private val FACT_FIELDS = setOf("PRODUCT_NAME", "BRAND", "QUANTITY", "ABV_PERCENT", "OTHER")
        private val PROVIDERS = setOf(
            "APP", "Local database", "Open Food Facts", "Firecrawl", "MODEL", "ON_DEVICE", "OLLAMA_CLOUD",
            "On-device Qwen3", "Ollama Cloud", "unknown",
        )

        fun safeDiagnosticUrl(raw: String): String? = runCatching {
            val uri = URI(raw.trim())
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
            val host = uri.host?.let(IDN::toASCII)?.lowercase() ?: return null
            if (!host.contains('.') || host.endsWith('.') || listOf(".local", ".localhost", ".internal", ".lan", ".home").any(host::endsWith)) return null
            // IP literals are not useful as product provenance and could expose a private endpoint.
            if (host.startsWith("[") || host.matches(Regex("[0-9.]+"))) return null
            val pathSegments = uri.rawPath.orEmpty().ifBlank { "/" }.split('/')
            val sensitiveSegment = Regex("(?i)^(?:key|api[-_]?key|token|access[-_]?token|signature|sig|auth|secret|credential)$")
            val safePath = (uri.rawPath.orEmpty().ifBlank { "/" })
                .split('/')
                .mapIndexed { index, segment ->
                    when {
                        sensitiveSegment.matches(segment) -> "<redacted>"
                        index > 0 && sensitiveSegment.matches(pathSegments.getOrNull(index - 1).orEmpty()) -> "<redacted>"
                        segment.length >= 32 && segment.matches(Regex("[A-Za-z0-9_-]+")) -> "<redacted>"
                        else -> segment.take(80)
                    }
                }
                .joinToString("/")
                .take(240)
            URI("https", null, host, -1, safePath.ifBlank { "/" }, null, null).toASCIIString()
        }.getOrNull()
    }
}

