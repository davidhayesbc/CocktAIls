package com.davidhayesbc.cocktails.core.data

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.interchange.photoSha256
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.*

data class ReferenceProduct(
    val recordId: String, val rawUpc: String, val gtin14: String?, val name: String,
    val category: String, val subcategory: String, val sourceClass: String, val country: String,
    val litres: String?, val containers: String?, val abv: String?,
    val lookupEligible: Boolean, val exclusions: List<String>,
) {
    val quantity: String? get() {
        val value = litres?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]{1,3})?")) } ?: return null
        val parts = value.split('.')
        val whole = parts[0].toLongOrNull()?.takeIf { it in 0..20 } ?: return null
        val fraction = parts.getOrNull(1).orEmpty().padEnd(3, '0').toLong()
        return "${whole * 1000 + fraction} mL"
    }
}

data class ReferenceProductPack(
    val schemaVersion: Int, val sourceId: String, val revision: Int, val sourceDate: String,
    val snapshotDate: String, val sourceSha256: String, val sourceUrl: String,
    val licenseTitle: String, val licenseUrl: String, val metadataIsOpen: Boolean,
    val metadataModified: String, val resourceModified: String, val attribution: String,
    val records: List<ReferenceProduct> = emptyList(),
)

data class ReferenceProductMatch(val product: ReferenceProduct, val source: ReferenceProductPack)

/** Dataset-only recovery. Never apply this padding rule to scanner or web evidence. */
fun recoverNumericCatalogueGtin(raw: String): String? {
    if (raw.length !in 1..14 || raw.any { it !in '0'..'9' }) return null
    return raw.padStart(14, '0').takeIf { normalizeGtinForLookup(it) != null }
}

class ReferenceProductStore(private val database: CocktailsDatabase) {
    private val queries get() = database.referenceProductsQueries

    /** Validate completely before one atomic snapshot replacement. Household tables are never written. */
    fun install(text: String): Int {
        require(text.length <= 10_000_000)
        val pack = ReferenceProductCodec.decode(text)
        require(pack.schemaVersion == 1 && pack.sourceId == "bc-liquor-products" && pack.revision > 0)
        require(pack.sourceDate.matches(Regex("\\d{4}-(0[1-9]|1[0-2])")))
        require(pack.sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(pack.sourceUrl.startsWith("https://catalogue.data.gov.bc.ca/"))
        require(pack.licenseTitle == "Open Government Licence - British Columbia" && pack.attribution == "Contains information licensed under the Open Government Licence – British Columbia.")
        require(pack.records.size in 1..20_000 && pack.records.map { it.recordId }.toSet().size == pack.records.size)
        val counts = pack.records.mapNotNull { it.gtin14 }.groupingBy { it }.eachCount()
        pack.records.forEach { row ->
            require(row.recordId.isNotBlank() && row.name.isNotBlank() && row.name.length <= 120)
            require(row.gtin14 == recoverNumericCatalogueGtin(row.rawUpc))
            if (row.lookupEligible) {
                require(row.exclusions.isEmpty() && row.rawUpc.length > 8 && row.gtin14 != null && counts[row.gtin14] == 1)
                require(row.category in setOf("Wine", "Spirits", "Beer", "Refreshment Beverages"))
                require(row.quantity != null && row.containers == "1" && row.litres?.toDoubleOrNull()?.let { it.isFinite() && it in 0.001..20.0 } == true)
                require(row.abv == null || row.abv.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true)
            }
        }
        val hash = photoSha256(text.encodeToByteArray())
        return database.transactionWithResult {
            val old = queries.catalog(pack.sourceId).executeAsOneOrNull()
            if (old?.content_hash == hash) return@transactionWithResult 0
            require(old == null || pack.sourceDate > old.source_date ||
                pack.sourceDate == old.source_date && pack.revision > old.revision)
            queries.deleteProducts(pack.sourceId)
            queries.writeCatalog(pack.sourceId, pack.sourceDate, pack.revision.toLong(), hash,
                ReferenceProductCodec.encode(pack.copy(records = emptyList())))
            pack.records.forEach { row -> queries.writeProduct(pack.sourceId, row.recordId, row.gtin14,
                if (row.lookupEligible) 1 else 0, ReferenceProductCodec.encodeRecord(row).toString()) }
            pack.records.size
        }
    }

    fun lookup(barcode: String): ReferenceProductMatch? {
        val normalized = normalizeGtinForLookup(barcode)?.takeUnless { it.length == 8 } ?: return null
        val rows = queries.matches(normalized.padStart(14, '0')).executeAsList()
        val row = rows.singleOrNull() ?: return null
        return ReferenceProductMatch(ReferenceProductCodec.decodeRecord(Json.parseToJsonElement(row.record_json).jsonObject), ReferenceProductCodec.decode(row.metadata_json))
    }
}

/** Explicit codec matches the shared core's existing JSON approach (no serializer plugin). */
object ReferenceProductCodec {
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optional(key: String) = getValue(key).jsonPrimitive.contentOrNull
    fun decodeRecord(r: JsonObject) = ReferenceProduct(
        r.text("recordId"), r.text("rawUpc"), r.optional("gtin14"), r.text("name"), r.text("category"),
        r.text("subcategory"), r.text("sourceClass"), r.text("country"), r.optional("litres"), r.optional("containers"),
        r.optional("abv"), r.getValue("lookupEligible").jsonPrimitive.boolean,
        r.getValue("exclusions").jsonArray.map { it.jsonPrimitive.content })
    fun decode(text: String): ReferenceProductPack {
        val p = Json.parseToJsonElement(text).jsonObject
        return ReferenceProductPack(p.getValue("schemaVersion").jsonPrimitive.int, p.text("sourceId"),
            p.getValue("revision").jsonPrimitive.int, p.text("sourceDate"), p.text("snapshotDate"), p.text("sourceSha256"),
            p.text("sourceUrl"), p.text("licenseTitle"), p.text("licenseUrl"), p.getValue("metadataIsOpen").jsonPrimitive.boolean,
            p.text("metadataModified"), p.text("resourceModified"), p.text("attribution"),
            p.getValue("records").jsonArray.map { decodeRecord(it.jsonObject) })
    }
    fun encodeRecord(r: ReferenceProduct) = buildJsonObject {
        put("recordId", r.recordId); put("rawUpc", r.rawUpc); put("gtin14", r.gtin14); put("name", r.name)
        put("category", r.category); put("subcategory", r.subcategory); put("sourceClass", r.sourceClass); put("country", r.country)
        put("litres", r.litres); put("containers", r.containers); put("abv", r.abv); put("lookupEligible", r.lookupEligible)
        put("exclusions", JsonArray(r.exclusions.map(::JsonPrimitive)))
    }
    fun encode(p: ReferenceProductPack) = buildJsonObject {
        put("schemaVersion", p.schemaVersion); put("sourceId", p.sourceId); put("revision", p.revision)
        put("sourceDate", p.sourceDate); put("snapshotDate", p.snapshotDate); put("sourceSha256", p.sourceSha256)
        put("sourceUrl", p.sourceUrl); put("licenseTitle", p.licenseTitle); put("licenseUrl", p.licenseUrl)
        put("metadataIsOpen", p.metadataIsOpen); put("metadataModified", p.metadataModified); put("resourceModified", p.resourceModified)
        put("attribution", p.attribution); put("records", JsonArray(p.records.map(::encodeRecord)))
    }.toString()
}
