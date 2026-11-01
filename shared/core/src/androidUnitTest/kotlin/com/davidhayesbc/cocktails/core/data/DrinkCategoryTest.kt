package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.core.interchange.*
import com.davidhayesbc.cocktails.core.model.*
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.*
import kotlin.test.*

class DrinkCategoryTest {
    @Test
    fun draftRestorationAcceptancePerBottleEditingAndBackupPreserveCustomAndClearedCategories() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CocktailsDatabase.Schema.create(driver)
            val database = CocktailsDatabase(driver)
            val repo = LocalCocktailRepository(database)
            repo.initialize(10)
            val home = repo.locations().single().id
            val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, 20)
            val first = repo.addBottleIntakeDraft(session.id, "Rum", "Spirit", home, InventoryLevel.FULL, 30, drinkCategory = "White Rum")!!
            val second = repo.addBottleIntakeDraft(session.id, "Rum", "Spirit", home, InventoryLevel.FULL, 40, drinkCategory = "Dark Rum")!!
            assertTrue(repo.updateBottleIntakeDrinkCategory(session.id, first.id, "Agricole blanc"))
            var restored = LocalCocktailRepository(database).openBottleIntakeSession()!!.drafts
            assertEquals(listOf("Agricole blanc", "Dark Rum"), restored.map { it.drinkCategory })
            assertTrue(repo.updateBottleIntakeDrinkCategory(session.id, first.id, ""))
            restored = repo.openBottleIntakeSession()!!.drafts
            // A later enrichment update copies the persisted draft and does not fill a cleared category.
            assertTrue(repo.updateBottleIntakeDraft(first.copy(brand = "Example")))
            assertEquals("", repo.openBottleIntakeSession()!!.drafts.first().drinkCategory)
            assertIs<BottleIntakeCommitResult.Confirmed>(repo.confirmBottleIntakeSession(session.id, 50))
            val bottles = repo.pantry(home)
            assertEquals(2, bottles.size)
            assertEquals(1, bottles.map { it.ingredientId }.distinct().size)
            val dark = bottles.single { it.drinkCategory == "Dark Rum" }
            val clear = bottles.single { it.drinkCategory.isEmpty() }
            assertTrue(repo.updateBottleDrinkCategory(dark.inventoryId, "Crème de pêche maison", 60))
            assertTrue(repo.updateBottleLevel(dark.inventoryId, InventoryLevel.HALF, 70))
            assertEquals("", repo.pantry(home).single { it.inventoryId == clear.inventoryId }.drinkCategory)
            assertTrue(repo.pantry(home).all { it.category == "Spirit" })
            val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repo.exportHouseholdBundle(80))).bundle
            CocktailsDatabase.Schema.create(targetDriver)
            val target = LocalCocktailRepository(CocktailsDatabase(targetDriver))
            target.initialize(90)
            val preview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
            assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(bundle, preview, emptyMap(), 100))
            assertEquals(repo.pantry(home).associate { it.inventoryId to it.drinkCategory }, target.pantry(home).associate { it.inventoryId to it.drinkCategory })
            assertEquals(InventoryLevel.HALF, target.pantry(home).single { it.inventoryId == dark.inventoryId }.level)
            // Legacy backups without the optional key still decode; malformed new values do not.
            val legacy = bundle.copy(records = bundle.records.map { record ->
                if (record.type == "INVENTORY_ITEM") record.copy(data = JsonObject(record.data - "drinkCategory")) else record
            })
            assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(HouseholdBundleCodec.encode(legacy)))
            val legacyPreview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(legacy)).preview
            assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(legacy, legacyPreview,
                legacyPreview.conflicts.associate { it.key to ConflictResolution(ConflictChoice.USE_INCOMING) }, 110))
            assertEquals("Crème de pêche maison", target.pantry(home).single { it.inventoryId == dark.inventoryId }.drinkCategory)
            assertTrue(repo.updateBottleDrinkCategory(dark.inventoryId, "", 120))
            val cleared = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repo.exportHouseholdBundle(130))).bundle
            val clearPreview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(cleared)).preview
            assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(cleared, clearPreview,
                clearPreview.conflicts.associate { it.key to ConflictResolution(ConflictChoice.USE_INCOMING) }, 140))
            assertEquals("", target.pantry(home).single { it.inventoryId == dark.inventoryId }.drinkCategory)
            val bad = bundle.copy(records = bundle.records.map { record ->
                if (record.type == "INVENTORY_ITEM") record.copy(data = JsonObject(record.data + ("drinkCategory" to JsonPrimitive(42)))) else record
            })
            assertIs<ImportPreviewResult.Invalid>(target.previewHouseholdBundle(bad))
        } finally { driver.close(); targetDriver.close() }
    }

    @Test
    fun manualCreationStoresCategoryAndClearWithoutChangingIngredientGroup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CocktailsDatabase.Schema.create(driver)
            val repo = LocalCocktailRepository(CocktailsDatabase(driver))
            repo.initialize(10)
            val home = repo.locations().single().id
            assertTrue(repo.addIngredient("Tonic", "Mixer", InventoryLevel.FULL, home, 20, drinkCategory = "Tonic Water"))
            val item = repo.pantry(home).single()
            assertEquals("Tonic Water", item.drinkCategory)
            assertTrue(repo.updateBottleDrinkCategory(item.inventoryId, "", 30))
            assertEquals("", repo.pantry(home).single().drinkCategory)
            assertEquals("Mixer", repo.pantry(home).single().category)
            assertFalse(repo.updateBottleDrinkCategory("missing", "Gin", 40))
        } finally { driver.close() }
    }
}
