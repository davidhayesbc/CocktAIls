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
        }
    }

    fun locations(): List<HomeLocation> =
        queries.selectLocations().executeAsList().map { HomeLocation(it.id, it.name) }

    fun addLocation(name: String, nowMillis: Long): HomeLocation? {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return null
        val id = newUuidV7(nowMillis)
        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            queries.insertLocation(id, householdId, cleaned, nowMillis)
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
        nowMillis: Long,
    ): Boolean {
        val cleanTitle = title.trim()
        val cleanIngredients = ingredientNames.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        if (cleanTitle.isEmpty() || cleanIngredients.isEmpty()) return false
        database.transaction {
            val familyId = queries.findFamilyByName(cleanTitle).executeAsOneOrNull()
                ?: newUuidV7(nowMillis).also { queries.insertRecipeFamily(it, cleanTitle, nowMillis) }
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
        }
        return true
    }

    fun addTastingNote(
        variantId: RecipeVariantId,
        rating: Int?,
        note: String,
        locationId: String?,
        nowMillis: Long,
    ) {
        database.transaction {
            val householdId = queries.selectFirstHousehold().executeAsOne().id
            val memberId = queries.selectFirstMember(householdId).executeAsOne().id
            queries.insertTastingNote(newUuidV7(nowMillis), variantId.value, memberId, locationId, rating?.toLong(), note.trim(), nowMillis)
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
            val memberId = queries.selectFirstMember(householdId).executeAsOne().id
            queries.insertIngredientIgnore(
                id = newUuidV7(nowMillis),
                canonical_name = cleanedName,
                category = cleanedCategory,
                created_at = nowMillis,
            )
            val ingredientId = queries
                .selectIngredientsByName(cleanedName)
                .executeAsOne()
            queries.updateIngredientCategory(cleanedCategory, ingredientId)
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
            val memberId = queries.selectFirstMember(householdId).executeAsOne().id
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
        if (existingId == null) {
            queries.insertInventory(
                id = newUuidV7(nowMillis),
                location_id = locationId,
                ingredient_id = ingredientId,
                level = level.name,
                updated_at = nowMillis,
                updated_by_member_id = memberId,
            )
        } else {
            queries.updateInventory(level.name, nowMillis, memberId, locationId, ingredientId)
        }
    }
}
