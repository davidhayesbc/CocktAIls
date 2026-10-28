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
    QUEUE_WAIT,
    LOCAL_MATCH,
    OPEN_FOOD_FACTS,
    FIRECRAWL_SEARCH,
    SOURCE_SELECTION,
    FIRECRAWL_FETCH,
    BARCODE_MATCH,
    DETAIL_EXTRACTION,
    LOCAL_INPUT_VALIDATION,
    LOCAL_RUNTIME_INITIALIZATION,
    LOCAL_MODEL_LOADING,
    LOCAL_PROMPT_SETUP,
    LOCAL_PROMPT_PREFILL,
    LOCAL_GENERATION,
    LOCAL_FIRST_TOKEN,
    LOCAL_RESPONSE_PARSING,
    LOCAL_IDENTITY_BINDING,
    LOCAL_RUNTIME_CLEANUP,
    REVIEW_PERSISTENCE,
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
    val durationMillis: Long? = null,
    val httpStatus: Int? = null,
    val resultCount: Int? = null,
    val sourceUrls: List<String> = emptyList(),
    val reasonCodes: List<String> = emptyList(),
    val factFields: List<String> = emptyList(),
    val failureType: String? = null,
    val failureLocations: List<String> = emptyList(),
    val promptCharacters: Int? = null,
    val outputChunks: Int? = null,
    val generatedTokens: Int? = null,
    val finishReason: String? = null,
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
    // Timing totals survive eviction from the bounded recent-event ring.
    private val timingTotals = mutableMapOf<BottleLookupDebugStage, Long>()
    private val timedStages = mutableSetOf<BottleLookupDebugStage>()
    private var aiProviderWasMeasured = false
    private var outcome: String = "RUNNING"
    private var queueWaitMillis: Long? = null
    private var workerElapsedMillis: Long? = null

    fun setQueueWaitMillis(value: Long?) = synchronized(lock) {
        queueWaitMillis = value?.coerceIn(0, MAX_DURATION_MS)
    }

    fun setWorkerElapsedMillis(value: Long?) = synchronized(lock) {
        workerElapsedMillis = value?.coerceIn(0, MAX_DURATION_MS)
    }

    fun record(event: BottleLookupDebugEvent) = synchronized(lock) {
        val safeEvent = sanitizeEvent(event)
        safeEvent.durationMillis?.let { duration ->
            timedStages += safeEvent.stage
            timingTotals[safeEvent.stage] = ((timingTotals[safeEvent.stage] ?: 0L) + duration)
                .coerceAtMost(MAX_DURATION_MS)
            if (safeEvent.stage == BottleLookupDebugStage.DETAIL_EXTRACTION &&
                safeEvent.provider in setOf("ON_DEVICE", "OLLAMA_CLOUD", "On-device Qwen3", "Ollama Cloud")
            ) aiProviderWasMeasured = true
        }
        events += safeEvent
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
            put("timing", timingSummary())
            put("events", buildJsonArray { events.forEach { add(it.toJson()) } })
            put("privacy", "No keys, headers, response bodies, page text, prompts, photos, notes, or household records are included. Local failures may include allowlisted exception types and first-party stack locations, never exception messages or file paths.")
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
        durationMillis = event.durationMillis?.coerceIn(0, MAX_DURATION_MS),
        httpStatus = event.httpStatus?.takeIf { it in 100..599 },
        resultCount = event.resultCount?.coerceIn(0, MAX_RESULTS),
        sourceUrls = event.sourceUrls.mapNotNull(::safeDiagnosticUrl).take(MAX_RESULTS),
        reasonCodes = event.reasonCodes.map { it.safeReasonCode() }.distinct().take(MAX_REASONS),
        factFields = event.factFields.map { it.safeCode(FACT_FIELDS, "OTHER") }.distinct().take(MAX_FACT_FIELDS),
        failureType = event.failureType?.safeFailureType(),
        failureLocations = event.failureLocations.mapNotNull(::safeFailureLocation).distinct().take(MAX_FAILURE_LOCATIONS),
        promptCharacters = event.promptCharacters?.coerceIn(0, MAX_PROMPT_CHARACTERS),
        outputChunks = event.outputChunks?.coerceIn(0, MAX_OUTPUT_CHUNKS),
        generatedTokens = event.generatedTokens?.coerceIn(0, MAX_GENERATED_TOKENS),
        finishReason = event.finishReason?.takeIf(FINISH_REASONS::contains),
    )

    private fun BottleLookupDebugEvent.toJson() = buildJsonObject {
        put("stage", stage.name)
        put("provider", provider)
        put("result", result)
        durationMillis?.let { put("durationMillis", it) }
        httpStatus?.let { put("httpStatus", it) }
        resultCount?.let { put("resultCount", it) }
        put("sourceUrls", buildJsonArray { sourceUrls.forEach { add(JsonPrimitive(it)) } })
        put("reasonCodes", buildJsonArray { reasonCodes.forEach { add(JsonPrimitive(it)) } })
        put("factFields", buildJsonArray { factFields.forEach { add(JsonPrimitive(it)) } })
        failureType?.let { put("failureType", it) }
        put("failureLocations", buildJsonArray { failureLocations.forEach { add(JsonPrimitive(it)) } })
        promptCharacters?.let { put("promptCharacters", it) }
        outputChunks?.let { put("outputChunks", it) }
        generatedTokens?.let { put("generatedTokens", it) }
        finishReason?.let { put("finishReason", it) }
    }

    private fun timingSummary() = buildJsonObject {
        fun total(stage: BottleLookupDebugStage) = timingTotals[stage] ?: 0L
        val searchMillis = total(BottleLookupDebugStage.FIRECRAWL_SEARCH)
        val fetchMillis = total(BottleLookupDebugStage.FIRECRAWL_FETCH)
        val offMillis = total(BottleLookupDebugStage.OPEN_FOOD_FACTS)
        // DETAIL_EXTRACTION is the outer provider call; local load/prefill/generation events are nested
        // diagnostics and are deliberately excluded from this aggregate to avoid double counting.
        val aiMillis = total(BottleLookupDebugStage.DETAIL_EXTRACTION)
        val networkMillis = searchMillis + fetchMillis + offMillis
        val providerMillis = networkMillis + aiMillis
        val networkMeasured = setOf(
            BottleLookupDebugStage.OPEN_FOOD_FACTS,
            BottleLookupDebugStage.FIRECRAWL_SEARCH,
            BottleLookupDebugStage.FIRECRAWL_FETCH,
        ).any(timedStages::contains)
        timingValue("queueWaitMillis", queueWaitMillis)
        timingValue("workerElapsedMillis", workerElapsedMillis)
        timingValue("openFoodFactsMillis", offMillis.takeIf { BottleLookupDebugStage.OPEN_FOOD_FACTS in timedStages })
        timingValue("firecrawlSearchMillis", searchMillis.takeIf { BottleLookupDebugStage.FIRECRAWL_SEARCH in timedStages })
        timingValue("firecrawlFetchMillis", fetchMillis.takeIf { BottleLookupDebugStage.FIRECRAWL_FETCH in timedStages })
        timingValue("networkProviderMillis", networkMillis.takeIf { networkMeasured })
        timingValue("aiProviderMillis", aiMillis.takeIf { BottleLookupDebugStage.DETAIL_EXTRACTION in timedStages && aiProviderWasMeasured })
        timingValue("providerActiveMillis", providerMillis.takeIf { networkMeasured || BottleLookupDebugStage.DETAIL_EXTRACTION in timedStages && aiProviderWasMeasured })
        timingValue("endToEndMillis", if (queueWaitMillis != null && workerElapsedMillis != null) queueWaitMillis!! + workerElapsedMillis!! else null)
        put("aggregateNote", "Provider totals sum measured network and AI request spans. Local-model substage events are nested in AI time and are not added again. Unmeasured or cross-reboot queue timing is unavailable.")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.timingValue(name: String, value: Long?) {
        put(name, buildJsonObject {
            put("status", if (value == null) "UNAVAILABLE" else "MEASURED")
            value?.let { put("durationMillis", it.coerceIn(0, MAX_DURATION_MS)) }
        })
    }

    private fun String.safeVersion() = filter { it.isLetterOrDigit() || it in ".-_+" }.take(80).ifBlank { "unknown" }
    private fun String.safeProvider() = takeIf(PROVIDERS::contains) ?: "unknown"
    private fun String.safeModel() = takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")) } ?: "unknown"
    private fun String.safeQuery() = filter { it.isLetterOrDigit() || it in " ._:-\"" }.take(256)
        .ifBlank { "not constructed" }
    private fun String.safeCode(allowed: Set<String>, fallback: String) = uppercase()
        .filter { it.isLetterOrDigit() || it == '_' }.take(48).takeIf(allowed::contains) ?: fallback

    private fun String.safeFailureType() = takeIf(FAILURE_TYPES::contains) ?: "OtherException"

    private fun String.safeReasonCode(): String {
        val normalized = uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(48)
        if (normalized in REASONS) return normalized
        return normalized.takeIf {
            Regex("CANDIDATE_[1-5]_(?:GENERIC_BARCODE_LOOKUP|HIT_TEXT_MISSING_BARCODE|SELECTED_EXACT_MATCH|SELECTED_FALLBACK|EXACT_MATCH_NOT_SELECTED|OF_[1-5]|VERIFIED|REJECTED)")
                .matches(it)
        } ?: "OTHER"
    }

    companion object {
        const val MAX_EVENTS = 24
        const val MAX_RESULTS = 5
        const val MAX_DURATION_MS = 10 * 60 * 1000L
        const val MAX_REASONS = 12
        const val MAX_FACT_FIELDS = 4
        const val MAX_FAILURE_LOCATIONS = 4
        const val MAX_PROMPT_CHARACTERS = 20_000
        const val MAX_OUTPUT_CHUNKS = 1_024
        const val MAX_GENERATED_TOKENS = 1_024

        private val OUTCOMES = setOf("RUNNING", "SUCCESS", "PARTIAL", "NOT_FOUND", "FAILED", "CANCELLED", "DISABLED")
        private val RESULTS = setOf(
            "RUNNING", "FOUND", "CACHE_HIT", "MATCH", "NO_MATCH", "NOT_FOUND", "DIFFERENT_PRODUCT_TYPE",
            "DISABLED", "MISSING_KEY", "MISSING_MODEL", "INVALID_BARCODE", "INVALID_QUERY", "LOCALLY_THROTTLED",
            "RATE_LIMITED", "AUTHENTICATION", "QUOTA", "NETWORK_ERROR", "INVALID_RESPONSE", "NO_RESULTS",
            "SELECTED_EXACT_MATCH", "SELECTED_FALLBACK", "CANDIDATE_SELECTED", "NO_USABLE_SOURCE", "FETCHED", "BARCODE_VERIFIED", "STRUCTURED_PRODUCT_MATCH",
            "BARCODE_MISSING", "BARCODE_MISMATCH", "AMBIGUOUS_BARCODE", "CANDIDATES_READY", "CANDIDATES_REJECTED",
            "CANCELLED", "UNEXPECTED_ERROR", "SKIPPED", "PERSISTED", "PERSISTENCE_FAILED", "UNKNOWN",
            "READY", "COMPLETE", "FAILED", "REJECTED", "TIMEOUT", "INVALID_MODEL", "INVALID_SOURCE_URL",
            "LOCAL_RUNTIME_ERROR", "LOCAL_MODEL_ERROR",
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
            "PRODUCT_NAME_MISSING", "INVALID_TARGET_BARCODE", "BARCODE_IN_STRUCTURED_PRODUCT",
            "LOCAL_MODEL_FILE_UNREADABLE", "LOCAL_RUNTIME_INITIALIZATION_FAILED", "LOCAL_NATIVE_RUNTIME_UNAVAILABLE",
            "LOCAL_MODEL_FILE_INVALID", "LOCAL_CPU_BACKEND_UNAVAILABLE", "LOCAL_CPU_BACKEND_REGISTERED",
            "LOCAL_MODEL_LOAD_FAILED", "LOCAL_MODEL_ALLOCATION_FAILED", "LOCAL_MODEL_ARCHITECTURE_UNSUPPORTED",
            "LOCAL_MODEL_PREPARATION_FAILED", "LOCAL_UNSUPPORTED_ARCHITECTURE",
            "LOCAL_PROMPT_SETUP_FAILED", "LOCAL_GENERATION_FAILED", "LOCAL_GENERATION_TIMEOUT",
            "LOCAL_MODEL_OUTPUT_TRUNCATED",
            "LOCAL_RUNTIME_CLEANUP_FAILED",
            "STRUCTURED_FACTS_FALLBACK_AFTER_TIMEOUT",
            "STRUCTURED_FACTS_FALLBACK_AFTER_TRUNCATION", "STRUCTURED_FACTS_FALLBACK_AFTER_MALFORMED_RESPONSE",
            "STRUCTURED_FACTS_FALLBACK_AFTER_MODEL_REJECTION",
            "LOCAL_GENERATION_TOKEN_LIMIT",
            "LOCAL_MODEL_OUTPUT_INVALID", "LOCAL_IDENTITY_BINDING_FAILED", "LOCAL_INFERENCE_FAILED",
        )
        private val FACT_FIELDS = setOf("PRODUCT_NAME", "BRAND", "QUANTITY", "ABV_PERCENT", "OTHER")
        private val FINISH_REASONS = setOf("END_OF_SEQUENCE", "TOKEN_LIMIT", "CANCELLED", "DECODE_ERROR", "UNKNOWN")
        private val PROVIDERS = setOf(
            "APP", "Local database", "Open Food Facts", "Firecrawl", "MODEL", "ON_DEVICE", "OLLAMA_CLOUD",
            "On-device Qwen3", "Ollama Cloud", "unknown",
        )
        private val FAILURE_TYPES = setOf(
            "IllegalStateException", "IllegalArgumentException", "IOException", "FileNotFoundException",
            "SecurityException", "UnsupportedArchitectureException", "UnsupportedModelArchitectureException",
            "NativeCpuBackendUnavailableException", "NativeModelAllocationException", "ModelLoadException",
            "UnsatisfiedLinkError", "NoSuchMethodError",
            "ExceptionInInitializerError", "OutOfMemoryError", "TimeoutCancellationException",
            "CancellationException", "RuntimeException", "LinkageError", "NativeFailure", "OtherException",
        )

        private val SAFE_FAILURE_LOCATION = Regex("[A-Za-z0-9_.$]+\\.[A-Za-z0-9_$<>]+:-?[0-9]+")

        private fun safeFailureLocation(value: String): String? = value
            .takeIf { it.length <= 120 && SAFE_FAILURE_LOCATION.matches(it) }

        fun safeDiagnosticUrl(raw: String): String? = runCatching {
            val uri = URI(raw.trim())
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
            val host = uri.host?.let(IDN::toASCII)?.lowercase() ?: return null
            if (!host.contains('.') || host.endsWith('.') || listOf(".local", ".localhost", ".internal", ".lan", ".home").any(host::endsWith)) return null
            // IP literals are not useful as product provenance and could expose a private endpoint.
            if (host.startsWith("[") || host.matches(Regex("[0-9.]+"))) return null
            // Paths can contain short or encoded credentials and personal identifiers. The copied
            // report retains only the public origin; the exact source remains available in the app.
            URI("https", null, host, -1, "/", null, null).toASCIIString()
        }.getOrNull()
    }
}

