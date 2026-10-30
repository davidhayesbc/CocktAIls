package com.davidhayesbc.cocktails.android.product

import java.math.BigDecimal
import java.util.Locale

/**
 * A deliberately narrow single-product record: the source starts with the exact page title,
 * followed only by labeled specification rows. Prose, navigation, recommendations, extra headings,
 * and unknown row types make the record ineligible. Never use mere numerical proximity as evidence.
 * This does not establish barcode identity for a manually selected page.
 */
internal fun labeledBottleSpecifications(
    page: WebPageText,
    requiredBarcode: String? = null,
): BottleDetailExtractionResult.Found? {
    val text = sanitizeSourceTextForCloud(page.markdown)
    if (text.length > 24_000) return null
    val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
    val title = page.title?.trim()?.takeIf { it.length in 1..120 } ?: return null
    val heading = lines.firstOrNull()?.removePrefix("# ")?.removePrefix("## ")?.removePrefix("### ") ?: return null
    if (!heading.equals(title, ignoreCase = true) || lines.size !in 2..30) return null
    if (Regex("(?i)\\b(?:related|similar|recommended|alternative|other products)\\b").containsMatchIn(heading)) return null
    val rows = lines.drop(1).map { line ->
        val match = Regex("^([^:|]{1,40})\\s*:\\s*(.+)$").matchEntire(line) ?: return null
        Triple(match.groupValues[1].trim().lowercase(Locale.ROOT), match.groupValues[2].trim(), line)
    }
    val sizeLabels = setOf("size", "bottle size", "container size", "taille du contenant")
    val abvLabels = setOf("abv", "alcohol", "alcohol content", "alcohol by volume", "teneur en alcool")
    val identityLabels = setOf("upc", "ean", "gtin", "barcode", "product code", "code de produit")
    val otherLabels = setOf("brand", "price", "deposit", "category", "availability")
    if (rows.any { it.first !in sizeLabels + abvLabels + identityLabels + otherLabels }) return null
    // Repeated product identifiers could denote variants even when the numerical facts agree.
    if (rows.count { it.first in identityLabels } > 1) return null
    if (requiredBarcode != null && (rows.none { it.first in identityLabels } ||
            scannedBarcodePageDecision(requiredBarcode, text) != ScannedBarcodePageDecision.VERIFIED)) return null
    val facts = mutableListOf<BottleFactEvidence>()
    val rejected = mutableListOf<BottleFactRejection>()
    fun collect(field: BottleFactField, labels: Set<String>, parse: (String) -> Pair<String, BigDecimal>?) {
        val candidates = rows.filter { it.first in labels }
        if (candidates.isEmpty()) return
        val parsed = candidates.map { parse(it.second) }
        val distinct = parsed.filterNotNull().map { it.second.stripTrailingZeros() }.distinct()
        if (parsed.any { it == null } || distinct.size != 1) {
            rejected += BottleFactRejection(field, if (candidates.size > 1) BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES
                else if (field == BottleFactField.QUANTITY) BottleExtractionRejectionReason.INVALID_QUANTITY_EVIDENCE
                else BottleExtractionRejectionReason.INVALID_ABV_EVIDENCE)
            return
        }
        facts += BottleFactEvidence(field, parsed.first()!!.first, candidates.first().third,
            barcodeBoundProductCode = requiredBarcode, barcodeBoundProductName = heading)
    }
    collect(BottleFactField.QUANTITY, sizeLabels) { value ->
        val match = Regex("(?i)^(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(mL|L)$").matchEntire(value)
        match?.let {
            val amount = it.groupValues[1].replace(',', '.').toBigDecimal()
            val unit = if (it.groupValues[2].equals("L", true)) "L" else "mL"
            val ml = if (unit == "L") amount * BigDecimal(1000) else amount
            if (ml < BigDecimal.ONE || ml > BigDecimal(20000)) null
            else "${amount.stripTrailingZeros().toPlainString()} $unit" to ml
        }
    }
    collect(BottleFactField.ABV_PERCENT, abvLabels) { value ->
        Regex("^(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*%$").matchEntire(value)?.let {
            val amount = it.groupValues[1].replace(',', '.').toBigDecimal()
            if (amount < BigDecimal.ZERO || amount > BigDecimal(100)) null
            else "${amount.stripTrailingZeros().toPlainString()}%" to amount
        }
    }
    if (facts.isEmpty() && rejected.isEmpty()) return null
    // A size embedded in the title must not contradict a labeled size.
    val conflicts = unresolvedBottleSourceFields(text)
    facts.removeAll { it.field in conflicts }
    rejected += conflicts.map { BottleFactRejection(it, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES) }
    return BottleDetailExtractionResult.Found(BottleDetailSuggestions(
        provider = "Verified labeled source specifications",
        model = "Deterministic extraction",
        sourceUrl = page.sourceUrl,
        facts = facts,
        unresolvedFields = BottleFactField.entries.toSet() - facts.map { it.field }.toSet(),
        targetBarcode = requiredBarcode,
        identityEvidence = requiredBarcode?.let { "$heading\n${rows.single { it.first in identityLabels }.third}" },
        retrievalProvider = page.provider,
        fieldRejections = rejected.distinct(),
        extractionMethod = BottleExtractionMethod.DETERMINISTIC_SOURCE_PARTIAL,
    ))
}
