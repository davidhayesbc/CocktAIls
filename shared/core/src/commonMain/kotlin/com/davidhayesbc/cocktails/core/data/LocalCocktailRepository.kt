package com.davidhayesbc.cocktails.core.data

import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import com.davidhayesbc.cocktails.core.model.HomeLocation
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.PantryEntry
import com.davidhayesbc.cocktails.core.model.CocktailFamilyId
import com.davidhayesbc.cocktails.core.model.RecipeCard
import com.davidhayesbc.cocktails.core.model.RecipeDetail
import com.davidhayesbc.cocktails.core.model.RecipeLine
import com.davidhayesbc.cocktails.core.model.RecipeIngredientDraft
import com.davidhayesbc.cocktails.core.model.RecentDrink
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
import com.davidhayesbc.cocktails.core.model.RecipeSourceInfo
import com.davidhayesbc.cocktails.core.model.TastingNote
import com.davidhayesbc.cocktails.core.model.CocktailFamily
import com.davidhayesbc.cocktails.core.model.RecipeIngredient
import com.davidhayesbc.cocktails.core.model.RecipeVariant
import com.davidhayesbc.cocktails.core.model.ShopSuggestion
import com.davidhayesbc.cocktails.core.model.MemberProfile
import com.davidhayesbc.cocktails.core.matching.rankSpiritUnlocks
import com.davidhayesbc.cocktails.core.matching.HomeLocationArea
import com.davidhayesbc.cocktails.core.model.newUuidV7
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundle
import com.davidhayesbc.cocktails.core.interchange.CatalogPackDecodeResult
import com.davidhayesbc.cocktails.core.interchange.decodeCatalogPack
import com.davidhayesbc.cocktails.core.interchange.RecipeImportApplyResult
import com.davidhayesbc.cocktails.core.interchange.RecipeImportPreview
import com.davidhayesbc.cocktails.core.interchange.RecipeImportProvenance
import com.davidhayesbc.cocktails.core.interchange.StagedRecipeImport
import com.davidhayesbc.cocktails.core.interchange.hasSameRecipeFormulation
import com.davidhayesbc.cocktails.core.interchange.MAX_PRODUCT_PHOTO_BYTES
import com.davidhayesbc.cocktails.core.interchange.isProductPhotoJpeg
import com.davidhayesbc.cocktails.core.interchange.photoSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString

class LocalCocktailRepository(private val database: CocktailsDatabase) {
    private val queries = database.householdQueries
    private val bundleStore = HouseholdBundleStore(database)

    fun exportHouseholdBundle(exportedAt: Long): String = bundleStore.export(exportedAt)

    fun previewHouseholdBundle(bundle: HouseholdBundle): ImportPreviewResult = bundleStore.preview(bundle)

    fun applyHouseholdBundle(
        bundle: HouseholdBundle,
        preview: HouseholdImportPreview,
        resolutions: Map<String, ConflictResolution>,
        nowMillis: Long,
    ): ImportApplyResult = bundleStore.apply(bundle, preview, resolutions, nowMillis)

    fun initialize(nowMillis: Long) {
        if (queries.selectFirstHousehold().executeAsOneOrNull() != null) return

        val householdId = newUuidV7(nowMillis)
        val memberId = newUuidV7(nowMillis)
        val locationId = newUuidV7(nowMillis)
        database.transaction {
            queries.insertHousehold(householdId, "Our Bar", nowMillis)
            queries.insertMember(memberId, householdId, "You", nowMillis)
            queries.insertLocation(locationId, householdId, "Home", nowMillis)
            queries.setPreferredHome(locationId)
            queries.setPreferredMember(memberId)
            writeRevision(householdId, "HOUSEHOLD", memberId, nowMillis, jsonObject("name" to jsonString("Our Bar")))
            writeRevision(memberId, "MEMBER", memberId, nowMillis, jsonObject("name" to jsonString("You")))
            writeRevision(locationId, "LOCATION", memberId, nowMillis, jsonObject("name" to jsonString("Home")))
        }
    }

    fun locations(): List<HomeLocation> =
        queries.selectLocations().executeAsList().map { HomeLocation(it.id, it.name) }

    fun preferredHomeId(): String? = queries.selectPreferredHome().executeAsOneOrNull()

    fun setPreferredHomeId(locationId: String) {
        queries.setPreferredHome(locationId)
    }

    fun automaticHomeSelectionEnabled(): Boolean = localPreference(AUTO_HOME_SELECTION_KEY) == "true"

    fun setAutomaticHomeSelectionEnabled(enabled: Boolean) {
        setLocalPreference(AUTO_HOME_SELECTION_KEY, enabled.toString())
        if (!enabled) setLocalPreference(AUTO_HOME_SELECTION_OVERRIDE_KEY, "false")
    }

    fun automaticHomeSelectionOverridden(): Boolean = localPreference(AUTO_HOME_SELECTION_OVERRIDE_KEY) == "true"

    fun setAutomaticHomeSelectionOverridden(overridden: Boolean) {
        setLocalPreference(AUTO_HOME_SELECTION_OVERRIDE_KEY, overridden.toString())
    }

    fun homeLocationAreas(): List<HomeLocationArea> = locations().mapNotNull { location ->
        val latitude = localPreference(homeAreaKey(location.id, "latitude"))?.toDoubleOrNull()
        val longitude = localPreference(homeAreaKey(location.id, "longitude"))?.toDoubleOrNull()
        val radius = localPreference(homeAreaKey(location.id, "radius"))?.toDoubleOrNull()
        if (latitude == null || longitude == null || radius == null) null
        else HomeLocationArea(location.id, latitude, longitude, radius)
    }

    fun setHomeLocationArea(locationId: String, latitude: Double, longitude: Double, radiusMeters: Double): Boolean {
        if (locations().none { it.id == locationId }) return false
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return false
        if (!radiusMeters.isFinite() || radiusMeters !in MIN_HOME_RADIUS_METERS..MAX_HOME_RADIUS_METERS) return false
        setLocalPreference(homeAreaKey(locationId, "latitude"), latitude.toString())
        setLocalPreference(homeAreaKey(locationId, "longitude"), longitude.toString())
        setLocalPreference(homeAreaKey(locationId, "radius"), radiusMeters.toString())
        return true
    }

    fun clearHomeLocationArea(locationId: String) {
        listOf("latitude", "longitude", "radius").forEach { removeLocalPreference(homeAreaKey(locationId, it)) }
    }

    fun localPreference(key: String): String? = queries.selectLocalPreference(key).executeAsOneOrNull()

    fun setLocalPreference(key: String, value: String) {
        queries.setLocalPreference(key, value)
    }

    fun removeLocalPreference(key: String) {
        queries.deleteLocalPreference(key)
    }

    fun members(): List<MemberProfile> {
        val householdId = queries.selectFirstHousehold().executeAsOne().id
        return queries.selectMembers(householdId).executeAsList().map { MemberProfile(it.id, it.display_name) }
    }

    fun addMember(name: String, nowMillis: Long): MemberProfile? {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return null
        val householdId = queries.selectFirstHousehold().executeAsOne().id
        val member = MemberProfile(newUuidV7(nowMillis), cleaned)
        database.transaction {
            queries.insertMember(member.id, householdId, member.name, nowMillis)
            writeRevision(member.id, "MEMBER", member.id, nowMillis, jsonObject("name" to jsonString(member.name)))
        }
        return member
    }

    fun preferredMemberId(): String? = queries.selectPreferredMember().executeAsOneOrNull()

    fun setPreferredMemberId(memberId: String) {
        queries.setPreferredMember(memberId)
    }

    fun addLocation(name: String, nowMillis: Long): HomeLocation? {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return null
        val id = newUuidV7(nowMillis)
        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
            queries.insertLocation(id, householdId, cleaned, nowMillis)
            writeRevision(id, "LOCATION", memberId, nowMillis, jsonObject("name" to jsonString(cleaned)))
        }
        return HomeLocation(id, cleaned)
    }

    fun recipeFamilies(): List<RecipeCard> = queries.selectRecipeFamilies().executeAsList().map {
        RecipeCard(CocktailFamilyId(it.family_id), it.canonical_name, it.variant_count.toInt())
    }

    fun favoriteFamilyIds(memberId: String): Set<CocktailFamilyId> =
        queries.selectActiveFavoriteFamilies(memberId).executeAsList().mapTo(mutableSetOf(), ::CocktailFamilyId)

    fun isFamilyFavorite(memberId: String, familyId: CocktailFamilyId): Boolean = familyId in favoriteFamilyIds(memberId)

    /** Favorites are household records; removal is a tombstone so stale backups cannot restore them. */
    fun setFamilyFavorite(memberId: String, familyId: CocktailFamilyId, favorite: Boolean, nowMillis: Long): Boolean {
        if (members().none { it.id == memberId } || recipeFamilies().none { it.familyId == familyId }) return false
        if (isFamilyFavorite(memberId, familyId) == favorite) return true
        val recordId = favoriteRecordId(memberId, familyId.value)
        val createdAt = queries.selectFavorite(memberId, familyId.value).executeAsOneOrNull() ?: nowMillis
        database.transaction {
            val revisionId = writeRevision(
                recordId,
                "FAVORITE",
                memberId,
                nowMillis,
                jsonObject(
                    "memberId" to jsonString(memberId),
                    "familyId" to jsonString(familyId.value),
                    "createdAt" to createdAt.toString(),
                    "deleted" to favorite.not().toString(),
                ),
            )
            if (favorite) {
                queries.insertFavoriteIgnore(memberId, familyId.value, createdAt)
                queries.deleteTombstone(recordId)
            } else {
                queries.insertFavoriteIgnore(memberId, familyId.value, createdAt)
                queries.insertTombstone(recordId, "FAVORITE", revisionId, nowMillis)
            }
        }
        return true
    }

    fun recipes(): List<RecipeDetail> = queries.selectRecipeVariants().executeAsList().map { row ->
        RecipeDetail(
            id = RecipeVariantId(row.variant_id),
            familyId = CocktailFamilyId(row.family_id),
            title = row.title,
            instructions = row.instructions,
            preparation = row.preparation,
            servings = row.servings,
            style = RecipeStyle.valueOf(row.style),
            ingredients = queries.selectRecipeIngredients(row.variant_id).executeAsList().map {
                RecipeLine(IngredientId(it.ingredient_id), it.canonical_name, it.amount_value, it.amount_unit, it.optional != 0L, it.preparation)
            },
            sourceKind = row.source_kind,
            sources = queries.selectRecipeSourcesForVariant(row.variant_id).executeAsList().map { source ->
                val sourceNotes = parseImportedSourceNotes(source.modifications)
                RecipeSourceInfo(
                    id = source.id,
                    name = source.source_name,
                    recordId = source.source_record_id,
                    url = source.source_url,
                    license = source.license_name,
                    attribution = source.attribution,
                    linked = sourceAssociationLinked(source.modifications),
                    canReassociate = sourceNotes != null,
                    historyUrl = source.modifications?.let(::parseSourceHistoryUrl),
                    modifications = parseSourceModificationDisplay(source.modifications),
                    originalIngredients = source.modifications?.let(::parseSourceOriginalIngredients),
                    licenseUrl = source.modifications?.let(::parseSourceLicenseUrl),
                    repositoryUrl = source.modifications?.let(::parseSourceRepositoryUrl),
                    repositoryRevision = source.modifications?.let(::parseSourceRepositoryRevision),
                )
            },
        )
    }

    fun hasStarterCatalog(): Boolean = queries.selectAllSourcesForBundle().executeAsList().any { source ->
        source.source_record_id?.startsWith("cocktails-wikipedia-iba-starter:") == true
    }

    /** Adds an audited bundled pack without overwriting any existing or archived household records. */
    fun installCatalogPack(packJson: String, nowMillis: Long): CatalogInstallResult {
        val pack = when (val decoded = decodeCatalogPack(packJson)) {
            is CatalogPackDecodeResult.Invalid -> return CatalogInstallResult.Invalid(decoded.reason)
            is CatalogPackDecodeResult.Valid -> decoded.pack
        }
        return try {
            database.transactionWithResult {
                val householdId = queries.selectFirstHousehold().executeAsOne().id
                val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
                val existingSources = queries.selectAllSourcesForBundle().executeAsList()
                var added = 0
                var skipped = 0
                for ((rowIndex, recipe) in pack.records.withIndex()) {
                    val sourceRecordId = "${pack.id}:${recipe.recordId}"
                    if (existingSources.any { it.source_name == pack.sourceName && it.source_record_id == sourceRecordId }) {
                        skipped++
                        continue
                    }
                    val familyId = newUuidV7(nowMillis + rowIndex * 20L)
                    val variantId = newUuidV7(nowMillis + rowIndex * 20L + 1)
                    val sourceId = newUuidV7(nowMillis + rowIndex * 20L + 2)
                    queries.insertRecipeFamily(familyId, recipe.title, nowMillis)
                    writeRevision(familyId, "COCKTAIL_FAMILY", memberId, nowMillis, jsonObject("name" to jsonString(recipe.title)))
                    queries.insertCatalogRecipeVariant(variantId, familyId, recipe.title, recipe.instructions, "", recipe.servings, recipe.style.name, nowMillis)
                    val ingredientIds = recipe.ingredients.map { it.name to it.category }.distinctBy { "${it.first.lowercase()}\u0000${it.second.lowercase()}" }
                    ingredientIds.forEachIndexed { index, (name, category) ->
                        queries.insertIngredientIgnore(newUuidV7(nowMillis + rowIndex * 100L + index + 100), name, category, nowMillis)
                    }
                    val ids = ingredientIds.associate { (name, category) ->
                        "${name.lowercase()}\u0000${category.lowercase()}" to queries.selectIngredientByNameAndCategory(name, category).executeAsOne()
                    }
                    recipe.ingredients.forEachIndexed { index, line ->
                        val ingredientId = ids.getValue("${line.name.lowercase()}\u0000${line.category.lowercase()}")
                        queries.insertRecipeIngredient(
                            id = newUuidV7(nowMillis + rowIndex * 100L + index + 500),
                            variant_id = variantId,
                            ingredient_id = ingredientId,
                            amount_value = line.amount,
                            amount_unit = line.unit,
                            preparation = line.preparation,
                            optional = 0L,
                            sort_order = index.toLong(),
                        )
                    }
                    val linesPayload = recipe.ingredients.joinToString(prefix = "[", postfix = "]") { line ->
                        jsonObject(
                            "name" to jsonString(line.name), "amount" to (line.amount?.toString() ?: "null"),
                            "unit" to (line.unit?.let(::jsonString) ?: "null"), "preparation" to (line.preparation?.let(::jsonString) ?: "null"),
                            "optional" to "false",
                        )
                    }
                    val variantRevision = writeRevision(
                        variantId, "RECIPE_VARIANT", memberId, nowMillis,
                        jsonObject("familyId" to jsonString(familyId), "title" to jsonString(recipe.title), "instructions" to jsonString(recipe.instructions),
                            "style" to jsonString(recipe.style.name), "servings" to recipe.servings.toString(), "ingredients" to linesPayload),
                    )
                    queries.updateRecipeVariantRevision(variantRevision, variantId)
                    val modifications = jsonObject(
                        "catalogPackId" to jsonString(pack.id), "catalogPackVersion" to pack.version.toString(),
                        "licenseUrl" to jsonString(pack.licenseUrl),
                        "repositoryUrl" to jsonString(pack.repositoryUrl), "repositoryRevision" to jsonString(pack.repositoryRevision),
                        "historyUrl" to jsonString(recipe.historyUrl), "modifications" to jsonString(recipe.modifications),
                        "originalIngredients" to jsonString(recipe.originalIngredients),
                        "associationState" to jsonString("LINKED"),
                    )
                    queries.insertRecipeSource(sourceId, variantId, pack.sourceName, sourceRecordId, recipe.permanentUrl, pack.licenseName, pack.attribution, modifications, nowMillis)
                    writeRecipeSourceRevision(sourceId, memberId, nowMillis)
                    added++
                }
                bundleStore.export(nowMillis)
                CatalogInstallResult.Installed(added, skipped)
            }
        } catch (exception: Exception) {
            CatalogInstallResult.Invalid("Catalog was not installed: ${exception.message ?: "database validation failed"}.")
        }
    }

    /** Reversible, explicit link management for a previously imported source record. */
    fun reassociateRecipeSource(sourceId: String, targetVariantId: RecipeVariantId?, nowMillis: Long): Boolean {
        var changed = false
        database.transaction {
            val source = queries.selectRecipeSourceById(sourceId).executeAsOneOrNull() ?: return@transaction
            val targetId = targetVariantId?.value ?: source.variant_id
            val target = recipes().firstOrNull { it.id.value == targetId } ?: return@transaction
            val staged = parseImportedSourceNotes(source.modifications) ?: return@transaction
            if (targetVariantId != null) {
                val targetLines = target.ingredients.map { RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional) }
                if (!hasSameRecipeFormulation(staged, target.servings, target.style, targetLines)) return@transaction
            }
            val shouldLink = targetVariantId != null
            val alreadyLinked = sourceAssociationLinked(source.modifications)
            if (source.variant_id == targetId && shouldLink == alreadyLinked) {
                changed = true
                return@transaction
            }
            if (queries.selectLatestRecordRevision(source.id, "RECIPE_SOURCE").executeAsOneOrNull() == null) {
                // Establish the pre-change link as a base revision for imports created by older app builds.
                val memberId = preferredMemberId() ?: queries.selectFirstMember(queries.selectFirstHousehold().executeAsOne().id).executeAsOne().id
                writeRecipeSourceRevision(source.id, memberId, nowMillis)
            }
            val updatedModifications = withSourceAssociationState(source.modifications, shouldLink)
            queries.updateRecipeSourceAssociation(targetId, updatedModifications, source.id)
            val memberId = preferredMemberId() ?: queries.selectFirstMember(queries.selectFirstHousehold().executeAsOne().id).executeAsOne().id
            writeRecipeSourceRevision(source.id, memberId, nowMillis)
            changed = true
        }
        return changed
    }

    private fun writeRecipeSourceRevision(sourceId: String, memberId: String, nowMillis: Long) {
        val source = queries.selectRecipeSourceById(sourceId).executeAsOne()
        val payload = jsonObject(
            "variantId" to jsonString(source.variant_id),
            "sourceName" to jsonString(source.source_name),
            "sourceRecordId" to (source.source_record_id?.let(::jsonString) ?: "null"),
            "sourceUrl" to (source.source_url?.let(::jsonString) ?: "null"),
            "licenseName" to (source.license_name?.let(::jsonString) ?: "null"),
            "attribution" to (source.attribution?.let(::jsonString) ?: "null"),
            "modifications" to (source.modifications?.let(::jsonString) ?: "null"),
            "importedAt" to source.imported_at.toString(),
        )
        val revisionId = writeRevision(sourceId, "RECIPE_SOURCE", memberId, nowMillis, payload)
        queries.selectRevisionHeads(sourceId, "RECIPE_SOURCE").executeAsList()
            .filter { it != revisionId }.forEach { queries.updateRecordRevisionParentIgnore(revisionId, it) }
    }

    fun addRecipe(
        title: String,
        ingredientNames: List<String>,
        instructions: String,
        style: RecipeStyle,
        existingFamilyId: CocktailFamilyId? = null,
        nowMillis: Long,
    ): Boolean = addRecipeLines(
        title = title,
        ingredientLines = ingredientNames.map(::RecipeIngredientDraft),
        instructions = instructions,
        style = style,
        existingFamilyId = existingFamilyId,
        servings = 1.0,
        nowMillis = nowMillis,
    )

    fun addRecipeLines(
        title: String,
        ingredientLines: List<RecipeIngredientDraft>,
        instructions: String,
        style: RecipeStyle,
        existingFamilyId: CocktailFamilyId? = null,
        servings: Double = 1.0,
        nowMillis: Long,
    ): Boolean = createRecipeLines(title, ingredientLines, instructions, style, existingFamilyId, servings, nowMillis) != null

    /** Applies a staged user-file import as one transaction; no recipe is written if validation finds a conflict. */
    fun applyRecipeImport(
        preview: RecipeImportPreview,
        provenance: RecipeImportProvenance,
        existingFamilyByGroup: Map<String, CocktailFamilyId>,
        nowMillis: Long,
        existingVariantByRow: Map<Int, RecipeVariantId> = emptyMap(),
    ): RecipeImportApplyResult {
        if (!preview.canApply) return RecipeImportApplyResult.Invalid("Fix every row and provenance error before importing.")
        val sourceName = provenance.sourceName.trim()
        val licenseName = provenance.licenseName.trim()
        val attribution = provenance.attribution.trim()
        if (sourceName.isEmpty() || licenseName.isEmpty() || attribution.isEmpty()) {
            return RecipeImportApplyResult.Invalid("Source name, license or terms, and attribution are required.")
        }
        if (existingFamilyByGroup.keys.any { key -> preview.recipes.none { it.familyGroupKey == key } }) {
            return RecipeImportApplyResult.Invalid("A selected cocktail-family association does not match this import preview.")
        }
        if (existingVariantByRow.keys.any { row -> preview.recipes.none { it.rowNumber == row } }) {
            return RecipeImportApplyResult.Invalid("A selected recipe match no longer exists in this import preview.")
        }

        return try {
            database.transactionWithResult {
                val householdId = queries.selectFirstHousehold().executeAsOne().id
                val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
                val activeFamilyIds = queries.selectRecipeFamilies().executeAsList().mapTo(mutableSetOf()) { it.family_id }
                if (existingFamilyByGroup.values.any { it.value !in activeFamilyIds }) {
                    return@transactionWithResult RecipeImportApplyResult.Invalid("A selected family no longer exists. Refresh the import preview.")
                }

                val existingSources = queries.selectAllSourcesForBundle().executeAsList()
                val existingVariants = queries.selectAllVariantsForBundle().executeAsList().associateBy { it.id }
                val activeVariants = existingVariants.values.filter {
                    queries.selectTombstone(it.id, "RECIPE_VARIANT").executeAsOneOrNull() == null
                }.associateBy { it.id }
                if (existingVariantByRow.values.any { it.value !in activeVariants }) {
                    return@transactionWithResult RecipeImportApplyResult.Invalid("A selected existing recipe is no longer available. Refresh the import preview.")
                }
                val prepared = mutableListOf<Triple<StagedRecipeImport, String, String?>>()
                val incomingByIdentity = mutableMapOf<String, StagedRecipeImport>()
                var skipped = 0

                for (recipe in preview.recipes) {
                    val sourceRecordId = recipe.sourceRecordId?.trim()?.takeIf(String::isNotEmpty)
                        ?: contentImportSourceId(sourceName, recipe)
                    val identity = "${sourceName.lowercase()}\u0000$sourceRecordId"
                    val previousInFile = incomingByIdentity[identity]
                    if (previousInFile != null) {
                        if (!sameImportedRecipe(previousInFile, recipe)) {
                            return@transactionWithResult RecipeImportApplyResult.Invalid(
                                "Source record '$sourceRecordId' appears with different recipe data. No rows were imported.",
                            )
                        }
                        skipped++
                        continue
                    }
                    incomingByIdentity[identity] = recipe

                    val existingSource = existingSources.firstOrNull {
                        it.source_name.equals(sourceName, ignoreCase = true) && it.source_record_id == sourceRecordId
                    }
                    if (existingSource != null) {
                        val existingVariant = existingVariants[existingSource.variant_id]
                            ?: return@transactionWithResult RecipeImportApplyResult.Invalid("An existing source record points to a missing recipe.")
                        val selectedExistingVariant = existingVariantByRow[recipe.rowNumber]?.value
                        val storedFingerprint = importFingerprintFromSourceNotes(existingSource.modifications)
                        val contentUnchanged = if (storedFingerprint != null) {
                            storedFingerprint == recipeImportFingerprint(recipe)
                        } else {
                            val existingLines = queries.selectRecipeIngredients(existingVariant.id).executeAsList().map {
                                RecipeIngredientDraft(it.canonical_name, it.amount_value, it.amount_unit, it.preparation, it.optional != 0L)
                            }
                            sameImportedRecipe(
                                recipe,
                                existingVariant.title,
                                existingVariant.instructions,
                                existingVariant.servings,
                                runCatching { RecipeStyle.valueOf(existingVariant.style) }.getOrDefault(RecipeStyle.OTHER),
                                existingLines,
                            )
                        }
                        if (!contentUnchanged || (selectedExistingVariant != null && selectedExistingVariant != existingVariant.id)) {
                            return@transactionWithResult RecipeImportApplyResult.Invalid(
                                "Source record '$sourceRecordId' was already imported with different content. No rows were imported.",
                            )
                        }
                        skipped++
                    } else {
                        val selectedExistingVariant = existingVariantByRow[recipe.rowNumber]?.value
                        if (selectedExistingVariant != null) {
                            val target = activeVariants.getValue(selectedExistingVariant)
                            val selectedFamily = existingFamilyByGroup[recipe.familyGroupKey]?.value
                            if (selectedFamily != null && selectedFamily != target.family_id) {
                                return@transactionWithResult RecipeImportApplyResult.Invalid(
                                    "The selected recipe and family belong to different groups. Choose one association or clear the family mapping.",
                                )
                            }
                            val existingLines = queries.selectRecipeIngredients(target.id).executeAsList().map {
                                RecipeIngredientDraft(it.canonical_name, it.amount_value, it.amount_unit, it.preparation, it.optional != 0L)
                            }
                            val targetStyle = runCatching { RecipeStyle.valueOf(target.style) }.getOrDefault(RecipeStyle.OTHER)
                            if (!hasSameRecipeFormulation(recipe, target.servings, targetStyle, existingLines)) {
                                return@transactionWithResult RecipeImportApplyResult.Invalid(
                                    "The selected existing recipe has a different measured formulation. No rows were imported.",
                                )
                            }
                        }
                        prepared += Triple(recipe, sourceRecordId, selectedExistingVariant)
                    }
                }

                val familyIdsByGroup = existingFamilyByGroup.mapValuesTo(mutableMapOf()) { it.value.value }
                var imported = 0
                for ((recipe, sourceRecordId, existingVariantId) in prepared) {
                    if (existingVariantId != null) {
                        val sourceId = newUuidV7(nowMillis + imported + 900)
                        queries.insertRecipeSource(
                            sourceId,
                            existingVariantId,
                            sourceName,
                            sourceRecordId,
                            provenance.sourceUrl?.trim()?.takeIf(String::isNotEmpty),
                            licenseName,
                            attribution,
                            importedRecipeSourceNotes(recipe),
                            nowMillis,
                        )
                        writeRecipeSourceRevision(sourceId, memberId, nowMillis)
                        imported++
                        continue
                    }
                    val familyId = familyIdsByGroup[recipe.familyGroupKey] ?: newUuidV7(nowMillis + imported).also { id ->
                        queries.insertRecipeFamily(id, recipe.familyName, nowMillis)
                        writeRevision(id, "COCKTAIL_FAMILY", memberId, nowMillis, jsonObject("name" to jsonString(recipe.familyName)))
                        familyIdsByGroup[recipe.familyGroupKey] = id
                    }
                    val cleanLines = recipe.ingredientLines.map { line ->
                        line.copy(name = line.name.trim(), unit = line.unit?.trim()?.takeIf(String::isNotEmpty), preparation = line.preparation?.trim()?.takeIf(String::isNotEmpty))
                    }
                    val variantId = newUuidV7(nowMillis + imported + 10)
                    queries.insertRecipeVariant(variantId, familyId, recipe.title.trim(), recipe.instructions.trim(), "", recipe.servings, recipe.style.name, nowMillis)
                    val uniqueIngredientNames = cleanLines.map(RecipeIngredientDraft::name).distinctBy { it.lowercase() }
                    uniqueIngredientNames.forEachIndexed { index, name ->
                        queries.insertIngredientIgnore(newUuidV7(nowMillis + imported + index + 100), name, "Other", nowMillis)
                    }
                    val ingredientIds = uniqueIngredientNames.associate { name -> name.lowercase() to queries.selectIngredientsByName(name).executeAsOne() }
                    cleanLines.forEachIndexed { index, line ->
                        queries.insertRecipeIngredient(
                            id = newUuidV7(nowMillis + imported + index + 500),
                            variant_id = variantId,
                            ingredient_id = ingredientIds.getValue(line.name.lowercase()),
                            amount_value = line.amount,
                            amount_unit = line.unit,
                            preparation = line.preparation,
                            optional = if (line.optional) 1L else 0L,
                            sort_order = index.toLong(),
                        )
                    }
                    val ingredientPayload = "[${cleanLines.joinToString(",") { line ->
                        jsonObject(
                            "name" to jsonString(line.name),
                            "amount" to (line.amount?.toString() ?: "null"),
                            "unit" to (line.unit?.let(::jsonString) ?: "null"),
                            "preparation" to (line.preparation?.let(::jsonString) ?: "null"),
                            "optional" to line.optional.toString(),
                        )
                    }}]"
                    val revisionId = writeRevision(
                        variantId,
                        "RECIPE_VARIANT",
                        memberId,
                        nowMillis,
                        jsonObject(
                            "familyId" to jsonString(familyId),
                            "title" to jsonString(recipe.title.trim()),
                            "instructions" to jsonString(recipe.instructions.trim()),
                            "style" to jsonString(recipe.style.name),
                            "servings" to recipe.servings.toString(),
                            "ingredients" to ingredientPayload,
                        ),
                    )
                    queries.updateRecipeVariantRevision(revisionId, variantId)
                    val sourceId = newUuidV7(nowMillis + imported + 900)
                    queries.insertRecipeSource(
                        sourceId,
                        variantId,
                        sourceName,
                        sourceRecordId,
                        provenance.sourceUrl?.trim()?.takeIf(String::isNotEmpty),
                        licenseName,
                        attribution,
                        importedRecipeSourceNotes(recipe),
                        nowMillis,
                    )
                    writeRecipeSourceRevision(sourceId, memberId, nowMillis)
                    imported++
                }
                // Preserve the ability to create a valid backup after the transaction commits.
                bundleStore.export(nowMillis)
                RecipeImportApplyResult.Applied(imported, skipped)
            }
        } catch (exception: Exception) {
            RecipeImportApplyResult.Invalid("Import was rolled back: ${exception.message ?: "database validation failed"}.")
        }
    }

    private fun createRecipeLines(
        title: String,
        ingredientLines: List<RecipeIngredientDraft>,
        instructions: String,
        style: RecipeStyle,
        existingFamilyId: CocktailFamilyId?,
        servings: Double,
        nowMillis: Long,
        copySourcesFromVariant: String? = null,
    ): RecipeVariantId? {
        val cleanTitle = title.trim()
        val cleanLines = ingredientLines.map { line ->
            line.copy(name = line.name.trim(), unit = line.unit?.trim()?.takeIf(String::isNotEmpty), preparation = line.preparation?.trim()?.takeIf(String::isNotEmpty))
        }.filter { it.name.isNotEmpty() }
        if (cleanTitle.isEmpty() || cleanLines.isEmpty() || !servings.isFinite() || servings <= 0.0 ||
            cleanLines.any { it.amount != null && (!it.amount.isFinite() || it.amount <= 0.0) }
        ) return null
        val cleanIngredients = cleanLines.map(RecipeIngredientDraft::name).distinctBy { it.lowercase() }
        var createdId: String? = null
        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
            val familyId = existingFamilyId?.value ?: newUuidV7(nowMillis).also {
                queries.insertRecipeFamily(it, cleanTitle, nowMillis)
                writeRevision(it, "COCKTAIL_FAMILY", memberId, nowMillis, jsonObject("name" to jsonString(cleanTitle)))
            }
            val variantId = newUuidV7(nowMillis)
            createdId = variantId
            queries.insertRecipeVariant(variantId, familyId, cleanTitle, instructions.trim(), "", servings, style.name, nowMillis)
            cleanIngredients.forEachIndexed { index, name ->
                queries.insertIngredientIgnore(newUuidV7(nowMillis + index + 1), name, "Other", nowMillis)
            }
            val ingredientIds = cleanIngredients.associate { name -> name.lowercase() to queries.selectIngredientsByName(name).executeAsOne() }
            cleanLines.forEachIndexed { index, line ->
                queries.insertRecipeIngredient(
                    id = newUuidV7(nowMillis + index + 100),
                    variant_id = variantId,
                    ingredient_id = ingredientIds.getValue(line.name.lowercase()),
                    amount_value = line.amount,
                    amount_unit = line.unit,
                    preparation = line.preparation,
                    optional = if (line.optional) 1L else 0L,
                    sort_order = index.toLong(),
                )
            }
            val ingredientPayload = "[${cleanLines.joinToString(",") { line ->
                jsonObject(
                    "name" to jsonString(line.name),
                    "amount" to (line.amount?.toString() ?: "null"),
                    "unit" to (line.unit?.let(::jsonString) ?: "null"),
                    "preparation" to (line.preparation?.let(::jsonString) ?: "null"),
                    "optional" to line.optional.toString(),
                )
            }}]"
            val revisionId = writeRevision(
                variantId,
                "RECIPE_VARIANT",
                memberId,
                nowMillis,
                jsonObject(
                    "familyId" to jsonString(familyId),
                    "title" to jsonString(cleanTitle),
                    "instructions" to jsonString(instructions.trim()),
                    "style" to jsonString(style.name),
                    "servings" to servings.toString(),
                    "ingredients" to ingredientPayload,
                ),
            )
            queries.updateRecipeVariantRevision(revisionId, variantId)
            copySourcesFromVariant?.let { sourceVariantId ->
                queries.selectRecipeSourcesForVariant(sourceVariantId).executeAsList().forEachIndexed { index, source ->
                    val copiedSourceId = newUuidV7(nowMillis + index + 500)
                    val copiedRecordId = "household-copy:$variantId:${source.id}"
                    val copiedNotes = copySourceModifications(source.modifications, source.id, source.source_record_id)
                    queries.insertRecipeSource(
                        copiedSourceId, variantId, source.source_name, copiedRecordId,
                        source.source_url, source.license_name, source.attribution, copiedNotes, source.imported_at,
                    )
                    writeRecipeSourceRevision(copiedSourceId, memberId, nowMillis)
                }
            }
        }
        return createdId?.let(::RecipeVariantId)
    }

    /** Edits the current recipe variant in place while retaining its prior immutable revision. */
    fun editRecipeVariant(
        variantId: RecipeVariantId,
        title: String,
        ingredientLines: List<RecipeIngredientDraft>,
        instructions: String,
        servings: Double,
        style: RecipeStyle,
        nowMillis: Long,
        targetFamilyId: CocktailFamilyId? = null,
    ): Boolean {
        val existing = recipes().firstOrNull { it.id == variantId } ?: return false
        if (existing.sourceKind == "CATALOG") return false
        val cleanTitle = title.trim()
        val cleanInstructions = instructions.trim()
        val cleanLines = ingredientLines.map { line ->
            line.copy(
                name = line.name.trim(),
                unit = line.unit?.trim()?.takeIf(String::isNotEmpty),
                preparation = line.preparation?.trim()?.takeIf(String::isNotEmpty),
            )
        }.filter { it.name.isNotEmpty() }
        if (cleanTitle.isEmpty() || cleanLines.isEmpty() || !servings.isFinite() || servings <= 0.0 ||
            cleanLines.any { it.amount != null && (!it.amount.isFinite() || it.amount <= 0.0) }
        ) return false

        val householdId = queries.selectFirstHousehold().executeAsOne().id
        val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
        var edited = false
        database.transaction {
            if (queries.selectTombstone(variantId.value, "RECIPE_VARIANT").executeAsOneOrNull() != null) return@transaction
            val familyId = targetFamilyId?.value ?: existing.familyId.value
            val activeFamilyIds = queries.selectRecipeFamilies().executeAsList().mapTo(mutableSetOf()) { it.family_id }
            if (familyId !in activeFamilyIds) return@transaction
            cleanLines.map(RecipeIngredientDraft::name).distinctBy { it.lowercase() }.forEachIndexed { index, name ->
                queries.insertIngredientIgnore(newUuidV7(nowMillis + index + 1), name, "Other", nowMillis)
            }
            val ingredientIds = cleanLines.map(RecipeIngredientDraft::name).distinctBy { it.lowercase() }
                .associate { name -> name.lowercase() to queries.selectIngredientsByName(name).executeAsOne() }
            val availableLineIds = queries.selectAllRecipeIngredientsForBundle().executeAsList()
                .filter { it.variant_id == variantId.value }
                .groupBy { it.ingredient_id }
                .mapValues { (_, rows) -> rows.map { it.id }.toMutableList() }
            queries.deleteRecipeIngredientsForVariant(variantId.value)
            cleanLines.forEachIndexed { index, line ->
                val ingredientId = ingredientIds.getValue(line.name.lowercase())
                val existingLineId = availableLineIds[ingredientId]?.removeFirstOrNull()
                queries.insertRecipeIngredient(
                    id = existingLineId ?: newUuidV7(nowMillis + index + 100),
                    variant_id = variantId.value,
                    ingredient_id = ingredientId,
                    amount_value = line.amount,
                    amount_unit = line.unit,
                    preparation = line.preparation,
                    optional = if (line.optional) 1L else 0L,
                    sort_order = index.toLong(),
                )
            }
            val ingredientPayload = "[${cleanLines.joinToString(",") { line ->
                jsonObject(
                    "name" to jsonString(line.name),
                    "amount" to (line.amount?.toString() ?: "null"),
                    "unit" to (line.unit?.let(::jsonString) ?: "null"),
                    "preparation" to (line.preparation?.let(::jsonString) ?: "null"),
                    "optional" to line.optional.toString(),
                )
            }}]"
            val revisionId = writeRevision(
                variantId.value,
                "RECIPE_VARIANT",
                memberId,
                nowMillis,
                jsonObject(
                    "familyId" to jsonString(familyId),
                    "title" to jsonString(cleanTitle),
                    "instructions" to jsonString(cleanInstructions),
                    "style" to jsonString(style.name),
                    "servings" to servings.toString(),
                    "ingredients" to ingredientPayload,
                ),
            )
            queries.updateRecipeVariantWithFamily(familyId, cleanTitle, cleanInstructions, servings, style.name, revisionId, variantId.value)
            edited = true
        }
        return edited
    }

    /** Creates a sibling variant in the same explicit cocktail family and carries source attribution forward. */
    fun duplicateRecipeVariant(variantId: RecipeVariantId, nowMillis: Long): RecipeVariantId? {
        val sourceRecipe = recipes().firstOrNull { it.id == variantId } ?: return null
        val duplicateId = createRecipeLines(
            title = "${sourceRecipe.title} (copy)",
            ingredientLines = sourceRecipe.ingredients.map {
                RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional)
            },
            instructions = sourceRecipe.instructions,
            style = sourceRecipe.style,
            existingFamilyId = sourceRecipe.familyId,
            servings = sourceRecipe.servings,
            nowMillis = nowMillis,
            copySourcesFromVariant = variantId.value,
        ) ?: return null
        return duplicateId
    }

    fun addTastingNote(
        variantId: RecipeVariantId,
        memberId: String,
        rating: Int?,
        note: String,
        locationId: String?,
        nowMillis: Long,
    ) {
        database.transaction {
            val noteId = newUuidV7(nowMillis)
            val cleanedNote = note.trim()
            queries.insertTastingNote(noteId, variantId.value, memberId, locationId, rating?.toLong(), cleanedNote, nowMillis)
            val revisionId = writeRevision(
                noteId,
                "TASTING_NOTE",
                memberId,
                nowMillis,
                jsonObject(
                    "variantId" to jsonString(variantId.value),
                    "memberId" to jsonString(memberId),
                    "locationId" to (locationId?.let(::jsonString) ?: "null"),
                    "rating" to (rating?.toString() ?: "null"),
                    "note" to jsonString(cleanedNote),
                ),
            )
            queries.updateTastingNoteRevision(revisionId, noteId)
        }
    }

    fun tastingNotes(variantId: RecipeVariantId): List<TastingNote> =
        queries.selectTastingNotes(variantId.value).executeAsList().map {
            TastingNote(it.id, it.display_name, it.rating?.toInt(), it.note, it.created_at)
        }

    fun markRecipeMade(variantId: RecipeVariantId, memberId: String, locationId: String?, nowMillis: Long): Boolean {
        if (recipes().none { it.id == variantId } || members().none { it.id == memberId }) return false
        if (locationId != null && locations().none { it.id == locationId }) return false
        database.transaction {
            val eventId = newUuidV7(nowMillis)
            queries.insertDrinkEvent(eventId, variantId.value, memberId, locationId, nowMillis, null)
            val revisionId = writeRevision(
                eventId,
                "DRINK_EVENT",
                memberId,
                nowMillis,
                jsonObject(
                    "variantId" to jsonString(variantId.value),
                    "memberId" to jsonString(memberId),
                    "locationId" to (locationId?.let(::jsonString) ?: "null"),
                    "madeAt" to nowMillis.toString(),
                ),
            )
            queries.updateDrinkEventRevision(revisionId, eventId)
        }
        return true
    }

    fun recentlyMade(limit: Int = 10): List<RecentDrink> = queries.selectRecentlyMade(limit.coerceIn(1, 100).toLong()).executeAsList().map {
        RecentDrink(it.id, RecipeVariantId(it.variant_id), it.title, it.display_name, it.made_at, it.location_name)
    }

    /** Hides a household recipe variant while retaining its rows and an exportable deletion revision. */
    fun archiveRecipeVariant(variantId: RecipeVariantId, nowMillis: Long): Boolean {
        val recipe = recipes().firstOrNull { it.id == variantId } ?: return false
        val householdId = queries.selectFirstHousehold().executeAsOne().id
        val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
        var archived = false
        database.transaction {
            if (queries.selectTombstone(variantId.value, "RECIPE_VARIANT").executeAsOneOrNull() != null) return@transaction
            val revisionId = writeRevision(
                recordId = variantId.value,
                recordType = "RECIPE_VARIANT",
                memberId = memberId,
                nowMillis = nowMillis,
                payload = jsonObject(
                    "deleted" to "true",
                    "recordId" to jsonString(variantId.value),
                    "recordType" to jsonString("RECIPE_VARIANT"),
                    "familyId" to jsonString(recipe.familyId.value),
                ),
            )
            queries.insertTombstone(variantId.value, "RECIPE_VARIANT", revisionId, nowMillis)
            archived = true
        }
        return archived
    }

    fun shopSuggestions(pantry: List<PantryEntry>): List<ShopSuggestion> {
        val variants = recipes()
        val families = variants.groupBy { it.familyId }.map { (familyId, familyVariants) ->
            CocktailFamily(
                id = familyId,
                canonicalName = familyVariants.first().title,
                variants = familyVariants.map { variant ->
                    RecipeVariant(
                        id = variant.id,
                        familyId = variant.familyId,
                        title = variant.title,
                        ingredients = variant.ingredients.map { RecipeIngredient(it.ingredientId, it.optional) },
                    )
                },
            )
        }
        val candidates = queries.selectSpiritCandidates().executeAsList()
        val namesById = candidates.associate { it.id to it.canonical_name }
        val unlocks = rankSpiritUnlocks(
            families = families,
            availableIngredients = pantry.filter { it.level != InventoryLevel.OUT }.map { it.ingredientId }.toSet(),
            spiritCandidates = namesById.keys.map(::IngredientId).toSet(),
        )
        return unlocks.filter { it.newlyMakeableFamilies > 0 }.mapNotNull { unlock ->
            namesById[unlock.spiritId.value]?.let { ShopSuggestion(it, unlock.newlyMakeableFamilies) }
        }
    }

    fun pantry(locationId: String): List<PantryEntry> =
        queries.selectPantryForLocation(locationId).executeAsList().map {
            PantryEntry(
                ingredientId = IngredientId(it.ingredient_id),
                name = it.canonical_name,
                category = it.category,
                level = InventoryLevel.valueOf(it.level),
                photoData = it.photo_data,
            )
        }

    fun addIngredient(
        name: String,
        category: String,
        level: InventoryLevel,
        locationId: String,
        nowMillis: Long,
        barcode: String? = null,
        brand: String? = null,
        productName: String? = null,
        productPhotoJpeg: ByteArray? = null,
    ): Boolean {
        val cleanedName = name.trim()
        val cleanedCategory = category.trim().ifEmpty { "Other" }
        if (cleanedName.isEmpty()) return false
        val originalBarcode = barcode?.trim()?.takeIf(String::isNotEmpty)
        val normalizedBarcode = originalBarcode?.let(::normalizeGtinForLookup)
        if (originalBarcode != null && normalizedBarcode == null) return false
        if (productPhotoJpeg != null && (productPhotoJpeg.size > MAX_PRODUCT_PHOTO_BYTES || !isProductPhotoJpeg(productPhotoJpeg))) return false
        val cleanedBrand = brand?.trim()?.takeIf(String::isNotEmpty)
        val cleanedProductName = productName?.trim()?.takeIf(String::isNotEmpty) ?: cleanedName

        return database.transactionWithResult {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
            val exactId = queries.selectIngredientByNameAndCategory(cleanedName, cleanedCategory).executeAsOneOrNull()
            val sameNameId = if (exactId == null && queries.countIngredientsByName(cleanedName).executeAsOne() == 1L) {
                queries.selectIngredientsByName(cleanedName).executeAsOneOrNull()
            } else null
            val matchingProduct = normalizedBarcode?.let { normalized ->
                queries.selectProductsForBarcodeDeduplication().executeAsList().firstOrNull { product ->
                    product.barcode?.let(::normalizeGtinForLookup) == normalized
                }
            }
            val targetIngredientId = exactId ?: sameNameId
            if (matchingProduct != null && targetIngredientId != matchingProduct.ingredient_id) {
                return@transactionWithResult false
            }
            val ingredientId = exactId ?: run {
                if (sameNameId != null) {
                    queries.updateIngredientCategory(cleanedCategory, sameNameId)
                    sameNameId
                } else {
                    queries.insertIngredientIgnore(newUuidV7(nowMillis), cleanedName, cleanedCategory, nowMillis)
                    queries.selectIngredientByNameAndCategory(cleanedName, cleanedCategory).executeAsOne()
                }
            }
            writeRevision(ingredientId, "INGREDIENT", memberId, nowMillis, jsonObject("name" to jsonString(cleanedName), "category" to jsonString(cleanedCategory)))
            writeInventory(locationId, ingredientId, level, memberId, nowMillis)

            if (originalBarcode != null || productPhotoJpeg != null) {
                val existingProduct = matchingProduct?.let { queries.selectProductById(it.id).executeAsOneOrNull() }
                    ?: queries.selectProductForIngredient(ingredientId).executeAsOneOrNull()
                val productId = existingProduct?.id ?: newUuidV7(nowMillis + 1)
                val productBarcode = originalBarcode ?: existingProduct?.barcode
                if (existingProduct == null) {
                    queries.insertProductIgnore(productId, ingredientId, cleanedBrand, cleanedProductName, productBarcode, nowMillis)
                }
                queries.updateProduct(ingredientId, cleanedBrand ?: existingProduct?.brand, cleanedProductName, productBarcode, productId)
                if (productPhotoJpeg != null) {
                    queries.updateProductPhoto("image/jpeg", productPhotoJpeg, photoSha256(productPhotoJpeg), productId)
                }
                val storedProduct = queries.selectProductById(productId).executeAsOne()
                val productPayload = jsonObject(
                    "id" to jsonString(productId),
                    "ingredientId" to jsonString(ingredientId),
                    "brand" to nullableJsonString(cleanedBrand),
                    "productName" to jsonString(cleanedProductName),
                    "barcode" to (storedProduct.barcode?.let(::jsonString) ?: "null"),
                    "photoSha256" to (storedProduct.photo_sha256?.let(::jsonString) ?: "null"),
                    "createdAt" to storedProduct.created_at.toString(),
                )
                writeRevision(productId, "PRODUCT", memberId, nowMillis, productPayload)
            }
            true
        }
    }

    fun updateInventoryLevel(
        ingredientId: IngredientId,
        locationId: String,
        level: InventoryLevel,
        nowMillis: Long,
    ) {
        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
            writeInventory(locationId, ingredientId.value, level, memberId, nowMillis)
        }
    }

    private fun writeInventory(
        locationId: String,
        ingredientId: String,
        level: InventoryLevel,
        memberId: String,
        nowMillis: Long,
    ) {
        val existingId = queries.selectInventoryId(locationId, ingredientId).executeAsOneOrNull()
        val inventoryId = existingId ?: newUuidV7(nowMillis)
        if (existingId == null) {
            queries.insertInventory(
                id = inventoryId,
                location_id = locationId,
                ingredient_id = ingredientId,
                level = level.name,
                updated_at = nowMillis,
                updated_by_member_id = memberId,
            )
        } else {
            queries.updateInventory(level.name, nowMillis, memberId, locationId, ingredientId)
        }
        writeRevision(
            inventoryId,
            "INVENTORY_ITEM",
            memberId,
            nowMillis,
            jsonObject(
                "locationId" to jsonString(locationId),
                "ingredientId" to jsonString(ingredientId),
                "level" to jsonString(level.name),
            ),
        )
    }

    private fun writeRevision(
        recordId: String,
        recordType: String,
        memberId: String,
        nowMillis: Long,
        payload: String,
    ): String {
        val revisionId = newUuidV7(nowMillis)
        val parentRevisionId = queries.selectLatestRecordRevision(recordId, recordType).executeAsOneOrNull()
        val deviceId = queries.selectLocalPreference("device_id").executeAsOneOrNull()
            ?: newUuidV7(nowMillis + 1).also { queries.setLocalPreference("device_id", it) }
        queries.insertRecordRevision(revisionId, recordId, recordType, memberId, deviceId, nowMillis, 1L, payload)
        parentRevisionId?.let { queries.insertRevisionParent(revisionId, it) }
        return revisionId
    }
}

private const val AUTO_HOME_SELECTION_KEY = "automatic_home_selection"
private const val AUTO_HOME_SELECTION_OVERRIDE_KEY = "automatic_home_selection_overridden"
private const val MIN_HOME_RADIUS_METERS = 50.0
private const val MAX_HOME_RADIUS_METERS = 10_000.0

sealed interface CatalogInstallResult {
    data class Installed(val added: Int, val alreadyPresent: Int) : CatalogInstallResult
    data class Invalid(val reason: String) : CatalogInstallResult
}

private fun homeAreaKey(locationId: String, coordinate: String): String = "home_area:$locationId:$coordinate"

private fun jsonObject(vararg fields: Pair<String, String>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (name, encodedValue) -> "${jsonString(name)}:$encodedValue" }

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else append(character)
        }
    }
    append('"')
}

private fun nullableJsonString(value: String?): String = value?.let(::jsonString) ?: "null"

private fun contentImportSourceId(sourceName: String, recipe: StagedRecipeImport): String {
    val parts = buildList {
        add(sourceName.trim().lowercase())
        add(recipe.title.trim())
        add(recipe.instructions.trim())
        add(recipe.servings.toString())
        add(recipe.style.name)
        add(recipe.familyName.trim().lowercase())
        recipe.ingredientLines.forEach { line ->
            add(line.name.trim())
            add(line.amount?.toString().orEmpty())
            add(line.unit.orEmpty())
            add(line.optional.toString())
            add(line.preparation.orEmpty())
        }
    }
    val canonical = parts.joinToString("") { "${it.length}#$it" }
    var hash = -3750763034362895579L
    canonical.encodeToByteArray().forEach { byte ->
        hash = (hash xor (byte.toInt() and 0xff).toLong()) * 1099511628211L
    }
    return "content-v1-${hash.toString(16)}"
}

private fun recipeImportFingerprint(recipe: StagedRecipeImport): String = contentImportSourceId("recipe-import", recipe)

private fun importedRecipeSourceNotes(recipe: StagedRecipeImport): String {
    val ingredients = "[${recipe.ingredientLines.joinToString(",") { line ->
        jsonObject(
            "name" to jsonString(line.name.trim()),
            "amount" to (line.amount?.toString() ?: "null"),
            "unit" to (line.unit?.let(::jsonString) ?: "null"),
            "preparation" to (line.preparation?.let(::jsonString) ?: "null"),
            "optional" to line.optional.toString(),
        )
    }}]"
    return jsonObject(
        "importFormat" to jsonString("cocktails.recipe-file-import.v1"),
        "importFingerprint" to jsonString(recipeImportFingerprint(recipe)),
        "sourceTitle" to jsonString(recipe.title),
        "sourceInstructions" to jsonString(recipe.instructions),
        "sourceStyle" to jsonString(recipe.style.name),
        "sourceServings" to recipe.servings.toString(),
        "sourceFamily" to jsonString(recipe.familyName),
        "sourceIngredients" to ingredients,
        "associationState" to jsonString("LINKED"),
        "normalization" to jsonString("Mapped from a user-provided file; any target-variant association was explicitly selected by the household."),
    )
}

private fun importFingerprintFromSourceNotes(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["importFingerprint"]?.jsonPrimitive?.contentOrNull }
} catch (_: Exception) {
    null
}

private fun parseImportedSourceNotes(modifications: String?): StagedRecipeImport? {
    return try {
    val notes = modifications?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
    if (notes["importFormat"]?.jsonPrimitive?.contentOrNull != "cocktails.recipe-file-import.v1") return null
    val title = notes["sourceTitle"]?.jsonPrimitive?.contentOrNull ?: return null
    val style = notes["sourceStyle"]?.jsonPrimitive?.contentOrNull?.let { RecipeStyle.valueOf(it) } ?: return null
    val ingredients = notes["sourceIngredients"]?.jsonArray?.map { element ->
        val line = element.jsonObject
        val amountField = line["amount"] ?: return null
        val amount = if (amountField == JsonNull) null else amountField.jsonPrimitive.doubleOrNull ?: return null
        fun optionalText(key: String): String? {
            val value = line[key] ?: return null
            return if (value == JsonNull) null else value.jsonPrimitive.contentOrNull ?: return null
        }
        RecipeIngredientDraft(
            name = line["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            amount = amount,
            unit = optionalText("unit"),
            preparation = optionalText("preparation"),
            optional = line["optional"]?.jsonPrimitive?.booleanOrNull ?: return null,
        )
    } ?: return null
    StagedRecipeImport(
        rowNumber = 0,
        title = title,
        ingredientLines = ingredients,
        instructions = notes["sourceInstructions"]?.jsonPrimitive?.contentOrNull ?: return null,
        servings = notes["sourceServings"]?.jsonPrimitive?.doubleOrNull ?: return null,
        style = style,
        familyName = notes["sourceFamily"]?.jsonPrimitive?.contentOrNull ?: title,
        familyGroupKey = "source-association",
        sourceRecordId = null,
    )
} catch (_: Exception) {
    null
}
}

private fun sourceAssociationLinked(modifications: String?): Boolean = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["associationState"]?.jsonPrimitive?.contentOrNull } != "UNLINKED"
} catch (_: Exception) { true }

private fun parseSourceHistoryUrl(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["historyUrl"]?.jsonPrimitive?.contentOrNull }
} catch (_: Exception) { null }

private fun parseSourceModificationDisplay(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["modifications"]?.jsonPrimitive?.contentOrNull }
        ?: modifications?.takeUnless { it.startsWith("{") }
} catch (_: Exception) { null }

private fun parseSourceOriginalIngredients(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["originalIngredients"]?.jsonPrimitive?.contentOrNull }
} catch (_: Exception) { null }

private fun parseSourceLicenseUrl(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["licenseUrl"]?.jsonPrimitive?.contentOrNull }
} catch (_: Exception) { null }

private fun parseSourceRepositoryUrl(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["repositoryUrl"]?.jsonPrimitive?.contentOrNull }
} catch (_: Exception) { null }

private fun parseSourceRepositoryRevision(modifications: String?): String? = try {
    modifications?.let { Json.parseToJsonElement(it).jsonObject["repositoryRevision"]?.jsonPrimitive?.contentOrNull }
} catch (_: Exception) { null }

private fun copySourceModifications(modifications: String?, sourceId: String, sourceRecordId: String?): String {
    val original = try {
        modifications?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(mapOf("originalModifications" to JsonPrimitive(modifications.orEmpty())))
    }
    val copy = JsonObject(original + mapOf(
        "associationState" to JsonPrimitive("LINKED"),
        "copiedFromSourceId" to JsonPrimitive(sourceId),
        "copiedFromRecordId" to (sourceRecordId?.let(::JsonPrimitive) ?: JsonNull),
    ))
    return Json.encodeToString(JsonObject.serializer(), copy)
}

private fun withSourceAssociationState(modifications: String?, linked: Boolean): String {
    val existing = try {
        modifications?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        buildJsonObject { modifications?.let { put("previousModifications", JsonPrimitive(it)) } }
    }
    return Json.encodeToString(JsonObject.serializer(), JsonObject(existing + ("associationState" to JsonPrimitive(if (linked) "LINKED" else "UNLINKED"))))
}

private fun sameImportedRecipe(first: StagedRecipeImport, second: StagedRecipeImport): Boolean =
    sameImportedRecipe(first, second.title, second.instructions, second.servings, second.style, second.ingredientLines)

private fun sameImportedRecipe(
    staged: StagedRecipeImport,
    title: String,
    instructions: String,
    servings: Double,
    style: RecipeStyle,
    ingredientLines: List<RecipeIngredientDraft>,
): Boolean = staged.title.trim() == title.trim() &&
    staged.instructions.trim() == instructions.trim() &&
    staged.servings == servings &&
    staged.style == style &&
    staged.ingredientLines == ingredientLines

private fun favoriteRecordId(memberId: String, familyId: String): String =
    "${memberId.length}#$memberId${familyId.length}#$familyId"
