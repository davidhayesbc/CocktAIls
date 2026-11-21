package com.davidhayesbc.cocktails.core.data

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.interchange.photoSha256
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.*

const val USER_CONTENT_NOTICE = "User-provided reference content for private use. Source attribution is retained; no redistribution rights are asserted for images or descriptions."

data class ReferenceProduct(
    val recordId: String, val rawUpc: String, val gtin14: String?, val name: String,
    val category: String, val subcategory: String, val sourceClass: String, val country: String,
    val litres: String?, val containers: String?, val abv: String?,
    val lookupEligible: Boolean, val exclusions: List<String>,
    val csvAbv: String? = abv, val websiteAbv: String? = null, val websiteFetchedAt: String? = null,
    val referenceDescription: String? = null, val imageSha256: String? = null, val imageMediaType: String? = null,
) {
    val abvConflict: Boolean get() = csvAbv != null && websiteAbv != null && csvAbv.toDoubleOrNull() != websiteAbv.toDoubleOrNull()
    val abvReviewNote: String? get() = if (abvConflict) "ABV needs verification: CSV $csvAbv%; supplied website $websiteAbv% (fetched $websiteFetchedAt). Neither value was selected." else null
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
    val supplementalSourceSha256: String? = null, val supplementalNotice: String? = null,
) {
    val providerLabel: String get() = if (sourceId == "bc-liquor-products") "BC Data Catalogue" else "User reference catalogue"
}

data class ReferenceProductMatch(val product: ReferenceProduct, val source: ReferenceProductPack)

/** Dataset-only recovery. Never apply this padding rule to scanner or web evidence. */
fun recoverNumericCatalogueGtin(raw: String): String? {
    if (raw.length !in 1..14 || raw.any { it !in '0'..'9' }) return null
    return raw.padStart(14, '0').takeIf { normalizeGtinForLookup(it) != null }
}

/** Calendar-valid ISO timestamp with an explicit UTC offset; no locale-dependent parsing. */
internal fun validReferenceTimestamp(value: String): Boolean {
    val parts = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})").matchEntire(value)?.groupValues ?: return false
    val year = parts[1].toInt(); val month = parts[2].toInt(); val day = parts[3].toInt()
    if (year == 0 || month !in 1..12 || parts[4].toInt() > 23 || parts[5].toInt() > 59 || parts[6].toInt() > 59) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    if (day !in 1..days[month - 1]) return false
    val offset = parts[7]
    if (offset == "Z") return true
    val hours = offset.substring(1, 3).toInt(); val minutes = offset.substring(4, 6).toInt()
    return hours <= 18 && minutes <= 59 && (hours < 18 || minutes == 0)
}

class ReferenceProductStore(private val database: CocktailsDatabase) {
    private val queries get() = database.referenceProductsQueries

    /** Validate completely before one atomic snapshot replacement. Household tables are never written. */
    fun validate(text: String): ReferenceProductPack {
        require(text.length <= 10_000_000)
        val pack = ReferenceProductCodec.decode(text)
        require(pack.schemaVersion == 1 && (pack.sourceId == "bc-liquor-products" || pack.sourceId.matches(Regex("user-[a-z0-9-]{1,60}"))) && pack.revision > 0)
        require(pack.sourceDate.matches(Regex("\\d{4}-(0[1-9]|1[0-2])")))
        require(pack.sourceSha256.matches(Regex("[0-9a-f]{64}")))
        if (pack.sourceId == "bc-liquor-products") {
            require(pack.sourceUrl.startsWith("https://catalogue.data.gov.bc.ca/"))
            require(pack.licenseTitle == "Open Government Licence - British Columbia" && pack.attribution == "Contains information licensed under the Open Government Licence – British Columbia.")
        } else {
            require(pack.sourceUrl.startsWith("https://") && pack.sourceUrl.length <= 2048)
            require(pack.licenseTitle.isNotBlank() && pack.licenseTitle.length <= 200 && pack.attribution.isNotBlank() && pack.attribution.length <= 2000)
            require(pack.supplementalNotice == USER_CONTENT_NOTICE)
        }
        require(pack.records.size in 1..20_000 && pack.records.map { it.recordId }.toSet().size == pack.records.size)
        require(pack.supplementalSourceSha256 == null || pack.supplementalSourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(pack.supplementalSourceSha256 == null || pack.supplementalNotice in setOf("Supplemental website ABV is private user-supplied evidence. The CSV licence attribution does not grant rights to supplemental website content.", USER_CONTENT_NOTICE))
        val counts = pack.records.mapNotNull { it.gtin14 }.groupingBy { it }.eachCount()
        pack.records.forEach { row ->
            require(row.recordId.isNotBlank() && row.name.isNotBlank() && row.name.length <= 120)
            require(row.gtin14 == recoverNumericCatalogueGtin(row.rawUpc))
            require(row.referenceDescription == null || row.referenceDescription.length <= 8000)
            require((row.imageSha256 == null) == (row.imageMediaType == null))
            require(row.imageSha256 == null || row.imageSha256.matches(Regex("[0-9a-f]{64}")) && row.imageMediaType in setOf("image/webp", "image/jpeg", "image/png"))
            require(row.referenceDescription == null && row.imageSha256 == null || pack.supplementalNotice == USER_CONTENT_NOTICE && pack.supplementalSourceSha256 != null)
            for (value in listOf(row.csvAbv, row.websiteAbv)) require(value == null ||
                value.matches(Regex("[0-9]+(?:\\.[0-9]{1,6})?")) && value.toDoubleOrNull()?.let { it in 0.0..100.0 } == true)
            require(row.websiteAbv == null || pack.supplementalSourceSha256 != null && row.websiteFetchedAt?.let(::validReferenceTimestamp) == true)
            require(row.abv == if (row.abvConflict) null else row.csvAbv)
            if (row.lookupEligible) {
                require(row.exclusions.isEmpty() && row.rawUpc.length > 8 && row.gtin14 != null && counts[row.gtin14] == 1)
                require(row.category in setOf("Wine", "Spirits", "Beer", "Refreshment Beverages"))
                require(row.quantity != null && row.containers == "1" && row.litres?.toDoubleOrNull()?.let { it.isFinite() && it in 0.001..20.0 } == true)
                require(row.abv == null || row.abv.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true)
            }
        }
        return pack
    }

    fun install(text: String): Int {
        val pack = validate(text)
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
        r.getValue("exclusions").jsonArray.map { it.jsonPrimitive.content },
        if (r.containsKey("csvAbv")) r["csvAbv"]?.jsonPrimitive?.contentOrNull else r.optional("abv"),
        r["websiteAbv"]?.jsonPrimitive?.contentOrNull, r["websiteFetchedAt"]?.jsonPrimitive?.contentOrNull,
        r["referenceDescription"]?.jsonPrimitive?.contentOrNull, r["imageSha256"]?.jsonPrimitive?.contentOrNull, r["imageMediaType"]?.jsonPrimitive?.contentOrNull)
    fun decode(text: String): ReferenceProductPack {
        val p = Json.parseToJsonElement(text).jsonObject
        return ReferenceProductPack(p.getValue("schemaVersion").jsonPrimitive.int, p.text("sourceId"),
            p.getValue("revision").jsonPrimitive.int, p.text("sourceDate"), p.text("snapshotDate"), p.text("sourceSha256"),
            p.text("sourceUrl"), p.text("licenseTitle"), p.text("licenseUrl"), p.getValue("metadataIsOpen").jsonPrimitive.boolean,
            p.text("metadataModified"), p.text("resourceModified"), p.text("attribution"),
            p.getValue("records").jsonArray.map { decodeRecord(it.jsonObject) }, p["supplementalSourceSha256"]?.jsonPrimitive?.contentOrNull, p["supplementalNotice"]?.jsonPrimitive?.contentOrNull)
    }
    fun encodeRecord(r: ReferenceProduct) = buildJsonObject {
        put("recordId", r.recordId); put("rawUpc", r.rawUpc); put("gtin14", r.gtin14); put("name", r.name)
        put("category", r.category); put("subcategory", r.subcategory); put("sourceClass", r.sourceClass); put("country", r.country)
        put("litres", r.litres); put("containers", r.containers); put("abv", r.abv); put("lookupEligible", r.lookupEligible)
        put("exclusions", JsonArray(r.exclusions.map(::JsonPrimitive)))
        put("referenceDescription", r.referenceDescription); put("imageSha256", r.imageSha256); put("imageMediaType", r.imageMediaType);
        put("csvAbv", r.csvAbv); put("websiteAbv", r.websiteAbv); put("websiteFetchedAt", r.websiteFetchedAt)
    }
    fun encode(p: ReferenceProductPack) = buildJsonObject {
        put("schemaVersion", p.schemaVersion); put("sourceId", p.sourceId); put("revision", p.revision)
        put("sourceDate", p.sourceDate); put("snapshotDate", p.snapshotDate); put("sourceSha256", p.sourceSha256)
        put("sourceUrl", p.sourceUrl); put("licenseTitle", p.licenseTitle); put("licenseUrl", p.licenseUrl)
        put("metadataIsOpen", p.metadataIsOpen); put("metadataModified", p.metadataModified); put("resourceModified", p.resourceModified)
        put("supplementalSourceSha256", p.supplementalSourceSha256); put("supplementalNotice", p.supplementalNotice); put("attribution", p.attribution); put("records", JsonArray(p.records.map(::encodeRecord)))
    }.toString()
}
