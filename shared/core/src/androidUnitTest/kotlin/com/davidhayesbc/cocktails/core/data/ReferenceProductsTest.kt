package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import com.davidhayesbc.cocktails.core.model.*
import com.davidhayesbc.cocktails.core.interchange.*
import java.io.File
import kotlin.test.*

class ReferenceProductsTest {
    private val text get() = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "catalog-packs/bc-products/products.json") }.first { it.exists() }.readText()
    private val pack get() = ReferenceProductCodec.decode(text)
    private fun database(): Pair<JdbcSqliteDriver, CocktailsDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        return driver to CocktailsDatabase(driver)
    }

    @Test fun exactSnapshotSeedsOnlyReferencesAndSurvivesReopen() {
        val (driver, db) = database()
        val repo = LocalCocktailRepository(db).also { it.initialize(10) }
        val before = repo.exportHouseholdBundle(20)
        assertEquals(8224, repo.installReferenceProducts(text))
        assertEquals(0, repo.installReferenceProducts(text))
        assertEquals(8224L, db.referenceProductsQueries.countProducts().executeAsOne())
        assertEquals(before, repo.exportHouseholdBundle(20))
        assertTrue(repo.pantry(repo.locations().single().id).isEmpty())
        val reopened = LocalCocktailRepository(CocktailsDatabase(driver))
        for (barcode in listOf("003105474011", "0003105474011", "00003105474011")) {
            val match = reopened.localProductForBarcode(barcode)!!
            assertEquals("CASAMIGOS - ANEJO", match.productName)
            assertEquals("750 mL", match.quantity)
            assertEquals("40%", match.abvPercent)
            assertNull(match.productId)
            assertNotNull(match.referenceSource)
        }
        assertEquals(6988, pack.records.count { it.lookupEligible })
        assertEquals(1561, pack.records.count { it.lookupEligible && it.category == "Spirits" })
        assertFalse(pack.metadataIsOpen)
    }

    @Test fun shortCodesPackagingCollisionsAndInvalidDigitsCannotMatch() {
        val (_, db) = database(); val store = ReferenceProductStore(db); store.install(text)
        assertEquals("00003105474011", recoverNumericCatalogueGtin("3105474011"))
        assertNull(recoverNumericCatalogueGtin("3105474012"))
        assertNull(recoverNumericCatalogueGtin("3.105474011E9"))
        assertNull(normalizeGtinForLookup("3105474011")) // scanner rules stay strict
        for (code in listOf("89005439", "00000089005439", "00062067427152", "003105474012")) assertNull(store.lookup(code))
        val multi = pack.records.first { it.gtin14 != null && it.rawUpc.length > 8 && it.containers != "1" }
        assertNull(store.lookup(multi.gtin14!!))
        assertEquals(2, pack.records.count { it.gtin14 == "00062067427152" })
    }

    @Test fun upgradeIsAtomicRejectsDowngradeAndNeverChangesHouseholdEditsOrDrafts() {
        val (driver, db) = database(); val repo = LocalCocktailRepository(db).also { it.initialize(10) }
        val home = repo.locations().single().id
        repo.addIngredient("My tequila", "Spirit", InventoryLevel.HALF, home, 20, "003105474011", productName = "My name")
        val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, 30)
        repo.addBottleIntakeDraft(session.id, "My draft", "Spirit", home, InventoryLevel.LOW, 40,
            barcode = "003105474011", quantityEdited = true, abvPercentEdited = true, drinkCategory = "Custom")
        repo.installReferenceProducts(text)
        val before = repo.exportHouseholdBundle(50)
        val draft = repo.openBottleIntakeSession()!!.drafts.single()
        val changed = pack.copy(revision = 2, records = pack.records.map { if (it.recordId == "957365") it.copy(name = "Changed source") else it })
        assertEquals(8224, repo.installReferenceProducts(ReferenceProductCodec.encode(changed)))
        assertEquals("My name", repo.localProductForBarcode("00003105474011")!!.productName)
        assertEquals(before, repo.exportHouseholdBundle(50))
        assertEquals(draft, repo.openBottleIntakeSession()!!.drafts.single())
        assertFailsWith<IllegalArgumentException> { repo.installReferenceProducts(text) }
        val invalid = changed.copy(revision = 3, records = changed.records + changed.records.first())
        assertFailsWith<IllegalArgumentException> { repo.installReferenceProducts(ReferenceProductCodec.encode(invalid)) }
        assertEquals(8224L, db.referenceProductsQueries.countProducts().executeAsOne())
        // Simulate a storage failure after deletion to prove transaction rollback, not just prevalidation.
        driver.execute(null, "CREATE TRIGGER reject_reference BEFORE INSERT ON reference_product BEGIN SELECT RAISE(ABORT, 'fixture storage failure'); END", 0)
        assertFails { repo.installReferenceProducts(ReferenceProductCodec.encode(changed.copy(revision = 3))) }
        assertEquals(8224L, db.referenceProductsQueries.countProducts().executeAsOne())
        assertEquals(2L, db.referenceProductsQueries.catalog("bc-liquor-products").executeAsOne().revision)

    }

    @Test fun acceptedReferenceFactsRoundTripButManualClearsDoNotGainSourceFacts() {
        val (_, db) = database(); val repo = LocalCocktailRepository(db).also { it.initialize(10) }
        repo.installReferenceProducts(text)
        val home = repo.locations().single().id
        val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, 20)
        val match = repo.localProductForBarcode("003105474011")!!
        val draft = repo.addBottleIntakeDraft(session.id, match.ingredientName, match.category, home, InventoryLevel.FULL, 30,
            barcode = match.barcode, productName = match.productName, quantity = match.quantity, abvPercent = match.abvPercent)!!
        repo.updateBottleIntakeDraft(draft.copy(quantity = null, quantityEdited = true))
        assertIs<BottleIntakeCommitResult.Confirmed>(repo.confirmBottleIntakeSession(session.id, 40))
        val exported = repo.exportHouseholdBundle(50)
        assertTrue(exported.contains("BC Data Catalogue"))
        assertTrue(exported.contains(pack.attribution))
        assertTrue(exported.contains("2026-07"))
        assertTrue(exported.contains("3105474011"))
        assertNull(repo.localProductForBarcode(match.barcode)!!.quantity)
        assertFalse(exported.contains("reference_catalog"))
        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(exported)).bundle
        assertEquals(1, bundle.records.count { it.type == "PRODUCT" })
        val (_, restoredDb) = database()
        val restored = LocalCocktailRepository(restoredDb).also { it.initialize(60); it.installReferenceProducts(text) }
        val preview = assertIs<ImportPreviewResult.Valid>(restored.previewHouseholdBundle(bundle)).preview
        assertIs<ImportApplyResult.Applied>(restored.applyHouseholdBundle(bundle, preview, emptyMap(), 70))
        val owned = restored.localProductForBarcode(match.barcode)!!
        assertNotNull(owned.productId)
        assertNull(owned.quantity)
        assertNull(owned.referenceSource)
        assertTrue(restored.productFactSources(owned.productId!!).any { it.evidenceQuote.contains(pack.attribution) })
        assertFalse(restored.productFactSources(owned.productId!!).any { it.fieldName == "quantity" })
        assertEquals(8224L, restoredDb.referenceProductsQueries.countProducts().executeAsOne())
    }

    @Test fun equivalentBarcodeFormsReuseOwnedProductForSingleAndBatchConfirmation() {
        for (single in listOf(true, false)) {
            val (_, db) = database(); val repo = LocalCocktailRepository(db).also { it.initialize(10) }
            val home = repo.locations().single().id
            assertTrue(repo.addIngredient("My tequila", "Spirit", InventoryLevel.HALF, home, 20,
                "003105474011", productName = "My name"))
            val ownedId = repo.localProductForBarcode("003105474011")!!.productId
            val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, 30)
            for ((i, code) in listOf("0003105474011", "00003105474011").withIndex()) {
                val match = repo.localProductForBarcode(code)!!
                assertEquals(ownedId, match.productId)
                val draft = repo.addBottleIntakeDraft(session.id, match.ingredientName, match.category,
                    home, InventoryLevel.FULL, 40L + i, barcode = code, productName = match.productName)!!
                if (single) assertIs<BottleIntakeCommitResult.Accepted>(repo.acceptBottleIntakeDraft(session.id, draft.id, 50L + i))
            }
            if (!single) assertIs<BottleIntakeCommitResult.Confirmed>(repo.confirmBottleIntakeSession(session.id, 60))
            val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repo.exportHouseholdBundle(70))).bundle
            assertEquals(1, bundle.records.count { it.type == "PRODUCT" })
            assertEquals(ownedId, repo.localProductForBarcode("00003105474011")!!.productId)
            assertFalse(repo.addIngredient("Different ingredient", "Spirit", InventoryLevel.FULL, home, 80, "00003105474011"))
        }
    }

    @Test fun batchRejectsEquivalentBarcodeFormsAssignedToDifferentIngredients() {
        val (_, db) = database(); val repo = LocalCocktailRepository(db).also { it.initialize(10) }
        val home = repo.locations().single().id
        val session = repo.startBottleIntakeSession(home, InventoryLevel.FULL, 20)
        repo.addBottleIntakeDraft(session.id, "First", "Spirit", home, InventoryLevel.FULL, 30, barcode = "003105474011")
        repo.addBottleIntakeDraft(session.id, "Second", "Spirit", home, InventoryLevel.FULL, 40, barcode = "00003105474011")
        assertIs<BottleIntakeCommitResult.Invalid>(repo.confirmBottleIntakeSession(session.id, 50))
        assertTrue(repo.pantry(home).isEmpty())
        assertEquals(2, repo.openBottleIntakeSession()!!.drafts.size)
    }

    @Test fun malformedEligibleRowsCannotReplaceInstalledSnapshot() {
        val (_, db) = database(); val store = ReferenceProductStore(db); store.install(text)
        val row = pack.records.first { it.lookupEligible }
        val corrupt = listOf(row.copy(gtin14 = "00000000000000"), row.copy(rawUpc = "89005439", gtin14 = "00000089005439"),
            row.copy(abv = "101"), row.copy(litres = "NaN"), row.copy(containers = "6"))
        for (bad in corrupt) {
            assertFailsWith<IllegalArgumentException> { store.install(ReferenceProductCodec.encode(pack.copy(revision = 2,
                records = pack.records.map { if (it.recordId == row.recordId) bad else it }))) }
        }
        assertFailsWith<IllegalArgumentException> { store.install(ReferenceProductCodec.encode(pack.copy(revision = 2,
            records = pack.records + row.copy(recordId = "new-duplicate-key")))) }
        assertEquals(8224L, db.referenceProductsQueries.countProducts().executeAsOne())
        assertEquals(1L, db.referenceProductsQueries.catalog("bc-liquor-products").executeAsOne().revision)
    }

    @Test fun migrationAddsReferenceTablesWithoutChangingExistingHousehold() {
        val (driver, db) = database(); val repo = LocalCocktailRepository(db).also { it.initialize(10) }
        val before = repo.exportHouseholdBundle(20)
        driver.execute(null, "DROP TABLE reference_product", 0)
        driver.execute(null, "DROP TABLE reference_catalog", 0)
        CocktailsDatabase.Schema.migrate(driver, 10, 11)
        assertEquals(before, repo.exportHouseholdBundle(20))
        assertEquals(8224, repo.installReferenceProducts(text))
    }
}
