package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.core.interchange.BundleDecodeResult
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundleCodec
import com.davidhayesbc.cocktails.core.interchange.encodePhotoBase64
import com.davidhayesbc.cocktails.core.interchange.photoSha256
import com.davidhayesbc.cocktails.core.interchange.RecipeImportApplyResult
import com.davidhayesbc.cocktails.core.interchange.RecipeImportMapping
import com.davidhayesbc.cocktails.core.interchange.RecipeImportProvenance
import com.davidhayesbc.cocktails.core.interchange.RecipeTableParseResult
import com.davidhayesbc.cocktails.core.interchange.MAX_PRODUCT_PHOTO_BYTES
import com.davidhayesbc.cocktails.core.interchange.parseRecipeImportTable
import com.davidhayesbc.cocktails.core.interchange.previewRecipeImport
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.RecipeIngredientDraft
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HouseholdBundleSyncTest {
    @Test
    fun genericRecipeImportRequiresExplicitFamilyLinkAndIsIdempotentWithProvenance() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        val database = CocktailsDatabase(driver)
        val repository = LocalCocktailRepository(database)
        repository.initialize(10)
        assertTrue(repository.addRecipe("Daiquiri", listOf("White rum", "Lime"), "House method", RecipeStyle.SOUR, nowMillis = 20))
        val originalFamily = repository.recipeFamilies().single().familyId
        val parsed = assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(
            """title,ingredients,family,source_id,yield,style,instructions
Daiquiri,"Rum | 60 | ml; Lime | 25 | ml",Daiquiri,book-1,2,SOUR,Shake""",
            "book.csv",
        )).table
        val mapping = RecipeImportMapping("title", "ingredients", "instructions", "yield", "style", "family", "source_id")
        val preview = previewRecipeImport(parsed, mapping, RecipeImportProvenance("Test book", "https://example.test", "CC BY 4.0", "Recipe author"))
        assertTrue(preview.canApply)
        assertEquals(1, repository.recipeFamilies().size)
        val groupKey = preview.recipes.single().familyGroupKey

        val first = assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(preview,
            RecipeImportProvenance("Test book", "https://example.test", "CC BY 4.0", "Recipe author"),
            mapOf(groupKey to originalFamily), 30))
        assertEquals(1, first.imported)
        assertEquals(0, first.skippedDuplicates)
        assertEquals(1, repository.recipeFamilies().size)
        val sourceRecord = database.householdQueries.selectAllSourcesForBundle().executeAsList().single { it.source_record_id == "book-1" }
        val imported = repository.recipes().single { it.id.value == sourceRecord.variant_id }
        assertEquals(originalFamily, imported.familyId)
        assertEquals(2.0, imported.servings)
        assertEquals(listOf("ml", "ml"), imported.ingredients.map { it.unit })
        val source = database.householdQueries.selectRecipeSourcesForVariant(imported.id.value).executeAsOne()
        assertEquals("book-1", source.source_record_id)
        assertEquals("CC BY 4.0", source.license_name)
        assertEquals("Recipe author", source.attribution)

        val repeated = assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(preview,
            RecipeImportProvenance("Test book", "https://example.test", "CC BY 4.0", "Recipe author"),
            mapOf(groupKey to originalFamily), 40))
        assertEquals(0, repeated.imported)
        assertEquals(1, repeated.skippedDuplicates)
        assertEquals(originalFamily, repository.recipes().single { it.id == imported.id }.familyId)

        val backup = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repository.exportHouseholdBundle(50))).bundle
        val partnerDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(partnerDriver)
        val partnerDatabase = CocktailsDatabase(partnerDriver)
        val partner = LocalCocktailRepository(partnerDatabase)
        partner.initialize(60)
        val backupPreview = assertIs<ImportPreviewResult.Valid>(partner.previewHouseholdBundle(backup)).preview
        assertIs<ImportApplyResult.Applied>(partner.applyHouseholdBundle(backup, backupPreview, emptyMap(), 70))
        val partnerSource = partnerDatabase.householdQueries.selectAllSourcesForBundle().executeAsList().single { it.source_record_id == "book-1" }
        assertEquals("Test book", partnerSource.source_name)
        assertEquals("CC BY 4.0", partnerSource.license_name)
        val partnerVariant = partner.recipes().single { it.id.value == partnerSource.variant_id }
        assertEquals(listOf("ml", "ml"), partnerVariant.ingredients.map { it.unit })
        partnerDriver.close()
        driver.close()
    }

    @Test
    fun changedSourceRecordRejectsWholeRecipeImportBatchWithoutPartialWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        val database = CocktailsDatabase(driver)
        val repository = LocalCocktailRepository(database)
        repository.initialize(10)
        val provenance = RecipeImportProvenance("Personal CSV", licenseName = "Personal notes", attribution = "Me")
        fun stage(csv: String) = previewRecipeImport(
            assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(csv, "recipes.csv")).table,
            RecipeImportMapping("title", "ingredients", sourceRecordIdField = "id"), provenance,
        )
        val original = stage("""title,ingredients,id
Martini,"Gin | 60 | ml",stable-1""")
        assertTrue(original.canApply)
        assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(original, provenance, emptyMap(), 20))
        val changedAndNew = stage("""title,ingredients,id
Martini,"Vodka | 60 | ml",stable-1
New drink,"Rum | 45 | ml",new-2""")
        assertTrue(changedAndNew.canApply)
        assertIs<RecipeImportApplyResult.Invalid>(repository.applyRecipeImport(changedAndNew, provenance, emptyMap(), 30))
        assertEquals(1, repository.recipes().size)
        assertEquals("Gin", repository.recipes().single().ingredients.single().name)
        driver.close()
    }

    @Test
    fun secondSourceCanBeExplicitlyAttachedToCompatibleVariantAndSurvivesRepeatAndBackup() {
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        val sourceDatabase = CocktailsDatabase(sourceDriver)
        val repository = LocalCocktailRepository(sourceDatabase)
        repository.initialize(10)
        assertTrue(repository.addRecipeLines(
            title = "Martini",
            ingredientLines = listOf(RecipeIngredientDraft("Gin", 60.0, "ml"), RecipeIngredientDraft("Vermouth", 10.0, "ml")),
            instructions = "House method", style = RecipeStyle.SHORT, servings = 1.0, nowMillis = 20,
        ))
        val original = repository.recipes().single()
        val memberId = repository.members().single().id
        repository.addTastingNote(original.id, memberId, 5, "Very dry", null, 25)
        val revisionsBefore = sourceDatabase.householdQueries.selectAllRevisionsForBundle().executeAsList().size
        val provenanceA = RecipeImportProvenance("Book A", licenseName = "Personal notes", attribution = "Author A")
        val provenanceB = RecipeImportProvenance("Book B", "https://example.test/book-b", "CC BY 4.0", "Author B")
        fun preview(csv: String, source: RecipeImportProvenance) = previewRecipeImport(
            assertIs<RecipeTableParseResult.Valid>(parseRecipeImportTable(csv, "source.csv")).table,
            RecipeImportMapping("title", "ingredients", "method", "yield", "style", sourceRecordIdField = "id"),
            source,
        )
        val firstSource = preview(
            """title,ingredients,method,yield,style,id
Martini Classic,"Gin | 60 | ml; Vermouth | 10 | ml",Stir for 30 seconds,1,SHORT,classic-1""",
            provenanceA,
        )
        assertTrue(firstSource.canApply)
        val firstApplied = assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(
            firstSource, provenanceA, emptyMap(), 30, mapOf(2 to original.id),
        ))
        assertEquals(1, firstApplied.imported)
        assertEquals(1, repository.recipes().size)
        assertEquals("Martini", repository.recipes().single().title)
        assertEquals(listOf("Very dry"), repository.tastingNotes(original.id).map { it.note })
        assertEquals(revisionsBefore, sourceDatabase.householdQueries.selectAllRevisionsForBundle().executeAsList().size)

        val secondSource = preview(
            """title,ingredients,method,yield,style,id
Dry Martini,"Gin | 60 | ml; Vermouth | 10 | ml",Stir until very cold,1,SHORT,dry-1""",
            provenanceB,
        )
        assertTrue(secondSource.canApply)
        val secondApplied = assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(
            secondSource, provenanceB, emptyMap(), 40, mapOf(2 to original.id),
        ))
        assertEquals(1, secondApplied.imported)
        assertEquals(1, repository.recipes().size)
        assertEquals(2, sourceDatabase.householdQueries.selectRecipeSourcesForVariant(original.id.value).executeAsList().size)
        assertTrue(sourceDatabase.householdQueries.selectRecipeSourcesForVariant(original.id.value).executeAsList()
            .any { it.modifications?.contains("Dry Martini") == true })
        assertEquals("Very dry", repository.tastingNotes(original.id).single().note)

        val repeated = assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(
            secondSource, provenanceB, emptyMap(), 50, mapOf(2 to original.id),
        ))
        assertEquals(0, repeated.imported)
        assertEquals(1, repeated.skippedDuplicates)
        assertEquals(2, sourceDatabase.householdQueries.selectRecipeSourcesForVariant(original.id.value).executeAsList().size)

        val incompatible = preview(
            """title,ingredients,method,yield,style,id
Martini,"Vodka | 60 | ml; Vermouth | 10 | ml",Stir,1,SHORT,unrelated-1""",
            provenanceB.copy(sourceName = "Book C"),
        )
        assertTrue(incompatible.canApply)
        assertIs<RecipeImportApplyResult.Invalid>(repository.applyRecipeImport(
            incompatible, provenanceB.copy(sourceName = "Book C"), emptyMap(), 60, mapOf(2 to original.id),
        ))
        assertEquals(1, repository.recipes().size)

        // The same title with a different formulation stays a separate family unless the user links it.
        assertIs<RecipeImportApplyResult.Applied>(repository.applyRecipeImport(
            incompatible, provenanceB.copy(sourceName = "Book C"), emptyMap(), 70,
        ))
        assertEquals(2, repository.recipes().size)
        assertEquals(2, repository.recipeFamilies().size)

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(targetDriver)
        val targetDatabase = CocktailsDatabase(targetDriver)
        val target = LocalCocktailRepository(targetDatabase)
        target.initialize(80)
        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repository.exportHouseholdBundle(75))).bundle
        val importPreview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
        assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(bundle, importPreview, emptyMap(), 90))
        val transferredOriginal = target.recipes().single { it.id == original.id }
        assertEquals("Very dry", target.tastingNotes(original.id).single().note)
        assertEquals(2, targetDatabase.householdQueries.selectRecipeSourcesForVariant(original.id.value).executeAsList().size)
        assertEquals(original.familyId, transferredOriginal.familyId)
        sourceDriver.close()
        targetDriver.close()
    }

    @Test
    fun optionalProductPhotoRoundTripsAndCorruptAssetsNeverWrite() {
        val photo = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 17, 0,
            0xff.toByte(), 0xda.toByte(), 0, 8, 1, 1, 0, 0, 63, 0, 0, 0xff.toByte(), 0xd9.toByte(),
        )
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        val sourceDatabase = CocktailsDatabase(sourceDriver)
        val source = LocalCocktailRepository(sourceDatabase)
        source.initialize(10)
        val location = source.locations().single()
        assertTrue(source.addIngredient("Aged Rum", "Spirit", InventoryLevel.HALF, location.id, 20, productPhotoJpeg = photo))
        assertContentEquals(photo, source.pantry(location.id).single().photoData)

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(targetDriver)
        val targetDatabase = CocktailsDatabase(targetDriver)
        val target = LocalCocktailRepository(targetDatabase)
        target.initialize(30)
        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(source.exportHouseholdBundle(40))).bundle
        val preview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
        assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(bundle, preview, emptyMap(), 50))
        assertContentEquals(photo, target.pantry(location.id).single().photoData)
        val exportedAgain = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(target.exportHouseholdBundle(60))).bundle
        val product = exportedAgain.records.single { it.type == "PRODUCT" }
        assertEquals(encodePhotoBase64(photo), product.data["photoBase64"]?.jsonPrimitive?.contentOrNull)
        assertEquals(photoSha256(photo), product.data["photoSha256"]?.jsonPrimitive?.contentOrNull)

        val corrupt = bundle.copy(records = bundle.records.map { record ->
            if (record.type == "PRODUCT") record.copy(data = buildJsonObject {
                record.data.forEach { (key, value) -> put(key, value) }
                put("photoSha256", "0".repeat(64))
            }) else record
        })
        val corruptPreview = target.previewHouseholdBundle(corrupt)
        assertIs<ImportPreviewResult.Invalid>(corruptPreview)
        val tooLargePhoto = ByteArray(MAX_PRODUCT_PHOTO_BYTES + 1).also { bytes ->
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 17, 0,
                0xff.toByte(), 0xda.toByte(), 0, 8, 1, 1, 0, 0, 63, 0).copyInto(bytes)
            bytes[bytes.lastIndex - 1] = 0xff.toByte()
            bytes[bytes.lastIndex] = 0xd9.toByte()
        }
        val oversized = bundle.copy(records = bundle.records.map { record ->
            if (record.type == "PRODUCT") record.copy(data = buildJsonObject {
                record.data.forEach { (key, value) -> put(key, value) }
                put("photoBase64", encodePhotoBase64(tooLargePhoto))
                put("photoSha256", photoSha256(tooLargePhoto))
            }) else record
        })
        assertIs<ImportPreviewResult.Invalid>(target.previewHouseholdBundle(oversized))
        assertEquals(1, target.pantry(location.id).size)
        assertContentEquals(photo, target.pantry(location.id).single().photoData)
        sourceDriver.close()
        targetDriver.close()
    }

    @Test
    fun explicitFamilyReassociationPreservesVariantHistoryNotesSourcesAndIds() {
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        val sourceDatabase = CocktailsDatabase(sourceDriver)
        val source = LocalCocktailRepository(sourceDatabase)
        source.initialize(10)
        assertTrue(source.addRecipe("Martini", listOf("Gin", "Vermouth"), "Stir", RecipeStyle.SHORT, nowMillis = 20))
        assertTrue(source.addRecipe("Martini", listOf("Vodka", "Vermouth"), "Stir", RecipeStyle.SHORT, nowMillis = 30))
        assertEquals(2, source.recipeFamilies().size)
        assertEquals(2, source.recipes().size)
        val original = source.recipes().first { it.ingredients.any { line -> line.name == "Gin" } }
        val destinationFamily = source.recipeFamilies().first { it.familyId != original.familyId }
        val memberId = source.members().single().id
        source.addTastingNote(original.id, memberId, 5, "Bright", null, 35)
        sourceDatabase.householdQueries.insertRecipeSource("manual-source", original.id.value, "House book", "martini-1", null, "Personal notes", "Dave", "", 36)
        val originalLineIds = sourceDatabase.householdQueries.selectAllRecipeIngredientsForBundle().executeAsList()
            .filter { it.variant_id == original.id.value }.map { it.id }
        val originalHead = sourceDatabase.householdQueries.selectLatestRecordRevision(original.id.value, "RECIPE_VARIANT").executeAsOne()
        val originalPayload = sourceDatabase.householdQueries.selectRevisionPayload(originalHead).executeAsOne()

        // The same title initially leaves two separate recipes; an explicit selection moves only one.
        assertTrue(source.editRecipeVariant(original.id, original.title, original.ingredients.map {
            RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional)
        }, original.instructions, original.servings, original.style, 41, targetFamilyId = destinationFamily.familyId))

        val moved = source.recipes().single { it.id == original.id }
        assertEquals(destinationFamily.familyId, moved.familyId)
        assertEquals(2, source.recipes().size)
        assertEquals(1, source.recipeFamilies().size)
        assertEquals(2, source.recipeFamilies().single().variantCount)
        assertEquals(listOf("Bright"), source.tastingNotes(original.id).map { it.note })
        assertEquals("House book", sourceDatabase.householdQueries.selectRecipeSourcesForVariant(original.id.value).executeAsOne().source_name)
        assertEquals(originalLineIds, sourceDatabase.householdQueries.selectAllRecipeIngredientsForBundle().executeAsList()
            .filter { it.variant_id == original.id.value }.map { it.id })
        assertEquals(originalPayload, sourceDatabase.householdQueries.selectRevisionPayload(originalHead).executeAsOne())
        val movedHead = sourceDatabase.householdQueries.selectLatestRecordRevision(original.id.value, "RECIPE_VARIANT").executeAsOne()
        assertEquals(originalHead, sourceDatabase.householdQueries.selectRevisionParents(movedHead).executeAsOne())
        assertTrue(sourceDatabase.householdQueries.selectRevisionPayload(movedHead).executeAsOne().contains(destinationFamily.familyId.value))

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(targetDriver)
        val targetDatabase = CocktailsDatabase(targetDriver)
        val target = LocalCocktailRepository(targetDatabase)
        target.initialize(50)
        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(source.exportHouseholdBundle(49))).bundle
        val preview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
        assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(bundle, preview, emptyMap(), 60))
        assertEquals(destinationFamily.familyId, target.recipes().single { it.id == original.id }.familyId)
        assertEquals("House book", targetDatabase.householdQueries.selectRecipeSourcesForVariant(original.id.value).executeAsOne().source_name)
        assertEquals("Bright", target.tastingNotes(original.id).single().note)
        sourceDriver.close()
        targetDriver.close()
    }

    @Test
    fun concurrentFamilyReassociationRequiresConflictResolutionAndKeepsBothParents() {
        val leftDatabase = database()
        val rightDatabase = database()
        val left = LocalCocktailRepository(leftDatabase)
        val right = LocalCocktailRepository(rightDatabase)
        left.initialize(10)
        assertTrue(left.addRecipe("Martini", listOf("Gin", "Vermouth"), "Stir", RecipeStyle.SHORT, nowMillis = 20))
        assertTrue(left.addRecipe("Martini", listOf("Vodka", "Vermouth"), "Stir", RecipeStyle.SHORT, nowMillis = 30))
        assertTrue(left.addRecipe("Martini", listOf("Rum", "Vermouth"), "Stir", RecipeStyle.SHORT, nowMillis = 40))
        val recipe = left.recipes().first { it.ingredients.any { line -> line.name == "Gin" } }
        val families = left.recipeFamilies()
        val leftTarget = families.first { it.familyId != recipe.familyId }
        val rightTarget = families.first { it.familyId != recipe.familyId && it.familyId != leftTarget.familyId }
        val baseline = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(50))).bundle
        right.initialize(60)
        val initialPreview = assertIs<ImportPreviewResult.Valid>(right.previewHouseholdBundle(baseline)).preview
        assertIs<ImportApplyResult.Applied>(right.applyHouseholdBundle(baseline, initialPreview, emptyMap(), 70))

        assertTrue(left.editRecipeVariant(recipe.id, recipe.title, recipe.ingredients.map {
            RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional)
        }, recipe.instructions, recipe.servings, recipe.style, 80, targetFamilyId = leftTarget.familyId))
        assertTrue(right.editRecipeVariant(recipe.id, recipe.title, recipe.ingredients.map {
            RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional)
        }, recipe.instructions, recipe.servings, recipe.style, 75, targetFamilyId = rightTarget.familyId))
        val incoming = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(right.exportHouseholdBundle(90))).bundle
        val preview = assertIs<ImportPreviewResult.Valid>(left.previewHouseholdBundle(incoming)).preview
        val conflict = preview.conflicts.single { it.type == "RECIPE_VARIANT" && it.id == recipe.id.value }
        assertIs<ImportApplyResult.Invalid>(left.applyHouseholdBundle(incoming, preview, emptyMap(), 100))
        assertIs<ImportApplyResult.Applied>(left.applyHouseholdBundle(
            incoming, preview, mapOf(conflict.key to ConflictResolution(ConflictChoice.USE_INCOMING)), 110,
        ))
        assertEquals(rightTarget.familyId, left.recipes().single { it.id == recipe.id }.familyId)
        val mergedHead = leftDatabase.householdQueries.selectLatestRecordRevision(recipe.id.value, "RECIPE_VARIANT").executeAsOne()
        assertEquals(setOf(conflict.localHeadIds.single(), conflict.incomingHeadIds.single()),
            leftDatabase.householdQueries.selectRevisionParents(mergedHead).executeAsList().toSet())
    }

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
    fun unsupportedRevisionSchemaIsRejectedBeforeImportWrites() {
        val sourceDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(sourceDriver)
        val source = LocalCocktailRepository(CocktailsDatabase(sourceDriver))
        source.initialize(10)
        assertTrue(source.addRecipe("Negroni", listOf("Gin", "Campari"), "Stir", RecipeStyle.BITTER, nowMillis = 20))
        val valid = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(source.exportHouseholdBundle(30))).bundle
        val futureRevision = valid.copy(revisions = valid.revisions.mapIndexed { index, revision ->
            if (index == 0) revision.copy(schemaVersion = 2) else revision
        })

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(targetDriver)
        val target = LocalCocktailRepository(CocktailsDatabase(targetDriver))
        target.initialize(40)
        val localCount = target.recipes().size
        val rejected = assertIs<ImportPreviewResult.Invalid>(target.previewHouseholdBundle(futureRevision))
        assertTrue(rejected.reason.contains("unsupported schema version"))
        assertEquals(localCount, target.recipes().size)
        assertTrue(target.recipeFamilies().isEmpty())
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
    fun concurrentRecipeArchiveAndEditRequireAnExplicitChoiceAndPreserveBothParents() {
        val leftDatabase = database()
        val rightDatabase = database()
        val left = LocalCocktailRepository(leftDatabase)
        val right = LocalCocktailRepository(rightDatabase)
        left.initialize(100)
        assertTrue(left.addRecipe("Daiquiri", listOf("Rum", "Lime"), "Shake with ice.", RecipeStyle.SOUR, nowMillis = 200))
        val recipe = left.recipes().single()
        val baseline = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(300))).bundle
        right.initialize(400)
        val initialPreview = assertIs<ImportPreviewResult.Valid>(right.previewHouseholdBundle(baseline)).preview
        assertIs<ImportApplyResult.Applied>(right.applyHouseholdBundle(baseline, initialPreview, emptyMap(), 500))

        assertTrue(right.editRecipeVariant(
            variantId = recipe.id,
            title = "Daiquiri (house method)",
            ingredientLines = recipe.ingredients.map { RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional) },
            instructions = "Shake hard and double strain.",
            servings = recipe.servings,
            style = recipe.style,
            nowMillis = 600,
        ))
        val localEditHead = rightDatabase.householdQueries.selectLatestRecordRevision(recipe.id.value, "RECIPE_VARIANT").executeAsOne()
        assertTrue(left.archiveRecipeVariant(recipe.id, 700))
        val incomingArchive = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(left.exportHouseholdBundle(800))).bundle

        val preview = assertIs<ImportPreviewResult.Valid>(right.previewHouseholdBundle(incomingArchive)).preview
        val conflict = preview.conflicts.single { it.type == "RECIPE_VARIANT" && it.id == recipe.id.value }
        assertFalse(conflict.localDeleted)
        assertTrue(conflict.incomingDeleted)
        assertIs<ImportApplyResult.Invalid>(right.applyHouseholdBundle(incomingArchive, preview, emptyMap(), 900))

        assertIs<ImportApplyResult.Applied>(right.applyHouseholdBundle(
            incomingArchive,
            preview,
            mapOf(conflict.key to ConflictResolution(ConflictChoice.USE_INCOMING)),
            1_000,
        ))
        assertFalse(right.recipes().any { it.id == recipe.id })
        assertEquals(recipe.id.value, rightDatabase.householdQueries.selectAllTombstonesForBundle().executeAsList().single().record_id)
        val mergeHead = rightDatabase.householdQueries.selectLatestRecordRevision(recipe.id.value, "RECIPE_VARIANT").executeAsOne()
        assertEquals(setOf(localEditHead, conflict.incomingHeadIds.single()), rightDatabase.householdQueries.selectRevisionParents(mergeHead).executeAsList().toSet())
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

    @Test
    fun barcodeProductLinkPreservesOriginalAndDeduplicatesEquivalentUpcAndEan() {
        val database = database()
        val repository = LocalCocktailRepository(database)
        repository.initialize(10)
        val homeId = repository.locations().single().id

        assertFalse(repository.addIngredient("Vodka", "Spirit", InventoryLevel.FULL, homeId, 20, barcode = "036000291453"))
        assertTrue(repository.pantry(homeId).isEmpty())
        assertTrue(repository.addIngredient("Vodka", "Spirit", InventoryLevel.FULL, homeId, 30, barcode = "036000291452", brand = "Example", productName = "Example Vodka 750 ml"))
        assertTrue(repository.addIngredient("Vodka", "Spirit", InventoryLevel.MOSTLY_FULL, homeId, 40, barcode = "0036000291452", brand = "Example", productName = "Example Vodka 750 ml"))

        val backup = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repository.exportHouseholdBundle(50))).bundle
        val currentProduct = backup.records.single { it.type == "PRODUCT" }
        assertEquals("0036000291452", currentProduct.data["barcode"]?.jsonPrimitive?.content)
        assertEquals("Example Vodka 750 ml", currentProduct.data["productName"]?.jsonPrimitive?.content)
        assertEquals(1, backup.records.count { it.type == "PRODUCT" })
        assertTrue(backup.revisions.any { it.recordType == "PRODUCT" && it.payload["barcode"]?.jsonPrimitive?.content == "036000291452" })
        assertEquals(InventoryLevel.MOSTLY_FULL, repository.pantry(homeId).single().level)

        val peer = LocalCocktailRepository(database())
        peer.initialize(60)
        val preview = assertIs<ImportPreviewResult.Valid>(peer.previewHouseholdBundle(backup)).preview
        assertIs<ImportApplyResult.Applied>(peer.applyHouseholdBundle(backup, preview, emptyMap(), 70))
        val peerBundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(peer.exportHouseholdBundle(80))).bundle
        assertEquals("0036000291452", peerBundle.records.single { it.type == "PRODUCT" }.data["barcode"]?.jsonPrimitive?.content)
    }

    @Test
    fun oneBarcodeCannotBeAssociatedWithUnrelatedIngredients() {
        val database = database()
        val repository = LocalCocktailRepository(database)
        repository.initialize(10)
        val homeId = repository.locations().single().id

        assertTrue(repository.addIngredient("Vodka", "Spirit", InventoryLevel.FULL, homeId, 20, barcode = "036000291452"))
        assertFalse(repository.addIngredient("Gin", "Spirit", InventoryLevel.FULL, homeId, 30, barcode = "0036000291452"))
        assertEquals("Vodka", repository.pantry(homeId).single().name)
    }

    private fun database(): CocktailsDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        return CocktailsDatabase(driver)
    }
}
