package com.davidhayesbc.cocktails.core.data

import com.davidhayesbc.cocktails.core.interchange.BundleDecodeResult
import com.davidhayesbc.cocktails.core.interchange.BundleRecord
import com.davidhayesbc.cocktails.core.interchange.BundleRevision
import com.davidhayesbc.cocktails.core.interchange.BundleTombstone
import com.davidhayesbc.cocktails.core.interchange.BundleValidator
import com.davidhayesbc.cocktails.core.interchange.HOUSEHOLD_BUNDLE_VERSION
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundle
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundleCodec
import com.davidhayesbc.cocktails.core.interchange.MAX_HOUSEHOLD_BUNDLE_BYTES
import com.davidhayesbc.cocktails.core.model.newUuidV7
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class BundleChangeKind { ADDITION, UPDATE, UNCHANGED, CONFLICT }

data class BundleRecordChange(
    val type: String,
    val id: String,
    val kind: BundleChangeKind,
    val incoming: BundleRecord,
    val local: BundleRecord?,
    val localHeadIds: List<String>,
    val incomingHeadIds: List<String>,
    val localDeleted: Boolean,
    val incomingDeleted: Boolean,
) {
    val key: String get() = "$type\u0000$id"
}

data class HouseholdImportPreview(
    val householdId: String,
    val changes: List<BundleRecordChange>,
    val localFingerprint: String,
    val replacesEmptyStarter: Boolean,
) {
    val conflicts: List<BundleRecordChange> get() = changes.filter { it.kind == BundleChangeKind.CONFLICT }
    val additions: Int get() = changes.count { it.kind == BundleChangeKind.ADDITION }
    val updates: Int get() = changes.count { it.kind == BundleChangeKind.UPDATE }
    val unchanged: Int get() = changes.count { it.kind == BundleChangeKind.UNCHANGED }
}

sealed interface ImportPreviewResult {
    data class Valid(val preview: HouseholdImportPreview) : ImportPreviewResult
    data class Invalid(val reason: String) : ImportPreviewResult
}

enum class ConflictChoice { KEEP_LOCAL, USE_INCOMING, MERGE_FIELDS }

data class ConflictResolution(
    val choice: ConflictChoice,
    val mergedData: JsonObject? = null,
    val mergedDeleted: Boolean? = null,
)

sealed interface ImportApplyResult {
    data class Applied(val additions: Int, val updates: Int, val resolvedConflicts: Int) : ImportApplyResult
    data class Invalid(val reason: String) : ImportApplyResult
}

/** Versioned, validated, additive household exchange. Local preferences never enter this service. */
class HouseholdBundleStore internal constructor(
    private val database: CocktailsDatabase,
    private val beforeApplyTransaction: (() -> Unit)? = null,
) {
    private val queries = database.householdQueries

    fun export(exportedAt: Long): String = HouseholdBundleCodec.encode(snapshot(exportedAt))

    fun preview(bundle: HouseholdBundle): ImportPreviewResult {
        BundleValidator.validate(bundle).firstOrNull()?.let { return ImportPreviewResult.Invalid(it) }
        val local = localSnapshot()
        val emptyStarter = local != null && bundle.householdId != local.householdId && isEmptyStarter(local)
        if (local != null && bundle.householdId != local.householdId && !emptyStarter) {
            return ImportPreviewResult.Invalid("This file belongs to a different household. Export a backup before switching households.")
        }
        val localRecords = local?.records.orEmpty().associateBy { it.type to it.id }
        val localRevisions = local?.revisions.orEmpty().associateBy { it.id }
        val incomingRevisions = bundle.revisions.associateBy { it.id }
        val duplicateRevisionIds = localRevisions.keys.intersect(incomingRevisions.keys)
        if (duplicateRevisionIds.any { localRevisions[it] != incomingRevisions[it] }) {
            return ImportPreviewResult.Invalid("The bundle reuses a revision ID with different content.")
        }
        val allRevisions = localRevisions + incomingRevisions
        val changes = bundle.records.map { incoming ->
            val localRecord = if (emptyStarter) null else localRecords[incoming.type to incoming.id]
            val localHeads = if (localRecord == null) emptyList() else localHeads(local, incoming.type, incoming.id)
            val incomingHeads = incomingHeads(bundle, incoming.type, incoming.id)
            val localDeleted = if (localRecord == null) false else local?.tombstones?.any { it.recordId == incoming.id && it.recordType == incoming.type } == true
            val incomingDeleted = bundle.tombstones.any { it.recordId == incoming.id && it.recordType == incoming.type }
            val kind = classify(
                localRecord = localRecord,
                incoming = incoming,
                localHeads = localHeads,
                incomingHeads = incomingHeads,
                local = local,
                allRevisions = allRevisions,
                localDeleted = localDeleted,
                incomingDeleted = incomingDeleted,
            )
            BundleRecordChange(incoming.type, incoming.id, kind, incoming, localRecord, localHeads, incomingHeads, localDeleted, incomingDeleted)
        }
        return ImportPreviewResult.Valid(
            HouseholdImportPreview(
                householdId = bundle.householdId,
                changes = changes,
                localFingerprint = local?.let(::fingerprint) ?: EMPTY_FINGERPRINT,
                replacesEmptyStarter = emptyStarter,
            ),
        )
    }

    fun apply(
        bundle: HouseholdBundle,
        preview: HouseholdImportPreview,
        resolutions: Map<String, ConflictResolution>,
        nowMillis: Long,
    ): ImportApplyResult {
        BundleValidator.validate(bundle).firstOrNull()?.let { return ImportApplyResult.Invalid(it) }
        val refreshed = preview(bundle)
        if (refreshed !is ImportPreviewResult.Valid || refreshed.preview.localFingerprint != preview.localFingerprint ||
            refreshed.preview.changes.map { it.key to it.kind } != preview.changes.map { it.key to it.kind }
        ) return ImportApplyResult.Invalid("Household data changed after preview. Preview the file again before importing.")
        val unresolved = preview.conflicts.filter { resolutions[it.key] == null }
        if (unresolved.isNotEmpty()) return ImportApplyResult.Invalid("Choose a resolution for every conflict before importing.")

        val selectedRecords = mutableMapOf<String, BundleRecord>()
        val selectedDeleted = mutableMapOf<String, Boolean>()
        val mergeParents = mutableMapOf<String, List<String>>()
        for (change in preview.changes) {
            when (change.kind) {
                BundleChangeKind.ADDITION, BundleChangeKind.UPDATE -> {
                    selectedRecords[change.key] = change.incoming
                    selectedDeleted[change.key] = change.incomingDeleted
                }
                BundleChangeKind.UNCHANGED -> {
                    selectedRecords[change.key] = change.local ?: change.incoming
                    selectedDeleted[change.key] = if (change.local == null) change.incomingDeleted else change.localDeleted
                }
                BundleChangeKind.CONFLICT -> {
                    val resolution = resolutions.getValue(change.key)
                    val record = when (resolution.choice) {
                        ConflictChoice.KEEP_LOCAL -> change.local!!
                        ConflictChoice.USE_INCOMING -> change.incoming
                        ConflictChoice.MERGE_FIELDS -> {
                            val merged = resolution.mergedData
                                ?: return ImportApplyResult.Invalid("A merged record is missing its selected field values.")
                            change.incoming.copy(data = merged)
                        }
                    }
                    val deleted = when (resolution.choice) {
                        ConflictChoice.KEEP_LOCAL -> change.localDeleted
                        ConflictChoice.USE_INCOMING -> change.incomingDeleted
                        ConflictChoice.MERGE_FIELDS -> resolution.mergedDeleted
                            ?: return ImportApplyResult.Invalid("Choose whether the merged record is archived or active.")
                    }
                    selectedRecords[change.key] = record
                    selectedDeleted[change.key] = deleted
                    mergeParents[change.key] = (change.localHeadIds + change.incomingHeadIds).distinct().sorted()
                }
            }
        }
        if (!hasNoNaturalKeyCollisions(selectedRecords.values, preview)) {
            return ImportApplyResult.Invalid("The import contains duplicate barcodes, ingredient names, or pantry slots. Resolve those records before importing.")
        }

        val authorMemberId = selectedPreferredMember(bundle) ?: return ImportApplyResult.Invalid("The household bundle has no member profile to author resolved revisions.")
        val deviceId = queries.selectLocalPreference("device_id").executeAsOneOrNull() ?: newUuidV7(nowMillis + 1)
        val mergedRevisions = mergeParents.map { (key, parents) ->
            val record = selectedRecords.getValue(key)
            BundleRevision(
                id = newUuidV7(nowMillis + mergedRevisionsOffset(key)),
                recordId = record.id,
                recordType = record.type,
                authorMemberId = authorMemberId,
                deviceId = deviceId,
                createdAt = nowMillis,
                schemaVersion = HOUSEHOLD_BUNDLE_VERSION.toLong(),
                payload = record.data,
                parentIds = parents,
            )
        }
        val revisionsToApply = bundle.revisions + mergedRevisions
        val mergedRevisionByKey = mergeParents.keys.zip(mergedRevisions).toMap()
        val currentRecords = selectedRecords.values.associateBy { it.type to it.id }
        val localForValidation = if (preview.replacesEmptyStarter) null else localSnapshot()
        val candidateRecords = (localForValidation?.records.orEmpty().associateBy { it.type to it.id } + currentRecords)
        val candidateRevisions = (localForValidation?.revisions.orEmpty() + revisionsToApply).distinctBy { it.id }
        val candidateTombstones = (localForValidation?.tombstones.orEmpty() + bundle.tombstones)
            .associateBy { it.recordId }.toMutableMap()
        currentRecords.values.forEach { record ->
            val key = "${record.type}\u0000${record.id}"
            if (selectedDeleted[key] == true) {
                val mergeRevision = mergedRevisionByKey[key]
                val incomingTombstone = bundle.tombstones.firstOrNull { it.recordId == record.id && it.recordType == record.type }
                val localTombstone = localForValidation?.tombstones?.firstOrNull { it.recordId == record.id && it.recordType == record.type }
                val selectedTombstone = when {
                    mergeRevision != null -> BundleTombstone(record.id, record.type, mergeRevision.id, nowMillis)
                    record == preview.changes.firstOrNull { it.key == key }?.local -> localTombstone
                    else -> incomingTombstone
                }
                if (selectedTombstone != null) candidateTombstones[record.id] = selectedTombstone
            } else candidateTombstones.remove(record.id)
        }
        val selectedBundle = bundle.copy(
            records = candidateRecords.values.toList(),
            revisions = candidateRevisions,
            tombstones = candidateTombstones.values.toList(),
        )
        BundleValidator.validate(selectedBundle).firstOrNull()?.let { return ImportApplyResult.Invalid("The selected merge is invalid: $it") }
        try {
            HouseholdBundleCodec.encode(selectedBundle)
        } catch (exception: IllegalArgumentException) {
            if (exception.message?.contains("exceeds", ignoreCase = true) == true) {
                return ImportApplyResult.Invalid("The combined household backup would exceed 10 MiB. Reduce the imported data before applying it.")
            }
            return ImportApplyResult.Invalid("The selected household data could not be encoded: ${exception.message ?: "invalid bundle"}.")
        }

        return try {
            // Test seam for a local writer racing the preview/commit boundary. Production callers omit it.
            beforeApplyTransaction?.invoke()
            var staleAtTransactionStart = false
            database.transaction {
                val transactionSnapshot = localSnapshot()
                val transactionFingerprint = transactionSnapshot?.let(::fingerprint) ?: EMPTY_FINGERPRINT
                if (transactionFingerprint != preview.localFingerprint) {
                    staleAtTransactionStart = true
                    return@transaction
                }
                if (preview.replacesEmptyStarter) clearEmptyStarterHousehold()
                val chosen = currentRecords.values
                chosen.filter { it.type == "HOUSEHOLD" }.forEach(::writeHousehold)
                chosen.filter { it.type == "MEMBER" }.forEach(::writeMember)
                chosen.filter { it.type == "LOCATION" }.forEach(::writeLocation)
                chosen.filter { it.type == "INGREDIENT" }.forEach(::writeIngredient)
                revisionsToApply.distinctBy { it.id }.forEach(::writeRevision)
                revisionsToApply.distinctBy { it.id }.forEach { revision ->
                    revision.parentIds.forEach { parent -> queries.updateRecordRevisionParentIgnore(revision.id, parent) }
                }
                chosen.filter { it.type == "PRODUCT" }.forEach(::writeProduct)
                chosen.filter { it.type == "INVENTORY_ITEM" }.forEach(::writeInventory)
                chosen.filter { it.type == "COCKTAIL_FAMILY" }.forEach(::writeFamily)
                chosen.filter { it.type == "RECIPE_VARIANT" }.forEach { record ->
                    val mergeRevision = mergedRevisionByKey["${record.type}\u0000${record.id}"]
                    writeRecipeVariant(record.withRevision(mergeRevision?.id))
                }
                chosen.filter { it.type == "RECIPE_SOURCE" }.forEach(::writeRecipeSource)
                chosen.filter { it.type == "TASTING_NOTE" }.forEach { record ->
                    val mergeRevision = mergedRevisionByKey["${record.type}\u0000${record.id}"]
                    writeTastingNote(record.withRevision(mergeRevision?.id))
                }
                chosen.filter { it.type == "FAVORITE" }.forEach(::writeFavorite)
                chosen.filter { it.type == "DRINK_EVENT" }.forEach(::writeDrinkEvent)
                selectedRecords.values.filter { it.type == "COCKTAIL_FAMILY" }.forEach { record ->
                    queries.deleteAliasesForFamily(record.id)
                    writeAliases(record)
                }

                selectedRecords.values.forEach { record ->
                    if (selectedDeleted["${record.type}\u0000${record.id}"] == true) {
                        val selectedTombstone = candidateTombstones[record.id]
                            ?.takeIf { it.recordType == record.type }
                            ?: error("Deleted record ${record.id} is missing its selected tombstone.")
                        queries.deleteTombstone(record.id)
                        queries.insertTombstoneBundle(record.id, record.type, selectedTombstone.revisionId, selectedTombstone.deletedAt)
                    } else if (record.type == "RECIPE_VARIANT" || record.type == "COCKTAIL_FAMILY" || record.type == "FAVORITE") {
                        queries.deleteTombstone(record.id)
                    }
                }
                repairLocalSelections(bundle)
            }
            if (staleAtTransactionStart) {
                return ImportApplyResult.Invalid("Household data changed after preview. Preview the file again before importing.")
            }
            ImportApplyResult.Applied(
                additions = preview.additions,
                updates = preview.updates,
                resolvedConflicts = preview.conflicts.size,
            )
        } catch (exception: Exception) {
            ImportApplyResult.Invalid("Import was rolled back: ${exception.message ?: "database validation failed"}.")
        }
    }

    private fun snapshot(exportedAt: Long): HouseholdBundle {
        val householdRows = queries.selectAllHouseholdsForBundle().executeAsList()
        require(householdRows.size == 1) { "Export requires exactly one household." }
        val householdId = householdRows.single().id
        val aliasesByFamily = queries.selectAllAliasesForBundle().executeAsList().groupBy { it.family_id }
        val linesByVariant = queries.selectAllRecipeIngredientsForBundle().executeAsList().groupBy { it.variant_id }
        val records = mutableListOf<BundleRecord>()
        householdRows.forEach { records += BundleRecord("HOUSEHOLD", it.id, obj("id" to str(it.id), "name" to str(it.name), "createdAt" to num(it.created_at))) }
        queries.selectAllMembersForBundle().executeAsList().forEach {
            records += BundleRecord("MEMBER", it.id, obj("id" to str(it.id), "householdId" to str(it.household_id), "displayName" to str(it.display_name), "createdAt" to num(it.created_at)))
        }
        queries.selectAllLocationsForBundle().executeAsList().forEach {
            records += BundleRecord("LOCATION", it.id, obj("id" to str(it.id), "householdId" to str(it.household_id), "name" to str(it.name), "createdAt" to num(it.created_at)))
        }
        queries.selectAllIngredientsForBundle().executeAsList().forEach {
            records += BundleRecord("INGREDIENT", it.id, obj("id" to str(it.id), "canonicalName" to str(it.canonical_name), "category" to str(it.category), "createdAt" to num(it.created_at)))
        }
        queries.selectAllProductsForBundle().executeAsList().forEach {
            records += BundleRecord("PRODUCT", it.id, obj("id" to str(it.id), "ingredientId" to str(it.ingredient_id), "brand" to nullableString(it.brand), "productName" to str(it.product_name), "barcode" to nullableString(it.barcode), "createdAt" to num(it.created_at)))
        }
        queries.selectAllInventoryForBundle().executeAsList().forEach {
            records += BundleRecord("INVENTORY_ITEM", it.id, obj("id" to str(it.id), "locationId" to str(it.location_id), "ingredientId" to str(it.ingredient_id), "level" to str(it.level), "updatedAt" to num(it.updated_at), "updatedByMemberId" to str(it.updated_by_member_id)))
        }
        queries.selectAllFamiliesForBundle().executeAsList().forEach { row ->
            records += BundleRecord("COCKTAIL_FAMILY", row.id, obj(
                "id" to str(row.id), "canonicalName" to str(row.canonical_name), "createdAt" to num(row.created_at),
                "aliases" to buildJsonArray { aliasesByFamily[row.id].orEmpty().forEach { add(str(it.alias)) } },
            ))
        }
        queries.selectAllVariantsForBundle().executeAsList().forEach { row ->
            val lines = linesByVariant[row.id].orEmpty().sortedBy { it.sort_order }
            records += BundleRecord("RECIPE_VARIANT", row.id, obj(
                "id" to str(row.id), "familyId" to str(row.family_id), "title" to str(row.title), "sourceKind" to str(row.source_kind),
                "instructions" to str(row.instructions), "preparation" to str(row.preparation), "servings" to decimal(row.servings),
                "style" to str(row.style), "revisionId" to nullableString(row.revision_id), "createdAt" to num(row.created_at),
                "ingredients" to buildJsonArray {
                    lines.forEach { line -> add(obj(
                        "id" to str(line.id), "ingredientId" to str(line.ingredient_id), "amountValue" to nullableDecimal(line.amount_value),
                        "amountUnit" to nullableString(line.amount_unit), "preparation" to nullableString(line.preparation),
                        "optional" to JsonPrimitive(line.optional != 0L), "sortOrder" to num(line.sort_order),
                    )) }
                },
            ))
        }
        queries.selectAllSourcesForBundle().executeAsList().forEach {
            records += BundleRecord("RECIPE_SOURCE", it.id, obj(
                "id" to str(it.id), "variantId" to str(it.variant_id), "sourceName" to str(it.source_name),
                "sourceRecordId" to nullableString(it.source_record_id), "sourceUrl" to nullableString(it.source_url),
                "licenseName" to nullableString(it.license_name), "attribution" to nullableString(it.attribution),
                "modifications" to nullableString(it.modifications), "importedAt" to num(it.imported_at),
            ))
        }
        queries.selectAllNotesForBundle().executeAsList().forEach {
            records += BundleRecord("TASTING_NOTE", it.id, obj(
                "id" to str(it.id), "variantId" to str(it.variant_id), "memberId" to str(it.member_id),
                "locationId" to nullableString(it.location_id), "rating" to nullableNumber(it.rating), "note" to str(it.note),
                "createdAt" to num(it.created_at), "revisionId" to nullableString(it.revision_id),
            ))
        }
        queries.selectAllFavoritesForBundle().executeAsList().forEach {
            records += BundleRecord("FAVORITE", compositeFavoriteId(it.member_id, it.family_id), obj(
                "memberId" to str(it.member_id), "familyId" to str(it.family_id), "createdAt" to num(it.created_at),
            ))
        }
        queries.selectAllDrinkEventsForBundle().executeAsList().forEach {
            records += BundleRecord("DRINK_EVENT", it.id, obj(
                "id" to str(it.id), "variantId" to str(it.variant_id), "memberId" to str(it.member_id),
                "locationId" to nullableString(it.location_id), "madeAt" to num(it.made_at), "revisionId" to nullableString(it.revision_id),
            ))
        }
        val parentsByRevision = queries.selectAllRevisionParentsForBundle().executeAsList().groupBy { it.revision_id }
        val revisions = queries.selectAllRevisionsForBundle().executeAsList().map { row ->
            val payload = Json.parseToJsonElement(row.payload).jsonObject
            BundleRevision(
                id = row.id, recordId = row.record_id, recordType = row.record_type, authorMemberId = row.author_member_id,
                deviceId = row.device_id, createdAt = row.created_at, schemaVersion = row.schema_version, payload = payload,
                parentIds = parentsByRevision[row.id].orEmpty().map { it.parent_revision_id }.sorted(),
            )
        }
        val tombstones = queries.selectAllTombstonesForBundle().executeAsList().map {
            BundleTombstone(it.record_id, it.record_type, it.revision_id, it.deleted_at)
        }
        val bundle = HouseholdBundle(householdId, exportedAt, records.sortedWith(compareBy(BundleRecord::type, BundleRecord::id)), revisions, tombstones)
        BundleValidator.validate(bundle).firstOrNull()?.let { error("Local household data cannot be exported: $it") }
        val encoded = HouseholdBundleCodec.encode(bundle)
        require(encoded.encodeToByteArray().size <= MAX_HOUSEHOLD_BUNDLE_BYTES)
        return bundle
    }

    private fun localSnapshot(): HouseholdBundle? =
        if (queries.selectAllHouseholdsForBundle().executeAsList().isEmpty()) null else snapshot(exportedAt = 0)

    private fun classify(
        localRecord: BundleRecord?,
        incoming: BundleRecord,
        localHeads: List<String>,
        incomingHeads: List<String>,
        local: HouseholdBundle?,
        allRevisions: Map<String, BundleRevision>,
        localDeleted: Boolean,
        incomingDeleted: Boolean,
    ): BundleChangeKind {
        if (localRecord == null) return BundleChangeKind.ADDITION
        val localRevisionIds = local?.revisions.orEmpty().mapTo(mutableSetOf()) { it.id }
        if (incomingHeads.isNotEmpty() && incomingHeads.all { it in localRevisionIds }) {
            val incomingIsAncestor = incomingHeads.all { incomingHead ->
                localHeads.any { localHead -> isAncestor(incomingHead, localHead, allRevisions) }
            }
            if (incomingIsAncestor) return BundleChangeKind.UNCHANGED
            return if (localRecord.data == incoming.data && localDeleted == incomingDeleted) BundleChangeKind.UNCHANGED else BundleChangeKind.CONFLICT
        }
        if (localHeads.isEmpty() || incomingHeads.isEmpty()) {
            return if (localRecord.data == incoming.data && localDeleted == incomingDeleted) BundleChangeKind.UNCHANGED else BundleChangeKind.CONFLICT
        }
        val localIsAncestor = localHeads.all { localHead -> incomingHeads.any { incomingHead -> isAncestor(localHead, incomingHead, allRevisions) } }
        if (localIsAncestor) {
            return if (localRecord.data == incoming.data && localDeleted == incomingDeleted) BundleChangeKind.UNCHANGED else BundleChangeKind.UPDATE
        }
        val incomingIsAncestor = incomingHeads.all { incomingHead -> localHeads.any { localHead -> isAncestor(incomingHead, localHead, allRevisions) } }
        if (incomingIsAncestor) return BundleChangeKind.UNCHANGED
        return BundleChangeKind.CONFLICT
    }

    private fun isAncestor(ancestor: String, descendant: String, revisions: Map<String, BundleRevision>): Boolean {
        if (ancestor == descendant) return true
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (!visited.add(id)) return false
            return revisions[id]?.parentIds.orEmpty().any { it == ancestor || visit(it) }
        }
        return visit(descendant)
    }

    private fun localHeads(bundle: HouseholdBundle?, type: String, recordId: String): List<String> =
        heads(bundle?.revisions.orEmpty(), type, recordId)

    private fun incomingHeads(bundle: HouseholdBundle, type: String, recordId: String): List<String> =
        heads(bundle.revisions, type, recordId)

    private fun heads(revisions: List<BundleRevision>, type: String, recordId: String): List<String> {
        val matching = revisions.filter { it.recordType == type && it.recordId == recordId }
        val parentIds = matching.flatMapTo(mutableSetOf()) { it.parentIds }
        return matching.map { it.id }.filterNot { it in parentIds }.sorted()
    }

    private fun isEmptyStarter(bundle: HouseholdBundle): Boolean =
        bundle.records.count { it.type == "HOUSEHOLD" } == 1 &&
            bundle.records.count { it.type == "MEMBER" } == 1 &&
            bundle.records.count { it.type == "LOCATION" } == 1 &&
            bundle.records.all { it.type in setOf("HOUSEHOLD", "MEMBER", "LOCATION") } &&
            bundle.tombstones.isEmpty()

    private fun clearEmptyStarterHousehold() {
        queries.deleteAllRevisionParents()
        queries.deleteAllRecordRevisions()
        queries.deleteSelectedHomeAndMemberPreferences()
        queries.deleteAllLocations()
        queries.deleteAllMembers()
        queries.deleteAllHouseholds()
    }

    private fun selectedPreferredMember(bundle: HouseholdBundle): String? {
        val preferred = queries.selectPreferredMember().executeAsOneOrNull()
        return preferred?.takeIf { id -> bundle.records.any { it.type == "MEMBER" && it.id == id } }
            ?: bundle.records.firstOrNull { it.type == "MEMBER" }?.id
    }

    private fun repairLocalSelections(bundle: HouseholdBundle) {
        val preferredMember = queries.selectPreferredMember().executeAsOneOrNull()
            ?.takeIf { id -> bundle.records.any { it.type == "MEMBER" && it.id == id } }
            ?: bundle.records.firstOrNull { it.type == "MEMBER" }?.id
        val preferredHome = queries.selectPreferredHome().executeAsOneOrNull()
            ?.takeIf { id -> bundle.records.any { it.type == "LOCATION" && it.id == id } }
            ?: bundle.records.firstOrNull { it.type == "LOCATION" }?.id
        preferredMember?.let(queries::setPreferredMember)
        preferredHome?.let(queries::setPreferredHome)
    }

    private fun hasNoNaturalKeyCollisions(records: Collection<BundleRecord>, preview: HouseholdImportPreview): Boolean {
        val local = if (preview.replacesEmptyStarter) emptyList() else localSnapshot()?.records.orEmpty()
        val combined = (local.associateBy { it.type to it.id } + records.associateBy { it.type to it.id }).values
        val ingredientKeys = combined.filter { it.type == "INGREDIENT" }
            .map { "${it.data.string("canonicalName").lowercase()}\u0000${it.data.string("category").lowercase()}" }
        if (ingredientKeys.size != ingredientKeys.toSet().size) return false
        val productBarcodes = combined.filter { it.type == "PRODUCT" }.mapNotNull { it.data.nullableString("barcode") }
        if (productBarcodes.size != productBarcodes.toSet().size) return false
        val inventoryKeys = combined.filter { it.type == "INVENTORY_ITEM" }
            .map { "${it.data.string("locationId")}\u0000${it.data.string("ingredientId")}" }
        if (inventoryKeys.size != inventoryKeys.toSet().size) return false
        return true
    }

    private fun writeHousehold(record: BundleRecord) {
        val data = record.data
        queries.insertHouseholdIgnore(record.id, data.string("name"), data.long("createdAt"))
        queries.updateHousehold(data.string("name"), record.id)
    }

    private fun writeMember(record: BundleRecord) {
        val data = record.data
        queries.insertMemberIgnore(record.id, data.string("householdId"), data.string("displayName"), data.long("createdAt"))
        queries.updateMember(data.string("householdId"), data.string("displayName"), record.id)
    }

    private fun writeLocation(record: BundleRecord) {
        val data = record.data
        queries.insertLocationIgnore(record.id, data.string("householdId"), data.string("name"), data.long("createdAt"))
        queries.updateLocation(data.string("householdId"), data.string("name"), record.id)
    }

    private fun writeIngredient(record: BundleRecord) {
        val data = record.data
        queries.insertIngredientBundleIgnore(record.id, data.string("canonicalName"), data.string("category"), data.long("createdAt"))
        queries.updateIngredientBundle(data.string("canonicalName"), data.string("category"), record.id)
    }

    private fun writeProduct(record: BundleRecord) {
        val data = record.data
        val ingredientId = data.string("ingredientId")
        val brand = data.nullableString("brand")
        val name = data.string("productName")
        val barcode = data.nullableString("barcode")
        val createdAt = data.long("createdAt")
        queries.insertProductIgnore(record.id, ingredientId, brand, name, barcode, createdAt)
        queries.updateProduct(ingredientId, brand, name, barcode, record.id)
    }

    private fun writeInventory(record: BundleRecord) {
        val data = record.data
        val locationId = data.string("locationId")
        val ingredientId = data.string("ingredientId")
        val level = data.string("level")
        val updatedAt = data.long("updatedAt")
        val memberId = data.string("updatedByMemberId")
        queries.insertInventoryBundleIgnore(record.id, locationId, ingredientId, level, updatedAt, memberId)
        queries.updateInventoryBundleById(locationId, ingredientId, level, updatedAt, memberId, record.id)
        queries.updateInventoryBundleByLocationIngredient(level, updatedAt, memberId, locationId, ingredientId)
    }

    private fun writeFamily(record: BundleRecord) {
        val data = record.data
        queries.insertFamilyIgnore(record.id, data.string("canonicalName"), data.long("createdAt"))
        queries.updateFamily(data.string("canonicalName"), record.id)
    }

    private fun writeAliases(record: BundleRecord) {
        record.data.array("aliases").forEach { alias ->
            queries.insertAliasIgnore(record.id, alias.jsonPrimitive.content)
        }
    }

    private fun writeRecipeVariant(record: BundleRecord) {
        val data = record.data
        queries.insertRecipeVariantBundleIgnore(
            record.id, data.string("familyId"), data.string("title"), data.string("sourceKind"), data.string("instructions"),
            data.string("preparation"), data.double("servings"), data.string("style"), data.nullableString("revisionId"), data.long("createdAt"),
        )
        queries.updateRecipeVariantBundle(
            data.string("familyId"), data.string("title"), data.string("sourceKind"), data.string("instructions"), data.string("preparation"),
            data.double("servings"), data.string("style"), data.nullableString("revisionId"), record.id,
        )
        queries.deleteRecipeIngredientsForVariant(record.id)
        record.data.array("ingredients").forEach { line ->
            val item = line.jsonObject
            queries.insertRecipeIngredient(
                item.string("id"), record.id, item.string("ingredientId"), item.nullableDouble("amountValue"), item.nullableString("amountUnit"),
                item.nullableString("preparation"), if (item.boolean("optional")) 1L else 0L, item.long("sortOrder"),
            )
        }
    }

    private fun writeRecipeSource(record: BundleRecord) {
        val data = record.data
        queries.insertRecipeSourceIgnore(
            record.id, data.string("variantId"), data.string("sourceName"), data.nullableString("sourceRecordId"), data.nullableString("sourceUrl"),
            data.nullableString("licenseName"), data.nullableString("attribution"), data.nullableString("modifications"), data.long("importedAt"),
        )
        queries.updateRecipeSource(
            data.string("variantId"), data.string("sourceName"), data.nullableString("sourceRecordId"), data.nullableString("sourceUrl"),
            data.nullableString("licenseName"), data.nullableString("attribution"), data.nullableString("modifications"), data.long("importedAt"), record.id,
        )
    }

    private fun writeTastingNote(record: BundleRecord) {
        val data = record.data
        queries.insertTastingNoteIgnore(
            record.id, data.string("variantId"), data.string("memberId"), data.nullableString("locationId"), data.nullableLong("rating"),
            data.string("note"), data.long("createdAt"), data.nullableString("revisionId"),
        )
        queries.updateTastingNote(
            data.string("variantId"), data.string("memberId"), data.nullableString("locationId"), data.nullableLong("rating"),
            data.string("note"), data.long("createdAt"), data.nullableString("revisionId"), record.id,
        )
    }

    private fun writeFavorite(record: BundleRecord) {
        val data = record.data
        val memberId = data.string("memberId")
        val familyId = data.string("familyId")
        val createdAt = data.long("createdAt")
        queries.insertFavoriteIgnore(memberId, familyId, createdAt)
        queries.updateFavorite(createdAt, memberId, familyId)
    }

    private fun writeDrinkEvent(record: BundleRecord) {
        val data = record.data
        queries.insertDrinkEventBundleIgnore(
            record.id, data.string("variantId"), data.string("memberId"), data.nullableString("locationId"),
            data.long("madeAt"), data.nullableString("revisionId"),
        )
        queries.updateDrinkEventBundle(
            data.string("variantId"), data.string("memberId"), data.nullableString("locationId"), data.long("madeAt"),
            data.nullableString("revisionId"), record.id,
        )
    }

    private fun writeRevision(revision: BundleRevision) {
        queries.insertRecordRevisionIgnore(
            revision.id, revision.recordId, revision.recordType, revision.authorMemberId, revision.deviceId,
            revision.createdAt, revision.schemaVersion, Json.encodeToString(JsonObject.serializer(), revision.payload),
        )
    }

    private fun BundleRecord.withRevision(revisionId: String?): BundleRecord = copy(data = JsonObject(data + ("revisionId" to (revisionId?.let(::str) ?: data["revisionId"] ?: JsonNull))))

    private fun fingerprint(bundle: HouseholdBundle): String = fnv1a(HouseholdBundleCodec.encode(bundle.copy(exportedAt = 0)))

    private fun fnv1a(value: String): String {
        var hash = 0xcbf29ce484222325UL
        value.encodeToByteArray().forEach { byte -> hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL }
        return hash.toString(16)
    }

    private fun compositeFavoriteId(memberId: String, familyId: String) = "${memberId.length}#$memberId${familyId.length}#$familyId"

    private fun mergedRevisionsOffset(key: String): Long = key.hashCode().toUInt().toLong()

    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Long) = JsonPrimitive(value)
    private fun decimal(value: Double) = JsonPrimitive(value)
    private fun nullableNumber(value: Long?): JsonElement = value?.let(::num) ?: JsonNull
    private fun nullableDecimal(value: Double?): JsonElement = value?.let(::decimal) ?: JsonNull
    private fun nullableString(value: String?): JsonElement = value?.let(::str) ?: JsonNull

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.nullableString(key: String): String? = getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.content }
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.longOrNull ?: error("Invalid integer field $key")
    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.doubleOrNull ?: error("Invalid number field $key")
    private fun JsonObject.nullableLong(key: String): Long? = getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.longOrNull ?: error("Invalid integer field $key") }
    private fun JsonObject.nullableDouble(key: String): Double? = getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.doubleOrNull ?: error("Invalid number field $key") }
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()
    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    private fun emptyBundle() = HouseholdBundle("", 0, emptyList(), emptyList(), emptyList())

    companion object {
        private const val EMPTY_FINGERPRINT = "empty"
    }
}
