package com.davidhayesbc.cocktails.core.data

import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import com.davidhayesbc.cocktails.core.model.HomeLocation
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.PantryEntry
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
                .selectIngredientByNameAndCategory(cleanedName, cleanedCategory)
                .executeAsOne()
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
