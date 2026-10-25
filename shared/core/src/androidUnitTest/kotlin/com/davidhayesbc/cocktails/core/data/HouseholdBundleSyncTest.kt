package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.core.interchange.BundleDecodeResult
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundleCodec
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.RecipeIngredientDraft
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
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
    fun concurrentRecipeEditsRemainReviewableAndMergedRevisionKeepsBothParents() {
        val leftDatabase = database()
        val rightDatabase = database()
        val left = LocalCocktailRepository(leftDatabase)
        val right = LocalCocktailRepository(rightDatabase)
        left.initialize(100)
        assertTrue(left.addRecipeLines(
            title = "Martini",
            ingredientLines = listOf(RecipeIngredientDraft("Gin", 60.0, "ml"), RecipeIngredientDraft("Vermouth", 10.0, "ml")),
            instructions = "Stir.", style = RecipeStyle.SHORT, servings = 1.0, nowMillis = 200,
        ))
        val original = left.recipes().single()
        val originalRevision = leftDatabase.householdQueries.selectLatestRecordRevision(original.id.value, "RECIPE_VARIANT").executeAsOne()
        val originalPayload = leftDatabase.householdQueries.selectRevisionPayload(originalRevision).executeAsOne()
        val originalGinLineId = leftDatabase.householdQueries.selectAllRecipeIngredientsForBundle().executeAsList()
            .single { it.variant_id == original.id.value && it.ingredient_id == original.ingredients.first().ingredientId.value }.id
        val baseline = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(300))).bundle
        right.initialize(400)
        val importPreview = assertIs<ImportPreviewResult.Valid>(right.previewHouseholdBundle(baseline)).preview
        assertIs<ImportApplyResult.Applied>(right.applyHouseholdBundle(baseline, importPreview, emptyMap(), 500))

        assertTrue(left.editRecipeVariant(
            original.id, "Dry Martini", listOf(RecipeIngredientDraft("Gin", 75.0, "ml"), RecipeIngredientDraft("Vermouth", 5.0, "ml")),
            "Stir until very cold.", 1.0, RecipeStyle.SHORT, 600,
        ))
        assertTrue(right.editRecipeVariant(
            original.id, "Shaken Martini", listOf(RecipeIngredientDraft("Gin", 60.0, "ml"), RecipeIngredientDraft("Vermouth", 15.0, "ml")),
            "Shake with ice.", 2.0, RecipeStyle.LONG, 700,
        ))

        val incoming = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(right.exportHouseholdBundle(800))).bundle
        val preview = assertIs<ImportPreviewResult.Valid>(left.previewHouseholdBundle(incoming)).preview
        val conflict = preview.conflicts.single { it.type == "RECIPE_VARIANT" && it.id == original.id.value }
        val merged = buildJsonObject {
            conflict.local!!.data.forEach { (key, value) -> put(key, value) }
            put("title", "Dry Martini (partner method)")
            put("instructions", "Stir, then shake once to chill.")
        }
        assertIs<ImportApplyResult.Applied>(left.applyHouseholdBundle(
            incoming, preview, mapOf(conflict.key to ConflictResolution(ConflictChoice.MERGE_FIELDS, merged, false)), 900,
        ))

        val result = left.recipes().single()
        assertEquals("Dry Martini (partner method)", result.title)
        assertEquals("Stir, then shake once to chill.", result.instructions)
        assertEquals(1.0, result.servings)
        assertEquals(listOf(75.0, 5.0), result.ingredients.map { it.amount })
        assertEquals(original.familyId, result.familyId)
        assertEquals(originalGinLineId, leftDatabase.householdQueries.selectAllRecipeIngredientsForBundle().executeAsList()
            .single { it.variant_id == original.id.value && it.ingredient_id == original.ingredients.first().ingredientId.value }.id)
        assertEquals(originalPayload, leftDatabase.householdQueries.selectRevisionPayload(originalRevision).executeAsOne())
        val mergedRevision = leftDatabase.householdQueries.selectLatestRecordRevision(original.id.value, "RECIPE_VARIANT").executeAsOne()
        assertEquals(setOf(conflict.localHeadIds.single(), conflict.incomingHeadIds.single()), leftDatabase.householdQueries.selectRevisionParents(mergedRevision).executeAsList().toSet())
    }

    @Test
    fun duplicatingRecipeKeepsMeasurementsFamilyAndSourceAttribution() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        val database = CocktailsDatabase(driver)
        val repository = LocalCocktailRepository(database)
        repository.initialize(1_000)
        assertTrue(repository.addRecipeLines(
            title = "House Martini", ingredientLines = listOf(RecipeIngredientDraft("Gin", 50.0, "ml", optional = true)),
            instructions = "Stir gently.", style = RecipeStyle.SHORT, servings = 2.0, nowMillis = 2_000,
        ))
        val original = repository.recipes().single()
        database.householdQueries.insertRecipeSource("source-row", original.id.value, "Example book", "record-1", "https://example.test/recipe", "CC BY 4.0", "Author", "normalized measures", 2_100)

        val duplicateId = repository.duplicateRecipeVariant(original.id, 3_000)!!
        val duplicate = repository.recipes().single { it.id == duplicateId }
        assertTrue(duplicate.id != original.id)
        assertEquals(original.familyId, duplicate.familyId)
        assertEquals("House Martini (copy)", duplicate.title)
        assertEquals(original.instructions, duplicate.instructions)
        assertEquals(original.servings, duplicate.servings)
        assertEquals(original.ingredients.map { it.name to it.amount }, duplicate.ingredients.map { it.name to it.amount })
        assertEquals("Example book", database.householdQueries.selectRecipeSourcesForVariant(duplicateId.value).executeAsOne().source_name)
        driver.close()
    }

    @Test
    fun favoriteRemovalUsesAConflictVisibleTombstoneAgainstStaleBackup() {
        val leftDatabase = database()
        val rightDatabase = database()
        val left = LocalCocktailRepository(leftDatabase)
        val right = LocalCocktailRepository(rightDatabase)
        left.initialize(10)
        assertTrue(left.addRecipe("Negroni", listOf("Gin"), "Stir.", RecipeStyle.BITTER, nowMillis = 20))
        val familyId = left.recipes().single().familyId
        val memberId = left.members().single().id
        assertTrue(left.setFamilyFavorite(memberId, familyId, true, 30))
        val originalBackup = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(40))).bundle
        right.initialize(50)
        val importPreview = assertIs<ImportPreviewResult.Valid>(right.previewHouseholdBundle(originalBackup)).preview
        assertIs<ImportApplyResult.Applied>(right.applyHouseholdBundle(originalBackup, importPreview, emptyMap(), 60))
        assertTrue(right.isFamilyFavorite(memberId, familyId))
        assertTrue(right.setFamilyFavorite(memberId, familyId, false, 65))
        assertTrue(right.setFamilyFavorite(memberId, familyId, true, 66))
        val concurrentFavoriteBackup = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(right.exportHouseholdBundle(67))).bundle

        assertTrue(left.setFamilyFavorite(memberId, familyId, false, 70))
        assertFalse(left.isFamilyFavorite(memberId, familyId))
        val stalePreview = assertIs<ImportPreviewResult.Valid>(left.previewHouseholdBundle(concurrentFavoriteBackup)).preview
        val favoriteConflict = stalePreview.conflicts.single { it.type == "FAVORITE" }
        assertTrue(favoriteConflict.localDeleted)
        assertFalse(favoriteConflict.incomingDeleted)
        assertIs<ImportApplyResult.Applied>(left.applyHouseholdBundle(
            concurrentFavoriteBackup,
            stalePreview,
            mapOf(favoriteConflict.key to ConflictResolution(ConflictChoice.KEEP_LOCAL)),
            80,
        ))
        assertFalse(left.isFamilyFavorite(memberId, familyId))
        val oldBackupPreview = assertIs<ImportPreviewResult.Valid>(left.previewHouseholdBundle(originalBackup)).preview
        assertTrue(oldBackupPreview.conflicts.none { it.type == "FAVORITE" })
        assertIs<ImportApplyResult.Applied>(left.applyHouseholdBundle(originalBackup, oldBackupPreview, emptyMap(), 85))
        assertFalse(left.isFamilyFavorite(memberId, familyId))
        val archivedBackup = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(90))).bundle
        assertTrue(archivedBackup.tombstones.any { it.recordType == "FAVORITE" })
        assertEquals(1, archivedBackup.records.count { it.type == "FAVORITE" })
    }

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

    @Test
    fun measuredRecipeLinesAndYieldSurviveHouseholdExportImport() {
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        CocktailsDatabase.Schema.create(targetDriver)
        val source = LocalCocktailRepository(CocktailsDatabase(sourceDriver))
        val target = LocalCocktailRepository(CocktailsDatabase(targetDriver))
        source.initialize(10)
        assertTrue(source.addRecipeLines(
            title = "Gimlet",
            ingredientLines = listOf(
                RecipeIngredientDraft("Gin", 60.0, "ml"),
                RecipeIngredientDraft("Lime juice", 25.0, "ml"),
            ),
            instructions = "Shake with ice.",
            style = RecipeStyle.SOUR,
            servings = 2.0,
            nowMillis = 20,
        ))
        val sourceRecipe = source.recipes().single()
        assertTrue(source.markRecipeMade(sourceRecipe.id, source.members().single().id, source.locations().single().id, 25))

        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(source.exportHouseholdBundle(30))).bundle
        val preview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
        assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(bundle, preview, emptyMap(), 40))
        val recipe = target.recipes().single()
        assertEquals(2.0, recipe.servings)
        assertEquals(listOf(60.0, 25.0), recipe.ingredients.map { it.amount })
        assertEquals(listOf("ml", "ml"), recipe.ingredients.map { it.unit })
        assertEquals("Gimlet", target.recentlyMade().single().title)
        assertEquals(25L, target.recentlyMade().single().madeAt)
        sourceDriver.close()
        targetDriver.close()
    }

    @Test
    fun automaticHomeAreasPersistLocallyAndStayOutOfHouseholdBackups() {
        val database = database()
        val repository = LocalCocktailRepository(database)
        repository.initialize(10)
        val homeId = repository.locations().single().id

        assertTrue(repository.setHomeLocationArea(homeId, 37.123456, -122.987654, 350.0))
        repository.setAutomaticHomeSelectionEnabled(true)
        repository.setAutomaticHomeSelectionOverridden(true)

        val reopenedRepository = LocalCocktailRepository(database)
        assertTrue(reopenedRepository.automaticHomeSelectionEnabled())
        assertTrue(reopenedRepository.automaticHomeSelectionOverridden())
        assertEquals(350.0, reopenedRepository.homeLocationAreas().single().radiusMeters)
        assertEquals(false, reopenedRepository.setHomeLocationArea(homeId, 91.0, 0.0, 350.0))
        assertEquals(false, reopenedRepository.setHomeLocationArea(homeId, 0.0, 0.0, 10.0))

        val backup = reopenedRepository.exportHouseholdBundle(20)
        assertFalse(backup.contains("home_area:"))
        assertFalse(backup.contains("automatic_home_selection"))
        assertFalse(backup.contains("37.123456"))
        assertFalse(backup.contains("-122.987654"))

        reopenedRepository.setAutomaticHomeSelectionEnabled(false)
        assertFalse(reopenedRepository.automaticHomeSelectionOverridden())
    }

    private fun database(): CocktailsDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        return CocktailsDatabase(driver)
    }
}
