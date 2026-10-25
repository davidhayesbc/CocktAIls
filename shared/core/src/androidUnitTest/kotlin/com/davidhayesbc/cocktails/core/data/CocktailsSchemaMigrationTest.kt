package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class CocktailsSchemaMigrationTest {
    @Test
    fun migratesVersionOnePantryWithoutChangingExistingIdsOrLevels() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        legacySchema.forEach { sql -> driver.execute(null, sql, 0) }

        val legacyHouseholdId = "household-v1"
        val legacyMemberId = "member-v1"
        val legacyLocationId = "location-v1"
        val legacyIngredientId = "00000000-0000-7000--800-000000000000"
        val legacyInventoryId = "inventory-v1"
        driver.execute(null, "INSERT INTO household VALUES (?, ?, ?)", 3) {
            bindString(0, legacyHouseholdId); bindString(1, "Our Bar"); bindLong(2, 1L)
        }
        driver.execute(null, "INSERT INTO household_member VALUES (?, ?, ?, ?)", 4) {
            bindString(0, legacyMemberId); bindString(1, legacyHouseholdId); bindString(2, "You"); bindLong(3, 1L)
        }
        driver.execute(null, "INSERT INTO household_location VALUES (?, ?, ?, ?)", 4) {
            bindString(0, legacyLocationId); bindString(1, legacyHouseholdId); bindString(2, "Home"); bindLong(3, 1L)
        }
        driver.execute(null, "INSERT INTO ingredient VALUES (?, ?, ?, ?)", 4) {
            bindString(0, legacyIngredientId); bindString(1, "Gin"); bindString(2, "Spirit"); bindLong(3, 1L)
        }
        driver.execute(null, "INSERT INTO inventory_item VALUES (?, ?, ?, ?, ?, ?)", 6) {
            bindString(0, legacyInventoryId); bindString(1, legacyLocationId); bindString(2, legacyIngredientId)
            bindString(3, "HALF"); bindLong(4, 10L); bindString(5, legacyMemberId)
        }
        driver.execute(null, "PRAGMA user_version = 1", 0)

        CocktailsDatabase.Schema.migrate(driver, 1, 2)
        val database = CocktailsDatabase(driver)
        val migratedPantry = database.householdQueries.selectPantryForLocation(legacyLocationId).executeAsOne()

        assertEquals(legacyIngredientId, migratedPantry.ingredient_id)
        assertEquals("Gin", migratedPantry.canonical_name)
        assertEquals("HALF", migratedPantry.level)
        assertEquals(legacyInventoryId, database.householdQueries.selectInventoryId(legacyLocationId, legacyIngredientId).executeAsOne())
        assertEquals(legacyHouseholdId, database.householdQueries.selectFirstHousehold().executeAsOne().id)
        assertEquals(legacyLocationId, database.householdQueries.selectPreferredHome().executeAsOne())
        database.householdQueries.setPreferredHome("manually-selected-location")
        assertEquals("manually-selected-location", CocktailsDatabase(driver).householdQueries.selectPreferredHome().executeAsOne())
        driver.close()
    }
}

private val legacySchema = listOf(
    "CREATE TABLE household (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL)",
    "CREATE TABLE household_location (id TEXT NOT NULL PRIMARY KEY, household_id TEXT NOT NULL REFERENCES household(id), name TEXT NOT NULL, created_at INTEGER NOT NULL)",
    "CREATE TABLE household_member (id TEXT NOT NULL PRIMARY KEY, household_id TEXT NOT NULL REFERENCES household(id), display_name TEXT NOT NULL, created_at INTEGER NOT NULL)",
    "CREATE TABLE ingredient (id TEXT NOT NULL PRIMARY KEY, canonical_name TEXT NOT NULL, category TEXT NOT NULL, created_at INTEGER NOT NULL)",
    "CREATE TABLE inventory_item (id TEXT NOT NULL PRIMARY KEY, location_id TEXT NOT NULL REFERENCES household_location(id), ingredient_id TEXT NOT NULL REFERENCES ingredient(id), level TEXT NOT NULL CHECK (level IN ('FULL', 'MOSTLY_FULL', 'HALF', 'LOW', 'OUT')), updated_at INTEGER NOT NULL, updated_by_member_id TEXT NOT NULL REFERENCES household_member(id), UNIQUE(location_id, ingredient_id))",
    "CREATE UNIQUE INDEX ingredient_name_category ON ingredient(canonical_name COLLATE NOCASE, category COLLATE NOCASE)",
    "CREATE TABLE cocktail_family (id TEXT NOT NULL PRIMARY KEY, canonical_name TEXT NOT NULL, created_at INTEGER NOT NULL)",
    "CREATE TABLE recipe_variant (id TEXT NOT NULL PRIMARY KEY, family_id TEXT NOT NULL REFERENCES cocktail_family(id), title TEXT NOT NULL, source_kind TEXT NOT NULL, created_at INTEGER NOT NULL)",
    "CREATE TABLE recipe_ingredient (id TEXT NOT NULL PRIMARY KEY, variant_id TEXT NOT NULL REFERENCES recipe_variant(id), ingredient_id TEXT NOT NULL REFERENCES ingredient(id), amount_value REAL, amount_unit TEXT, optional INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL)",
)
