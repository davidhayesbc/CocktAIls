package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.interchange.photoSha256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CocktailsSchemaMigrationTest {
    private fun minimalJpegPhoto() = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 17, 0,
        0xff.toByte(), 0xda.toByte(), 0, 8, 1, 1, 0, 0, 63, 0, 0, 0xff.toByte(), 0xd9.toByte(),
    )

    @Test
    fun migratesVersionFourProductAndAddsOptionalPhotoStorage() {
        var directory: File? = File(System.getProperty("user.dir")!!)
        var versionFour: File? = null
        while (directory != null && versionFour == null) {
            versionFour = sequenceOf(
                File(directory, "shared/core/src/commonMain/sqldelight/databases/4.db"),
                File(directory, "src/commonMain/sqldelight/databases/4.db"),
            ).firstOrNull(File::isFile)
            directory = directory.parentFile
        }
        val sourceSnapshot = versionFour ?: error("Could not find the checked-in v4 schema snapshot")
        val migratedFile = File.createTempFile("cocktails-v4-to-v5", ".db")
        sourceSnapshot.copyTo(migratedFile, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePath}")
        try {
            driver.execute(null, "INSERT INTO household VALUES (?, ?, ?)", 3) {
                bindString(0, "household-v4"); bindString(1, "Our Bar"); bindLong(2, 1L)
            }
            driver.execute(null, "INSERT INTO household_member VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "member-v4"); bindString(1, "household-v4"); bindString(2, "You"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO household_location VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "home-v4"); bindString(1, "household-v4"); bindString(2, "Home"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO ingredient VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "gin-v4"); bindString(1, "Gin"); bindString(2, "Spirit"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO ingredient_product VALUES (?, ?, ?, ?, ?, ?)", 6) {
                bindString(0, "bottle-v4"); bindString(1, "gin-v4"); bindString(2, "Brand")
                bindString(3, "Bottle"); bindString(4, "0012345678905"); bindLong(5, 2L)
            }

            CocktailsDatabase.Schema.migrate(driver, 4, 5)
            val database = CocktailsDatabase(driver)
            assertEquals("0012345678905", database.householdQueries.selectProductById("bottle-v4").executeAsOne().barcode)
            val jpeg = minimalJpegPhoto()
            val repository = LocalCocktailRepository(database)
            assertTrue(repository.addIngredient("Gin", "Spirit", InventoryLevel.FULL, "home-v4", 10, productPhotoJpeg = jpeg))
            val migratedProduct = database.householdQueries.selectProductById("bottle-v4").executeAsOne()
            assertEquals("image/jpeg", migratedProduct.photo_mime_type)
            assertEquals(photoSha256(jpeg), migratedProduct.photo_sha256)
            assertContentEquals(jpeg, migratedProduct.photo_data)
        } finally {
            driver.close()
            migratedFile.delete()
        }
    }

    @Test
    fun migratesPopulatedVersionThreeDatabaseToFiveAndStoresMadeEvents() {
        var directory: File? = File(System.getProperty("user.dir")!!)
        var versionThree: File? = null
        while (directory != null && versionThree == null) {
            versionThree = sequenceOf(
                File(directory, "shared/core/src/commonMain/sqldelight/databases/3.db"),
                File(directory, "src/commonMain/sqldelight/databases/3.db"),
            ).firstOrNull(File::isFile)
            directory = directory.parentFile
        }
        val sourceSnapshot = versionThree ?: error("Could not find the checked-in v3 schema snapshot from ${System.getProperty("user.dir")}")
        val migratedFile = File.createTempFile("cocktails-v3-to-v4", ".db")
        sourceSnapshot.copyTo(migratedFile, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePath}")
        try {
            driver.execute(null, "INSERT INTO household VALUES (?, ?, ?)", 3) {
                bindString(0, "household-v3"); bindString(1, "Our Bar"); bindLong(2, 1L)
            }
            driver.execute(null, "INSERT INTO household_member VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "member-v3"); bindString(1, "household-v3"); bindString(2, "You"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO household_location VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "home-v3"); bindString(1, "household-v3"); bindString(2, "Home"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO ingredient VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "gin-v3"); bindString(1, "Gin"); bindString(2, "Spirit"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO inventory_item VALUES (?, ?, ?, ?, ?, ?)", 6) {
                bindString(0, "stock-v3"); bindString(1, "home-v3"); bindString(2, "gin-v3")
                bindString(3, "HALF"); bindLong(4, 12L); bindString(5, "member-v3")
            }

            CocktailsDatabase.Schema.migrate(driver, 3, 5)
            val database = CocktailsDatabase(driver)
            assertEquals("stock-v3", database.householdQueries.selectInventoryId("home-v3", "gin-v3").executeAsOne())
            assertEquals("HALF", database.householdQueries.selectPantryForLocation("home-v3").executeAsOne().level)

            val repository = LocalCocktailRepository(database)
            assertTrue(repository.addRecipe("Gin Fizz", listOf("Gin"), "Shake and top.", RecipeStyle.LONG, nowMillis = 20))
            val recipeId = repository.recipes().single().id
            assertTrue(repository.markRecipeMade(recipeId, "member-v3", "home-v3", 30))
            assertEquals(recipeId, repository.recentlyMade().single().recipeId)
        } finally {
            driver.close()
            migratedFile.delete()
        }
    }

    @Test
    fun migratesPopulatedVersionTwoDatabaseThroughCurrentSchemaWithoutLosingPantryOrAddingPhotos() {
        var directory: File? = File(System.getProperty("user.dir")!!)
        var versionTwo: File? = null
        while (directory != null && versionTwo == null) {
            versionTwo = sequenceOf(
                File(directory, "shared/core/src/commonMain/sqldelight/databases/2.db"),
                File(directory, "src/commonMain/sqldelight/databases/2.db"),
            ).firstOrNull(File::isFile)
            directory = directory.parentFile
        }
        val sourceSnapshot = versionTwo ?: error("Could not find the checked-in v2 schema snapshot")
        val migratedFile = File.createTempFile("cocktails-v2-to-v4", ".db")
        sourceSnapshot.copyTo(migratedFile, overwrite = true)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${migratedFile.absolutePath}")
        try {
            driver.execute(null, "INSERT INTO household VALUES (?, ?, ?)", 3) {
                bindString(0, "household-v2"); bindString(1, "Our Bar"); bindLong(2, 1L)
            }
            driver.execute(null, "INSERT INTO household_member VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "member-v2"); bindString(1, "household-v2"); bindString(2, "You"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO household_location VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "home-v2"); bindString(1, "household-v2"); bindString(2, "Home"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO ingredient VALUES (?, ?, ?, ?)", 4) {
                bindString(0, "gin-v2"); bindString(1, "Gin"); bindString(2, "Spirit"); bindLong(3, 1L)
            }
            driver.execute(null, "INSERT INTO inventory_item VALUES (?, ?, ?, ?, ?, ?)", 6) {
                bindString(0, "stock-v2"); bindString(1, "home-v2"); bindString(2, "gin-v2")
                bindString(3, "LOW"); bindLong(4, 2L); bindString(5, "member-v2")
            }

            CocktailsDatabase.Schema.migrate(driver, 2, 5)
            val database = CocktailsDatabase(driver)
            val repository = LocalCocktailRepository(database)
            assertEquals("stock-v2", database.householdQueries.selectInventoryId("home-v2", "gin-v2").executeAsOne())
            assertEquals("LOW", repository.pantry("home-v2").single().level.name)
            assertTrue(repository.addRecipe("Gin Fizz", listOf("Gin"), "Shake and top", RecipeStyle.LONG, nowMillis = 10))
            val recipe = repository.recipes().single()
            assertTrue(repository.markRecipeMade(recipe.id, "member-v2", "home-v2", 20))
            assertEquals(recipe.id, repository.recentlyMade().single().recipeId)
        } finally {
            driver.close()
            migratedFile.delete()
        }
    }

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

        CocktailsDatabase.Schema.migrate(driver, 1, 5)
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

        val repository = LocalCocktailRepository(database)
        repository.addIngredient("Vodka", "Spirit", InventoryLevel.LOW, legacyLocationId, 20L)
        val vodkaId = database.householdQueries.selectIngredientsByName("Vodka").executeAsOne()
        val inventoryId = database.householdQueries.selectInventoryId(legacyLocationId, vodkaId).executeAsOne()
        val firstRevision = database.householdQueries.selectLatestRecordRevision(inventoryId, "INVENTORY_ITEM").executeAsOne()
        assertTrue(database.householdQueries.selectRevisionPayload(firstRevision).executeAsOne().contains("LOW"))

        repository.updateInventoryLevel(IngredientId(vodkaId), legacyLocationId, InventoryLevel.FULL, 30L)
        val secondRevision = database.householdQueries.selectLatestRecordRevision(inventoryId, "INVENTORY_ITEM").executeAsOne()
        assertEquals(firstRevision, database.householdQueries.selectRevisionParents(secondRevision).executeAsOne())

        repository.addRecipe("Sidecar", listOf("Brandy", "Lemon"), "Shake", RecipeStyle.SHORT, nowMillis = 40L)
        repository.addRecipe("sidecar", listOf("Whisky", "Lime"), "Stir", RecipeStyle.SHORT, nowMillis = 50L)
        val sameTitleFamilies = repository.recipeFamilies().filter { it.name.equals("Sidecar", ignoreCase = true) }
        assertEquals(2, sameTitleFamilies.size)
        repository.addRecipe("House Sidecar", listOf("Brandy", "Lemon"), "Shake", RecipeStyle.SHORT, sameTitleFamilies.first().familyId, 60L)
        assertEquals(2, repository.recipeFamilies().first { it.familyId == sameTitleFamilies.first().familyId }.variantCount)

        val partner = repository.addMember("Partner", 70L)!!
        repository.setPreferredMemberId(partner.id)
        val recipe = repository.recipes().first { it.familyId == sameTitleFamilies.first().familyId }
        repository.addTastingNote(recipe.id, partner.id, 5, "Balanced", legacyLocationId, 80L)
        assertEquals("Partner", repository.tastingNotes(recipe.id).single().memberName)

        val previousRecipeRevision = database.householdQueries
            .selectLatestRecordRevision(recipe.id.value, "RECIPE_VARIANT").executeAsOne()
        assertTrue(repository.archiveRecipeVariant(recipe.id, 90L))
        assertFalse(repository.recipes().any { it.id == recipe.id })
        assertEquals(1, repository.recipeFamilies().first { it.familyId == recipe.familyId }.variantCount)
        val tombstoneRevision = database.householdQueries.selectTombstone(recipe.id.value, "RECIPE_VARIANT").executeAsOne()
        assertEquals(previousRecipeRevision, database.householdQueries.selectRevisionParents(tombstoneRevision).executeAsOne())
        assertTrue(database.householdQueries.selectRevisionPayload(tombstoneRevision).executeAsOne().contains("\"deleted\":true"))
        assertFalse(repository.archiveRecipeVariant(recipe.id, 100L))
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
