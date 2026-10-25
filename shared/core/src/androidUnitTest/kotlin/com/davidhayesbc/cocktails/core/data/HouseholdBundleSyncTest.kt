package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.core.interchange.BundleDecodeResult
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundleCodec
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HouseholdBundleSyncTest {
    @Test
    fun twoDatabasesPreserveSkewedConcurrentEditsAndMergeBothParents() {
        val left = database()
        val right = database()
        val leftRepo = LocalCocktailRepository(left)
        val rightRepo = LocalCocktailRepository(right)
        leftRepo.initialize(1_000)
        val leftMember = leftRepo.members().single().id
        val partner = leftRepo.addMember("Partner", 1_100)!!
        val location = leftRepo.locations().single().id
        assertTrue(leftRepo.addIngredient("Gin", "Spirit", InventoryLevel.FULL, location, 2_000))
        assertTrue(leftRepo.addRecipe("Dry Martini", listOf("Gin"), "Stir with ice", RecipeStyle.SHORT, nowMillis = 3_000))
        val recipe = leftRepo.recipes().single()
        leftRepo.addTastingNote(recipe.id, leftMember, 4, "Bright and dry", location, 4_000)
        leftRepo.addTastingNote(recipe.id, leftMember, 4, "", location, 4_001)
        val initialText = leftRepo.exportHouseholdBundle(5_000)
        val initial = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(initialText)).bundle

        rightRepo.initialize(6_000)
        val initialPreview = assertIs<ImportPreviewResult.Valid>(rightRepo.previewHouseholdBundle(initial)).preview
        assertTrue(initialPreview.additions > 0)
        assertIs<ImportApplyResult.Applied>(rightRepo.applyHouseholdBundle(initial, initialPreview, emptyMap(), 7_000))
        assertEquals(listOf("Gin"), rightRepo.pantry(location).map { it.name })
        assertTrue(rightRepo.tastingNotes(recipe.id).any { it.rating == 4 && it.note.isEmpty() })

        // A slow clock on the right must not make its edit disappear behind the later left timestamp.
        val ginId = left.householdQueries.selectIngredientsByName("Gin").executeAsOne()
        val rightPartner = rightRepo.members().first { it.name == "Partner" }
        rightRepo.updateInventoryLevel(IngredientId(ginId), location, InventoryLevel.LOW, 20_000)
        rightRepo.addTastingNote(recipe.id, rightPartner.id, 5, "Partner prefers it colder", location, 21_000)
        leftRepo.updateInventoryLevel(IngredientId(ginId), location, InventoryLevel.HALF, 900_000)
        leftRepo.addTastingNote(recipe.id, leftMember, 3, "Dilute a little more", location, 900_001)

        // Preview/cancel is read-only and bundle data excludes local-only settings.
        left.householdQueries.setLocalPreference("api_key", "secret-canary")
        left.householdQueries.setLocalPreference("latitude", "coordinate-canary")
        left.householdQueries.setLocalPreference("location_radius", "radius-canary")
        val localBundleText = leftRepo.exportHouseholdBundle(21_999)
        assertFalse(localBundleText.contains("secret-canary"))
        assertFalse(localBundleText.contains("coordinate-canary"))
        assertFalse(localBundleText.contains("radius-canary"))
        val rightBundleText = rightRepo.exportHouseholdBundle(22_000)
        val rightBundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(rightBundleText)).bundle
        val inventoryId = left.householdQueries.selectInventoryId(location, ginId).executeAsOne()
        val beforeCancel = leftRepo.pantry(location).single().level
        val preview = assertIs<ImportPreviewResult.Valid>(leftRepo.previewHouseholdBundle(rightBundle)).preview
        assertEquals(1, preview.conflicts.count { it.type == "INVENTORY_ITEM" && it.id == inventoryId })
        assertEquals(beforeCancel, leftRepo.pantry(location).single().level)

        val conflict = preview.conflicts.single { it.type == "INVENTORY_ITEM" && it.id == inventoryId }
        val mergedInventory = buildJsonObject {
            conflict.local!!.data.forEach { (key, value) -> put(key, value) }
            put("level", "LOW")
            put("updatedAt", 900_000L) // preserve local device's later clock metadata
        }
        val applied = assertIs<ImportApplyResult.Applied>(leftRepo.applyHouseholdBundle(
            rightBundle,
            preview,
            mapOf(conflict.key to ConflictResolution(ConflictChoice.MERGE_FIELDS, mergedInventory, false)),
            900_002,
        ))
        assertEquals(1, applied.resolvedConflicts)
        assertEquals(InventoryLevel.LOW, leftRepo.pantry(location).single().level)
        assertEquals(setOf("", "Bright and dry", "Partner prefers it colder", "Dilute a little more"), leftRepo.tastingNotes(recipe.id).map { it.note }.toSet())

        val mergedRevisionId = left.householdQueries.selectLatestRecordRevision(inventoryId, "INVENTORY_ITEM").executeAsOne()
        val mergeParents = left.householdQueries.selectRevisionParents(mergedRevisionId).executeAsList().toSet()
        assertEquals(setOf(conflict.localHeadIds.single(), conflict.incomingHeadIds.single()), mergeParents)

        val revisionsBeforeReimport = left.householdQueries.selectAllRevisionsForBundle().executeAsList().size
        val reimportPreview = assertIs<ImportPreviewResult.Valid>(leftRepo.previewHouseholdBundle(rightBundle)).preview
        assertFalse(reimportPreview.conflicts.any { it.id == inventoryId })
        assertTrue(reimportPreview.changes.filter { it.id == inventoryId }.all { it.kind == BundleChangeKind.UNCHANGED })
        assertIs<ImportApplyResult.Applied>(leftRepo.applyHouseholdBundle(rightBundle, reimportPreview, emptyMap(), 900_003))
        assertEquals(revisionsBeforeReimport, left.householdQueries.selectAllRevisionsForBundle().executeAsList().size)
        assertEquals(4, leftRepo.tastingNotes(recipe.id).size)

    }

    @Test
    fun invalidReferencesAreRejectedBeforeAnyImportWrites() {
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        val source = LocalCocktailRepository(CocktailsDatabase(sourceDriver))
        source.initialize(10)
        assertTrue(source.addIngredient("Gin", "Spirit", InventoryLevel.FULL, source.locations().single().id, 20))
        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(source.exportHouseholdBundle(30))).bundle
        val broken = bundle.copy(records = bundle.records.map { record ->
            if (record.type == "INVENTORY_ITEM") record.copy(data = buildJsonObject {
                record.data.forEach { (key, value) -> put(key, value) }
                put("ingredientId", "missing-ingredient")
            }) else record
        })

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(targetDriver)
        val target = LocalCocktailRepository(CocktailsDatabase(targetDriver))
        target.initialize(40)
        assertIs<ImportPreviewResult.Invalid>(target.previewHouseholdBundle(broken))
        assertTrue(target.pantry(target.locations().single().id).isEmpty())
        sourceDriver.close()
        targetDriver.close()
    }

    @Test
    fun databaseFailureRollsBackTheWholeImport() {
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        val sourceDb = CocktailsDatabase(sourceDriver)
        val source = LocalCocktailRepository(sourceDb)
        source.initialize(10)
        assertTrue(source.addIngredient("Gin", "Spirit", InventoryLevel.FULL, source.locations().single().id, 20))
        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(source.exportHouseholdBundle(30))).bundle

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(targetDriver)
        targetDriver.execute(null, "CREATE TRIGGER reject_bundle_ingredient BEFORE INSERT ON ingredient BEGIN SELECT RAISE(ABORT, 'forced import failure'); END", 0)
        val targetDb = CocktailsDatabase(targetDriver)
        val target = LocalCocktailRepository(targetDb)
        val preview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
        val result = assertIs<ImportApplyResult.Invalid>(target.applyHouseholdBundle(bundle, preview, emptyMap(), 40))
        assertTrue(result.reason.contains("rolled back", ignoreCase = true))
        assertEquals(0, targetDb.householdQueries.selectAllHouseholdsForBundle().executeAsList().size)
        assertEquals(0, targetDb.householdQueries.selectAllMembersForBundle().executeAsList().size)
        sourceDriver.close()
        targetDriver.close()
    }

    @Test
    fun codecRejectsUnsupportedVersionsAndOversizedFiles() {
        val unsupported = HouseholdBundleCodec.decode("""{"format":"com.davidhayesbc.cocktails.household","formatVersion":4294967297}""")
        assertIs<BundleDecodeResult.Invalid>(unsupported)
        val oversized = HouseholdBundleCodec.decode(" ".repeat(10 * 1024 * 1024 + 1))
        assertIs<BundleDecodeResult.Invalid>(oversized)
    }

    @Test
    fun localEditRacingThePreviewCommitBoundaryIsNotOverwritten() {
        val left = database()
        val right = database()
        val local = LocalCocktailRepository(left)
        val remote = LocalCocktailRepository(right)
        local.initialize(10)
        val location = local.locations().single().id
        assertTrue(local.addIngredient("Gin", "Spirit", InventoryLevel.FULL, location, 20))
        val initial = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(local.exportHouseholdBundle(30))).bundle
        remote.initialize(40)
        val initialPreview = assertIs<ImportPreviewResult.Valid>(remote.previewHouseholdBundle(initial)).preview
        assertIs<ImportApplyResult.Applied>(remote.applyHouseholdBundle(initial, initialPreview, emptyMap(), 50))

        val ginId = left.householdQueries.selectIngredientsByName("Gin").executeAsOne()
        remote.updateInventoryLevel(IngredientId(ginId), location, InventoryLevel.LOW, 60)
        local.updateInventoryLevel(IngredientId(ginId), location, InventoryLevel.HALF, 70)
        val incoming = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(remote.exportHouseholdBundle(70))).bundle
        val preview = assertIs<ImportPreviewResult.Valid>(local.previewHouseholdBundle(incoming)).preview
        val conflict = preview.conflicts.single { it.type == "INVENTORY_ITEM" }
        val store = HouseholdBundleStore(left) {
            local.updateInventoryLevel(IngredientId(ginId), location, InventoryLevel.MOSTLY_FULL, 80)
        }

        val result = assertIs<ImportApplyResult.Invalid>(store.apply(
            incoming,
            preview,
            mapOf(conflict.key to ConflictResolution(ConflictChoice.USE_INCOMING)),
            90,
        ))
        assertTrue(result.reason.contains("changed after preview"))
        assertEquals(InventoryLevel.MOSTLY_FULL, local.pantry(location).single().level)
    }

    @Test
    fun reimportingOldLiveBackupPreservesOriginalArchiveTombstone() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        val database = CocktailsDatabase(driver)
        val repository = LocalCocktailRepository(database)
        repository.initialize(100)
        assertTrue(repository.addRecipe("Daiquiri", listOf("Rum", "Lime"), "Shake", RecipeStyle.SOUR, nowMillis = 200))
        val recipe = repository.recipes().single()
        val oldLiveBackup = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repository.exportHouseholdBundle(300))).bundle
        assertTrue(repository.archiveRecipeVariant(recipe.id, 400))
        val originalTombstone = database.householdQueries.selectAllTombstonesForBundle().executeAsList().single()
        val originalRevisionCount = database.householdQueries.selectAllRevisionsForBundle().executeAsList().size

        val preview = assertIs<ImportPreviewResult.Valid>(repository.previewHouseholdBundle(oldLiveBackup)).preview
        assertIs<ImportApplyResult.Applied>(repository.applyHouseholdBundle(oldLiveBackup, preview, emptyMap(), 50_000))
        val tombstoneAfter = database.householdQueries.selectAllTombstonesForBundle().executeAsList().single()
        assertEquals(originalTombstone.revision_id, tombstoneAfter.revision_id)
        assertEquals(originalTombstone.deleted_at, tombstoneAfter.deleted_at)
        assertEquals(originalRevisionCount, database.householdQueries.selectAllRevisionsForBundle().executeAsList().size)
        assertFalse(repository.recipes().any { it.id == recipe.id })
        driver.close()
    }

    @Test
    fun combinedImportCannotExceedHouseholdBackupLimit() {
        val leftDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val rightDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(leftDriver)
        CocktailsDatabase.Schema.create(rightDriver)
        val leftDb = CocktailsDatabase(leftDriver)
        val rightDb = CocktailsDatabase(rightDriver)
        val left = LocalCocktailRepository(leftDb)
        val right = LocalCocktailRepository(rightDb)
        left.initialize(1)
        val memberId = left.members().single().id
        val location = left.locations().single().id
        assertTrue(left.addRecipe("House Sour", listOf("Gin"), "Shake", RecipeStyle.SOUR, nowMillis = 2))
        val recipe = left.recipes().single()
        val baseline = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(3))).bundle
        right.initialize(4)
        val baselinePreview = assertIs<ImportPreviewResult.Valid>(right.previewHouseholdBundle(baseline)).preview
        assertIs<ImportApplyResult.Applied>(right.applyHouseholdBundle(baseline, baselinePreview, emptyMap(), 5))

        val longNote = "x".repeat(4_600_000)
        left.addTastingNote(recipe.id, memberId, 4, longNote, location, 10)
        right.addTastingNote(recipe.id, memberId, 5, longNote, location, 11)
        val incoming = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(right.exportHouseholdBundle(12))).bundle
        val localBackup = left.exportHouseholdBundle(13)
        assertTrue(incoming.let { HouseholdBundleCodec.encode(it).encodeToByteArray().size < 10 * 1024 * 1024 })
        assertTrue(localBackup.encodeToByteArray().size < 10 * 1024 * 1024)

        val preview = assertIs<ImportPreviewResult.Valid>(left.previewHouseholdBundle(incoming)).preview
        assertTrue(preview.conflicts.isEmpty())
        val result = assertIs<ImportApplyResult.Invalid>(left.applyHouseholdBundle(incoming, preview, emptyMap(), 14))
        assertTrue(result.reason.contains("combined household backup would exceed 10 MiB"))
        assertEquals(1, left.tastingNotes(recipe.id).size)
        assertTrue(HouseholdBundleCodec.decode(left.exportHouseholdBundle(15)) is BundleDecodeResult.Valid)
        leftDriver.close()
        rightDriver.close()
    }

    private fun database(): CocktailsDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        return CocktailsDatabase(driver)
    }
}
