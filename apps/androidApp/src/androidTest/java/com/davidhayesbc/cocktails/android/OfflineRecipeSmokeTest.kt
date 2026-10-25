package com.davidhayesbc.cocktails.android

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.davidhayesbc.cocktails.core.data.CatalogInstallResult
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.core.matching.countMissingRequiredIngredients
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineRecipeSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun displayedVersionMatchesInstalledPackageMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val versionName = packageInfo.versionName ?: "Unknown"
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val buildStatus = when {
            isDebuggable -> "Debug build"
            versionName.contains("preview", ignoreCase = true) -> "Preview build"
            else -> "Installed build"
        }

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithTag("app-version").assertIsDisplayed()
            .assertTextEquals("$versionName · version code $versionCode")
        composeRule.onNodeWithTag("app-build-status").assertIsDisplayed().assertTextEquals(buildStatus)
    }

    @Test
    fun canCreateRecipeOfflineAndFindItAfterActivityRecreation() {
        val title = "Offline CI Martini"
        composeRule.onNodeWithText("Recipes").performClick()
        composeRule.onNodeWithText("＋ Add recipe").performClick()
        composeRule.onNodeWithTag("recipe-title").performTextInput(title)
        composeRule.onNodeWithTag("recipe-ingredients").performTextInput("Gin | 45 | ml")
        composeRule.onNodeWithTag("recipe-servings").performTextClearance()
        composeRule.onNodeWithTag("recipe-servings").performTextInput("1")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText(title))
        composeRule.onNode(hasTestTag("recipe-family-title") and hasText(title, substring = false))
            .assertIsDisplayed().assertTextEquals(title)

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Recipes").performClick()
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText(title))
        composeRule.onNode(hasTestTag("recipe-family-title") and hasText(title, substring = false))
            .assertIsDisplayed().assertTextEquals(title)
    }

    @Test
    fun manualHomeSelectorWorksWithoutLocationPermission() {
        composeRule.waitForIdle()
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            composeRule.activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        )

        composeRule.onNodeWithTag("home-location-selector").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun bundledCatalogLoadsOfflineAndStaysInstalledAfterActivityRecreation() {
        composeRule.onNodeWithText("Recipes").performClick()
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText("Add 8 starter recipes"))
        composeRule.onNodeWithText("Add 8 starter recipes").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Daiquiri").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText("Daiquiri"))
        composeRule.onNodeWithText("Daiquiri").assertIsDisplayed()
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText("Search recipes or ingredients"))
        composeRule.onNodeWithText("Search recipes or ingredients").performTextInput("Whiskey Sour")
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText("Whiskey Sour"))
        composeRule.onNode(hasTestTag("recipe-family-title") and hasText("Whiskey Sour", substring = false))
            .assertIsDisplayed().assertTextEquals("Whiskey Sour")
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Recipes").performClick()
        composeRule.onNodeWithTag("recipe-browser-scroll").performScrollToNode(hasText("Daiquiri"))
        composeRule.onNodeWithText("Daiquiri").assertIsDisplayed()
        composeRule.onNodeWithText("Add 8 starter recipes").assertDoesNotExist()
    }

    @Test
    fun bundledIngredientsMapIntoPantryAvailability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "catalog-test-${System.currentTimeMillis()}.db"
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)
        try {
            val repository = LocalCocktailRepository(CocktailsDatabase(driver))
            repository.initialize(1)
            val pack = context.assets.open("iba-wikipedia-starter/recipes.json").bufferedReader().use { it.readText() }
            assertEquals(CatalogInstallResult.Installed(8, 0), repository.installCatalogPack(pack, 2))
            val recipes = repository.recipes()
            assertEquals(8, recipes.size)
            val homeId = repository.locations().first().id
            recipes.flatMap { it.ingredients }.map { it.ingredientId }.distinct().forEachIndexed { index, ingredientId ->
                repository.updateInventoryLevel(ingredientId, homeId, InventoryLevel.FULL, 10L + index)
            }
            val available = repository.pantry(homeId).mapTo(mutableSetOf()) { it.ingredientId }
            assertTrue(recipes.all { countMissingRequiredIngredients(it.ingredients, available) == 0 })
            val partialInventory = recipes.first().ingredients.drop(1).mapTo(mutableSetOf()) { it.ingredientId }
            assertTrue(countMissingRequiredIngredients(recipes.first().ingredients, partialInventory) > 0)
            assertTrue(recipes.all { recipe -> recipe.ingredients.all { line -> line.ingredientId != IngredientId("") } })
        } finally {
            driver.close()
            context.deleteDatabase(databaseName)
        }
    }
}
