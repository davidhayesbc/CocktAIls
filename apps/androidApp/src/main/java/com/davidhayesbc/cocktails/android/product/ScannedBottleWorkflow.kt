package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.ProductFactSourceDraft
import kotlinx.coroutines.CancellationException

/** Changes barcode identity while keeping fields the user explicitly entered. */
internal fun BottleIntakeDraft.correctedForBarcode(
    barcode: String,
    editedFields: Set<BottleDraftField>,
): BottleIntakeDraft = copy(
    barcode = barcode,
    productId = null,
    ingredientName = ingredientName.takeIf { BottleDraftField.INGREDIENT_NAME in editedFields } ?: "Unidentified bottle",
    brand = brand.takeIf { BottleDraftField.BRAND in editedFields },
    productName = productName.takeIf { BottleDraftField.PRODUCT_NAME in editedFields },
    quantity = quantity.takeIf { BottleDraftField.QUANTITY in editedFields },
    quantityEdited = BottleDraftField.QUANTITY in editedFields,
    abvPercent = abvPercent.takeIf { BottleDraftField.ABV_PERCENT in editedFields },
    abvPercentEdited = BottleDraftField.ABV_PERCENT in editedFields,
)

internal fun bottleFieldsToPreserveOnBarcodeCorrection(
    draft: BottleIntakeDraft,
    persistedEditedFields: Set<BottleDraftField>,
    isAutomatedScan: Boolean,
): Set<BottleDraftField> = buildSet {
    addAll(persistedEditedFields)
    if (!isAutomatedScan) {
        if (draft.ingredientName.isNotBlank() && draft.ingredientName != "Unidentified bottle") add(BottleDraftField.INGREDIENT_NAME)
        if (!draft.brand.isNullOrBlank()) add(BottleDraftField.BRAND)
        if (!draft.productName.isNullOrBlank()) add(BottleDraftField.PRODUCT_NAME)
        if (!draft.quantity.isNullOrBlank() || draft.quantityEdited) add(BottleDraftField.QUANTITY)
        if (!draft.abvPercent.isNullOrBlank() || draft.abvPercentEdited) add(BottleDraftField.ABV_PERCENT)
    }
    if (draft.quantityEdited) add(BottleDraftField.QUANTITY)
    if (draft.abvPercentEdited) add(BottleDraftField.ABV_PERCENT)
}

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

/** Structured barcode data is accepted only when it uniquely names the product shown in the page title. */
internal enum class StructuredIdentityStatus(val debugCode: String) {
    VERIFIED("STRUCTURED_IDENTITY_VERIFIED"),
    INVALID_BARCODE("STRUCTURED_IDENTITY_INVALID_BARCODE"),
    PAGE_TITLE_MISSING("STRUCTURED_IDENTITY_TITLE_MISSING"),
    STRUCTURED_DATA_MISSING("STRUCTURED_IDENTITY_DATA_MISSING"),
    BARCODE_NOT_PRESENT("STRUCTURED_IDENTITY_BARCODE_NOT_PRESENT"),
    AMBIGUOUS_MATCH("STRUCTURED_IDENTITY_AMBIGUOUS"),
    TITLE_MISMATCH("STRUCTURED_IDENTITY_TITLE_MISMATCH"),
}

internal data class StructuredBottleIdentityResolution(
    val identity: StructuredBottleIdentity?,
    val status: StructuredIdentityStatus,
)

internal fun resolveStructuredBottleIdentityForPage(barcode: String, page: WebPageText): StructuredBottleIdentityResolution {
    val expected = normalizeGtinForLookup(barcode)
        ?: return StructuredBottleIdentityResolution(null, StructuredIdentityStatus.INVALID_BARCODE)
    val title = page.title?.takeIf(String::isNotBlank)
        ?: return StructuredBottleIdentityResolution(null, StructuredIdentityStatus.PAGE_TITLE_MISSING)
    if (page.structuredBottleIdentities.isEmpty()) {
        return StructuredBottleIdentityResolution(null, StructuredIdentityStatus.STRUCTURED_DATA_MISSING)
    }
    val matches = page.structuredBottleIdentities
        .filter { normalizeGtinForLookup(it.barcode) == expected }
        .distinctBy { normalizeProductTitle(it.productName) }
    if (matches.isEmpty()) return StructuredBottleIdentityResolution(null, StructuredIdentityStatus.BARCODE_NOT_PRESENT)
    if (matches.size != 1) return StructuredBottleIdentityResolution(null, StructuredIdentityStatus.AMBIGUOUS_MATCH)
    val identity = matches.single()
    val identityTokens = productIdentityTokens(identity.productName)
    val titleTokens = productIdentityTokens(title)
    if (identityTokens.isEmpty() || titleTokens.isEmpty()) {
        return StructuredBottleIdentityResolution(null, StructuredIdentityStatus.TITLE_MISMATCH)
    }
    val sharedTokens = identityTokens.intersect(titleTokens)
    val titleMatchesProduct = if (identityTokens.size == 1) {
        sharedTokens.size == 1
    } else {
        sharedTokens.size >= 2 && sharedTokens.size.toDouble() / identityTokens.size >= 0.75
    }
    return if (titleMatchesProduct) {
        StructuredBottleIdentityResolution(identity, StructuredIdentityStatus.VERIFIED)
    } else {
        StructuredBottleIdentityResolution(null, StructuredIdentityStatus.TITLE_MISMATCH)
    }
}

internal fun structuredBottleIdentityForPage(barcode: String, page: WebPageText): StructuredBottleIdentity? =
    resolveStructuredBottleIdentityForPage(barcode, page).identity

/** Produces a bounded source passage tying a product name to one exact barcode for local validation. */
internal fun scannedProductIdentityEvidence(barcode: String, productName: String, sourceText: String): String? {
    val expected = normalizeGtinForLookup(barcode) ?: return null
    val cleanName = productName.trim().takeIf(String::isNotEmpty) ?: return null
    for (section in splitProductSections(sourceText)) {
        if (sourceBarcodeCandidates(section).toSet() != setOf(expected)) continue
        val passage = splitIdentityPassages(section).firstOrNull { candidate ->
            sourceMentionsScannedBarcode(barcode, candidate) && candidate.contains(cleanName, ignoreCase = true)
        } ?: continue
        if (passage.length > 320 || Regex("(?i)\\b(?:related|similar|recommended|recommendation|alternative|other\\s+products?)\\b").containsMatchIn(passage)) continue
        return passage
    }
    return null
}

/** Binds model-extracted facts to the locally verified page barcode without sending the code to a model. */
internal fun bindPageBarcodeIdentity(
    result: BottleDetailExtractionResult,
    requiredBarcode: String,
    page: WebPageText,
): BottleDetailExtractionResult {
    if (normalizeGtinForLookup(requiredBarcode) == null) {
        return rejectWithIdentityReasons(result, BottleExtractionRejectionReason.INVALID_TARGET_BARCODE)
    }
    if (result !is BottleDetailExtractionResult.Found) return result
    val nameFact = result.suggestions.facts.firstOrNull { it.field == BottleFactField.PRODUCT_NAME }
        ?: return rejectWithIdentityReasons(result, BottleExtractionRejectionReason.PRODUCT_NAME_MISSING)
    val title = page.title
    if (!title.isNullOrBlank() && !structuredProductNamesAgree(nameFact.value, title)) {
        return rejectWithIdentityReasons(result, BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE)
    }
    val identityEvidence = scannedProductIdentityEvidence(requiredBarcode, nameFact.value, page.markdown)
        ?: return rejectWithIdentityReasons(result, BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE)
    val unboundFacts = result.suggestions.facts.filter { fact ->
        fact.field != BottleFactField.PRODUCT_NAME && !factQuoteIsBoundToProductIdentity(fact, nameFact.value)
    }
    val unresolvedFields = result.suggestions.unresolvedFields + unboundFacts.map(BottleFactEvidence::field)
    val acceptedFacts = result.suggestions.facts.filter { fact ->
        fact.field == BottleFactField.PRODUCT_NAME || fact !in unboundFacts
    }.filterNot { it.field in unresolvedFields }
    val identityRejections = result.suggestions.fieldRejections + unboundFacts.map {
        BottleFactRejection(it.field, BottleExtractionRejectionReason.EVIDENCE_NOT_IN_SOURCE)
    }
    return result.copy(
        suggestions = result.suggestions.copy(
            facts = acceptedFacts,
            unresolvedFields = unresolvedFields,
            targetBarcode = requiredBarcode,
            identityEvidence = identityEvidence,
            fieldRejections = identityRejections.distinct(),
        ),
        rejectedCandidates = result.rejectedCandidates + if (unboundFacts.isNotEmpty()) {
            setOf(BottleExtractionRejectionReason.EVIDENCE_NOT_IN_SOURCE)
        } else emptySet(),
    )
}

private fun rejectWithIdentityReasons(
    result: BottleDetailExtractionResult,
    reason: BottleExtractionRejectionReason,
): BottleDetailExtractionResult {
    if (result !is BottleDetailExtractionResult.Found) return result
    val fieldReasons = buildList {
        addAll(result.suggestions.fieldRejections)
        if (reason == BottleExtractionRejectionReason.PRODUCT_NAME_MISSING) {
            add(BottleFactRejection(BottleFactField.PRODUCT_NAME, reason))
        }
        result.suggestions.facts.forEach { fact ->
            val fieldReason = if (fact.field == BottleFactField.PRODUCT_NAME || reason == BottleExtractionRejectionReason.INVALID_TARGET_BARCODE) {
                reason
            } else {
                BottleExtractionRejectionReason.EVIDENCE_NOT_IN_SOURCE
            }
            add(BottleFactRejection(fact.field, fieldReason))
        }
    }.distinct()
    return BottleDetailExtractionResult.Rejected(
        reason = reason,
        rejectedFields = fieldReasons.map(BottleFactRejection::field).toSet(),
        fieldRejections = fieldReasons,
    )
}

private fun productIdentityTokens(value: String): Set<String> = normalizeProductTitle(value)
    .split(' ')
    .filter { it.length > 1 && it !in GENERIC_PRODUCT_TITLE_WORDS }
    .toSet()

internal fun structuredProductNamesAgree(candidate: String, expected: String): Boolean {
    val candidateTokens = productIdentityTokens(candidate)
    val expectedTokens = productIdentityTokens(expected)
    if (candidateTokens.isEmpty() || expectedTokens.isEmpty()) return false
    val shared = candidateTokens.intersect(expectedTokens).size
    if (candidateTokens.size == 1 || expectedTokens.size == 1) return candidateTokens == expectedTokens
    return shared >= 2 && shared.toDouble() / expectedTokens.size >= 0.67 && shared.toDouble() / candidateTokens.size >= 0.8
}

private fun normalizeProductTitle(value: String): String = value.lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private val GENERIC_PRODUCT_TITLE_WORDS = setOf(
    "single", "malt", "scotch", "whisky", "whiskey", "bourbon", "gin", "vodka", "rum", "tequila",
    "liqueur", "spirit", "spirits", "product", "bottle", "shop", "store", "liquor", "canada",
)

internal data class ScannedBottleSearchSelection(
    val selected: WebSearchHit?,
    /** Candidates are ordered exact-code hit text first, followed by other non-generic hits. */
    val candidates: List<WebSearchHit>,
    val resultCode: String,
    val rejectionCodes: List<String>,
)

/** A scan may fetch at most three search results automatically (Firecrawl charges per page). */
internal const val MAX_AUTOMATIC_SOURCE_FETCHES = 3

/** Avoids spending a page-fetch request on generic barcode lookup tools when a source page is absent. */
internal fun selectScannedBottleSearchHit(barcode: String, hits: List<WebSearchHit>): ScannedBottleSearchSelection {
    if (hits.isEmpty()) return ScannedBottleSearchSelection(null, emptyList(), "NO_USABLE_SOURCE", emptyList())
    val eligible = hits.filterNot { isGenericBarcodeLookupHit(barcode, it) }
    if (eligible.isEmpty()) {
        return ScannedBottleSearchSelection(
            null,
            emptyList(),
            "NO_USABLE_SOURCE",
            hits.indices.map { "CANDIDATE_${it + 1}_GENERIC_BARCODE_LOOKUP" },
        )
    }
    val ordered = eligible
        .sortedByDescending { hit -> sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}") }
        .distinctBy { hit -> normalizePublicHttpsUrl(hit.url)?.substringBefore('#') ?: hit.url }
    val exact = ordered.firstOrNull { hit -> sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}") }
    if (exact != null) {
        return ScannedBottleSearchSelection(
            exact,
            ordered.take(MAX_AUTOMATIC_SOURCE_FETCHES),
            "SELECTED_EXACT_MATCH",
            hits.mapIndexed { index, hit ->
                val code = when {
                    hit === exact -> "SELECTED_EXACT_MATCH"
                    isGenericBarcodeLookupHit(barcode, hit) -> "GENERIC_BARCODE_LOOKUP"
                    sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}") -> "EXACT_MATCH_NOT_SELECTED"
                    else -> "HIT_TEXT_MISSING_BARCODE"
                }
                "CANDIDATE_${index + 1}_$code"
            },
        )
    }
    val fallback = eligible.first()
    return ScannedBottleSearchSelection(
        fallback,
        ordered.take(MAX_AUTOMATIC_SOURCE_FETCHES),
        "SELECTED_FALLBACK",
        hits.mapIndexed { index, hit ->
            val code = when {
                hit === fallback -> "SELECTED_FALLBACK"
                isGenericBarcodeLookupHit(barcode, hit) -> "GENERIC_BARCODE_LOOKUP"
                else -> "HIT_TEXT_MISSING_BARCODE"
            }
            "CANDIDATE_${index + 1}_$code"
        },
    )
}

private fun isGenericBarcodeLookupHit(barcode: String, hit: WebSearchHit): Boolean {
    val uri = runCatching { java.net.URI(hit.url) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    val genericDomains = setOf("barcodelookup.com", "go-upc.com", "ean-search.org", "ean-search.com")
    val genericDomain = genericDomains.any { host == it || host.endsWith(".$it") }
    val exactCodeInHit = sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()} ${uri?.rawPath.orEmpty()}")
    val genericTitle = Regex("(?i)^\\s*(?:barcode lookup(?:\\s*\\|\\s*upc, ean & isbn search)?|upc(?:\\s*/\\s*ean)? (?:lookup|search)|ean search)\\s*$")
        .matches(hit.title.take(180))
    return genericTitle || (genericDomain && !exactCodeInHit && uri?.rawPath.orEmpty().trim('/') in setOf("", "search", "barcode-lookup"))
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
            BottleFactField.QUANTITY -> fact.value == draft.quantity
            BottleFactField.ABV_PERCENT -> fact.value == draft.abvPercent
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
    /** User opt-in can be true while a provider is not configured or its model is unavailable. */
    val firecrawlRequested: Boolean = firecrawlEnabled,
    val aiRequested: Boolean = extractor != null,
)

internal data class ScannedBottleOutcome(
    val barcode: String,
    val openFoodFacts: OpenFoodFactsLookupResult? = null,
    val search: WebSearchResult? = null,
    val selectedHit: WebSearchHit? = null,
    val page: WebPageResult? = null,
    val barcodeVerifiedInPage: Boolean = false,
    val barcodeVerifiedByStructuredProduct: Boolean = false,
    val extraction: BottleDetailExtractionResult? = null,
)

/** Converts one completed scan into fixed, resumable Web/AI indicators for that bottle only. */
internal fun bottleProviderOutcomes(
    plan: ScannedBottlePlan,
    outcome: ScannedBottleOutcome?,
    lastOperation: BottleEnrichmentOperation? = null,
): BottleEnrichmentProviderOutcomes {
    val web = when {
        plan.firecrawlEnabled -> when (val search = outcome?.search) {
            is WebSearchResult.Found -> when {
                search.hits.isEmpty() -> BottleProviderOutcome.NO_RESULTS
                outcome.barcodeVerifiedInPage || outcome.barcodeVerifiedByStructuredProduct -> BottleProviderOutcome.COMPLETE
                outcome.page is WebPageResult.QuotaExceeded -> BottleProviderOutcome.QUOTA
                outcome.page is WebPageResult.AuthenticationRequired -> BottleProviderOutcome.AUTHENTICATION_REQUIRED
                outcome.page is WebPageResult.SetupRequired -> BottleProviderOutcome.NOT_CONFIGURED
                outcome.page is WebPageResult.Unavailable || outcome.page is WebPageResult.InvalidResponse -> BottleProviderOutcome.FAILED
                else -> BottleProviderOutcome.NO_MATCH
            }
            WebSearchResult.QuotaExceeded -> BottleProviderOutcome.QUOTA
            WebSearchResult.AuthenticationRequired -> BottleProviderOutcome.AUTHENTICATION_REQUIRED
            WebSearchResult.SetupRequired -> BottleProviderOutcome.NOT_CONFIGURED
            WebSearchResult.RateLimited -> BottleProviderOutcome.QUOTA
            WebSearchResult.Unavailable, WebSearchResult.InvalidResponse -> BottleProviderOutcome.FAILED
            WebSearchResult.InvalidQuery -> BottleProviderOutcome.NO_MATCH
            null -> when (lastOperation) {
                BottleEnrichmentOperation.FIRECRAWL_SEARCH, BottleEnrichmentOperation.FIRECRAWL_FETCH -> BottleProviderOutcome.FAILED
                else -> BottleProviderOutcome.NOT_RECORDED
            }
        }
        plan.openFoodFactsEnabled -> when (outcome?.openFoodFacts) {
            is OpenFoodFactsLookupResult.Found -> BottleProviderOutcome.COMPLETE
            OpenFoodFactsLookupResult.NotFound, OpenFoodFactsLookupResult.DifferentProductType -> BottleProviderOutcome.NO_RESULTS
            OpenFoodFactsLookupResult.ProviderRateLimited, OpenFoodFactsLookupResult.LocallyThrottled -> BottleProviderOutcome.QUOTA
            OpenFoodFactsLookupResult.Unavailable, OpenFoodFactsLookupResult.InvalidResponse -> BottleProviderOutcome.FAILED
            OpenFoodFactsLookupResult.InvalidBarcode -> BottleProviderOutcome.NO_MATCH
            null -> BottleProviderOutcome.NOT_RECORDED
        }
        plan.firecrawlRequested -> BottleProviderOutcome.NOT_CONFIGURED
        else -> BottleProviderOutcome.DISABLED
    }

    val ai = when (val extraction = outcome?.extraction) {
        is BottleDetailExtractionResult.Found -> BottleProviderOutcome.COMPLETE
        is BottleDetailExtractionResult.Rejected, BottleDetailExtractionResult.InvalidResponse -> BottleProviderOutcome.FAILED
        BottleDetailExtractionResult.SetupRequired, BottleDetailExtractionResult.InvalidModel -> BottleProviderOutcome.NOT_CONFIGURED
        BottleDetailExtractionResult.AuthenticationRequired -> BottleProviderOutcome.AUTHENTICATION_REQUIRED
        BottleDetailExtractionResult.QuotaOrRateLimited -> BottleProviderOutcome.QUOTA
        BottleDetailExtractionResult.Unavailable -> BottleProviderOutcome.FAILED
        is BottleDetailExtractionResult.LocalFailure -> when (extraction.code) {
            LocalInferenceFailureCode.MODEL_FILE_UNREADABLE,
            LocalInferenceFailureCode.MODEL_FILE_INVALID,
            LocalInferenceFailureCode.MODEL_ARCHITECTURE_UNSUPPORTED -> BottleProviderOutcome.NOT_CONFIGURED
            else -> BottleProviderOutcome.FAILED
        }
        null -> when {
            outcome == null && lastOperation in setOf(BottleEnrichmentOperation.LOCAL_EXTRACTION, BottleEnrichmentOperation.OLLAMA_EXTRACTION) -> BottleProviderOutcome.FAILED
            !plan.aiRequested -> BottleProviderOutcome.DISABLED
            outcome?.barcodeVerifiedInPage != true && outcome?.barcodeVerifiedByStructuredProduct != true -> BottleProviderOutcome.SKIPPED_NO_SOURCE
            plan.extractor == null -> BottleProviderOutcome.NOT_CONFIGURED
            lastOperation in setOf(BottleEnrichmentOperation.LOCAL_EXTRACTION, BottleEnrichmentOperation.OLLAMA_EXTRACTION) -> BottleProviderOutcome.FAILED
            else -> BottleProviderOutcome.NOT_RECORDED
        }
    }
    return BottleEnrichmentProviderOutcomes(web, ai)
}

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
        onStage: suspend (ScannedBottleStage, sourceUrl: String?, progressLabel: String?) -> Unit = { _, _, _ -> },
        onDebugEvent: (BottleLookupDebugEvent) -> Unit = {},
        detailedCapture: DetailedBottleLookupRecorder? = null,
    ): ScannedBottleOutcome {
        val query = scannedBottleSearchQuery(barcode) ?: run {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.COMPLETE, "APP", "INVALID_BARCODE"))
            return ScannedBottleOutcome(barcode)
        }
        var outcome = ScannedBottleOutcome(barcode)
        if (plan.openFoodFactsEnabled) {
            onStage(ScannedBottleStage.BARCODE_LOOKUP, null, null)
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

        onStage(ScannedBottleStage.WEB_SEARCH, null, null)
        val searchCall = measured(BottleLookupDebugStage.FIRECRAWL_SEARCH, "Firecrawl", onDebugEvent) { search(query) }
        val searchResult = searchCall.result
        val hits = (searchResult as? WebSearchResult.Found)?.hits.orEmpty()
        if (hits.isEmpty()) detailedCapture?.recordSearchSelection(emptyList(), null, "NO_SEARCH_RESULTS")
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
        val selectedIndex = selection.selected?.let(hits::indexOf)?.takeIf { it >= 0 }
        detailedCapture?.recordSearchSelection(hits, selectedIndex, selection.resultCode)
        onDebugEvent(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.SOURCE_SELECTION,
            provider = "APP",
            result = selection.resultCode,
            sourceUrls = hits.map(WebSearchHit::url),
            reasonCodes = selection.rejectionCodes,
        ))
        val candidates = selection.candidates
        val selected = selection.selected ?: run {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "MODEL", "SKIPPED"))
            return outcome
        }
        var lastPageResult: WebPageResult? = null
        var lastHit: WebSearchHit = selected
        var verifiedPage: WebPageText? = null
        var lastDecision = ScannedBarcodePageDecision.MISSING
        for ((index, hit) in candidates.withIndex()) {
            lastHit = hit
            val hitContainsCode = sourceMentionsScannedBarcode(barcode, "${hit.title} ${hit.snippet.orEmpty()}")
            onStage(
                ScannedBottleStage.SOURCE_FETCH,
                hit.url,
                "Checking result ${index + 1} of ${candidates.size} for the exact barcode (up to $MAX_AUTOMATIC_SOURCE_FETCHES page fetches)",
            )
            onDebugEvent(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.SOURCE_SELECTION,
                provider = "APP",
                result = "CANDIDATE_SELECTED",
                sourceUrls = listOf(hit.url),
                reasonCodes = listOf(when {
                    hit == selected && selection.resultCode == "SELECTED_EXACT_MATCH" -> "CANDIDATE_${index + 1}_SELECTED_EXACT_MATCH"
                    hitContainsCode -> "CANDIDATE_${index + 1}_EXACT_MATCH_NOT_SELECTED"
                    hit == selected -> "CANDIDATE_${index + 1}_SELECTED_FALLBACK"
                    else -> "CANDIDATE_${index + 1}_HIT_TEXT_MISSING_BARCODE"
                }),
            ))
            val fetchCall = measured(BottleLookupDebugStage.FIRECRAWL_FETCH, "Firecrawl", onDebugEvent) { fetch(hit.url) }
            lastPageResult = fetchCall.result
            onDebugEvent(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.FIRECRAWL_FETCH,
                provider = "Firecrawl",
                result = fetchCall.result.toDebugCode(fetchCall.httpStatus),
                durationMillis = fetchCall.durationMillis,
                httpStatus = fetchCall.httpStatus,
                sourceUrls = listOf(hit.url),
                reasonCodes = listOf("CANDIDATE_${index + 1}_OF_${candidates.size}"),
            ))
            val page = (fetchCall.result as? WebPageResult.Found)?.page
            if (page == null) {
                if (fetchCall.result in setOf(WebPageResult.QuotaExceeded, WebPageResult.RateLimited, WebPageResult.AuthenticationRequired, WebPageResult.SetupRequired)) break
                continue
            }
            val markdownDecision = scannedBarcodePageDecision(barcode, page.markdown)
            val structuredResolution = resolveStructuredBottleIdentityForPage(barcode, page)
            val structuredIdentity = if (markdownDecision == ScannedBarcodePageDecision.VERIFIED) null else structuredResolution.identity
            val decision = if (structuredIdentity != null) ScannedBarcodePageDecision.VERIFIED else markdownDecision
            detailedCapture?.recordFetchedPage(
                index = index,
                url = page.sourceUrl,
                title = page.title,
                barcodeDecision = if (structuredIdentity != null) "STRUCTURED_PRODUCT_MATCH" else decision.name,
                structuredIdentities = page.structuredBottleIdentities,
                structuredIdentityStatus = structuredResolution.status,
            )
            lastDecision = decision
            onDebugEvent(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.BARCODE_MATCH,
                provider = "APP",
                result = if (structuredIdentity != null) "STRUCTURED_PRODUCT_MATCH" else decision.toDebugCode(),
                sourceUrls = listOf(page.sourceUrl),
                reasonCodes = if (structuredIdentity != null) {
                    listOf("BARCODE_IN_STRUCTURED_PRODUCT", "CANDIDATE_${index + 1}_VERIFIED")
                } else when (decision) {
                    ScannedBarcodePageDecision.VERIFIED -> listOf("CANDIDATE_${index + 1}_VERIFIED")
                    ScannedBarcodePageDecision.INVALID_TARGET -> listOf("INVALID_BARCODE", "CANDIDATE_${index + 1}_REJECTED")
                    ScannedBarcodePageDecision.MISSING -> listOf("NO_EXACT_CODE_IN_PAGE", "CANDIDATE_${index + 1}_REJECTED")
                    ScannedBarcodePageDecision.MISMATCH -> listOf("TARGET_BARCODE_MISMATCH", "CANDIDATE_${index + 1}_REJECTED")
                    ScannedBarcodePageDecision.AMBIGUOUS -> listOf("MULTIPLE_BARCODES_IN_PAGE", "CANDIDATE_${index + 1}_REJECTED")
                },
            ))
            if (decision == ScannedBarcodePageDecision.VERIFIED) {
                verifiedPage = if (structuredIdentity == null) {
                    page
                } else {
                    page.copy(structuredBottleIdentities = listOf(structuredIdentity))
                }
                outcome = outcome.copy(barcodeVerifiedByStructuredProduct = structuredIdentity != null)
                break
            }
        }
        val page = verifiedPage
        if (page == null) {
            outcome = outcome.copy(selectedHit = lastHit, page = lastPageResult, barcodeVerifiedInPage = false)
            onDebugEvent(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.DETAIL_EXTRACTION,
                provider = "MODEL",
                result = "SKIPPED",
                reasonCodes = listOf(when (lastDecision) {
                    ScannedBarcodePageDecision.MISMATCH -> "TARGET_BARCODE_MISMATCH"
                    ScannedBarcodePageDecision.AMBIGUOUS -> "MULTIPLE_BARCODES_IN_PAGE"
                    else -> "NO_EXACT_CODE_IN_PAGE"
                }),
            ))
            return outcome
        }
        outcome = outcome.copy(selectedHit = lastHit, page = WebPageResult.Found(page), barcodeVerifiedInPage = true)
        val extractor = plan.extractor
        if (extractor == null) {
            onDebugEvent(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "MODEL", "SKIPPED", reasonCodes = listOfNotNull(
                "MODEL_NOT_CONFIGURED",
            )))
            return outcome
        }

        onStage(ScannedBottleStage.DETAIL_EXTRACTION, page.sourceUrl, null)
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
            rejectedFacts = when (extraction) {
                is BottleDetailExtractionResult.Found -> extraction.suggestions.fieldRejections
                is BottleDetailExtractionResult.Rejected -> extraction.fieldRejections
                else -> emptyList()
            },
            reasonCodes = buildList {
                (extraction as? BottleDetailExtractionResult.Found)?.rejectedCandidates?.forEach { add(it.debugCode) }
                suggestions?.unresolvedFields?.forEach { add("${it.wireValue.uppercase()}_UNRESOLVED") }
                (extraction as? BottleDetailExtractionResult.Rejected)?.let { add(it.reason.debugCode) }
                (extraction as? BottleDetailExtractionResult.LocalFailure)?.let { add(it.code.wireValue) }
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
            val localInference = stage == BottleLookupDebugStage.DETAIL_EXTRACTION && provider == "On-device Qwen3"
            onDebugEvent(BottleLookupDebugEvent(
                stage = stage,
                provider = provider,
                result = if (localInference) "LOCAL_RUNTIME_ERROR" else "NETWORK_ERROR",
                durationMillis = elapsedMillis(started),
                reasonCodes = listOf(if (localInference) LocalInferenceFailureCode.INFERENCE_FAILED.wireValue else "UNEXPECTED_EXCEPTION"),
                failureType = failure.javaClass.simpleName,
                failureLocations = failure.stackTrace.asSequence()
                    .filter { it.className.startsWith("com.arm.aichat.") || it.className.startsWith("com.davidhayesbc.cocktails.android.product.") }
                    .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    .take(BottleLookupDebugReport.MAX_FAILURE_LOCATIONS)
                    .toList(),
            ))
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
    is BottleDetailExtractionResult.LocalFailure -> when ((this as BottleDetailExtractionResult.LocalFailure).code) {
        LocalInferenceFailureCode.MODEL_OUTPUT_INVALID,
        LocalInferenceFailureCode.IDENTITY_BINDING_FAILED -> "CANDIDATES_REJECTED"
        else -> "LOCAL_RUNTIME_ERROR"
    }
}

private fun ScannedBarcodePageDecision.toDebugCode() = when (this) {
    ScannedBarcodePageDecision.VERIFIED -> "BARCODE_VERIFIED"
    ScannedBarcodePageDecision.INVALID_TARGET -> "INVALID_BARCODE"
    ScannedBarcodePageDecision.MISSING -> "BARCODE_MISSING"
    ScannedBarcodePageDecision.MISMATCH -> "BARCODE_MISMATCH"
    ScannedBarcodePageDecision.AMBIGUOUS -> "AMBIGUOUS_BARCODE"
}
