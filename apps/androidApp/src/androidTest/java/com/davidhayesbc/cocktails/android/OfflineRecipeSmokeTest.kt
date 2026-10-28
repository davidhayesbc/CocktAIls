package com.davidhayesbc.cocktails.android

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.davidhayesbc.cocktails.android.ui.BottleIntakeScreen
import com.davidhayesbc.cocktails.android.ui.BottleIntakeRoute
import com.davidhayesbc.cocktails.android.ui.BottleLabelCameraScreen
import com.davidhayesbc.cocktails.android.ui.BottleWebSearchState
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import com.davidhayesbc.cocktails.android.product.BottleDetailSuggestions
import com.davidhayesbc.cocktails.android.product.BottleCaptureToken
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentJob
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentOperation
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentQueue
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentQueueStorage
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentStatus
import com.davidhayesbc.cocktails.android.product.BottleFactEvidence
import com.davidhayesbc.cocktails.android.product.BottleFactField
import com.davidhayesbc.cocktails.android.product.acceptedProductSourcesForBottle
import com.davidhayesbc.cocktails.android.product.WebPageText
import com.davidhayesbc.cocktails.android.product.WebSearchHit
import com.davidhayesbc.cocktails.android.product.PendingBottleEvidenceStore
import com.davidhayesbc.cocktails.android.product.SharedPreferencesPendingBottleEvidenceStorage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsLocalCache
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsSuggestion
import com.davidhayesbc.cocktails.android.product.SharedPreferencesLookupStorage
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugEvent
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugReport
import com.davidhayesbc.cocktails.android.product.BottleProviderCall
import com.davidhayesbc.cocktails.android.product.stripJpegMetadata
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsLookupResult
import com.davidhayesbc.cocktails.android.product.WebSearchResult
import com.davidhayesbc.cocktails.android.product.ScannedBottleEnrichmentCoordinator
import com.davidhayesbc.cocktails.android.product.ScannedBottlePlan
import kotlinx.coroutines.runBlocking
import java.io.File
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.davidhayesbc.cocktails.core.data.CatalogInstallResult
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.core.interchange.photoSha256
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
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class OfflineRecipeSmokeTest {
    @get:Rule(order = 0)
    val cameraPermissionRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                if (description.methodName == "integratedLabelCameraCaptureShowsPhotoAndContinuesToNextBottle") {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val appContext = instrumentation.targetContext
                    instrumentation.uiAutomation.executeShellCommand(
                        "pm grant ${appContext.packageName} ${Manifest.permission.CAMERA}",
                    ).close()
                    check(appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        "Instrumentation did not grant CAMERA before the integrated label capture test."
                    }
                }
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
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
    fun lookupDebugReportIsPreviewedAndCopiedOnlyAfterUserAction() {
        LocalDiagnostics.clear()
        val events = mutableListOf<BottleLookupDebugEvent>()
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound, 404) },
            search = { query ->
                assertEquals("\"5011333150725\"", query)
                BottleProviderCall(WebSearchResult.Found(listOf(
                    WebSearchHit("Barcode Lookup | UPC, EAN & ISBN Search", "https://www.barcodelookup.com/", "Search by UPC"),
                    WebSearchHit("UPC Lookup", "https://go-upc.com/barcode-lookup", "Look up product details"),
                )), 200)
            },
            fetch = { error("Generic lookup pages must not be fetched") },
            extract = { _, _, _ -> error("Unverified pages must not reach extraction") },
        )
        val outcome = runBlocking {
            coordinator.processScannedBarcode(
                "5011333150725",
                ScannedBottlePlan(false, true, null),
                onDebugEvent = events::add,
            )
        }
        assertEquals(null, outcome.selectedHit)
        val report = BottleLookupDebugReport(
            createdAtMillis = 1,
            versionName = "preview-test",
            versionCode = 99,
            originalBarcode = "5011333150725",
            normalizedBarcode = "5011333150725",
            openFoodFactsEnabled = false,
            openFoodFactsConfigured = true,
            firecrawlEnabled = true,
            firecrawlConfigured = true,
            extractorProvider = null,
            modelIdentifier = null,
            query = "\"5011333150725\"",
        )
        events.forEach(report::record)
        report.finish("NOT_FOUND")
        LocalDiagnostics.recordBottleLookup(report)
        assertTrue(LocalDiagnostics.latestBottleLookupPreview()?.contains("5011333150725") == true)

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithTag("household-scroll").performScrollToNode(hasText("View or export diagnostics"))
        composeRule.onNodeWithText("View or export diagnostics").performClick()
        composeRule.onNodeWithTag("copy-bottle-lookup-report").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics-dialog-scroll").performScrollToNode(hasTestTag("latest-lookup-report-preview"))
        val lookupPreviewNode = composeRule.onNodeWithTag("latest-lookup-report-preview")
        lookupPreviewNode.assertIsDisplayed()
        val previewText = lookupPreviewNode.fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString(separator = "") { it.text }
        assertTrue("Lookup preview did not show the scanned barcode", previewText.contains("5011333150725"))
        composeRule.onNodeWithTag("copy-bottle-lookup-report").assertIsDisplayed()
        composeRule.onNodeWithTag("copy-bottle-lookup-report").performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).coerceToText(context).toString()
        assertTrue(copied.contains("5011333150725"))
        assertTrue(copied.contains("CANDIDATE_1_GENERIC_BARCODE_LOOKUP"))
        assertFalse(copied.contains("/barcode-lookup"))
        assertFalse(copied.contains("Authorization"))
        assertTrue(copied.contains("No keys"))
        val copyConfirmation = "Lookup report copied. Review it before sharing."
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(copyConfirmation).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(copyConfirmation).assertIsDisplayed()
    }

    @Test
    fun batchIntakeDraftsResumeAfterActivityRecreationAndConfirmSeparateBottles() {
        val bottleName = "Batch Test Gin ${System.currentTimeMillis()}"
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithTag("start-bottle-intake").performClick()
        composeRule.onNodeWithTag("intake-manual-start").performClick()
        composeRule.onNodeWithTag("batch-manual-name").performTextInput(bottleName)
        composeRule.onNodeWithTag("batch-manual-name").assert(editableTextIs(bottleName))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-add-manual-bottle"))
        composeRule.onNodeWithTag("batch-add-manual-bottle").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.waitForIdle()
        captureOnboardingScreenshot("batch-manual-add-result.png")
        val routeTitle = composeRule.onNodeWithTag("bottle-intake-route-title")
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val submitError = composeRule.onAllNodesWithTag("batch-manual-error").fetchSemanticsNodes()
            .joinToString { it.config[SemanticsProperties.Text].joinToString { text -> text.text } }
        assertEquals("Manual add should return to scanning after save; inline error=$submitError", "Scan the next bottle", routeTitle)
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-manual-entry"))
        composeRule.onNodeWithTag("batch-manual-entry").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Add a bottle manually")
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-manual-name"))
        composeRule.onNodeWithTag("batch-manual-name").performTextInput(bottleName)
        composeRule.onNodeWithTag("batch-manual-name").assert(editableTextIs(bottleName))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithTag("batch-manual-name").assert(editableTextIs(bottleName))
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-add-manual-bottle"))
        composeRule.onNodeWithTag("batch-add-manual-bottle").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.waitForIdle()
        val secondRouteTitle = composeRule.onNodeWithTag("bottle-intake-route-title")
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val secondSubmitError = composeRule.onAllNodesWithTag("batch-manual-error").fetchSemanticsNodes()
            .joinToString { it.config[SemanticsProperties.Text].joinToString { text -> text.text } }
        assertEquals(
            "Second manual add should return to scanning after save; inline error=$secondSubmitError",
            "Scan the next bottle",
            secondRouteTitle,
        )
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("open-bottle-queue"))
        assertBatchCountVisibleWithDiagnostics()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithTag("start-bottle-intake").performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("open-bottle-queue"))
        composeRule.onNodeWithTag("open-bottle-queue").assertIsDisplayed()
        composeRule.onNodeWithTag("open-bottle-queue").performClick()
        composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Your bottle queue")
        composeRule.onNodeWithText("0 reviewed · 2 draft bottles").assertIsDisplayed()
        val firstQueueTag = composeRule.onNodeWithText("Bottle 1 · $bottleName · Manual")
            .fetchSemanticsNode().config[SemanticsProperties.TestTag]
        assertTrue(firstQueueTag.startsWith("queue-bottle-"))
        val firstDraftId = firstQueueTag.removePrefix("queue-bottle-")
        composeRule.onNodeWithTag(firstQueueTag).performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-name-0"))
        composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextClearance()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-error-0"))
        composeRule.onNodeWithTag("bottle-intake-draft-error-0").assertTextEquals("Enter a bottle name before accepting this bottle.")
        composeRule.onNodeWithTag("bottle-intake-draft-error-0").assertIsDisplayed()
        val acceptFirstBottleTag = "accept-scanned-bottle-$firstDraftId"
        composeRule.onNodeWithTag(acceptFirstBottleTag).assertIsNotEnabled()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-name-0"))
        composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextInput(bottleName)
        composeRule.onNodeWithTag("keep-bottle-review-draft").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Scan the next bottle")
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("open-bottle-queue"))
        composeRule.onNodeWithTag("open-bottle-queue").performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-confirm"))
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
        val evidencePreferences = context.getSharedPreferences("cocktails_pending_bottle_evidence", 0)
        evidencePreferences.edit().clear().commit()
        val pendingEvidenceStore = PendingBottleEvidenceStore(SharedPreferencesPendingBottleEvidenceStorage(context))
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
            targetBarcode = barcode,
            identityEvidence = "UPC 5011333150725 · Product name Auchentoshan Three Wood",
        )
        var activeSession by mutableStateOf(repository.openBottleIntakeSession()!!)
        var activeDraftId by mutableStateOf<String?>(first.id)
        var intakeVisible by mutableStateOf(true)
        var webStates by mutableStateOf(mapOf(first.id to BottleWebSearchState(
            automatedScan = true,
            automaticStage = null,
            barcodeVerifiedInPage = true,
            page = page,
            extractedSuggestions = suggestions,
            message = "Suggestions are ready. Review the source before accepting.",
        )))
        var jobs by mutableStateOf(mapOf(first.id to BottleEnrichmentJob(
            id = "job-${first.id}", sessionId = session.id, draftId = first.id,
            operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH, status = BottleEnrichmentStatus.READY_FOR_REVIEW,
            sourceUrl = sourceUrl, model = "qwen3-0.6b-q8", resultCode = "CANDIDATES_READY",
            labelCaptureRequired = false, updatedAtMillis = 3,
        )))
        var nextScanRequested by mutableStateOf(false)
        var secondDraftId by mutableStateOf<String?>(null)
        var route by mutableStateOf(BottleIntakeRoute.REVIEW)

        try {
            setTestContent {
                CocktailsTheme {
                    if (intakeVisible) BottleIntakeScreen(
                        modifier = Modifier.fillMaxSize(),
                        session = activeSession,
                        route = route,
                        onRouteChange = { route = it },
                        homes = listOf(home),
                        capturePhoto = null,
                        captureMessage = null,
                        permissionMessage = null,
                        webSearchEnabled = false,
                        webSearchStates = webStates,
                        pendingEnrichmentJobs = jobs,
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
                            assertTrue(pendingEvidenceStore.save(draftId, suggestions))
                            val reviewedFirst = repository.openBottleIntakeSession()!!.drafts.single { it.id == first.id }
                            val accepted = repository.acceptBottleIntakeDraft(
                                session.id, draftId, 4,
                                acceptedProductSourcesForBottle(reviewedFirst, pendingEvidenceStore.read(draftId)),
                            )
                            assertTrue(accepted is BottleIntakeCommitResult.Accepted)
                            pendingEvidenceStore.remove(draftId)
                            jobs = jobs + (first.id to jobs.getValue(first.id).copy(
                                status = BottleEnrichmentStatus.REVIEWED,
                                resultCode = "USER_ACCEPTED",
                            ))
                            nextScanRequested = true
                            val next = repository.addBottleIntakeDraft(
                                session.id, "Auchentoshan Three Wood", "Spirit", home.id, InventoryLevel.FULL, 5,
                                barcode = barcode, brand = "Auchentoshan", productName = "Auchentoshan Three Wood",
                            )!!
                            secondDraftId = next.id
                            activeDraftId = next.id
                            jobs = jobs + (next.id to jobs.getValue(first.id).copy(
                                id = "job-${next.id}", draftId = next.id,
                                status = BottleEnrichmentStatus.READY_FOR_REVIEW,
                            ))
                            webStates = mapOf(next.id to BottleWebSearchState(automatedScan = true, message = "Review the next bottle."))
                            activeSession = repository.openBottleIntakeSession()!!
                            route = BottleIntakeRoute.REVIEW
                            BottleIntakeCommitResult.Accepted(
                                inventoryId = repository.pantry(home.id).single().inventoryId,
                                remainingDraftCount = activeSession.drafts.size,
                            )
                        },
                        onAddManual = { _, _, _, _, _, _, _ -> false },
                        onRemove = { draft -> repository.removeBottleIntakeDraft(draft.sessionId, draft.id); activeSession = repository.openBottleIntakeSession()!! },
                        onUpdate = { draft ->
                            val updated = repository.updateBottleIntakeDraft(draft)
                            if (updated) activeSession = repository.openBottleIntakeSession()!!
                            updated
                        },
                        onFieldEdited = { draftId, field ->
                            webStates = webStates + (draftId to webStates[draftId]!!.copy(userEditedFields = webStates[draftId]!!.userEditedFields + field))
                        },
                        onHomeChanged = {},
                        onLevelChanged = {},
                        onConfirm = {
                            val openSession = repository.openBottleIntakeSession()!!
                            val sourcesByDraft = openSession.drafts.mapNotNull { draft ->
                                acceptedProductSourcesForBottle(draft, pendingEvidenceStore.read(draft.id))
                                    .takeIf { it.isNotEmpty() }?.let { draft.id to it }
                            }.toMap()
                            val result = repository.confirmBottleIntakeSession(openSession.id, 5, sourcesByDraft)
                            if (result is BottleIntakeCommitResult.Confirmed || result is BottleIntakeCommitResult.AlreadyConfirmed) {
                                openSession.drafts.forEach { pendingEvidenceStore.remove(it.id) }
                            }
                            result
                        },
                        onCancel = {},
                        onDismiss = {
                            webStates = emptyMap()
                            activeDraftId = null
                            intakeVisible = false
                        },
                        onPauseQueue = { route = BottleIntakeRoute.PAUSE },
                        onResumeScanning = { route = BottleIntakeRoute.SCANNING },
                    )
                }
            }

            composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Review your bottle")
            captureOnboardingScreenshot("onboarding-review-real-route.png")
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-evidence-toggle"))
            composeRule.onNodeWithTag("review-evidence-toggle").assertIsDisplayed().performClick()
            val identityEvidenceNote = "This page contains the scanned barcode. Review the quoted evidence before accepting."
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText(identityEvidenceNote))
            composeRule.onNodeWithText(identityEvidenceNote).assertIsDisplayed()
            val bottleSizeWarning = "Bottle size is unclear in this source. No value was selected; check it manually."
            composeRule.onNodeWithText(bottleSizeWarning).assertExists()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText(bottleSizeWarning))
            composeRule.onNodeWithText(bottleSizeWarning).assertIsDisplayed()
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").assert(
                editableTextIs("Auchentoshan Three Wood"),
            )
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-more-details"))
            composeRule.onNodeWithTag("review-more-details").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-brand-0"))
            composeRule.onNodeWithTag("bottle-intake-draft-brand-0").assert(
                editableTextIs("Auchentoshan"),
            )
            assertEquals("Auchentoshan Three Wood", repository.openBottleIntakeSession()!!.drafts.first().ingredientName)
            assertEquals("Auchentoshan Three Wood", repository.openBottleIntakeSession()!!.drafts.first().productName)
            val acceptScannedBottle = "accept-scanned-bottle-${first.id}"
            composeRule.onNodeWithTag(acceptScannedBottle).assertIsDisplayed().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { nextScanRequested }
            assertTrue(secondDraftId != null)
            assertEquals(BottleIntakeRoute.REVIEW, route)
            composeRule.onNodeWithTag("bottle-intake-draft-name-1").assert(editableTextIs("Auchentoshan Three Wood"))
            val firstAcceptedBottle = repository.pantry(home.id).single()
            assertTrue(repository.productFactSources(firstAcceptedBottle.productId!!).any { it.fieldValue == "Auchentoshan Three Wood" })
            assertEquals(1, repository.openBottleIntakeSession()!!.drafts.size)

            // Accepting a scan, pausing the batch, and losing the in-memory screen state must not
            // discard its evidence. Confirmation reads the persisted suggestion and transfers it.
            keepBottleIntakeForLater()
            assertTrue(!intakeVisible)
            assertEquals(null, pendingEvidenceStore.read(first.id))
            intakeVisible = true
            route = BottleIntakeRoute.QUEUE
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("batch-confirm").performClick()
            assertTrue(repository.openBottleIntakeSession() == null)
            val savedProductId = repository.pantry(home.id).first().productId!!
            assertEquals(2, repository.pantry(home.id).size)
            assertEquals(setOf("Auchentoshan Three Wood", "Auchentoshan", "43%"), repository.productFactSources(savedProductId).map { it.fieldValue }.toSet())
            assertEquals(null, pendingEvidenceStore.read(first.id))
        } finally {
            setTestContent { }
            evidencePreferences.edit().clear().commit()
            driver.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun delayedSuggestionsDoNotOverwriteUserEditedOrClearedBottleFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "scanned-edit-race-${System.currentTimeMillis()}.db"
        context.deleteDatabase(databaseName)
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(10)
        val home = repository.locations().single()
        val session = repository.startBottleIntakeSession(home.id, InventoryLevel.FULL, 11)
        val draft = repository.addBottleIntakeDraft(
            session.id, "Unidentified bottle", "Spirit", home.id, InventoryLevel.FULL, 12, barcode = "5011333150725",
        )!!
        val sourceUrl = "https://shop.example/three-wood"
        val page = WebPageText("Three Wood", sourceUrl, "UPC 5011333150725 · Three Wood · Brand: Example Brand", "Firecrawl")
        val delayedSuggestions = BottleDetailSuggestions(
            provider = "Ollama Cloud", model = "test-model", sourceUrl = sourceUrl,
            facts = listOf(
                BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Three Wood", "UPC 5011333150725 · Three Wood"),
                BottleFactEvidence(BottleFactField.BRAND, "Example Brand", "Three Wood · Brand: Example Brand"),
            ),
            targetBarcode = "5011333150725",
            identityEvidence = "UPC 5011333150725 · Three Wood",
        )
        var activeSession by mutableStateOf(repository.openBottleIntakeSession()!!)
        var webState by mutableStateOf(BottleWebSearchState(automatedScan = true, barcodeVerifiedInPage = true, page = page))

        try {
            setTestContent {
                CocktailsTheme {
                    BottleIntakeScreen(
                        modifier = Modifier.fillMaxSize(), session = activeSession, route = BottleIntakeRoute.REVIEW, onRouteChange = {}, homes = listOf(home),
                        capturePhoto = null, captureMessage = null, permissionMessage = null,
                        webSearchEnabled = false, webSearchStates = mapOf(draft.id to webState),
                        pendingEnrichmentJobs = emptyMap(), activeReviewDraftId = draft.id,
                        ollamaCloudEnabled = false, localAiEnabled = false,
                        onSearchDraft = {}, onFetchDraftSource = { _, _ -> }, onExtractDraftDetails = {},
                        onExtractDraftDetailsOnDevice = {}, onResumeEnrichment = { _, _ -> }, onCancelDraftSearch = {},
                        onCaptureLabel = {}, onDiscardCapturedLabel = {}, onAttachCapturedLabel = {}, onScan = {},
                        onAcceptAndScanNext = { BottleIntakeCommitResult.Invalid("Test only") }, onAddManual = { _, _, _, _, _, _, _ -> false },
                        onRemove = { },
                        onUpdate = { updated ->
                            val saved = repository.updateBottleIntakeDraft(updated)
                            if (saved) activeSession = repository.openBottleIntakeSession()!!
                            saved
                        },
                        onFieldEdited = { id, field ->
                            webState = webState.copy(userEditedFields = webState.userEditedFields + field)
                        },
                        onHomeChanged = {}, onLevelChanged = {},
                        onConfirm = { BottleIntakeCommitResult.Invalid("Test only") }, onCancel = {}, onDismiss = {}, onPauseQueue = {}, onResumeScanning = {},
                    )
                }
            }

            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-name-0"))
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextClearance()
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextInput("My chosen whisky")
            webState = webState.copy(extractedSuggestions = delayedSuggestions)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").assert(
                editableTextIs("My chosen whisky"),
            )
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-more-details"))
            composeRule.onNodeWithTag("review-more-details").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("bottle-intake-draft-brand-0"))
            composeRule.onNodeWithTag("bottle-intake-draft-brand-0").assert(
                editableTextIs("Example Brand"),
            )
            composeRule.onNodeWithTag("bottle-intake-draft-product-0").assert(
                editableTextIs("Three Wood"),
            )

            composeRule.onNodeWithTag("bottle-intake-draft-name-0").performTextClearance()
            webState = webState.copy(extractedSuggestions = delayedSuggestions.copy(
                facts = delayedSuggestions.facts.map { fact ->
                    if (fact.field == BottleFactField.PRODUCT_NAME) fact.copy(value = "Another suggested bottle") else fact
                },
            ))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").assert(
                editableTextIs(""),
            )
            val acceptTag = "accept-scanned-bottle-${draft.id}"
            composeRule.onNodeWithTag(acceptTag).assertIsNotEnabled()

            // Non-automated suggestions stay separate until a user explicitly applies them,
            // and application is offered only when the source identity matches this barcode.
            webState = webState.copy(automatedScan = false, barcodeVerifiedInPage = false)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-evidence-toggle"))
            composeRule.onNodeWithTag("review-evidence-toggle").performClick()
            composeRule.onNodeWithTag("apply-manual-suggestion-product_name-${draft.id}").assertDoesNotExist()
            webState = webState.copy(barcodeVerifiedInPage = true)
            composeRule.waitForIdle()
            val applyProductTag = "apply-manual-suggestion-product_name-${draft.id}"
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag(applyProductTag))
            composeRule.onNodeWithTag(applyProductTag).assertIsDisplayed().performClick()
            assertEquals("Another suggested bottle", repository.openBottleIntakeSession()!!.drafts.single().productName)
        } finally {
            setTestContent { }
            driver.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun barcodeFirstLabelStepKeepsOneBottleIdentityAndOffersCaptureOrSkip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "barcode-first-label-${System.currentTimeMillis()}.db"
        context.deleteDatabase(databaseName)
        val repository = LocalCocktailRepository(CocktailsDatabase(AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)))
        repository.initialize(10)
        val home = repository.locations().single()
        val session = repository.startBottleIntakeSession(home.id, InventoryLevel.FULL, 11)
        val draft = repository.addBottleIntakeDraft(
            session.id, "Unidentified bottle", "Spirit", home.id, InventoryLevel.FULL, 12, barcode = "5011333150725",
        )!!
        var captureRequested = false
        val cameraToken = mutableStateOf<BottleCaptureToken?>(null)
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val photoOutput = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, photoOutput)
        bitmap.recycle()
        val labelPhoto = photoOutput.toByteArray()
        var skippedDraft: String? = null
        try {
            setTestContent {
                CocktailsTheme {
                    BottleIntakeScreen(
                        modifier = Modifier.fillMaxSize(), session = repository.openBottleIntakeSession()!!,
                        route = BottleIntakeRoute.CAPTURE_LABEL, onRouteChange = {}, homes = listOf(home),
                        capturePhoto = labelPhoto, originalCapturePhoto = labelPhoto, captureMessage = null, permissionMessage = null,
                        pendingLabelDraftId = draft.id,
                        labelCameraToken = cameraToken.value,
                        labelCameraOutputFile = cameraToken.value?.let { File(context.cacheDir, "${it.generation}-${it.draftId}.jpg") },
                        webSearchEnabled = false, webSearchStates = emptyMap(), pendingEnrichmentJobs = emptyMap(),
                        activeReviewDraftId = null, ollamaCloudEnabled = false, localAiEnabled = false,
                        onSearchDraft = {}, onFetchDraftSource = { _, _ -> }, onExtractDraftDetails = {},
                        onExtractDraftDetailsOnDevice = {}, onResumeEnrichment = { _, _ -> }, onCancelDraftSearch = {},
                        onCaptureLabel = { captureRequested = true; cameraToken.value = BottleCaptureToken(1, draft.id) },
                        onLabelPhotoSaved = { _, _ -> cameraToken.value = null },
                        onLabelCameraCancel = { cameraToken.value = null },
                        onUsePendingLabel = { true },
                        onSkipPendingLabel = { skippedDraft = it; true }, onDiscardCapturedLabel = {},
                        onAttachCapturedLabel = {}, onScan = {}, onAcceptAndScanNext = { BottleIntakeCommitResult.Invalid("Test only") },
                        onAddManual = { _, _, _, _, _, _, _ -> false }, onRemove = {}, onUpdate = { true },
                        onFieldEdited = { _, _ -> }, onHomeChanged = {}, onLevelChanged = {},
                        onConfirm = { BottleIntakeCommitResult.Invalid("Test only") }, onCancel = {}, onDismiss = {},
                        onPauseQueue = {}, onResumeScanning = {},
                    )
                }
            }
            composeRule.onNodeWithText("Barcode captured · Bottle #1").assertIsDisplayed()
            composeRule.onNodeWithText("5011333150725").assertIsDisplayed()
            composeRule.onNodeWithTag("retake-label-${draft.id}").assertIsDisplayed().performClick()
            assertTrue(captureRequested)
            composeRule.onNodeWithTag("label-live-camera").assertIsDisplayed()
            composeRule.onNodeWithText("Retaking label · your previous photo stays safe until you use the new one.").assertIsDisplayed()
            composeRule.onNodeWithTag("cancel-live-label").performClick()
            composeRule.onNodeWithTag("retake-label-${draft.id}").assertIsDisplayed()
            composeRule.onNodeWithTag("skip-label-${draft.id}").assertIsDisplayed().performClick()
            assertEquals(draft.id, skippedDraft)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun liveLabelCameraShowsEdgeGuideAndWaitsForExplicitShutter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "label-camera-ui-test.jpg")
        val captureToken = BottleCaptureToken(7, "camera-test-draft")
        var photoSaved = false
        var cancelledToken: BottleCaptureToken? = null
        try {
            setTestContent {
                CocktailsTheme {
                    BottleLabelCameraScreen(
                        outputFile = output,
                        captureToken = captureToken,
                        onPhotoSaved = { token, _ -> photoSaved = token == captureToken },
                        onCancel = { cancelledToken = it },
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("label-live-camera").assertExists()
            composeRule.onNodeWithTag("live-label-edge-overlay").assertExists()
            composeRule.onNodeWithTag("capture-live-label").assertExists()
            composeRule.onNodeWithText("Take label photo").assertExists()
            composeRule.waitForIdle()
            captureOnboardingScreenshot("onboarding-label-camera-layout-simulated.png")
            assertTrue("A label must not be captured before the user taps the shutter", !photoSaved)
            composeRule.onNodeWithTag("cancel-live-label").performClick()
            assertEquals("The callback must retain the capture that opened this screen", captureToken, cancelledToken)
        } finally {
            setTestContent { }
            output.delete()
        }
    }

    @Test
    fun integratedLabelCameraCaptureShowsPhotoAndContinuesToNextBottle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "label-camera-flow-${System.currentTimeMillis()}.db"
        val outputDirectory = File(context.cacheDir, "label-camera-flow-test")
        outputDirectory.mkdirs()
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(1)
        val home = repository.locations().single()
        val initialSession = repository.startBottleIntakeSession(home.id, InventoryLevel.FULL, 2)
        val initialDraft = repository.addBottleIntakeDraft(
            initialSession.id,
            "Camera Callback Test Bottle",
            "Spirit",
            home.id,
            InventoryLevel.FULL,
            3,
            barcode = "5011333150725",
        )!!
        var activeSession by mutableStateOf(repository.openBottleIntakeSession()!!)
        var route by mutableStateOf(BottleIntakeRoute.CAPTURE_LABEL)
        var pendingDraftId by mutableStateOf<String?>(initialDraft.id)
        var cameraToken by mutableStateOf<BottleCaptureToken?>(BottleCaptureToken(1, initialDraft.id))
        var nextCaptureGeneration = 1L
        var capturedPhoto by mutableStateOf<ByteArray?>(null)
        var captureMessage by mutableStateOf<String?>(null)
        var savedCallbackToken by mutableStateOf<BottleCaptureToken?>(null)
        var callbackCount by mutableStateOf(0)
        var nextScanRequested by mutableStateOf(false)

        fun outputFor(token: BottleCaptureToken): File = File(
            outputDirectory,
            "capture-${token.generation}-${token.draftId}.jpg",
        )

        try {
            LocalDiagnostics.clear()
            setTestContent {
                CocktailsTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        BottleIntakeScreen(
                            modifier = Modifier.fillMaxSize(),
                            session = activeSession,
                            route = route,
                            onRouteChange = { route = it },
                            homes = listOf(home),
                            capturePhoto = capturedPhoto,
                            originalCapturePhoto = capturedPhoto,
                            captureMessage = captureMessage,
                            permissionMessage = null,
                            pendingLabelDraftId = pendingDraftId,
                            labelCameraOutputFile = cameraToken?.let(::outputFor),
                            labelCameraToken = cameraToken,
                            onLabelPhotoSaved = { token, _ ->
                                val output = outputFor(token)
                                savedCallbackToken = token
                                callbackCount += 1
                                captureMessage = if (output.isFile && output.length() > 0) {
                                    capturedPhoto = stripJpegMetadata(output.readBytes())
                                    output.delete()
                                    null
                                } else {
                                    "CameraX reported capture completion without a readable photo."
                                }
                                cameraToken = null
                            },
                            onLabelCameraCancel = { token ->
                                outputFor(token).delete()
                                cameraToken = null
                            },
                            webSearchEnabled = false,
                            webSearchStates = emptyMap(),
                            pendingEnrichmentJobs = emptyMap(),
                            activeReviewDraftId = null,
                            ollamaCloudEnabled = false,
                            localAiEnabled = false,
                            onSearchDraft = {},
                            onFetchDraftSource = { _, _ -> },
                            onExtractDraftDetails = {},
                            onExtractDraftDetailsOnDevice = {},
                            onResumeEnrichment = { _, _ -> },
                            onCancelDraftSearch = {},
                            onCaptureLabel = {
                                nextCaptureGeneration += 1
                                cameraToken = BottleCaptureToken(nextCaptureGeneration, initialDraft.id)
                            },
                            onUsePendingLabel = { draftId ->
                                val photo = capturedPhoto
                                val draft = activeSession.drafts.firstOrNull { it.id == draftId }
                                val saved = if (draftId == initialDraft.id && photo != null && draft != null) {
                                    repository.updateBottleIntakeDraft(draft.copy(photoData = photo, photoSha256 = photoSha256(photo)))
                                } else false
                                if (saved) {
                                    activeSession = repository.openBottleIntakeSession()!!
                                    capturedPhoto = null
                                    pendingDraftId = null
                                    route = BottleIntakeRoute.SCANNING
                                    nextScanRequested = true
                                }
                                saved
                            },
                            onSkipPendingLabel = { false },
                            onDiscardCapturedLabel = { capturedPhoto = null },
                            onAttachCapturedLabel = {},
                            onScan = { nextScanRequested = true },
                            onAcceptAndScanNext = { BottleIntakeCommitResult.Invalid("Test only") },
                            onAddManual = { _, _, _, _, _, _, _ -> false },
                            onRemove = {},
                            onUpdate = { repository.updateBottleIntakeDraft(it) },
                            onFieldEdited = { _, _ -> },
                            onHomeChanged = {},
                            onLevelChanged = {},
                            onConfirm = { BottleIntakeCommitResult.Invalid("Test only") },
                            onCancel = {},
                            onDismiss = {},
                            onPauseQueue = {},
                            onResumeScanning = { route = BottleIntakeRoute.SCANNING },
                        )
                    }
                }
            }

            composeRule.onNodeWithTag("label-live-camera").assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithTag("capture-live-label").fetchSemanticsNodes()
                    .any { node -> !node.config.contains(SemanticsProperties.Disabled) }
            }
            composeRule.onNodeWithTag("capture-live-label").assertIsDisplayed().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithTag("label-photo-preview-${initialDraft.id}").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("CameraX must deliver exactly one saved-image callback", 1, callbackCount)
            assertEquals(BottleCaptureToken(1, initialDraft.id), savedCallbackToken)
            composeRule.onNodeWithTag("label-photo-preview-${initialDraft.id}").assertIsDisplayed()
            composeRule.onAllNodesWithTag("capture-live-label").assertCountEquals(0)
            composeRule.onNodeWithTag("use-label-${initialDraft.id}").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Scan the next bottle")
            composeRule.onNodeWithTag("batch-scan-bottles").assertIsDisplayed().assertIsEnabled()
            assertTrue("Captured label should advance to the next bottle only after use is confirmed", nextScanRequested)
            val savedBottle = repository.openBottleIntakeSession()!!.drafts.single()
            assertTrue(savedBottle.photoData?.isNotEmpty() == true)
            assertEquals(photoSha256(savedBottle.photoData!!), savedBottle.photoSha256)
            val diagnostics = LocalDiagnostics.preview()
            assertTrue(diagnostics.contains("LABEL_CAMERA_CAPTURE_STARTED"))
            assertTrue(diagnostics.contains("LABEL_CAMERA_CAPTURE_SAVED"))
        } finally {
            setTestContent { }
            driver.close()
            outputDirectory.listFiles()?.forEach(File::delete)
            outputDirectory.delete()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun unacceptedScannedBottleEvidenceRestoresAfterKeepLaterAndActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("cocktails_local_scan_review", android.content.Context.MODE_PRIVATE)
        val evidenceStore = PendingBottleEvidenceStore(SharedPreferencesPendingBottleEvidenceStorage(context))
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, "cocktails.db")
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(5)
        val priorSession = repository.openBottleIntakeSession()
        val home = repository.locations().first()
        val session = repository.startBottleIntakeSession(home.id, InventoryLevel.FULL, System.currentTimeMillis())
        val code = "5011333150725"
        val draft = repository.addBottleIntakeDraft(
            session.id, "Auchentoshan Three Wood", "Spirit", session.locationId, InventoryLevel.FULL,
            System.currentTimeMillis(), barcode = code, brand = "Auchentoshan", productName = "Auchentoshan Three Wood",
        )!!
        assertTrue(evidenceStore.save(draft.id, BottleDetailSuggestions(
            provider = "On-device Qwen3", model = "Qwen3-0.6B-Q8_0", sourceUrl = "https://shop.example/item",
            facts = listOf(BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Auchentoshan Three Wood", "UPC 5011333150725 · Auchentoshan Three Wood")),
            targetBarcode = code,
            identityEvidence = "UPC 5011333150725 · Auchentoshan Three Wood",
        )))
        preferences.edit().putString("active_draft_id", draft.id).commit()
        driver.close()

        try {
            composeRule.activityRule.scenario.recreate()
            openBottleIntake()
            assertRestoredBottleEvidenceVisible(draft.id)

            keepBottleIntakeForLater()
            openBottleIntake()
            assertRestoredBottleEvidenceVisible(draft.id)

            keepBottleIntakeForLater()
            composeRule.activityRule.scenario.recreate()
            openBottleIntake()
            assertRestoredBottleEvidenceVisible(draft.id)
        } finally {
            preferences.edit().remove("active_draft_id").commit()
            evidenceStore.remove(draft.id)
            val cleanupDriver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, "cocktails.db")
            val cleanup = LocalCocktailRepository(CocktailsDatabase(cleanupDriver))
            if (priorSession == null) cleanup.cancelBottleIntakeSession(session.id)
            else cleanup.removeBottleIntakeDraft(session.id, draft.id)
            cleanupDriver.close()
        }
    }

    private fun openBottleIntake() {
        composeRule.onNodeWithTag("navigation-my_bar").performClick()
        composeRule.onNodeWithTag("start-bottle-intake").performClick()
    }

    private fun editableTextIs(expected: String): SemanticsMatcher = SemanticsMatcher(
        "editable and input text equal '$expected'",
    ) { node ->
        node.config[SemanticsProperties.EditableText].text == expected &&
            node.config[SemanticsProperties.InputText].text == expected
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            val contentRoot = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
            val composeView = contentRoot.findComposeView()
                ?: error("MainActivity content has no ComposeView: ${contentRoot.describeChildren()}")
            composeView.setContent(content)
        }
    }

    private fun assertRestoredBottleEvidenceVisible(draftId: String) {
        val suggestions = "Suggestions · On-device Qwen3 · Qwen3-0.6B-Q8_0"
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("open-bottle-queue"))
        composeRule.onNodeWithTag("open-bottle-queue").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("queue-bottle-$draftId")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("queue-bottle-$draftId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("bottle-review-card-$draftId")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Review your bottle")
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-evidence-toggle"))
        composeRule.onNodeWithTag("review-evidence-toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-evidence-message-$draftId"))
        composeRule.onNodeWithTag("review-evidence-message-$draftId").assertIsDisplayed()
        // The suggestion card follows the restoration message and can be below the viewport.
        // Assert it exists in the source UI, then scroll it into view before checking visibility.
        composeRule.onNodeWithText(suggestions).assertExists()
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText(suggestions))
        composeRule.onNodeWithText(suggestions).assertIsDisplayed()
        // Keep returns directly to scanning without retrying or interrupting lookup.
        composeRule.onNodeWithTag("keep-bottle-review-draft").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Scan the next bottle")
        composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-scan-bottles"))
        composeRule.onNodeWithTag("batch-scan-bottles").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun captureFirstQueueKeepsScanningAvailableAndReviewIsExplicit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "capture-queue-${System.currentTimeMillis()}.db"
        context.deleteDatabase(databaseName)
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, context, databaseName)
        val repository = LocalCocktailRepository(CocktailsDatabase(driver))
        repository.initialize(1)
        val home = repository.locations().single()
        val session = repository.startBottleIntakeSession(home.id, InventoryLevel.FULL, 2)
        var rawQueue: String? = null
        val queueStorage = object : BottleEnrichmentQueueStorage {
            override fun read(): String? = rawQueue
            override fun write(value: String): Boolean { rawQueue = value; return true }
        }
        val queue = BottleEnrichmentQueue(queueStorage)
        val testLabelPhoto = syntheticLabelReferencePhoto()
        var activeSession by mutableStateOf(repository.openBottleIntakeSession()!!)
        var jobs by mutableStateOf<Map<String, BottleEnrichmentJob>>(emptyMap())
        var pendingLabelId by mutableStateOf<String?>(null)
        var capturedLabel by mutableStateOf<ByteArray?>(null)
        var activeReviewId by mutableStateOf<String?>(null)
        var webStates by mutableStateOf<Map<String, BottleWebSearchState>>(emptyMap())
        var route by mutableStateOf(BottleIntakeRoute.SETUP)
        var sequence = 10L
        val barcode = "5011333150725"

        fun updateJobs() { jobs = queue.jobs().associateBy { it.draftId } }
        fun scanAnotherBottle() {
            val draft = repository.addBottleIntakeDraft(
                session.id, "Unidentified bottle", "Spirit", home.id, InventoryLevel.FULL,
                sequence++, barcode = barcode,
            )!!
            queue.requireLabel(session.id, draft.id, sequence++)
            queue.begin(session.id, draft.id, BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = sequence++)
            pendingLabelId = draft.id
            activeSession = repository.openBottleIntakeSession()!!
            updateJobs()
        }

        try {
            setTestContent {
                CocktailsTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        BottleIntakeScreen(
                        modifier = Modifier.fillMaxSize(), session = activeSession, route = route, onRouteChange = { route = it }, homes = listOf(home),
                        capturePhoto = capturedLabel, originalCapturePhoto = capturedLabel, captureMessage = null, permissionMessage = null,
                        pendingLabelDraftId = pendingLabelId,
                        webSearchEnabled = false, webSearchStates = webStates, pendingEnrichmentJobs = jobs,
                        activeReviewDraftId = activeReviewId, ollamaCloudEnabled = false, localAiEnabled = false,
                        onSearchDraft = {}, onFetchDraftSource = { _, _ -> }, onExtractDraftDetails = {},
                        onExtractDraftDetailsOnDevice = {}, onResumeEnrichment = { _, _ -> }, onCancelDraftSearch = {},
                        onCaptureLabel = { capturedLabel = testLabelPhoto },
                        onUsePendingLabel = { draftId ->
                            val photo = capturedLabel
                            val draft = activeSession.drafts.firstOrNull { it.id == draftId }
                            if (photo != null && draft != null) {
                                assertTrue(repository.updateBottleIntakeDraft(draft.copy(photoData = photo, photoSha256 = photoSha256(photo))))
                                activeSession = repository.openBottleIntakeSession()!!
                            }
                            queue.completeLabelCapture(draftId, sequence++)
                            pendingLabelId = null
                            capturedLabel = null
                            route = BottleIntakeRoute.SCANNING
                            updateJobs()
                            true
                        },
                        onSkipPendingLabel = { draftId ->
                            queue.completeLabelCapture(draftId, sequence++)
                            pendingLabelId = null
                            route = BottleIntakeRoute.SCANNING
                            updateJobs()
                            true
                        },
                        onDiscardCapturedLabel = {}, onAttachCapturedLabel = {},
                        onScan = {
                            if (route == BottleIntakeRoute.SETUP) route = BottleIntakeRoute.SCANNING
                            else { scanAnotherBottle(); route = BottleIntakeRoute.CAPTURE_LABEL }
                        },
                        onReviewBottle = { activeReviewId = it; route = BottleIntakeRoute.REVIEW },
                        onAcceptAndScanNext = { activeReviewId = null; route = BottleIntakeRoute.SCANNING; BottleIntakeCommitResult.Invalid("Test only") },
                        onAddManual = { _, _, _, _, _, _, _ -> false },
                        onRemove = {}, onUpdate = { repository.updateBottleIntakeDraft(it) }, onFieldEdited = { _, _ -> },
                        onHomeChanged = {}, onLevelChanged = {},
                        onConfirm = { BottleIntakeCommitResult.Invalid("Test only") }, onCancel = {}, onDismiss = {},
                        onPauseQueue = { route = BottleIntakeRoute.PAUSE }, onResumeScanning = { route = BottleIntakeRoute.SCANNING },
                        )
                    }
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithText("CocktAIls").assertIsDisplayed()
            composeRule.onNodeWithTag("intake-setup-card-capture").assertIsDisplayed()
            composeRule.onNodeWithTag("intake-setup-card-background-work").assertIsDisplayed()
            composeRule.onNodeWithTag("intake-close").assertIsDisplayed()
            composeRule.onNodeWithTag("intake-allow-camera").assertIsDisplayed()
            captureOnboardingScreenshot("onboarding-setup-component-simulated.png")
            composeRule.onNodeWithTag("intake-allow-camera").performClick()
            composeRule.waitForIdle()
            captureOnboardingScreenshot("onboarding-scanning-route-simulated.png")
            composeRule.onNodeWithTag("batch-scan-bottles").assertIsEnabled().performClick()
            val first = activeSession.drafts.single()
            assertEquals(BottleEnrichmentStatus.RUNNING, jobs.getValue(first.id).status)
            assertTrue(jobs.getValue(first.id).labelCaptureRequired)
            composeRule.onNodeWithTag("capture-label-${first.id}").assertIsDisplayed()
            composeRule.onAllNodes(hasTestTag("batch-scan-bottles")).assertCountEquals(0)
            composeRule.waitForIdle()
            val labelStepScreenshotHash = captureOnboardingScreenshot("onboarding-label-step-simulated.png")

            composeRule.onNodeWithTag("capture-label-${first.id}").performClick()
            composeRule.waitForIdle()
            val labelPreviewScreenshotHash = captureOnboardingScreenshot("onboarding-label-preview-simulated.png")
            composeRule.onNodeWithTag("label-photo-preview-${first.id}").assertIsDisplayed()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("adjust-bottle-label-crop"))
            composeRule.onNodeWithTag("adjust-bottle-label-crop").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("bottle-label-crop-dialog").assertIsDisplayed()
            composeRule.onNodeWithTag("apply-bottle-label-crop").assertIsDisplayed()
            captureOnboardingScreenshot("onboarding-label-crop-dialog.png")
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.onNodeWithTag("use-label-${first.id}").assertIsDisplayed()
            assertTrue("The captured label preview must render differently from the empty capture step", labelStepScreenshotHash != labelPreviewScreenshotHash)
            composeRule.onNodeWithTag("use-label-${first.id}").assertIsDisplayed().performClick()
            assertEquals(BottleEnrichmentStatus.RUNNING, jobs.getValue(first.id).status)
            assertFalse(jobs.getValue(first.id).labelCaptureRequired)
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-scan-bottles"))
            composeRule.onNodeWithTag("batch-scan-bottles").assertIsEnabled().performClick()

            val second = activeSession.drafts.last()
            assertEquals(first.barcode, second.barcode)
            assertTrue(first.id != second.id)
            assertEquals(BottleEnrichmentStatus.RUNNING, jobs.getValue(first.id).status)
            assertEquals(BottleEnrichmentStatus.RUNNING, jobs.getValue(second.id).status)
            assertTrue(jobs.getValue(second.id).labelCaptureRequired)
            composeRule.onNodeWithTag("capture-label-${second.id}").assertExists()
            composeRule.onNodeWithTag("skip-label-${second.id}").assertIsDisplayed().performClick()
            assertEquals(BottleEnrichmentStatus.RUNNING, jobs.getValue(second.id).status)
            composeRule.onNodeWithTag("batch-scan-bottles").assertIsEnabled()

            composeRule.onNodeWithTag("open-bottle-queue").performClick()
            composeRule.waitForIdle()
            captureOnboardingScreenshot("onboarding-queue-simulated.png")
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("Resume scanner"))
            composeRule.onNodeWithText("Resume scanner").performClick()

            val previewDraft = activeSession.drafts.first { it.id == first.id }
            val sampleUrl = "https://example.invalid/sample-bottle"
            val sampleName = "Garden Sample Gin"
            assertTrue(repository.updateBottleIntakeDraft(previewDraft.copy(
                ingredientName = sampleName,
                category = "Spirit",
                brand = "Juniper House",
                productName = sampleName,
                quantity = "700 mL",
                abvPercent = "40%",
            )))
            activeSession = repository.openBottleIntakeSession()!!
            webStates = webStates + (first.id to BottleWebSearchState(
                automatedScan = true,
                barcodeVerifiedInPage = true,
                page = WebPageText("Sample bottle reference", sampleUrl, "Sample code $barcode · Garden Sample Gin · Juniper House · 700 mL · 40% ABV", "Fixture"),
                extractedSuggestions = BottleDetailSuggestions(
                    provider = "Sample fixture",
                    model = "UI review sample",
                    sourceUrl = sampleUrl,
                    facts = listOf(
                        BottleFactEvidence(BottleFactField.PRODUCT_NAME, sampleName, "Garden Sample Gin"),
                        BottleFactEvidence(BottleFactField.BRAND, "Juniper House", "Juniper House"),
                        BottleFactEvidence(BottleFactField.QUANTITY, "700 mL", "700 mL"),
                        BottleFactEvidence(BottleFactField.ABV_PERCENT, "40%", "40% ABV"),
                    ),
                    targetBarcode = barcode,
                    identityEvidence = "Sample code $barcode · Garden Sample Gin",
                ),
            ))
            assertTrue(queue.finish(first.id, BottleEnrichmentStatus.READY_FOR_REVIEW, "CANDIDATES_READY", sequence++))
            updateJobs()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-ready-bottles"))
            composeRule.onNodeWithTag("review-ready-bottles").performClick()
            assertEquals(first.id, activeReviewId)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("review-label-photo-${first.id}").assertIsDisplayed()
            composeRule.onNodeWithTag("bottle-intake-draft-name-0").assert(editableTextIs(sampleName))
            composeRule.onNodeWithTag("bottle-intake-draft-category-0").assert(editableTextIs("Spirit"))
            captureOnboardingScreenshot("onboarding-review-simulated.png")
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("review-evidence-toggle"))
            composeRule.onNodeWithTag("review-evidence-toggle").assertIsDisplayed()
            composeRule.onNodeWithTag("accept-scanned-bottle-${first.id}").assertIsDisplayed()
            route = BottleIntakeRoute.PAUSE
            composeRule.waitForIdle()
            captureOnboardingScreenshot("onboarding-pause-simulated.png")
        } finally {
            repository.cancelBottleIntakeSession(session.id)
            driver.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun View.findComposeView(): ComposeView? {
        if (this is ComposeView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findComposeView()?.let { return it }
        }
        return null
    }

    private fun View.describeChildren(): String = buildString {
        append(javaClass.name)
        if (this@describeChildren is ViewGroup) {
            append("[")
            append((0 until childCount).joinToString { index -> getChildAt(index).describeChildren() })
            append("]")
        }
    }

    private fun keepBottleIntakeForLater() {
        composeRule.onNodeWithTag("intake-pause").performClick()
        composeRule.onNodeWithText("Keep drafts & exit").performClick()
    }

    private fun captureOnboardingScreenshot(fileName: String): String {
        val digest = saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), fileName)
        Log.i("CocktAIlsOnboardingScreenshot", "$fileName captured in app-private test storage for CI collection")
        return digest
    }

    /** Generated, fictional label used only to make the review preview legible in screenshots. */
    private fun syntheticLabelReferencePhoto(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(420, 620, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(232, 229, 218))
            val bottlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(44, 72, 57)
            }
            canvas.drawRoundRect(72f, 118f, 348f, 598f, 64f, 64f, bottlePaint)
            canvas.drawRoundRect(170f, 54f, 250f, 180f, 18f, 18f, bottlePaint)
            canvas.drawRect(178f, 32f, 242f, 76f, bottlePaint)
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(243, 236, 211)
            }
            canvas.drawRoundRect(100f, 230f, 320f, 482f, 12f, 12f, labelPaint)
            val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(148, 112, 54)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
            }
            canvas.drawRoundRect(112f, 242f, 308f, 470f, 8f, 8f, borderPaint)
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(46, 57, 43)
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            textPaint.textSize = 24f
            canvas.drawText("SAMPLE LABEL", 210f, 320f, textPaint)
            textPaint.textSize = 36f
            canvas.drawText("BOTANICAL", 210f, 372f, textPaint)
            canvas.drawText("GIN", 210f, 416f, textPaint)
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
            return stripJpegMetadata(output.toByteArray())
                ?: error("Synthetic label fixture did not pass product-photo metadata sanitation")
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertBatchCountVisibleWithDiagnostics() {
        try {
            composeRule.onNodeWithTag("open-bottle-queue").assertTextEquals("Your bottle queue · 2")
        } catch (failure: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.uiAutomation
            val semantics = runCatching {
                val node = composeRule.onNodeWithTag("open-bottle-queue").fetchSemanticsNode()
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
