package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.ProductFactSourceDraft
import kotlinx.coroutines.CancellationException

/** A deliberately narrow search string: exact barcode text avoids encouraging generic lookup pages. */
internal fun scannedBottleSearchQuery(barcode: String): String? {
    val digits = barcode.filter(Char::isDigit)
    if (normalizeGtinForLookup(digits) == null) return null
    return "\"$digits\""
}

internal enum class ScannedBarcodePageDecision { VERIFIED, INVALID_TARGET, MISSING, MISMATCH, AMBIGUOUS }

internal fun scannedBarcodePageDecision(barcode: String, sourceText: String): ScannedBarcodePageDecision {
    val expected = normalizeGtinForLookup(barcode) ?: return ScannedBarcodePageDecision.INVALID_TARGET
    val found = sourceBarcodeCandidates(sourceText).toSet()
    if (found.isEmpty()) return ScannedBarcodePageDecision.MISSING
    if (found.size > 1) return ScannedBarcodePageDecision.AMBIGUOUS
    return if (expected in found) ScannedBarcodePageDecision.VERIFIED else ScannedBarcodePageDecision.MISMATCH
}

/** The source page must identify the scanned item before its text is sent to an extractor. */
internal fun sourceMentionsScannedBarcode(barcode: String, sourceText: String): Boolean =
    scannedBarcodePageDecision(barcode, sourceText) == ScannedBarcodePageDecision.VERIFIED

internal data class ScannedBottleSearchSelection(
    val selected: WebSearchHit?,
    val resultCode: String,
    val rejectionCodes: List<String>,
)

/** Avoids spending a page-fetch request on generic barcode lookup tools when a source page is absent. */
internal fun selectScannedBottleSearchHit(barcode: String, hits: List<WebSearchHit>): ScannedBottleSearchSelection {
    if (hits.isEmpty()) return ScannedBottleSearchSelection(null, "NO_USABLE_SOURCE", emptyList())
    val eligible = hits.filterNot(::isGenericBarcodeLookupHit)
    if (eligible.isEmpty()) {
        return ScannedBottleSearchSelection(null, "NO_USABLE_SOURCE", List(hits.size) { "GENERIC_BARCODE_LOOKUP" })
    }
    val exact = eligible.firstOrNull { hit -> sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}") }
    if (exact != null) {
        return ScannedBottleSearchSelection(
            exact,
            "SELECTED_EXACT_MATCH",
            hits.filter { it !== exact }.map { rejectionCode(barcode, it) },
        )
    }
    val fallback = eligible.first()
    return ScannedBottleSearchSelection(
        fallback,
        "SELECTED_FALLBACK",
        hits.filter { it !== fallback }.map { rejectionCode(barcode, it) } + "HIT_TEXT_MISSING_BARCODE",
    )
}

private fun rejectionCode(barcode: String, hit: WebSearchHit) = when {
    isGenericBarcodeLookupHit(hit) -> "GENERIC_BARCODE_LOOKUP"
    !sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}") -> "HIT_TEXT_MISSING_BARCODE"
    else -> "OTHER"
}

private fun isGenericBarcodeLookupHit(hit: WebSearchHit): Boolean {
    val host = runCatching { java.net.URI(hit.url).host?.lowercase() }.getOrNull().orEmpty()
    val genericDomains = setOf("barcodelookup.com", "go-upc.com", "ean-search.org", "ean-search.com")
    val genericDomain = genericDomains.any { host == it || host.endsWith(".$it") }
    val genericTitle = Regex("(?i)^\\s*(?:barcode lookup|upc(?:\\s*/\\s*ean)? (?:lookup|search)|ean search)(?:\\s*[|:-].*)?\\s*$")
        .matches(hit.title.take(180))
    return genericDomain || genericTitle
}

/**
 * Finds evidence scoped to a single product passage. A page-level barcode match is not enough: nearby
 * recommendations or another barcode must never lend a different product name to the scanned bottle.
 */
internal fun scannedProductIdentityPassage(
    barcode: String,
    productName: String,
    sourceText: String,
    identityQuote: String,
): String? {
    val expected = normalizeGtinForLookup(barcode) ?: return null
    val cleanName = productName.trim().takeIf(String::isNotEmpty) ?: return null
    val cleanQuote = identityQuote.trim().takeIf(String::isNotEmpty) ?: return null
    val quotePassages = splitIdentityPassages(cleanQuote)
    if (quotePassages.size != 1) return null

    val section = splitProductSections(sourceText).firstOrNull { candidate ->
        candidate.contains(cleanQuote, ignoreCase = true)
    } ?: return null
    val sectionBarcodes = sourceBarcodeCandidates(section).toSet()
    if (sectionBarcodes != setOf(expected)) return null

    val passage = splitIdentityPassages(section).firstOrNull { candidate ->
        candidate.contains(cleanQuote, ignoreCase = true) &&
            candidate.contains(cleanName, ignoreCase = true) &&
            sourceMentionsScannedBarcode(barcode, candidate)
    } ?: return null
    if (Regex("(?i)\\b(?:related|similar|recommended|recommendation|alternative|other\\s+products?)\\b")
            .containsMatchIn(passage)
    ) return null
    return section
}

/**
 * Converts only explicitly reviewed values into durable product provenance. This runs at final
 * batch confirmation, so accepting a scan and then pausing the session does not lose its evidence.
 */
internal fun acceptedProductSourcesForBottle(
    draft: BottleIntakeDraft,
    suggestions: BottleDetailSuggestions?,
): List<ProductFactSourceDraft> {
    val scanned = draft.barcode?.let(::normalizeGtinForLookup) ?: return emptyList()
    if (suggestions == null || suggestions.targetBarcode?.let(::normalizeGtinForLookup) != scanned ||
        suggestions.identityEvidence?.let { sourceMentionsScannedBarcode(draft.barcode.orEmpty(), it) } != true
    ) return emptyList()

    return suggestions.facts.mapNotNull { fact ->
        val valueWasAccepted = when (fact.field) {
            BottleFactField.PRODUCT_NAME -> fact.value == draft.productName || fact.value == draft.ingredientName
            BottleFactField.BRAND -> fact.value == draft.brand
            else -> false
        }
        if (!valueWasAccepted) return@mapNotNull null
        ProductFactSourceDraft(
            retrievalProvider = suggestions.retrievalProvider,
            extractionProvider = suggestions.provider,
            extractionModel = suggestions.model,
            sourceUrl = suggestions.sourceUrl,
            fieldName = fact.field.wireValue,
            fieldValue = fact.value,
            evidenceQuote = fact.quote,
        )
    }
}

private fun sourceBarcodeCandidates(text: String): Sequence<String> =
    Regex("(?<!\\d)(?:\\d[\\s-]?){7,17}(?!\\d)")
        .findAll(text)
        .mapNotNull { normalizeGtinForLookup(it.value.filter(Char::isDigit)) }

/** Markdown headings, blank lines, horizontal rules and list items define conservative product blocks. */
private fun splitProductSections(text: String): List<String> = text
    .replace("\r\n", "\n")
    .replace("\r", "\n")
    .split(Regex("(?m)(?:^\\s*#{1,6}\\s+|^\\s*(?:[-*_]\\s*){3,}\\s*$|^\\s*[-*+]\\s+|^\\s*\\d+\\.\\s+)|\\n\\s*\\n"))
    .map(String::trim)
    .filter(String::isNotEmpty)

/** Keep the barcode/name association inside one sentence or line, never a quote spanning passages. */
private fun splitIdentityPassages(text: String): List<String> = text
    .replace("\r\n", "\n")
    .replace("\r", "\n")
    .split(Regex("(?<=[.!?;])\\s+|\\n+|\\s+[|•]\\s+"))
    .map(String::trim)
    .filter(String::isNotEmpty)

internal enum class AutomaticBottleExtractor { ON_DEVICE, OLLAMA_CLOUD }

/** Local inference is preferred when explicitly enabled; cloud is used only when local is off. */
internal fun automaticBottleExtractor(
    onDeviceEnabled: Boolean,
    ollamaCloudEnabled: Boolean,
): AutomaticBottleExtractor? = when {
    onDeviceEnabled -> AutomaticBottleExtractor.ON_DEVICE
    ollamaCloudEnabled -> AutomaticBottleExtractor.OLLAMA_CLOUD
    else -> null
}

internal enum class ScannedBottleStage { BARCODE_LOOKUP, WEB_SEARCH, SOURCE_FETCH, DETAIL_EXTRACTION }

internal data class ScannedBottlePlan(
    val openFoodFactsEnabled: Boolean,
    val firecrawlEnabled: Boolean,
    val extractor: AutomaticBottleExtractor?,
)

internal data class ScannedBottleOutcome(
    val barcode: String,
    val openFoodFacts: OpenFoodFactsLookupResult? = null,
    val search: WebSearchResult? = null,
    val selectedHit: WebSearchHit? = null,
    val page: WebPageResult? = null,
    val barcodeVerifiedInPage: Boolean = false,
    val extraction: BottleDetailExtractionResult? = null,
)

internal data class ScannedBottleDebugStageEvent(
    val event: BottleLookupDebugEvent,
)

/**
 * The scan path is deliberately sequential and injectable: tests can prove which providers are called,
 * while the UI can persist only job metadata and keep candidates/evidence in the current review.
 */
internal class ScannedBottleEnrichmentCoordinator(
    private val lookup: suspend (String) -> BottleProviderCall<OpenFoodFactsLookupResult>,
    private val search: suspend (String) -> BottleProviderCall<WebSearchResult>,
    private val fetch: suspend (String) -> BottleProviderCall<WebPageResult>,
    private val extract: suspend (WebPageText, AutomaticBottleExtractor, requiredBarcode: String) -> BottleProviderCall<BottleDetailExtractionResult>,
) {
    suspend fun processScannedBarcode(
        barcode: String,
        plan: ScannedBottlePlan,
        onStage: (ScannedBottleStage, sourceUrl: String?) -> Unit = { _, _ -> },
        onDebugEvent: (BottleLookupDebugEvent) -> Unit = {},
    ): ScannedBottleOutcome {
        val query = scannedBottleSearchQuery(barcode) ?: run {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.COMPLETE, "APP", "INVALID_BARCODE"))
            return ScannedBottleOutcome(barcode)
        }
        var outcome = ScannedBottleOutcome(barcode)
        if (plan.openFoodFactsEnabled) {
            onStage(ScannedBottleStage.BARCODE_LOOKUP, null)
            val call = measured(BottleLookupDebugStage.OPEN_FOOD_FACTS, "Open Food Facts", onDebugEvent) { lookup(barcode) }
            val result = call.result
            onDebugEvent(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.OPEN_FOOD_FACTS,
                provider = "Open Food Facts",
                result = result.toDebugCode(call.httpStatus),
                durationMillis = call.durationMillis,
                httpStatus = call.httpStatus,
                sourceUrls = (result as? OpenFoodFactsLookupResult.Found)?.let {
                    listOf("https://world.openfoodfacts.org/product/${it.suggestion.canonicalCode}")
                }.orEmpty(),
                reasonCodes = result.debugReasonCodes(),
            ))
            outcome = outcome.copy(openFoodFacts = result)
        } else {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.OPEN_FOOD_FACTS, "Open Food Facts", "DISABLED"))
        }
        if (!plan.firecrawlEnabled) {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.FIRECRAWL_SEARCH, "Firecrawl", "DISABLED"))
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "MODEL", "SKIPPED"))
            return outcome
        }

        onStage(ScannedBottleStage.WEB_SEARCH, null)
        val searchCall = measured(BottleLookupDebugStage.FIRECRAWL_SEARCH, "Firecrawl", onDebugEvent) { search(query) }
        val searchResult = searchCall.result
        val hits = (searchResult as? WebSearchResult.Found)?.hits.orEmpty()
        onDebugEvent(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.FIRECRAWL_SEARCH,
            provider = "Firecrawl",
            result = searchResult.toDebugCode(searchCall.httpStatus),
            durationMillis = searchCall.durationMillis,
            httpStatus = searchCall.httpStatus,
            resultCount = hits.size,
            sourceUrls = hits.map(WebSearchHit::url),
        ))
        outcome = outcome.copy(search = searchResult)
        if (hits.isEmpty()) {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.SOURCE_SELECTION, "APP", "NO_USABLE_SOURCE", reasonCodes = listOf("NO_SEARCH_RESULTS")))
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "MODEL", "SKIPPED"))
            return outcome
        }

        val selection = selectScannedBottleSearchHit(barcode, hits)
        onDebugEvent(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.SOURCE_SELECTION,
            provider = "APP",
            result = selection.resultCode,
            sourceUrls = hits.map(WebSearchHit::url),
            reasonCodes = selection.rejectionCodes,
        ))
        val selected = selection.selected ?: run {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "MODEL", "SKIPPED"))
            return outcome
        }
        outcome = outcome.copy(selectedHit = selected)
        onStage(ScannedBottleStage.SOURCE_FETCH, selected.url)
        val fetchCall = measured(BottleLookupDebugStage.FIRECRAWL_FETCH, "Firecrawl", onDebugEvent) { fetch(selected.url) }
        val pageResult = fetchCall.result
        onDebugEvent(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.FIRECRAWL_FETCH,
            provider = "Firecrawl",
            result = pageResult.toDebugCode(fetchCall.httpStatus),
            durationMillis = fetchCall.durationMillis,
            httpStatus = fetchCall.httpStatus,
            sourceUrls = listOf(selected.url),
        ))
        outcome = outcome.copy(page = pageResult)
        val page = (pageResult as? WebPageResult.Found)?.page ?: return outcome
        val pageDecision = scannedBarcodePageDecision(barcode, page.markdown)
        val verified = pageDecision == ScannedBarcodePageDecision.VERIFIED
        onDebugEvent(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.BARCODE_MATCH,
            provider = "APP",
            result = pageDecision.toDebugCode(),
            sourceUrls = listOf(page.sourceUrl),
            reasonCodes = when (pageDecision) {
                ScannedBarcodePageDecision.VERIFIED -> emptyList()
                ScannedBarcodePageDecision.INVALID_TARGET -> listOf("INVALID_BARCODE")
                ScannedBarcodePageDecision.MISSING -> listOf("NO_EXACT_CODE_IN_PAGE")
                ScannedBarcodePageDecision.MISMATCH -> listOf("TARGET_BARCODE_MISMATCH")
                ScannedBarcodePageDecision.AMBIGUOUS -> listOf("MULTIPLE_BARCODES_IN_PAGE")
            },
        ))
        outcome = outcome.copy(barcodeVerifiedInPage = verified)
        val extractor = plan.extractor
        if (!verified || extractor == null) {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "MODEL", "SKIPPED", reasonCodes = listOfNotNull(
                if (!verified) "NO_EXACT_CODE_IN_PAGE" else null,
                if (extractor == null) "MODEL_NOT_CONFIGURED" else null,
            )))
            return outcome
        }

        onStage(ScannedBottleStage.DETAIL_EXTRACTION, page.sourceUrl)
        val extractionCall = measured(BottleLookupDebugStage.DETAIL_EXTRACTION, extractor.name, onDebugEvent) {
            extract(page, extractor, barcode)
        }
        val extraction = extractionCall.result
        val suggestions = (extraction as? BottleDetailExtractionResult.Found)?.suggestions
        onDebugEvent(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.DETAIL_EXTRACTION,
            provider = extractor.name,
            result = extraction.toDebugCode(extractionCall.httpStatus),
            durationMillis = extractionCall.durationMillis,
            httpStatus = extractionCall.httpStatus,
            sourceUrls = listOf(page.sourceUrl),
            factFields = suggestions?.facts?.map { it.field.wireValue }.orEmpty(),
            reasonCodes = buildList {
                (extraction as? BottleDetailExtractionResult.Found)?.rejectedCandidates?.forEach { add(it.debugCode) }
                suggestions?.unresolvedFields?.forEach { add("${it.wireValue.uppercase()}_UNRESOLVED") }
                (extraction as? BottleDetailExtractionResult.Rejected)?.let { add(it.reason.debugCode) }
            },
        ))
        return outcome.copy(extraction = extraction)
    }

    private suspend fun <T> measured(
        stage: BottleLookupDebugStage,
        provider: String,
        onDebugEvent: (BottleLookupDebugEvent) -> Unit,
        call: suspend () -> BottleProviderCall<T>,
    ): BottleProviderCall<T> {
        val started = System.nanoTime()
        onDebugEvent(BottleLookupDebugEvent(stage, provider, "RUNNING"))
        return try {
            call().let { result -> result.copy(durationMillis = elapsedMillis(started)) }
        } catch (cancelled: CancellationException) {
            onDebugEvent(BottleLookupDebugEvent(stage, provider, "CANCELLED", elapsedMillis(started), reasonCodes = listOf("REQUEST_CANCELLED")))
            throw cancelled
        } catch (failure: Exception) {
            onDebugEvent(BottleLookupDebugEvent(stage, provider, "NETWORK_ERROR", elapsedMillis(started), reasonCodes = listOf("UNEXPECTED_EXCEPTION")))
            throw failure
        }
    }

    private fun elapsedMillis(startedNanos: Long) = ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
}

private fun OpenFoodFactsLookupResult.toDebugCode(httpStatus: Int?): String = when (this) {
    is OpenFoodFactsLookupResult.Found -> if (fromCache) "CACHE_HIT" else "FOUND"
    OpenFoodFactsLookupResult.InvalidBarcode -> "INVALID_BARCODE"
    OpenFoodFactsLookupResult.NotFound -> "NOT_FOUND"
    OpenFoodFactsLookupResult.DifferentProductType -> "DIFFERENT_PRODUCT_TYPE"
    OpenFoodFactsLookupResult.LocallyThrottled -> "LOCALLY_THROTTLED"
    OpenFoodFactsLookupResult.ProviderRateLimited -> "RATE_LIMITED"
    OpenFoodFactsLookupResult.Unavailable -> if (httpStatus == 401 || httpStatus == 403) "AUTHENTICATION" else "NETWORK_ERROR"
    OpenFoodFactsLookupResult.InvalidResponse -> "INVALID_RESPONSE"
}

private fun OpenFoodFactsLookupResult.debugReasonCodes(): List<String> = when (this) {
    OpenFoodFactsLookupResult.InvalidBarcode -> listOf("INVALID_BARCODE")
    OpenFoodFactsLookupResult.NotFound -> listOf("PRODUCT_NOT_FOUND")
    OpenFoodFactsLookupResult.DifferentProductType -> listOf("DIFFERENT_PRODUCT_TYPE")
    OpenFoodFactsLookupResult.LocallyThrottled -> listOf("LOCALLY_THROTTLED")
    else -> emptyList()
}

private fun WebSearchResult.toDebugCode(httpStatus: Int?): String = when (this) {
    is WebSearchResult.Found -> if (hits.isEmpty()) "NO_RESULTS" else "FOUND"
    WebSearchResult.SetupRequired -> "MISSING_KEY"
    WebSearchResult.AuthenticationRequired -> "AUTHENTICATION"
    WebSearchResult.InvalidQuery -> "INVALID_QUERY"
    WebSearchResult.QuotaExceeded -> "QUOTA"
    WebSearchResult.RateLimited -> "RATE_LIMITED"
    WebSearchResult.Unavailable -> if (httpStatus == 401 || httpStatus == 403) "AUTHENTICATION" else "NETWORK_ERROR"
    WebSearchResult.InvalidResponse -> "INVALID_RESPONSE"
}

private fun WebPageResult.toDebugCode(httpStatus: Int?): String = when (this) {
    is WebPageResult.Found -> "FETCHED"
    WebPageResult.SetupRequired -> "MISSING_KEY"
    WebPageResult.AuthenticationRequired -> "AUTHENTICATION"
    WebPageResult.InvalidUrl -> "INVALID_QUERY"
    WebPageResult.QuotaExceeded -> "QUOTA"
    WebPageResult.RateLimited -> "RATE_LIMITED"
    WebPageResult.Unavailable -> if (httpStatus == 401 || httpStatus == 403) "AUTHENTICATION" else "NETWORK_ERROR"
    WebPageResult.InvalidResponse -> "INVALID_RESPONSE"
}

private fun BottleDetailExtractionResult.toDebugCode(httpStatus: Int?): String = when (this) {
    is BottleDetailExtractionResult.Found -> "CANDIDATES_READY"
    BottleDetailExtractionResult.SetupRequired -> "MISSING_MODEL"
    BottleDetailExtractionResult.InvalidModel -> "MISSING_MODEL"
    BottleDetailExtractionResult.AuthenticationRequired -> "AUTHENTICATION"
    BottleDetailExtractionResult.QuotaOrRateLimited -> if (httpStatus == 402) "QUOTA" else "RATE_LIMITED"
    BottleDetailExtractionResult.Unavailable -> "NETWORK_ERROR"
    is BottleDetailExtractionResult.Rejected -> "CANDIDATES_REJECTED"
    BottleDetailExtractionResult.InvalidResponse -> "CANDIDATES_REJECTED"
}

private fun ScannedBarcodePageDecision.toDebugCode() = when (this) {
    ScannedBarcodePageDecision.VERIFIED -> "BARCODE_VERIFIED"
    ScannedBarcodePageDecision.INVALID_TARGET -> "INVALID_BARCODE"
    ScannedBarcodePageDecision.MISSING -> "BARCODE_MISSING"
    ScannedBarcodePageDecision.MISMATCH -> "BARCODE_MISMATCH"
    ScannedBarcodePageDecision.AMBIGUOUS -> "AMBIGUOUS_BARCODE"
}
