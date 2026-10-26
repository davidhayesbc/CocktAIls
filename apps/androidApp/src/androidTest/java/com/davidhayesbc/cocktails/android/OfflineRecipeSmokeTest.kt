package com.davidhayesbc.cocktails.android

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.setContent
import com.davidhayesbc.cocktails.android.ui.BottleIntakeScreen
import com.davidhayesbc.cocktails.android.ui.BottleWebSearchState
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import com.davidhayesbc.cocktails.android.product.BottleDetailSuggestions
import com.davidhayesbc.cocktails.android.product.BottleFactEvidence
import com.davidhayesbc.cocktails.android.product.BottleFactField
import com.davidhayesbc.cocktails.android.product.WebPageText
import com.davidhayesbc.cocktails.android.product.WebSearchHit
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
import com.davidhayesbc.cocktails.core.model.BottleIntakeCommitResult
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.HomeLocation
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-error-0"))
        composeRule.onNodeWithTag("bottle-intake-draft-error-0").assertTextEquals("Enter a bottle name before confirming this batch.")
        composeRule.onNodeWithTag("bottle-intake-draft-error-0").assertIsDisplayed()
        composeRule.onNodeWithTag("batch-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-name-0"))
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

    @Test
    fun scannedBottleReviewPrefillsEvidenceAndAcceptsBeforeNextScan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "scanned-review-${System.currentTimeMillis()}.db"
        context.deleteDatabase(databaseName)
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(1)
        val home = repository.locations().single()
        val session = repository.startBottleIntakeSession(home.id, InventoryLevel.FULL, 2)
        val barcode = "5011333150725"
        val first = repository.addBottleIntakeDraft(
            session.id, "Unidentified bottle", "Spirit", home.id, InventoryLevel.FULL, 3, barcode = barcode,
        )!!
        val sourceUrl = "https://shop.example/auchentoshan-three-wood"
        val page = WebPageText(
            title = "Auchentoshan Three Wood",
            sourceUrl = sourceUrl,
            markdown = "UPC 5011333150725 · Product name Auchentoshan Three Wood · Brand: Auchentoshan · Available in 700 mL and 750 mL · 43% ABV",
            provider = "Firecrawl",
        )
        val suggestions = BottleDetailSuggestions(
            provider = "On-device Qwen3",
            model = "qwen3-0.6b-q8",
            sourceUrl = sourceUrl,
            facts = listOf(
                BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Auchentoshan Three Wood", "Product name Auchentoshan Three Wood"),
                BottleFactEvidence(BottleFactField.BRAND, "Auchentoshan", "Brand: Auchentoshan"),
                BottleFactEvidence(BottleFactField.ABV_PERCENT, "43%", "43% ABV"),
            ),
            unresolvedFields = setOf(BottleFactField.QUANTITY),
        )
        var activeSession by mutableStateOf(repository.openBottleIntakeSession()!!)
        var activeDraftId by mutableStateOf<String?>(first.id)
        var webStates by mutableStateOf(mapOf(first.id to BottleWebSearchState(
            automatedScan = true,
            automaticStage = null,
            barcodeVerifiedInPage = true,
            page = page,
            extractedSuggestions = suggestions,
            message = "Suggestions are ready. Review the source before accepting.",
        )))
        var nextScanRequested by mutableStateOf(false)
        var secondDraftId by mutableStateOf<String?>(null)

        try {
            composeRule.setContent {
                CocktailsTheme {
                    BottleIntakeScreen(
                        modifier = Modifier.fillMaxSize(),
                        session = activeSession,
                        homes = listOf(home),
                        capturePhoto = null,
                        captureMessage = null,
                        permissionMessage = null,
                        webSearchEnabled = false,
                        webSearchStates = webStates,
                        pendingEnrichmentJobs = emptyMap(),
                        activeReviewDraftId = activeDraftId,
                        ollamaCloudEnabled = false,
                        localAiEnabled = false,
                        onSearchDraft = {},
                        onFetchDraftSource = { _, _ -> },
                        onExtractDraftDetails = {},
                        onExtractDraftDetailsOnDevice = {},
                        onResumeEnrichment = { _, _ -> },
                        onCancelDraftSearch = {},
                        onCaptureLabel = {},
                        onDiscardCapturedLabel = {},
                        onAttachCapturedLabel = {},
                        onScan = { nextScanRequested = true },
                        onAcceptAndScanNext = { draftId ->
                            assertEquals(first.id, draftId)
                            nextScanRequested = true
                            val next = repository.addBottleIntakeDraft(
                                session.id, "Unidentified bottle", "Spirit", home.id, InventoryLevel.FULL, 4, barcode = barcode,
                            )!!
                            secondDraftId = next.id
                            activeDraftId = next.id
                            webStates = webStates + (next.id to BottleWebSearchState(automatedScan = true, message = "Review the next bottle."))
                            activeSession = repository.openBottleIntakeSession()!!
                        },
                        onAddManual = { _, _, _, _, _, _, _ -> false },
                        onRemove = { draft -> repository.removeBottleIntakeDraft(draft.sessionId, draft.id); activeSession = repository.openBottleIntakeSession()!! },
                        onUpdate = { draft ->
                            val updated = repository.updateBottleIntakeDraft(draft)
                            if (updated) activeSession = repository.openBottleIntakeSession()!!
                            updated
                        },
                        onHomeChanged = {},
                        onLevelChanged = {},
                        onConfirm = { BottleIntakeCommitResult.Invalid("Test review only") },
                        onCancel = {},
                        onDismiss = {},
                    )
                }
            }

            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("This page contains the scanned barcode. Evidence-backed suggestions are filled into this review only; edit or reject them before accepting."))
            composeRule.onNodeWithText("This page contains the scanned barcode. Evidence-backed suggestions are filled into this review only; edit or reject them before accepting.").assertIsDisplayed()
            composeRule.onNodeWithText("Bottle size is unclear in this source. No value was selected; check it manually.").assertIsDisplayed()
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Auchentoshan Three Wood")),
            )
            composeRule.onNodeWithTag("bottle-intake-draft-brand-0").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Auchentoshan")),
            )
            assertEquals("Auchentoshan Three Wood", repository.openBottleIntakeSession()!!.drafts.first().ingredientName)
            assertEquals("Auchentoshan Three Wood", repository.openBottleIntakeSession()!!.drafts.first().productName)
            composeRule.onNodeWithTag("accept-scanned-bottle-${first.id}").assertIsEnabled().performClick()
            assertTrue(nextScanRequested)
            assertTrue(secondDraftId != null)
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-name-1"))
            composeRule.onNodeWithTag("bottle-intake-draft-name-1").assertIsDisplayed()
            assertEquals(2, repository.openBottleIntakeSession()!!.drafts.size)
        } finally {
            composeRule.runOnUiThread { composeRule.activity.setContent {} }
            driver.close()
            context.deleteDatabase(databaseName)
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
                saveTestScreenshot(instrumentation, "batch-intake-count-failure.png")
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
