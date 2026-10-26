package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup

/** Builds a bounded Firecrawl query from the scan itself, never from household names or notes. */
internal fun scannedBottleSearchQuery(barcode: String): String? {
    val digits = barcode.filter(Char::isDigit)
    if (normalizeGtinForLookup(digits) == null) return null
    return "barcode UPC EAN product code $digits"
}

/** The source page must identify the scanned item before its text is sent to an extractor. */
internal fun sourceMentionsScannedBarcode(barcode: String, sourceText: String): Boolean {
    val expected = normalizeGtinForLookup(barcode) ?: return false
    val candidates = Regex("(?<!\\d)(?:\\d[\\s-]?){7,17}(?!\\d)")
        .findAll(sourceText)
        .map { it.value.filter(Char::isDigit) }
    return candidates.any { normalizeGtinForLookup(it) == expected }
}

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

/**
 * The scan path is deliberately sequential and injectable: tests can prove which providers are called,
 * while the UI can persist only job metadata and keep candidates/evidence in the current review.
 */
internal class ScannedBottleEnrichmentCoordinator(
    private val lookup: suspend (String) -> OpenFoodFactsLookupResult,
    private val search: suspend (String) -> WebSearchResult,
    private val fetch: suspend (String) -> WebPageResult,
    private val extract: suspend (WebPageText, AutomaticBottleExtractor, requiredBarcode: String) -> BottleDetailExtractionResult,
) {
    suspend fun processScannedBarcode(
        barcode: String,
        plan: ScannedBottlePlan,
        onStage: (ScannedBottleStage, sourceUrl: String?) -> Unit = { _, _ -> },
    ): ScannedBottleOutcome {
        val query = scannedBottleSearchQuery(barcode) ?: return ScannedBottleOutcome(barcode)
        var outcome = ScannedBottleOutcome(barcode)
        if (plan.openFoodFactsEnabled) {
            onStage(ScannedBottleStage.BARCODE_LOOKUP, null)
            outcome = outcome.copy(openFoodFacts = lookup(barcode))
        }
        if (!plan.firecrawlEnabled) return outcome

        onStage(ScannedBottleStage.WEB_SEARCH, null)
        val searchResult = search(query)
        outcome = outcome.copy(search = searchResult)
        val hits = (searchResult as? WebSearchResult.Found)?.hits.orEmpty()
        if (hits.isEmpty()) return outcome

        // Prefer a hit whose own title/snippet states the code. Otherwise inspect only the first result;
        // the fetched page must independently prove the barcode before extraction is permitted.
        val selected = hits.firstOrNull { hit -> sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}") }
            ?: hits.first()
        outcome = outcome.copy(selectedHit = selected)
        onStage(ScannedBottleStage.SOURCE_FETCH, selected.url)
        val pageResult = fetch(selected.url)
        outcome = outcome.copy(page = pageResult)
        val page = (pageResult as? WebPageResult.Found)?.page ?: return outcome
        val verified = sourceMentionsScannedBarcode(barcode, page.markdown)
        outcome = outcome.copy(barcodeVerifiedInPage = verified)
        val extractor = plan.extractor
        if (!verified || extractor == null) return outcome

        onStage(ScannedBottleStage.DETAIL_EXTRACTION, page.sourceUrl)
        return outcome.copy(extraction = extract(page, extractor, barcode))
    }
}
