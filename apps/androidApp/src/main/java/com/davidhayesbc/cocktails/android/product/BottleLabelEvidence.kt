package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import kotlinx.serialization.json.*

/** Only validated bounded quotes are retained; the full OCR transcript is never persisted. */
internal data class BottleLabelEvidence(
    val sessionId: String,
    val draftId: String,
    val photoSha256: String,
    val barcode: String?,
    val facts: List<BottleFactEvidence>,
    val unresolved: Set<BottleFactField>,
    val conflicts: Set<BottleFactField> = emptySet(),
)

internal fun BottleLabelEvidence.belongsTo(draft: BottleIntakeDraft): Boolean =
    sessionId == draft.sessionId && draftId == draft.id && photoSha256 == draft.photoSha256 &&
        draft.photoData != null && barcode?.let(::normalizeGtinForLookup) == draft.barcode?.let(::normalizeGtinForLookup)

internal data class IndependentBottleFacts(val facts: List<BottleFactEvidence>, val unresolved: Set<BottleFactField>, val conflicts: Set<BottleFactField> = emptySet(), val explicitCandidateCount: Int = 0, val modelCandidateCount: Int = 0)

/** Validate each field independently; one missing/invalid name cannot discard a supported size. */
internal fun independentBottleFacts(source: String, candidates: List<JsonObject>): IndependentBottleFacts {
    val accepted = candidates.take(24).flatMap { candidate ->
        val result = parseBottleFactCandidate(
            buildJsonObject { put("facts", JsonArray(listOf(candidate))) }.toString(),
            "On-device label text", LOCAL_MODEL_LABEL, "", source, requiredBarcode = null,
        )
        (result as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty()
    }
    val conflicts = accepted.groupBy { it.field }.filterValues { values ->
        values.map(::bottleFactFingerprint).distinct().size > 1
    }.keys + unresolvedBottleSourceFields(source)
    val titleOnlyBrands = titleOnlyBottleBrands(accepted, source)
    val facts = accepted.filterNot { it.field in conflicts || it in titleOnlyBrands }.distinctBy { it.field }
    return IndependentBottleFacts(facts, BottleFactField.entries.toSet() - facts.map { it.field }.toSet(), conflicts)
}

internal fun explicitLabelCandidates(text: String): List<JsonObject> = buildList {
    text.lineSequence().filter { it.length <= 320 }.forEach { line ->
        fun candidate(field: BottleFactField, value: String) {
            add(buildJsonObject { put("field", field.wireValue); put("value", value); put("evidence", line.trim()) })
        }
        Regex("(?i)(?<![\\d.])\\d{1,4}(?:[.,]\\d{1,2})?\\s*(?:ml|cl|l)\\b").findAll(line).forEach {
            candidate(BottleFactField.QUANTITY, it.value)
        }
        if (Regex("(?i)\\b(?:abv|alc(?:ohol)?|vol)\\b").containsMatchIn(line)) {
            Regex("(?<![\\d.])\\d{1,3}(?:[.,]\\d{1,2})?\\s*%").findAll(line).forEach {
                candidate(BottleFactField.ABV_PERCENT, it.value)
            }
        }
    }
}

internal fun bottleFactFingerprint(fact: BottleFactEvidence): String {
    val value = fact.value.lowercase().replace(Regex("\\s+"), "").replace(',', '.')
    if (fact.field == BottleFactField.QUANTITY) {
        Regex("(\\d+(?:\\.\\d+)?)(ml|cl|l)").matchEntire(value)?.let {
            val factor = when (it.groupValues[2]) { "l" -> 1000; "cl" -> 10; else -> 1 }
            return it.groupValues[1].toBigDecimal().multiply(factor.toBigDecimal()).stripTrailingZeros().toPlainString()
        }
    }
    if (fact.field == BottleFactField.ABV_PERCENT) return value.removeSuffix("%").toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value
    return value
}

internal fun verifiedWebFacts(suggestions: BottleDetailSuggestions?, draft: BottleIntakeDraft): List<BottleFactEvidence> {
    val code = draft.barcode?.let(::normalizeGtinForLookup) ?: return emptyList()
    if (suggestions?.targetBarcode?.let(::normalizeGtinForLookup) != code ||
        suggestions.identityEvidence?.let { sourceMentionsScannedBarcode(code, it) } != true
    ) return emptyList()
    return suggestions.facts.filterNot { it.fromLabelPhoto }
}

/** Separate provenance survives merging. Conflicts never choose an arbitrary winning source. */
internal fun mergeLabelAndWebEvidence(
    web: BottleDetailSuggestions?, label: BottleLabelEvidence?, draft: BottleIntakeDraft,
): BottleDetailSuggestions? {
    val currentLabel = label?.takeIf { it.belongsTo(draft) }
    if (currentLabel == null) return web
    val webFacts = verifiedWebFacts(web, draft)
    val allFacts = webFacts + currentLabel.facts.map { it.copy(fromLabelPhoto = true) }
    val conflicts = allFacts.groupBy { it.field }.filterValues { values -> values.map(::bottleFactFingerprint).distinct().size > 1 }.keys
    val unresolvedConflicts = conflicts + currentLabel.conflicts + web?.fieldRejections.orEmpty()
        .filter { it.reason == BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES }.map { it.field }
    val facts = allFacts.filterNot { it.field in unresolvedConflicts }.distinctBy { it.field }
    return (web ?: BottleDetailSuggestions("On-device label text", "Validated OCR", "", emptyList())).copy(
        facts = facts,
        fieldRejections = web?.fieldRejections.orEmpty() + unresolvedConflicts.map { BottleFactRejection(it, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES) },
        unresolvedFields = BottleFactField.entries.toSet() - facts.map { it.field }.toSet(),
    )
}

/** Partial web facts still require their own same-passage, exact-barcode identity evidence. */
internal fun barcodeBoundPartialSpecifications(page: WebPageText, barcode: String): BottleDetailExtractionResult.Found? {
    val lines = page.markdown.lineSequence().filter {
        it.length <= 320 && scannedBarcodePageDecision(barcode, it) == ScannedBarcodePageDecision.VERIFIED &&
            isBarcodeSpecificationRecord(it, barcode)
    }.toList()
    val candidates = lines.flatMap(::explicitLabelCandidates)
    if (candidates.isEmpty()) return null
    val partial = independentBottleFacts(page.markdown, candidates)
    if (partial.facts.isEmpty() && partial.conflicts.isEmpty()) return null
    return BottleDetailExtractionResult.Found(BottleDetailSuggestions(
        provider = "Verified barcode-bound specifications", model = "Deterministic extraction",
        sourceUrl = page.sourceUrl, facts = partial.facts, unresolvedFields = partial.unresolved,
        fieldRejections = partial.conflicts.map { BottleFactRejection(it, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES) },
        targetBarcode = barcode, identityEvidence = lines.first(), retrievalProvider = page.provider,
        extractionMethod = BottleExtractionMethod.DETERMINISTIC_SOURCE_PARTIAL,
    ))
}

/** Without a product name, accept a field-only barcode record, never surrounding prose
 * that might describe a recommendation or a different product on the same line. */
private fun isBarcodeSpecificationRecord(line: String, barcode: String): Boolean {
    val code = normalizeGtinForLookup(barcode) ?: return false
    var remainder = Regex("[0-9]{8,14}").replace(line) { match ->
        if (normalizeGtinForLookup(match.value) == code) " " else match.value
    }
    explicitLabelCandidates(line).forEach { candidate ->
        remainder = remainder.replace(candidate["value"]!!.jsonPrimitive.content, " ", ignoreCase = true)
    }
    val labels = setOf("upc", "ean", "gtin", "barcode", "size", "bottle", "volume", "quantity", "abv", "alc", "alcohol", "content", "by", "vol")
    return Regex("[A-Za-z]+|[0-9]+|[^\\p{ASCII}]").findAll(remainder.replace('·', ' '))
        .all { it.value.lowercase() in labels }
}
