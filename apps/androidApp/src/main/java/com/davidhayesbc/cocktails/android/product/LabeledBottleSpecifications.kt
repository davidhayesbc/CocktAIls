package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
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
    // A compound page heading is not independently verified product identity. A pipe may
    // separate a site attribution, a variant, a package, or another product. Do not guess
    // which segment is the product or attach deterministic facts to a shortened model name.
    // Leave these pages to the existing evidence-validated model/structured-table paths.
    if ('|' in heading) return null
    if (Regex("(?i)\\b(?:related|similar|recommended|alternative|other products)\\b").containsMatchIn(heading)) return null
    var extendedRecord = false
    val rows = lines.drop(1).flatMap { line ->
        // Exactly two cells: a labeled, checksum-valid code and one explicit size.
        // Preserve the original line as evidence for both cells.
        val combined = Regex("""(?i)^(UPC|EAN|GTIN|barcode)\s*:?[ \t]*(\d{8,14})\s*\|\s*([^|]+)$""")
            .matchEntire(line)
        if (combined != null) {
            if (normalizeGtinForLookup(combined.groupValues[2]) == null) return null
            extendedRecord = true
            listOf(Triple(combined.groupValues[1].lowercase(Locale.ROOT), combined.groupValues[2], line),
                Triple("size", combined.groupValues[3].trim(), line))
        } else {
            // A combined-looking identifier row must satisfy the exact combined grammar.
            // Do not reinterpret malformed/extra cells as the value of an ordinary colon row.
            if ('|' in line && Regex("""(?i)^(?:UPC|EAN|GTIN|barcode)\b""").containsMatchIn(line)) return null
            val match = Regex("^([^:|]{1,40})\\s*:\\s*(.+)$").matchEntire(line) ?: return null
            val label = match.groupValues[1].trim().lowercase(Locale.ROOT)
            if (label in setOf("bottles available in", "alcohol grade")) extendedRecord = true
            listOf(Triple(label, match.groupValues[2].trim(), line))
        }
    }
    val sizeLabels = setOf("size", "bottle size", "container size", "taille du contenant", "bottles available in")
    val abvLabels = setOf("abv", "alcohol", "alcohol content", "alcohol by volume", "teneur en alcool", "alcohol grade")
    val identityLabels = setOf("upc", "ean", "gtin", "barcode", "product code", "code de produit")
    val otherLabels = setOf("brand", "price", "deposit", "category", "availability")
    if (rows.any { it.first !in sizeLabels + abvLabels + identityLabels + otherLabels }) return null
    // Repeated product identifiers could denote variants even when the numerical facts agree.
    if (rows.count { it.first in identityLabels } > 1) return null
    if (extendedRecord) {
        // A SKU is a record identifier, never proof of a scanned barcode. Reject lists/prose.
        if (rows.filter { it.first in identityLabels }.any { row ->
                if (row.first in setOf("product code", "code de produit"))
                    !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}").matches(row.second)
                else normalizeGtinForLookup(row.second) == null
            }) return null
        val sizes = rows.filter { it.first in sizeLabels }.map { parseLabeledBottleSize(it.second) }
        if (sizes.any { it == null } || sizes.filterNotNull().map { it.second.stripTrailingZeros() }.distinct().size > 1)
            return null
        // A package suffix in the heading must agree too; do not retain ABV for an ambiguous package.
        if (BottleFactField.QUANTITY in unresolvedBottleSourceFields(text)) return null
    }
    if (requiredBarcode != null) {
        val expectedCode = normalizeGtinForLookup(requiredBarcode) ?: return null
        val identifier = rows.singleOrNull { it.first in identityLabels } ?: return null
        // A title, SKU suffix, or unrelated row cannot supply the scanned identifier.
        if (normalizeGtinForLookup(identifier.second) != expectedCode ||
            scannedBarcodePageDecision(requiredBarcode, text) != ScannedBarcodePageDecision.VERIFIED) return null
    }
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
    collect(BottleFactField.QUANTITY, sizeLabels, ::parseLabeledBottleSize)
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

private fun parseLabeledBottleSize(value: String): Pair<String, BigDecimal>? {
    val match = Regex("""(?i)^(\d{1,4}(?:[.,]\d{1,2})?)\s*(mL|L)$""").matchEntire(value) ?: return null
    val amount = match.groupValues[1].replace(',', '.').toBigDecimal()
    val unit = if (match.groupValues[2].equals("L", true)) "L" else "mL"
    val ml = if (unit == "L") amount * BigDecimal(1000) else amount
    if (ml < BigDecimal.ONE || ml > BigDecimal(20000)) return null
    return "${amount.stripTrailingZeros().toPlainString()} $unit" to ml
}
