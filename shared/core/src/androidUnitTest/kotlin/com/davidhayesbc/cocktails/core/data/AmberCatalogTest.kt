package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.core.interchange.*
import com.davidhayesbc.cocktails.core.model.*
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class AmberCatalogTest {
    private fun asset(path: String): File {
        var directory: File? = File(System.getProperty("user.dir")!!)
        while (directory != null) {
            val candidate = File(directory, "catalog-packs/$path")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Missing checked-in catalog asset $path")
    }
    private val amber get() = asset("amber-book/recipes.json").readText()
    private val starter get() = asset("iba-wikipedia-starter/recipes.json").readText()

    @Test fun allSourceRecordsAndMeasuresRemainExactWithSeparateBaseStyleAndDesignation() {
        val bytes = asset("amber-book/source-records.json").readBytes()
        assertEquals("8c0fe99512b0327924e0342bd0ba01cbf5235da23e45851dd3851b8225c6e002", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val originals = Json.parseToJsonElement(bytes.decodeToString()).jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        val pack = assertIs<CatalogPackDecodeResult.Valid>(decodeCatalogPack(amber)).pack
        assertEquals(48, pack.records.size)
        assertEquals(48, originals.size)
        assertEquals(asset("amber-book/LICENSE").readText(), pack.licenseNotice)
        assertNull(pack.repositoryUrl)
        assertEquals(7, pack.records.count { it.sourceMetadata!!["mocktail"]!!.jsonPrimitive.boolean })
        for (recipe in pack.records) {
            assertNull(recipe.permanentUrl)
            assertNull(recipe.historyUrl)
            assertEquals(originals.getValue(recipe.recordId), recipe.sourceMetadata!!.getValue("originalRecord"))
            assertEquals(originals.getValue(recipe.recordId).jsonObject.getValue("name").jsonPrimitive.content, recipe.title)
            val raw = originals.getValue(recipe.recordId).jsonObject.getValue("ingredients").jsonArray
            assertEquals(raw.map { it.jsonObject.getValue("amount").jsonPrimitive.content }, recipe.ingredients.map { it.unit })
            assertTrue(recipe.ingredients.all { it.amount == null })
        }
        val blonde = pack.records.single { it.recordId == "french-blonde" }.sourceMetadata!!
        assertEquals("Gin", blonde["baseSpirit"]!!.jsonPrimitive.content)
        assertEquals("Shaken", blonde["styleLabel"]!!.jsonPrimitive.content)
        assertEquals("Bubbly", blonde["originalRecord"]!!.jsonObject["category"]!!.jsonPrimitive.content)
        val french75 = pack.records.single { it.recordId == "french-75" }.sourceMetadata!!
        assertEquals("Gin", french75["baseSpirit"]!!.jsonPrimitive.content)
        assertEquals("Bubbly", french75["styleLabel"]!!.jsonPrimitive.content)
        assertTrue(pack.records.single { it.recordId == "whiskey-sour" }.ingredients.single { it.name == "Egg white" }.optional)
        assertIs<CatalogPackDecodeResult.Invalid>(decodeCatalogPack(amber.replace("\"licenseNotice\":", "\"missingNotice\":")))
    }

    @Test fun installBothOrdersGroupsSeedAliasesAndPreservesHouseholdEditsFavoritesNotesAndProvenance() {
        for (amberFirst in listOf(false, true)) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                CocktailsDatabase.Schema.create(driver)
                val repo = LocalCocktailRepository(CocktailsDatabase(driver))
                repo.initialize(10)
                assertTrue(repo.addRecipeLines("Daiquiri", listOf(RecipeIngredientDraft("House rum", 45.0, "ml")), "My own method", RecipeStyle.SOUR, nowMillis = 20))
                val own = repo.recipes().single()
                val member = repo.members().single().id
                assertTrue(repo.setFamilyFavorite(member, own.familyId, true, 21))
                repo.addTastingNote(own.id, member, 4, "Keep my note", null, 22)
                assertIs<CatalogInstallResult.Installed>(repo.installCatalogPack(if (amberFirst) amber else starter, 1000))
                assertIs<CatalogInstallResult.Installed>(repo.installCatalogPack(if (amberFirst) starter else amber, 10000))
                assertEquals(57, repo.recipes().size) // 48 Amber formulations + 8 distinct starter specs + the household variant.
                assertEquals(48, repo.recipeFamilies().size) // The eight overlapping names, including the Martini alias, share families.
                assertEquals(own, repo.recipes().single { it.id == own.id })
                assertEquals("Keep my note", repo.tastingNotes(own.id).single().note)
                assertTrue(repo.isFamilyFavorite(member, own.familyId))
                val repeated = assertIs<CatalogInstallResult.Installed>(repo.installCatalogPack(amber, 20000))
                assertEquals(0, repeated.added)
                assertEquals(48, repeated.alreadyPresent)
                val recipes = repo.recipes().filter { it.sources.any { s -> s.recordId?.startsWith("uploaded-amber-book:") == true } }
                assertEquals(48, recipes.size)
                assertTrue(recipes.all { it.sources.single().rawQuantities })
                assertEquals("2-3 dashes", recipes.single { it.title == "Old Fashioned" }.ingredients.single { it.name == "Angostura bitters" }.unit)
                val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repo.exportHouseholdBundle(21000))).bundle
                CocktailsDatabase.Schema.create(targetDriver)
                val target = LocalCocktailRepository(CocktailsDatabase(targetDriver))
                target.initialize(22000)
                val preview = assertIs<ImportPreviewResult.Valid>(target.previewHouseholdBundle(bundle)).preview
                assertIs<ImportApplyResult.Applied>(target.applyHouseholdBundle(bundle, preview, emptyMap(), 23000))
                val restored = target.recipes().single { it.id == recipes.first().id }
                assertEquals(recipes.first(), restored)
                assertEquals(asset("amber-book/LICENSE").readText(), restored.sources.single().licenseNotice)
                assertEquals("Keep my note", target.tastingNotes(own.id).single().note)
            } finally { driver.close(); targetDriver.close() }
        }
    }
}
