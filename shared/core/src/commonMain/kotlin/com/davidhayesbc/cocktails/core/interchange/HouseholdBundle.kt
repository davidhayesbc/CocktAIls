package com.davidhayesbc.cocktails.core.interchange

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.model.ProductFactSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val HOUSEHOLD_BUNDLE_FORMAT = "com.davidhayesbc.cocktails.household"
const val HOUSEHOLD_BUNDLE_VERSION = 2
private const val LEGACY_HOUSEHOLD_BUNDLE_VERSION = 1
const val MAX_HOUSEHOLD_BUNDLE_BYTES = 10 * 1024 * 1024
private const val MAX_BUNDLE_RECORDS = 100_000

data class BundleRecord(val type: String, val id: String, val data: JsonObject)

data class BundleRevision(
    val id: String,
    val recordId: String,
    val recordType: String,
    val authorMemberId: String,
    val deviceId: String,
    val createdAt: Long,
    val schemaVersion: Long,
    val payload: JsonObject,
    val parentIds: List<String>,
)

data class BundleTombstone(
    val recordId: String,
    val recordType: String,
    val revisionId: String,
    val deletedAt: Long,
)

data class HouseholdBundle(
    val householdId: String,
    val exportedAt: Long,
    val records: List<BundleRecord>,
    val revisions: List<BundleRevision>,
    val tombstones: List<BundleTombstone>,
    val formatVersion: Int = HOUSEHOLD_BUNDLE_VERSION,
    val productSources: List<ProductFactSource> = emptyList(),
)

sealed interface BundleDecodeResult {
    data class Valid(val bundle: HouseholdBundle) : BundleDecodeResult
    data class Invalid(val reason: String) : BundleDecodeResult
}

object HouseholdBundleCodec {
    private val json = Json { prettyPrint = false; isLenient = false; ignoreUnknownKeys = false }

    fun encode(bundle: HouseholdBundle): String {
        val root = buildJsonObject {
            put("format", HOUSEHOLD_BUNDLE_FORMAT)
            put("formatVersion", bundle.formatVersion)
            put("householdId", bundle.householdId)
            put("exportedAt", bundle.exportedAt)
            put("records", buildJsonArray {
                bundle.records.forEach { record ->
                    add(buildJsonObject {
                        put("type", record.type)
                        put("id", record.id)
                        put("data", record.data)
                    })
                }
            })
            put("revisions", buildJsonArray {
                bundle.revisions.forEach { revision ->
                    add(buildJsonObject {
                        put("id", revision.id)
                        put("recordId", revision.recordId)
                        put("recordType", revision.recordType)
                        put("authorMemberId", revision.authorMemberId)
                        put("deviceId", revision.deviceId)
                        put("createdAt", revision.createdAt)
                        put("schemaVersion", revision.schemaVersion)
                        put("payload", revision.payload)
                        put("parentIds", JsonArray(revision.parentIds.map(::JsonPrimitive)))
                    })
                }
            })
            put("tombstones", buildJsonArray {
                bundle.tombstones.forEach { tombstone ->
                    add(buildJsonObject {
                        put("recordId", tombstone.recordId)
                        put("recordType", tombstone.recordType)
                        put("revisionId", tombstone.revisionId)
                        put("deletedAt", tombstone.deletedAt)
                    })
                }
            })
            if (bundle.formatVersion >= HOUSEHOLD_BUNDLE_VERSION) {
                put("productSources", buildJsonArray {
                    bundle.productSources.forEach { source ->
                        add(buildJsonObject {
                            put("id", source.id)
                            put("productId", source.productId)
                            put("retrievalProvider", source.retrievalProvider)
                            put("extractionProvider", source.extractionProvider)
                            put("extractionModel", source.extractionModel)
                            put("sourceUrl", source.sourceUrl)
                            put("fieldName", source.fieldName)
                            put("fieldValue", source.fieldValue)
                            put("evidenceQuote", source.evidenceQuote)
                            put("licenseName", source.licenseName?.let(::JsonPrimitive) ?: JsonNull)
                            put("createdAt", source.createdAt)
                        })
                    }
                })
            }
        }
        val encoded = json.encodeToString(JsonObject.serializer(), root)
        require(encoded.encodeToByteArray().size <= MAX_HOUSEHOLD_BUNDLE_BYTES) {
            "Bundle exceeds the ${MAX_HOUSEHOLD_BUNDLE_BYTES / (1024 * 1024)} MiB limit."
        }
        return encoded
    }

    fun decode(contents: String): BundleDecodeResult {
        if (contents.encodeToByteArray().size > MAX_HOUSEHOLD_BUNDLE_BYTES) {
            return BundleDecodeResult.Invalid("Bundle exceeds the ${MAX_HOUSEHOLD_BUNDLE_BYTES / (1024 * 1024)} MiB limit.")
        }
        return try {
            val root = json.parseToJsonElement(contents).jsonObject
            if (root.requiredString("format") != HOUSEHOLD_BUNDLE_FORMAT) {
                return BundleDecodeResult.Invalid("This file is not a CocktAIls household bundle.")
            }
            val versionNumber = root.requiredLong("formatVersion")
            if (versionNumber !in LEGACY_HOUSEHOLD_BUNDLE_VERSION.toLong()..HOUSEHOLD_BUNDLE_VERSION.toLong()) {
                return BundleDecodeResult.Invalid("Bundle version $versionNumber is not supported.")
            }
            val version = versionNumber.toInt()
            val bundle = HouseholdBundle(
                householdId = root.requiredString("householdId"),
                exportedAt = root.requiredLong("exportedAt"),
                records = root.requiredArray("records").map { element ->
                    val row = element.jsonObject
                    BundleRecord(row.requiredString("type"), row.requiredString("id"), row["data"]!!.jsonObject)
                },
                revisions = root.requiredArray("revisions").map { element ->
                    val revision = element.jsonObject
                    BundleRevision(
                        id = revision.requiredString("id"),
                        recordId = revision.requiredString("recordId"),
                        recordType = revision.requiredString("recordType"),
                        authorMemberId = revision.requiredString("authorMemberId"),
                        deviceId = revision.requiredString("deviceId"),
                        createdAt = revision.requiredLong("createdAt"),
                        schemaVersion = revision.requiredLong("schemaVersion"),
                        payload = revision["payload"]!!.jsonObject,
                        parentIds = revision.requiredArray("parentIds").map { it.jsonPrimitive.content },
                    )
                },
                tombstones = root.requiredArray("tombstones").map { element ->
                    val tombstone = element.jsonObject
                    BundleTombstone(
                        recordId = tombstone.requiredString("recordId"),
                        recordType = tombstone.requiredString("recordType"),
                        revisionId = tombstone.requiredString("revisionId"),
                        deletedAt = tombstone.requiredLong("deletedAt"),
                    )
                },
                formatVersion = version,
                productSources = root["productSources"]?.jsonArray?.map { element ->
                    val source = element.jsonObject
                    ProductFactSource(
                        id = source.requiredString("id"),
                        productId = source.requiredString("productId"),
                        retrievalProvider = source.requiredString("retrievalProvider"),
                        extractionProvider = source.requiredString("extractionProvider"),
                        extractionModel = source.requiredString("extractionModel"),
                        sourceUrl = source.requiredString("sourceUrl"),
                        fieldName = source.requiredString("fieldName"),
                        fieldValue = source.requiredString("fieldValue"),
                        evidenceQuote = source.requiredString("evidenceQuote"),
                        licenseName = source.optionalString("licenseName"),
                        createdAt = source.requiredLong("createdAt"),
                    )
                }.orEmpty(),
            )
            BundleValidator.validate(bundle).firstOrNull()?.let(BundleDecodeResult::Invalid)
                ?: BundleDecodeResult.Valid(bundle)
        } catch (exception: Exception) {
            val message = if (exception is SerializationException) "Bundle JSON is malformed." else "Bundle is missing a required field or has an invalid field type."
            BundleDecodeResult.Invalid(message)
        }
    }
}

object BundleValidator {
    private val recordTypes = setOf(
        "HOUSEHOLD", "MEMBER", "LOCATION", "INGREDIENT", "PRODUCT", "INVENTORY_ITEM",
        "COCKTAIL_FAMILY", "RECIPE_VARIANT", "RECIPE_SOURCE", "TASTING_NOTE", "FAVORITE",
        "DRINK_EVENT",
    )

    fun validate(bundle: HouseholdBundle): List<String> {
        val errors = mutableListOf<String>()
        if (bundle.formatVersion !in LEGACY_HOUSEHOLD_BUNDLE_VERSION..HOUSEHOLD_BUNDLE_VERSION) errors += "Bundle version is unsupported."
        if (bundle.formatVersion == LEGACY_HOUSEHOLD_BUNDLE_VERSION && bundle.productSources.isNotEmpty()) errors += "Version 1 household bundles cannot contain product source evidence."
        if (bundle.householdId.isBlank()) errors += "Household ID is required."
        if (bundle.records.size > MAX_BUNDLE_RECORDS) errors += "Bundle has too many records."
        if (bundle.revisions.size > MAX_BUNDLE_RECORDS * 4) errors += "Bundle has too many revisions."
        if (bundle.productSources.size > MAX_BUNDLE_RECORDS) errors += "Bundle has too many product source records."

        val recordsByKey = bundle.records.associateBy { it.type to it.id }
        if (recordsByKey.size != bundle.records.size) errors += "Bundle contains duplicate record IDs."
        if (bundle.records.any { it.type !in recordTypes || it.id.isBlank() }) errors += "Bundle contains an unknown record type or empty ID."
        val households = bundle.records.filter { it.type == "HOUSEHOLD" }
        if (households.size != 1 || households.singleOrNull()?.id != bundle.householdId) errors += "Bundle must contain its one matching household record."

        fun ids(type: String) = bundle.records.filter { it.type == type }.mapTo(mutableSetOf()) { it.id }
        val memberIds = ids("MEMBER")
        val locationIds = ids("LOCATION")
        val ingredientIds = ids("INGREDIENT")
        val productIds = ids("PRODUCT")
        val familyIds = ids("COCKTAIL_FAMILY")
        val variantIds = ids("RECIPE_VARIANT")

        val ingredientNaturalKeys = mutableSetOf<String>()
        val barcodeKeys = mutableSetOf<String>()
        val recipeLineIds = mutableSetOf<String>()
        var totalProductPhotoBytes = 0

        bundle.records.forEach { record ->
            fun requiredString(field: String) {
                if (!record.data.hasString(field) || record.data.optionalString(field).isNullOrBlank()) errors += "${record.type} ${record.id} is missing $field."
            }
            fun requiredLong(field: String) {
                val value = record.data[field] as? JsonPrimitive
                if (value == null || value.isString || value.longOrNull == null) errors += "${record.type} ${record.id} has an invalid $field."
            }
            fun reference(field: String, validIds: Set<String>) {
                val value = record.data.optionalString(field)
                if (value == null || value !in validIds) errors += "${record.type} ${record.id} has an invalid $field reference."
            }
            when (record.type) {
                "HOUSEHOLD" -> { requiredString("name"); requiredLong("createdAt") }
                "MEMBER" -> {
                    requiredString("displayName"); requiredLong("createdAt")
                    if (record.data.optionalString("householdId") != bundle.householdId) errors += "${record.type} ${record.id} belongs to another household."
                }
                "LOCATION" -> {
                    requiredString("name"); requiredLong("createdAt")
                    if (record.data.optionalString("householdId") != bundle.householdId) errors += "${record.type} ${record.id} belongs to another household."
                }
                "INGREDIENT" -> {
                    requiredString("canonicalName"); requiredString("category"); requiredLong("createdAt")
                    val naturalKey = "${record.data.optionalString("canonicalName")?.lowercase()}\u0000${record.data.optionalString("category")?.lowercase()}"
                    if (!ingredientNaturalKeys.add(naturalKey)) errors += "Bundle contains duplicate ingredient names and categories."
                }
                "PRODUCT" -> {
                    reference("ingredientId", ingredientIds); requiredString("productName"); requiredLong("createdAt")
                    record.data.optionalString("barcode")?.let { barcode ->
                        val normalizedBarcode = normalizeGtinForLookup(barcode)
                        if (normalizedBarcode == null) errors += "PRODUCT ${record.id} has an invalid UPC/EAN barcode."
                        else if (!barcodeKeys.add(normalizedBarcode)) errors += "Bundle contains duplicate product barcodes."
                    }
                    val quantity = record.data["bottleQuantity"]
                    if (quantity != null && quantity != JsonNull) {
                        val value = (quantity as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (value.isNullOrBlank() || value.length > 80 || value.any { it < ' ' || it == '\u007f' }) {
                            errors += "PRODUCT ${record.id} has an invalid bottle quantity."
                        }
                    }
                    val abv = record.data["abvPercent"]
                    if (abv != null && abv != JsonNull) {
                        val value = (abv as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val number = value?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull()
                        if (value == null || number == null || !number.isFinite() || number !in 0.0..100.0 || value.length > 16) {
                            errors += "PRODUCT ${record.id} has an invalid alcohol percentage."
                        }
                    }
                    val mimeType = record.data.optionalString("photoMimeType")
                    val base64 = record.data.optionalString("photoBase64")
                    val checksum = record.data.optionalString("photoSha256")
                    if (mimeType != null || base64 != null || checksum != null) {
                        if (mimeType != "image/jpeg" || base64 == null || checksum == null) {
                            errors += "PRODUCT ${record.id} has incomplete photo asset metadata."
                        } else if (base64.length > ((MAX_PRODUCT_PHOTO_BYTES + 2) / 3) * 4) {
                            errors += "PRODUCT ${record.id} photo exceeds the per-image size limit."
                        } else {
                            val photo = decodePhotoBase64(base64)
                            if (photo == null || !isProductPhotoJpeg(photo)) {
                                errors += "PRODUCT ${record.id} photo data is malformed or exceeds the per-image size limit."
                            } else {
                                totalProductPhotoBytes += photo.size
                                if (totalProductPhotoBytes > MAX_BUNDLE_PHOTO_BYTES) errors += "Bundle product photos exceed the 6 MiB total limit."
                                if (!photoSha256(photo).equals(checksum, ignoreCase = true)) errors += "PRODUCT ${record.id} photo checksum does not match its data."
                            }
                        }
                    }
                }
                "INVENTORY_ITEM" -> {
                    reference("locationId", locationIds)
                    reference("ingredientId", ingredientIds)
                    reference("updatedByMemberId", memberIds)
                    requiredLong("updatedAt")
                    if (record.data.optionalString("level") !in setOf("FULL", "MOSTLY_FULL", "HALF", "LOW", "OUT")) errors += "INVENTORY_ITEM ${record.id} has an invalid stock level."
                    record.data.optionalString("productId")?.let { if (it !in productIds) errors += "INVENTORY_ITEM ${record.id} has an invalid productId reference." }
                }
                "COCKTAIL_FAMILY" -> {
                    requiredString("canonicalName"); requiredLong("createdAt")
                    val aliases = record.data["aliases"]?.jsonArray
                    if (aliases == null || aliases.any { it !is JsonPrimitive || !it.jsonPrimitive.isString }) errors += "COCKTAIL_FAMILY ${record.id} has invalid aliases."
                }
                "RECIPE_VARIANT" -> {
                    reference("familyId", familyIds); requiredString("title"); requiredString("sourceKind"); requiredString("style"); requiredLong("createdAt")
                    if (!record.data.hasString("instructions") || !record.data.hasString("preparation")) errors += "RECIPE_VARIANT ${record.id} has invalid method fields."
                    if (record.data.optionalString("style") !in setOf("SHORT", "LONG", "SOUR", "BITTER", "SWEET", "HOT", "COLD", "OTHER")) errors += "RECIPE_VARIANT ${record.id} has an unknown style."
                    val servings = record.data["servings"]?.jsonPrimitive?.doubleOrNull
                    if (servings == null || !servings.isFinite() || servings <= 0.0) errors += "RECIPE_VARIANT ${record.id} has invalid servings."
                    val lines = record.data["ingredients"]?.jsonArray
                    if (lines == null || lines.isEmpty()) errors += "RECIPE_VARIANT ${record.id} has no ingredient lines."
                    lines.orEmpty().forEach { element ->
                        val line = element as? JsonObject
                        if (line == null) {
                            errors += "RECIPE_VARIANT ${record.id} has an invalid ingredient line."
                        } else {
                            val lineId = line.optionalString("id")
                            if (lineId.isNullOrBlank() || !recipeLineIds.add(lineId)) errors += "RECIPE_VARIANT ${record.id} has a duplicate or empty ingredient-line ID."
                            val ingredientId = line.optionalString("ingredientId")
                            if (ingredientId == null || ingredientId !in ingredientIds) errors += "RECIPE_VARIANT ${record.id} references an unknown ingredient."
                            if (line["optional"]?.jsonPrimitive?.booleanOrNull == null || line["sortOrder"]?.jsonPrimitive?.longOrNull == null) errors += "RECIPE_VARIANT ${record.id} has invalid ingredient-line flags or ordering."
                            line["amountValue"]?.let { if (it != JsonNull && (it.jsonPrimitive.doubleOrNull == null || it.jsonPrimitive.doubleOrNull!!.isFinite().not())) errors += "RECIPE_VARIANT ${record.id} has an invalid ingredient amount." }
                            listOf("amountUnit", "preparation").forEach { field ->
                                val value = line[field]
                                if (value != null && value != JsonNull && (value !is JsonPrimitive || !value.isString)) errors += "RECIPE_VARIANT ${record.id} has an invalid ingredient $field."
                            }
                        }
                    }
                }
                "RECIPE_SOURCE" -> {
                    reference("variantId", variantIds); requiredString("sourceName"); requiredLong("importedAt")
                }
                "TASTING_NOTE" -> {
                    reference("variantId", variantIds); reference("memberId", memberIds); requiredLong("createdAt")
                    if (!record.data.hasString("note")) errors += "TASTING_NOTE ${record.id} is missing note text."
                    record.data.optionalString("locationId")?.let { if (it !in locationIds) errors += "TASTING_NOTE ${record.id} has an invalid locationId reference." }
                    val rating = record.data["rating"]
                    if (rating != null && rating != JsonNull && (rating.jsonPrimitive.longOrNull !in 1L..5L)) errors += "TASTING_NOTE ${record.id} has an invalid rating."
                }
                "FAVORITE" -> {
                    reference("memberId", memberIds); reference("familyId", familyIds); requiredLong("createdAt")
                }
                "DRINK_EVENT" -> {
                    reference("variantId", variantIds); reference("memberId", memberIds); requiredLong("madeAt")
                    record.data.optionalString("locationId")?.let { if (it !in locationIds) errors += "DRINK_EVENT ${record.id} has an invalid locationId reference." }
                }
            }
            record.data.optionalString("id")?.let { if (it != record.id) errors += "${record.type} ${record.id} has a mismatched internal ID." }
        }

        val productSourceIds = mutableSetOf<String>()
        val productSourceKeys = mutableSetOf<String>()
        bundle.productSources.forEach { source ->
            if (source.id.isBlank() || !productSourceIds.add(source.id)) errors += "Bundle contains duplicate or empty product source IDs."
            if (source.productId !in productIds) errors += "Product source ${source.id} has an invalid product reference."
            if (source.retrievalProvider.isBlank() || source.retrievalProvider.length > 120 ||
                source.extractionProvider.isBlank() || source.extractionProvider.length > 120 ||
                source.extractionModel.isBlank() || source.extractionModel.length > 128
            ) errors += "Product source ${source.id} has invalid provider metadata."
            if (!isSafeProductSourceUrl(source.sourceUrl)) errors += "Product source ${source.id} must use a public HTTPS source URL."
            if (source.fieldName !in setOf("product_name", "brand", "quantity", "abv_percent") ||
                source.fieldValue.isBlank() || source.fieldValue.length > 120 ||
                source.evidenceQuote.isBlank() || source.evidenceQuote.length > 320 ||
                !source.evidenceQuote.contains(source.fieldValue, ignoreCase = true)
            ) errors += "Product source ${source.id} has invalid field evidence."
            if (source.licenseName != null && source.licenseName.length > 120) errors += "Product source ${source.id} has invalid license metadata."
            if (source.createdAt < 0) errors += "Product source ${source.id} has an invalid timestamp."
            val naturalKey = listOf(source.productId, source.retrievalProvider, source.extractionProvider, source.extractionModel, source.sourceUrl, source.fieldName, source.fieldValue)
                .joinToString("\u0000")
            if (!productSourceKeys.add(naturalKey)) errors += "Bundle contains duplicate product source evidence."
        }

        val revisionsById = bundle.revisions.associateBy { it.id }
        if (revisionsById.size != bundle.revisions.size) errors += "Bundle contains duplicate revision IDs."
        bundle.revisions.forEach { revision ->
            if (revision.id.isBlank() || revision.recordId.isBlank()) errors += "Bundle contains a revision with an empty ID."
            if (revision.schemaVersion != 1L) errors += "Revision ${revision.id} uses unsupported schema version ${revision.schemaVersion}."
            if (revision.authorMemberId !in memberIds) errors += "Revision ${revision.id} has an unknown author."
            if (recordsByKey[revision.recordType to revision.recordId] == null) errors += "Revision ${revision.id} has no matching record."
            revision.parentIds.forEach { parentId ->
                val parent = revisionsById[parentId]
                if (parent == null || parent.recordId != revision.recordId || parent.recordType != revision.recordType) {
                    errors += "Revision ${revision.id} has an invalid parent reference."
                }
            }
        }
        if (!hasAcyclicAncestry(bundle.revisions.associate { it.id to it.parentIds })) errors += "Revision ancestry contains a cycle."

        val tombstonesById = bundle.tombstones.associateBy { it.recordId }
        if (tombstonesById.size != bundle.tombstones.size) errors += "Bundle contains duplicate tombstones."
        bundle.tombstones.forEach { tombstone ->
            if (recordsByKey[tombstone.recordType to tombstone.recordId] == null) errors += "Tombstone ${tombstone.recordId} has no matching record."
            val revision = revisionsById[tombstone.revisionId]
            if (revision == null || revision.recordId != tombstone.recordId || revision.recordType != tombstone.recordType) {
                errors += "Tombstone ${tombstone.recordId} has an invalid revision reference."
            }
        }
        return errors.distinct()
    }

    private fun hasAcyclicAncestry(parents: Map<String, List<String>>): Boolean {
        val visited = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in active) return false
            if (!visited.add(id)) return true
            active += id
            val valid = parents[id].orEmpty().all(::visit)
            active -= id
            return valid
        }
        return parents.keys.all(::visit)
    }
}

private fun isSafeProductSourceUrl(value: String): Boolean {
    if (value.length !in 9..2048 || value.any(Char::isWhitespace) || '@' in value) return false
    val match = Regex("(?i)^https://([a-z0-9.-]+)(?::[0-9]{1,5})?(?:/|$)").find(value) ?: return false
    val host = match.groupValues[1].trimEnd('.').lowercase()
    if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    if (host.startsWith("127.") || host == "0.0.0.0" || host == "::1" || host.startsWith("10.") || host.startsWith("192.168.")) return false
    return true
}

private fun JsonObject.requiredString(field: String): String = this[field]?.jsonPrimitive?.content
    ?.takeIf { it.isNotBlank() } ?: error("Missing string field $field")

private fun JsonObject.requiredLong(field: String): Long = this[field]?.jsonPrimitive?.longOrNull
    ?: error("Missing integer field $field")

private fun JsonObject.requiredArray(field: String): JsonArray = this[field]?.jsonArray
    ?: error("Missing array field $field")

private fun JsonObject.optionalString(field: String): String? = when (val value = this[field]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.content.takeIf { value.isString }
    else -> null
}

private fun JsonObject.hasString(field: String): Boolean = (this[field] as? JsonPrimitive)?.isString == true
