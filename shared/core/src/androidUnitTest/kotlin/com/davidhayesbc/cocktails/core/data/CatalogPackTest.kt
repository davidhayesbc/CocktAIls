package com.davidhayesbc.cocktails.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.davidhayesbc.cocktails.core.interchange.BundleDecodeResult
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundleCodec
import com.davidhayesbc.cocktails.core.model.RecipeIngredientDraft
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatalogPackTest {
    @Test
    fun installIsIdempotentPreservesHouseholdDataAndRoundTripsProvenance() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        val database = CocktailsDatabase(driver)
        val repository = LocalCocktailRepository(database)
        repository.initialize(10)
        assertTrue(repository.addRecipeLines(
            "Daiquiri",
            listOf(RecipeIngredientDraft("House rum", 45.0, "ml")),
            "My recipe", RecipeStyle.SOUR, nowMillis = 20,
        ))
        val householdRecipe = repository.recipes().single()
        val member = repository.members().single()
        repository.addTastingNote(householdRecipe.id, member.id, 4, "Keep this note", null, 25)

        val first = assertIs<CatalogInstallResult.Installed>(repository.installCatalogPack(pack, 30))
        assertEquals(2, first.added)
        assertEquals(0, first.alreadyPresent)
        assertEquals(3, repository.recipes().size)
        assertEquals("Keep this note", repository.tastingNotes(householdRecipe.id).single().note)
        val catalogRecipe = repository.recipes().single { it.title == "Catalog Daiquiri" }
        assertEquals("CATALOG", catalogRecipe.sourceKind)
        assertEquals("CC BY-SA 3.0", catalogRecipe.sources.single().license)
        assertEquals("https://en.wikipedia.org/w/index.php?title=Daiquiri&oldid=1137204541", catalogRecipe.sources.single().url)
        assertEquals("https://en.wikipedia.org/w/index.php?title=Daiquiri&action=history", catalogRecipe.sources.single().historyUrl)
        assertEquals("60 ml white Cuban rum", catalogRecipe.sources.single().originalIngredients)
        assertEquals("https://creativecommons.org/licenses/by-sa/3.0/", catalogRecipe.sources.single().licenseUrl)
        assertEquals("9148d3302f582b06695684f3bb446631ab99d160", catalogRecipe.sources.single().repositoryRevision)
        assertFalse(repository.editRecipeVariant(catalogRecipe.id, "Edited", catalogRecipe.ingredients.map {
            RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional)
        }, "Changed", 1.0, RecipeStyle.SOUR, 35))
        val copy = repository.duplicateRecipeVariant(catalogRecipe.id, 36)!!
        assertEquals("HOUSEHOLD", repository.recipes().single { it.id == copy }.sourceKind)
        assertEquals(catalogRecipe.title, repository.recipes().single { it.id == catalogRecipe.id }.title)
        assertTrue(repository.recipes().single { it.id == copy }.sources.single().recordId!!.startsWith("household-copy:"))

        val repeated = assertIs<CatalogInstallResult.Installed>(repository.installCatalogPack(pack, 40))
        assertEquals(0, repeated.added)
        assertEquals(2, repeated.alreadyPresent)
        assertEquals("Keep this note", repository.tastingNotes(householdRecipe.id).single().note)

        val bundle = assertIs<BundleDecodeResult.Valid>(HouseholdBundleCodec.decode(repository.exportHouseholdBundle(50))).bundle
        val partnerDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(partnerDriver)
        val partner = LocalCocktailRepository(CocktailsDatabase(partnerDriver))
        partner.initialize(60)
        val preview = assertIs<ImportPreviewResult.Valid>(partner.previewHouseholdBundle(bundle)).preview
        assertIs<ImportApplyResult.Applied>(partner.applyHouseholdBundle(bundle, preview, emptyMap(), 70))
        val restored = partner.recipes().single { it.title == "Catalog Daiquiri" }
        assertEquals("CATALOG", restored.sourceKind)
        assertEquals(catalogRecipe.sources.single().url, restored.sources.single().url)
        assertEquals(catalogRecipe.sources.single().historyUrl, restored.sources.single().historyUrl)
        assertEquals(catalogRecipe.sources.single().repositoryRevision, restored.sources.single().repositoryRevision)
        partnerDriver.close()
        driver.close()
    }

    @Test
    fun malformedPackMakesNoWrites() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CocktailsDatabase.Schema.create(driver)
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(10)
        val result = assertIs<CatalogInstallResult.Invalid>(repository.installCatalogPack("{}", 20))
        assertTrue(result.reason.isNotBlank())
        assertTrue(repository.recipes().isEmpty())
        driver.close()
    }

    private val pack = """
        {"format":"cocktails.catalog-pack.v1","id":"sample-pack","version":1,
         "sourceName":"Wikipedia test data","license":"CC BY-SA 3.0","licenseUrl":"https://creativecommons.org/licenses/by-sa/3.0/","attribution":"Wikipedia contributors",
         "repositoryUrl":"https://github.com/rasmusab/iba-cocktails","repositoryRevision":"9148d3302f582b06695684f3bb446631ab99d160",
         "recipes":[
          {"recordId":"daiquiri-1137204541","title":"Catalog Daiquiri","style":"SOUR","servings":1,
           "instructions":"Shake and strain.","permanentUrl":"https://en.wikipedia.org/w/index.php?title=Daiquiri&oldid=1137204541",
           "historyUrl":"https://en.wikipedia.org/w/index.php?title=Daiquiri&action=history","modifications":"Method newly authored.","originalIngredients":"60 ml white Cuban rum",
           "ingredients":[{"name":"White Cuban rum","category":"Spirit","amount":60,"unit":"ml"}]},
          {"recordId":"manhattan-1138697614","title":"Catalog Manhattan","style":"SHORT","servings":1,
           "instructions":"Stir and strain.","permanentUrl":"https://en.wikipedia.org/w/index.php?title=Manhattan&oldid=1138697614",
           "historyUrl":"https://en.wikipedia.org/w/index.php?title=Manhattan&action=history","modifications":"Method newly authored.","originalIngredients":"50 ml Rye whiskey",
           "ingredients":[{"name":"Rye whiskey","category":"Spirit","amount":50,"unit":"ml"}]}
         ]}
    """.trimIndent()
}
