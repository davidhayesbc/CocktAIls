package com.davidhayesbc.cocktails.android.ui

import android.content.ClipboardManager
import android.database.Cursor
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davidhayesbc.cocktails.android.MainActivity
import com.davidhayesbc.cocktails.android.saveTestScreenshot
import com.davidhayesbc.cocktails.android.product.DetailedCaptureArmResult
import com.davidhayesbc.cocktails.android.product.DetailedCaptureStatus
import com.davidhayesbc.cocktails.android.product.DetailedBottleLookupCaptureStore
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import com.davidhayesbc.cocktails.android.ui.DebugSettingsRouteState
import com.davidhayesbc.cocktails.android.ui.routeFromDiagnosticsToDebugSettings
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailedBottleLookupCaptureUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun detailedCaptureRequiresPreviewBeforeExplicitCopy() {
        val capture = """{"draftId":"draft-ui-test","sourceText":"Public source excerpt","response":"Model response"}"""
        val content = mutableStateOf(capture)
        val copied = mutableStateOf(false)

        setContent {
            CaptureDialogHost(
                content = content.value,
                onCopied = { copied.value = true },
            )
        }

        composeRule.onNodeWithTag("preview-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detailed-lookup-capture-preview").assertIsDisplayed()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-capture-preview.png")
        assertPreviewActionsDoNotOverlap()
        scrollThroughPreviewActions()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-capture-preview-actions.png")
        composeRule.onNodeWithTag("copy-detailed-lookup-capture").performScrollTo()
        composeRule.onNodeWithTag("copy-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { copied.value }

        val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(capture, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        composeRule.onAllNodes(hasTestTag("detailed-lookup-capture-preview")).assertCountEquals(0)
    }

    @Test
    fun previewActionsRemainSeparateAndReachableWithLargeText() {
        val content = "{\"sourceText\":\"${"Public source excerpt. ".repeat(100)}\"}"
        setContent {
            CaptureDialogHost(
                content = content,
                onCopied = {},
                fontScale = 1.5f,
            )
        }

        composeRule.onNodeWithTag("preview-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detailed-lookup-capture-preview").assertIsDisplayed()
        assertPreviewActionsDoNotOverlap()
        scrollThroughPreviewActions()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-capture-preview-large-font.png")
    }

    @Test
    fun settingsDebugModeRequiresConsentAndShowsBoundedSessionStatus() {
        setContent { ConsentDialogHost() }

        composeRule.onNodeWithTag("detailed-debug-mode-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("detailed-debug-mode-status").performScrollTo().assertTextContains("OFF", substring = true)
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Enable debug mode?").assertIsDisplayed()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-consent.png")
        composeRule.onNodeWithTag("detailed-debug-mode-status").performScrollTo().assertTextContains("OFF", substring = true)
        composeRule.onNodeWithTag("confirm-enable-detailed-debug-mode").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detailed-debug-mode-status").performScrollTo().assertTextContains("ON", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-armed.png")
    }

    @Test
    fun settingsShowsBottleScopedCapturedReportPreviewAction() {
        val previewedDraft = mutableStateOf<String?>(null)
        setContent {
            CocktailsTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        DetailedCaptureDebugModeSettings(
                        status = DetailedCaptureStatus("ACTIVE", null, null, 2_000_000L, 1_000_000L, null, sessionActive = true, captureCount = 1, captureLimit = 5),
                        reports = listOf(DetailedCaptureStatus("FAILED", "draft-captured", "00000000-0000-0000-0000-000000000099", 2_000_000L, 1_000_000L, "Qwen3-0.6B-Q8_0", captureId = "00000000-0000-0000-0000-000000000098")),
                        bottleLabels = mapOf("00000000-0000-0000-0000-000000000098" to "Bottle 3 · Auchentoshan Three Wood"),
                        message = null,
                        enabled = true,
                        onEnable = {},
                        onDisable = {},
                        onPreview = { previewedDraft.value = it },
                        onDelete = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("1/5", substring = true)
        composeRule.onNodeWithText("Bottle 3 · Auchentoshan Three Wood · failed", substring = true).assertIsDisplayed()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-captured.png")
        composeRule.onNodeWithTag("preview-captured-debug-report").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("00000000-0000-0000-0000-000000000098", previewedDraft.value)
    }

    @Test
    fun actualMoreRouteShowsDebugModeUsingProductionThemeAndSettingsLayout() {
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithTag("household-scroll")
            .performScrollToNode(hasTestTag("detailed-debug-mode-settings"))
        composeRule.onNodeWithTag("detailed-debug-mode-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("OFF", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-settings-real-route.png")
    }

    @Test
    fun debugSessionAndMultipleReportsRemainReachableWithLargeText() {
        val firstId = "00000000-0000-0000-0000-000000000091"
        val secondId = "00000000-0000-0000-0000-000000000092"
        val density = composeRule.activity.resources.displayMetrics.density
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                CocktailsTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            DetailedCaptureDebugModeSettings(
                            status = DetailedCaptureStatus("ACTIVE", null, null, 2_000_000L, 1_000_000L, null, sessionActive = true, captureCount = 2, captureLimit = 5),
                            reports = listOf(
                                DetailedCaptureStatus("PARTIAL", "draft-a", "00000000-0000-0000-0000-000000000191", 2_000_000L, 1_000_000L, "Qwen3-0.6B-Q8_0", captureId = firstId),
                                DetailedCaptureStatus("FAILED", "draft-a", "00000000-0000-0000-0000-000000000192", 2_000_000L, 1_000_001L, "Qwen3-0.6B-Q8_0", captureId = secondId),
                            ),
                            bottleLabels = mapOf(firstId to "Bottle 1 · Auchentoshan Three Wood", secondId to "Bottle 1 · Auchentoshan Three Wood"),
                            message = null, enabled = true, onEnable = {}, onDisable = {}, onPreview = {}, onDelete = {},
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("detailed-debug-mode-status").performScrollTo().assertTextContains("2/5", substring = true)
        composeRule.onNodeWithTag("preview-captured-debug-report").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("preview-captured-debug-report-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("delete-captured-debug-report-1").performScrollTo().assertIsDisplayed()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-session-large-font.png")
    }

    @Test
    fun settingsDoesNotArmDebugCaptureWithoutInstalledEnabledLocalModel() {
        setContent {
            CocktailsTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        DetailedCaptureDebugModeSettings(
                        status = null,
                        reports = emptyList(),
                        message = null,
                        enabled = false,
                        onEnable = {},
                        onDisable = {},
                        onPreview = {},
                        onDelete = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").performScrollTo()
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").assertIsNotEnabled()
        composeRule.onNodeWithText("Enable on-device model suggestions in More and install the Qwen3 model before starting a debug session.")
            .assertIsDisplayed()
    }

    @Test
    fun diagnosticsRouteOpensMoreSettingsAndPreservesOpenIntakeDrafts() {
        val initial = DebugSettingsRouteState(
            selectedPage = "MY_BAR",
            bottleIntakeVisible = true,
            diagnosticsVisible = true,
            diagnosticsDraftId = "draft-under-review",
            intakeSessionId = "intake-session-1",
            intakeDraftIds = listOf("draft-under-review", "draft-pending"),
        )
        var resulting = initial
        setContent {
            CocktailsTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state = androidx.compose.runtime.remember { mutableStateOf(initial) }
                    resulting = state.value
                    if (state.value.diagnosticsVisible) {
                        DiagnosticsDialog(
                        preview = "Local diagnostics",
                        lookupPreview = null,
                        lookupSubject = "Lookup report for this bottle",
                        lookupMissingMessage = "No lookup report",
                        lookupCopyMessage = null,
                        detailedCaptureStatus = null,
                        detailedCaptureMessage = null,
                        onOpenDetailedDebugModeSettings = { state.value = routeFromDiagnosticsToDebugSettings(state.value) },
                        onPreviewDetailedCapture = {}, onDeleteDetailedCapture = {}, onCopyLookup = {},
                        onExport = {}, onClear = { true }, onDismiss = {},
                        )
                    } else if (state.value.selectedPage == "MORE" && !state.value.bottleIntakeVisible) {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            DetailedCaptureDebugModeSettings(
                            status = DetailedCaptureStatus("OFF", null, null, 0, null, null),
                            reports = emptyList(), message = null, enabled = true, onEnable = {}, onDisable = {},
                            onPreview = {}, onDelete = {},
                            )
                            androidx.compose.material3.Text("Draft session ${state.value.intakeSessionId}: ${state.value.intakeDraftIds.joinToString()}", modifier = androidx.compose.ui.Modifier.testTag("preserved-intake-drafts"))
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("open-debug-mode-settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detailed-debug-mode-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("preserved-intake-drafts").performScrollTo().assertTextContains("intake-session-1", substring = true)
        composeRule.onNodeWithTag("preserved-intake-drafts").assertTextContains("draft-under-review", substring = true)
        composeRule.onNodeWithTag("preserved-intake-drafts").assertTextContains("draft-pending", substring = true)
        assertEquals("MORE", resulting.selectedPage)
        assertTrue(!resulting.bottleIntakeVisible && !resulting.diagnosticsVisible)
        assertEquals(initial.intakeSessionId, resulting.intakeSessionId)
        assertEquals(initial.intakeDraftIds, resulting.intakeDraftIds)
    }

    @Test
    fun productionShareUriDeniesOpenAndMetadataRequestsAfterExpiry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val draftId = "share-${UUID.randomUUID()}"
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() - 60 * 60 * 1000L + 10_000L
        val storeFile = File(context.noBackupFilesDir, "detailed-capture-test-${UUID.randomUUID()}.json")
        val store = DetailedBottleLookupCaptureStore(
            storeFile,
            { now },
            shareCacheDirectory = context.cacheDir,
            processSessionId = "instrumentation-test",
        )
        assertEquals(DetailedCaptureArmResult.ARMED, store.arm(draftId, token, "preview", now))
        val recorder = store.claim(draftId, token, "5011333150725", "preview", 1, "test-model", now)
            ?: error("The test capture should be claimable")
        recorder.finish("COMPLETE")
        val reviewed = store.previewForDraft(draftId, now) ?: error("The completed capture should be reviewable")
        val sharedFile = store.exportReviewedCopyForDraft(draftId, reviewed, context.cacheDir, now)
            ?: error("The reviewed capture should export to the configured share cache")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sharedFile)
        assertEquals("detailed_lookup_share", uri.pathSegments.firstOrNull())

        assertEquals("application/json", context.contentResolver.getType(uri))
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor -> assertTrue(cursor.moveToFirst()) }
        context.contentResolver.openInputStream(uri)?.use { input -> assertTrue(input.read() >= 0) }

        val expiresAt = Regex("^lookup-[0-9a-f]{12}-(\\d+)-[0-9a-fA-F-]{36}\\.json$")
            .matchEntire(sharedFile.name)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("The shared copy should carry its enforced expiry")
        val waitMillis = (expiresAt - System.currentTimeMillis() + 100L).coerceAtLeast(0L)
        Thread.sleep(waitMillis)

        assertExpiredTypeDenied { context.contentResolver.getType(uri) }
        assertExpiredQueryDenied { context.contentResolver.query(uri, null, null, null, null) }
        assertOpenDenied { context.contentResolver.openInputStream(uri)?.close() }
        store.deleteForDraft(draftId)
    }

    @Test
    fun deletingAnUnreadableCaptureDoesNotReportSuccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "detailed-capture-unreadable-${UUID.randomUUID()}.json")
        val store = DetailedBottleLookupCaptureStore(file, processSessionId = "instrumentation-test")
        file.writeText("not a valid capture")

        assertTrue("unreadable data must not be reported as deleted", !store.deleteForDraft("draft-test"))
        assertTrue(file.exists())
        assertTrue(store.deleteAll())
        assertTrue(!file.exists())
    }

    private fun assertExpiredTypeDenied(request: () -> String?) {
        var denied = false
        try {
            denied = request() == null
        } catch (_: IllegalArgumentException) {
            denied = true
        }
        assertTrue("expired share MIME metadata should be denied", denied)
    }

    private fun assertExpiredQueryDenied(request: () -> Cursor?) {
        var denied = false
        try {
            val cursor = request()
            denied = cursor == null || cursor.use { !it.moveToFirst() }
        } catch (_: IllegalArgumentException) {
            denied = true
        }
        assertTrue("expired share query must not return metadata", denied)
    }

    private fun assertOpenDenied(request: () -> Unit) {
        var denied = false
        try {
            request()
        } catch (_: FileNotFoundException) {
            denied = true
        }
        assertTrue("expired share content should be denied", denied)
    }

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            val contentRoot = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
            val composeView = contentRoot.findComposeView() ?: error("MainActivity content has no ComposeView")
            composeView.setContent(content)
        }
    }

    @Composable
    private fun CaptureDialogHost(content: String, onCopied: () -> Unit, fontScale: Float = 1f) {
        val preview = androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
        val status = androidx.compose.runtime.remember {
            DetailedCaptureStatus("COMPLETE", "draft-ui-test", "00000000-0000-0000-0000-000000000099", 2_000_000L, 1_000_000L, "Qwen3-0.6B-Q8_0")
        }
        val currentDensity = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, fontScale)) {
            CocktailsTheme(darkTheme = true) {
                if (preview.value == null) {
                    DiagnosticsDialog(
                        preview = "Local diagnostics",
                        lookupPreview = "Latest report",
                        lookupSubject = "Lookup report for this bottle",
                        lookupMissingMessage = "No lookup report",
                        lookupCopyMessage = null,
                        detailedCaptureStatus = status,
                        detailedCaptureMessage = null,
                        onOpenDetailedDebugModeSettings = {},
                        onPreviewDetailedCapture = { preview.value = content },
                        onDeleteDetailedCapture = {},
                        onCopyLookup = {},
                        onExport = {},
                        onClear = { true },
                        onDismiss = {},
                    )
                } else {
                    DetailedLookupCapturePreviewDialog(
                        preview = preview.value.orEmpty(),
                        onCopy = {
                            copyDetailedLookupCaptureToClipboard(composeRule.activity, preview.value.orEmpty())
                            preview.value = null
                            onCopied()
                        },
                        onShare = {},
                        onDelete = {},
                        onDismiss = { preview.value = null },
                    )
                }
            }
        }
    }

    private fun assertPreviewActionsDoNotOverlap() {
        val tags = listOf(
            "copy-detailed-lookup-capture",
            "share-detailed-lookup-capture",
            "delete-detailed-lookup-capture-from-preview",
            "done-detailed-lookup-capture",
        )
        val bounds = tags.map { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        bounds.forEachIndexed { index, first ->
            assertTrue("Action $index should have a visible layout", first.width > 0f && first.height > 0f)
            bounds.drop(index + 1).forEach { second ->
                val overlap = first.intersect(second)
                assertTrue("Preview actions must not overlap: $first and $second", overlap.width <= 0f || overlap.height <= 0f)
            }
        }
    }

    private fun scrollThroughPreviewActions() {
        listOf(
            "copy-detailed-lookup-capture",
            "share-detailed-lookup-capture",
            "delete-detailed-lookup-capture-from-preview",
            "done-detailed-lookup-capture",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Composable
    private fun ConsentDialogHost() {
        val consent = androidx.compose.runtime.remember { mutableStateOf(false) }
        val status = androidx.compose.runtime.remember { mutableStateOf<DetailedCaptureStatus?>(null) }
        CocktailsTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    DetailedCaptureDebugModeSettings(
                    status = status.value,
                    reports = emptyList(),
                    message = null,
                    enabled = true,
                    onEnable = { consent.value = true },
                    onDisable = { status.value = null },
                    onPreview = {},
                    onDelete = {},
                    )
                }
                if (consent.value) {
                    DetailedCaptureDebugModeConsentDialog(
                    onConfirm = {
                        consent.value = false
                        status.value = DetailedCaptureStatus("ACTIVE", null, null, 2_000_000L, null, null, nextLookupArmed = true, sessionActive = true, captureLimit = 5)
                    },
                    onDismiss = { consent.value = false },
                    )
                }
            }
        }
    }

    private fun View.findComposeView(): ComposeView? {
        if (this is ComposeView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findComposeView()?.let { return it }
        return null
    }
}
