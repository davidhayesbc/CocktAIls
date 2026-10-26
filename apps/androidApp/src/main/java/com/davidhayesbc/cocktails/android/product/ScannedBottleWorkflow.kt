package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.ProductFactSourceDraft

/** Builds a bounded Firecrawl query from the scan itself, never from household names or notes. */
internal fun scannedBottleSearchQuery(barcode: String): String? {
    val digits = barcode.filter(Char::isDigit)
    if (normalizeGtinForLookup(digits) == null) return null
    return "barcode UPC EAN product code $digits"
}

/** The source page must identify the scanned item before its text is sent to an extractor. */
internal fun sourceMentionsScannedBarcode(barcode: String, sourceText: String): Boolean {
    val expected = normalizeGtinForLookup(barcode) ?: return false
    return sourceBarcodeCandidates(sourceText).any { it == expected }
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
