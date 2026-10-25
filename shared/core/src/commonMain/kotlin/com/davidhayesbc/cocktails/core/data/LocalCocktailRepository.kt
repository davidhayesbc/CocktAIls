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
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
import com.davidhayesbc.cocktails.core.model.TastingNote
import com.davidhayesbc.cocktails.core.model.CocktailFamily
import com.davidhayesbc.cocktails.core.model.RecipeIngredient
import com.davidhayesbc.cocktails.core.model.RecipeVariant
import com.davidhayesbc.cocktails.core.model.ShopSuggestion
import com.davidhayesbc.cocktails.core.model.MemberProfile
import com.davidhayesbc.cocktails.core.matching.rankSpiritUnlocks
import com.davidhayesbc.cocktails.core.model.newUuidV7

class LocalCocktailRepository(private val database: CocktailsDatabase) {
    private val queries = database.householdQueries

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
                RecipeLine(IngredientId(it.ingredient_id), it.canonical_name, it.amount_value, it.amount_unit, it.optional != 0L)
            },
        )
    }

    fun addRecipe(
        title: String,
        ingredientNames: List<String>,
        instructions: String,
        style: RecipeStyle,
        existingFamilyId: CocktailFamilyId? = null,
        nowMillis: Long,
    ): Boolean {
        val cleanTitle = title.trim()
        val cleanIngredients = ingredientNames.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        if (cleanTitle.isEmpty() || cleanIngredients.isEmpty()) return false
        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
            val familyId = existingFamilyId?.value ?: newUuidV7(nowMillis).also {
                queries.insertRecipeFamily(it, cleanTitle, nowMillis)
                writeRevision(it, "COCKTAIL_FAMILY", memberId, nowMillis, jsonObject("name" to jsonString(cleanTitle)))
            }
            val variantId = newUuidV7(nowMillis)
            queries.insertRecipeVariant(variantId, familyId, cleanTitle, instructions.trim(), "", 1.0, style.name, nowMillis)
            cleanIngredients.forEachIndexed { index, name ->
                queries.insertIngredientIgnore(newUuidV7(nowMillis + index + 1), name, "Other", nowMillis)
                val ingredientId = queries.selectIngredientsByName(name).executeAsOne()
                queries.insertRecipeIngredient(
                    id = newUuidV7(nowMillis + index + 100),
                    variant_id = variantId,
                    ingredient_id = ingredientId,
                    amount_value = null,
                    amount_unit = null,
                    preparation = null,
                    optional = 0L,
                    sort_order = index.toLong(),
                )
            }
            val ingredientPayload = "[${cleanIngredients.joinToString(",") { jsonString(it) }}]"
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
                    "ingredients" to ingredientPayload,
                ),
            )
            queries.updateRecipeVariantRevision(revisionId, variantId)
        }
        return true
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
            )
        }

    fun addIngredient(
        name: String,
        category: String,
        level: InventoryLevel,
        locationId: String,
        nowMillis: Long,
    ): Boolean {
        val cleanedName = name.trim()
        val cleanedCategory = category.trim().ifEmpty { "Other" }
        if (cleanedName.isEmpty()) return false

        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = preferredMemberId() ?: queries.selectFirstMember(householdId).executeAsOne().id
            val exactId = queries.selectIngredientByNameAndCategory(cleanedName, cleanedCategory).executeAsOneOrNull()
            val ingredientId = exactId ?: run {
                val existingId = queries.selectIngredientsByName(cleanedName).executeAsOneOrNull()
                if (existingId != null && queries.countIngredientsByName(cleanedName).executeAsOne() == 1L) {
                    queries.updateIngredientCategory(cleanedCategory, existingId)
                    existingId
                } else {
                    queries.insertIngredientIgnore(newUuidV7(nowMillis), cleanedName, cleanedCategory, nowMillis)
                    queries.selectIngredientByNameAndCategory(cleanedName, cleanedCategory).executeAsOne()
                }
            }
            writeRevision(ingredientId, "INGREDIENT", memberId, nowMillis, jsonObject("name" to jsonString(cleanedName), "category" to jsonString(cleanedCategory)))
            writeInventory(locationId, ingredientId, level, memberId, nowMillis)
        }
        return true
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
