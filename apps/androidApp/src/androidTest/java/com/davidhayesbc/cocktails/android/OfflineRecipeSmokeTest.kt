package com.davidhayesbc.cocktails.android

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsLocalCache
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsSuggestion
import com.davidhayesbc.cocktails.android.product.SharedPreferencesLookupStorage
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.davidhayesbc.cocktails.core.data.CatalogInstallResult
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.core.matching.countMissingRequiredIngredients
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineRecipeSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onDeviceModelControlsAreHonestWhenNoWeightsAreInstalled() {
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithTag("household-scroll").performScrollToNode(hasText("On-device model · Qwen3 0.6B Q8"))
        composeRule.onNodeWithText("On-device model · Qwen3 0.6B Q8").assertIsDisplayed()
        composeRule.onNodeWithTag("local-ai-toggle").assertIsNotEnabled()
        composeRule.onNodeWithTag("household-scroll").performScrollToNode(hasTestTag("choose-local-model"))
        composeRule.onNodeWithTag("choose-local-model").assertIsDisplayed()
    }

    @Test
    fun batchIntakeDraftsResumeAfterActivityRecreationAndConfirmSeparateBottles() {
        val bottleName = "Batch Test Gin ${System.currentTimeMillis()}"
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithTag("start-bottle-intake").performClick()
        composeRule.onNodeWithTag("batch-manual-name").performTextInput(bottleName)
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-add-manual-bottle"))
        composeRule.onNodeWithTag("batch-add-manual-bottle").performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-manual-name"))
        composeRule.onNodeWithTag("batch-manual-name").performTextInput(bottleName)
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-add-manual-bottle"))
        composeRule.onNodeWithTag("batch-add-manual-bottle").performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("2 bottles in this batch"))
        assertBatchCountVisibleWithDiagnostics()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithTag("start-bottle-intake").performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("2 bottles in this batch"))
        composeRule.onNodeWithText("2 bottles in this batch").assertIsDisplayed()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-name-0"))
        composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextClearance()
        composeRule.onNodeWithText("Enter a bottle name before confirming this batch.").assertIsDisplayed()
        composeRule.onNodeWithTag("batch-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextInput(bottleName)
        composeRule.onNodeWithTag("batch-confirm").assertIsEnabled()
        composeRule.onNodeWithTag("batch-confirm").performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, "cocktails.db")
        try {
            val repository = LocalCocktailRepository(CocktailsDatabase(driver))
            repository.initialize(1)
            val homeId = repository.preferredHomeId() ?: repository.locations().first().id
            val bottles = repository.pantry(homeId).filter { it.name == bottleName }
            assertEquals(2, bottles.size)
            assertEquals(2, bottles.map { it.inventoryId }.toSet().size)
        } finally {
            driver.close()
        }
    }

    private fun assertBatchCountVisibleWithDiagnostics() {
        try {
            composeRule.onNodeWithText("2 bottles in this batch").assertIsDisplayed()
        } catch (failure: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val semantics = runCatching {
                val node = composeRule.onNodeWithText("2 bottles in this batch").fetchSemanticsNode()
                "boundsInRoot=${node.boundsInRoot}; boundsInWindow=${node.boundsInWindow}; config=${node.config}"
            }.getOrElse { "semantics unavailable: ${it.message}" }
            val screenshot = runCatching {
                val file = File(requireNotNull(composeRule.activity.externalCacheDir), "batch-intake-count-failure.png")
                file.parentFile?.mkdirs()
                val bitmap = automation.takeScreenshot()
                try {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
                file.absolutePath
            }.getOrElse { "screenshot unavailable: ${it.message}" }
            val hierarchy = runCatching { dumpAccessibilityNode(automation.rootInActiveWindow, 0) }
                .getOrElse { "accessibility hierarchy unavailable: ${it.message}" }
            runCatching {
                composeRule.onAllNodes(hasTestTag("bottle-intake-scroll"), useUnmergedTree = true).printToLog("CocktAIlsBatchFailure")
            }.onFailure { Log.e("CocktAIlsBatchFailure", "Compose dialog semantics unavailable: ${it.message}") }
            Log.e("CocktAIlsBatchFailure", "Batch count not displayed: ${failure.message}\n$semantics\nScreenshot: $screenshot\nAccessibility hierarchy:\n$hierarchy")
            throw AssertionError("Batch count not displayed after scrolling. $semantics; screenshot=$screenshot", failure)
        }
    }

    private fun dumpAccessibilityNode(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > 12) return ""
        val bounds = Rect().also(node::getBoundsInScreen)
        return buildString {
            append("  ".repeat(depth))
            append("class=").append(node.className)
            append(" text=").append(node.text)
            append(" bounds=").append(bounds)
            append("\n")
            for (index in 0 until node.childCount) append(dumpAccessibilityNode(node.getChild(index), depth + 1))
        }
    }

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
    fun providerLookupIsExplicitAndCachedSuggestionsStaySeparateFromManualFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("open_food_facts_local_cache", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val cache = OpenFoodFactsLocalCache(SharedPreferencesLookupStorage(context))
            cache.store(
                OpenFoodFactsSuggestion(
                    requestedBarcode = "036000291452",
                    requestCode = "036000291452",
                    canonicalCode = "0036000291452",
                    productName = "Provider Gin",
                    brands = "Provider Brand",
                    quantity = "700 ml",
                    lastModifiedSeconds = 1_700_000_000L,
                    schemaVersion = "3.6",
                    fetchedAtMillis = System.currentTimeMillis(),
                    warningIds = emptyList(),
                ),
            )

            composeRule.onNodeWithText("More").performClick()
            composeRule.onNodeWithTag("household-scroll").performScrollToNode(hasTestTag("online-product-lookup-toggle"))
            val lookupToggle = composeRule.onNodeWithTag("online-product-lookup-toggle")
            if (lookupToggle.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] != ToggleableState.On) lookupToggle.performClick()
            composeRule.onNodeWithText("My Bar").performClick()
            composeRule.onNodeWithText("＋ Add one").performClick()
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasText("Ingredient name"))
            composeRule.onNodeWithTag("ingredient-name").performTextInput("My Manual Gin")
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasTestTag("ingredient-barcode"))
            composeRule.onNodeWithTag("ingredient-barcode").performTextInput("036000291452")
            composeRule.onAllNodesWithText("Open Food Facts suggestion").assertCountEquals(0)
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasTestTag("open-food-facts-lookup"))
            composeRule.onNodeWithTag("open-food-facts-lookup").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("Product: Provider Gin").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasText("Product: Provider Gin"))
            composeRule.onNodeWithText("Product: Provider Gin").assertIsDisplayed()
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasText("Showing a result saved on this phone."))
            composeRule.onNodeWithText("Showing a result saved on this phone.").assertIsDisplayed()
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasText("Ingredient name"))
            composeRule.onNodeWithTag("ingredient-name").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("My Manual Gin")),
            )
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasTestTag("ingredient-brand"))
            composeRule.onNodeWithTag("ingredient-brand").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            composeRule.onNodeWithTag("ingredient-form-scroll").performScrollToNode(hasTestTag("ingredient-product-name"))
            composeRule.onNodeWithTag("ingredient-product-name").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )

            val databaseName = "off-lookup-export-${System.currentTimeMillis()}.db"
            val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)
            try {
                val repository = LocalCocktailRepository(CocktailsDatabase(driver))
                repository.initialize(1)
                val homeId = repository.locations().first().id
                assertTrue(repository.addIngredient("My Manual Gin", "Spirit", InventoryLevel.FULL, homeId, 2, barcode = "036000291452"))
                val backup = repository.exportHouseholdBundle(3)
                assertTrue(backup.contains("My Manual Gin"))
                assertFalse(backup.contains("Provider Gin"))
                assertFalse(backup.contains("Provider Brand"))
                assertFalse(backup.contains("700 ml"))
            } finally {
                driver.close()
                context.deleteDatabase(databaseName)
            }
        } finally {
            preferences.edit().clear().commit()
        }
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
