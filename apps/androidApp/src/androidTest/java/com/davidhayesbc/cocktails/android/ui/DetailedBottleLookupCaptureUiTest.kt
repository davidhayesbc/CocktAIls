package com.davidhayesbc.cocktails.android.ui

import android.content.ClipboardManager
import android.database.Cursor
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.MainActivity
import com.davidhayesbc.cocktails.android.saveTestScreenshot
import com.davidhayesbc.cocktails.android.product.DetailedBottleLookupCaptureStore
import com.davidhayesbc.cocktails.android.product.DetailedBottleLookupDebugSessionStore
import com.davidhayesbc.cocktails.android.product.DetailedCaptureArmResult
import com.davidhayesbc.cocktails.android.product.DetailedCaptureStatus
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailedBottleLookupCaptureUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsToggleIsPersistentAndExplainsLocalCapture() {
        val enabled = mutableStateOf(false)
        setContent { DebugSettingsHost(enabled.value, onChange = { enabled.value = it }) }

        composeRule.onNodeWithTag("detailed-debug-mode-disclosure").assertIsDisplayed().assertTextContains("Nothing is uploaded", substring = true)
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("OFF", substring = true)
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { enabled.value }
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("ON", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-on.png")
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").performClick()
        composeRule.waitUntil(5_000) { !enabled.value }
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("OFF", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-off.png")
    }

    @Test
    fun actualMoreRouteShowsPersistentDebugToggleInProductionTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DetailedBottleLookupDebugSessionStore(context.noBackupFilesDir).disableDebugMode()
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithTag("detailed-debug-mode-settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("OFF", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-settings-real-route.png")
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").performClick()
        composeRule.waitUntil(5_000) {
            DetailedBottleLookupDebugSessionStore(context.noBackupFilesDir).sessionStatus().sessionActive
        }
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("ON", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-settings-on-real-route.png")
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").performClick()
        composeRule.waitUntil(5_000) {
            !DetailedBottleLookupDebugSessionStore(context.noBackupFilesDir).sessionStatus().sessionActive
        }
    }

    @Test
    fun productionDiagnosticsCopiesSavedBottleScopedDetailedCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DetailedBottleLookupDebugSessionStore(context.noBackupFilesDir, shareCacheDirectory = context.cacheDir)
        store.disableDebugMode()
        assertTrue(store.deleteAll())
        assertEquals(DetailedCaptureArmResult.ARMED, store.enableDebugMode("instrumentation"))
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("View or export diagnostics").performScrollTo().performClick()
        composeRule.onNodeWithTag("detailed-capture-inclusion-status").assertTextContains("Detailed captures included: none", substring = true)

        val draftA = "debug-ui-a-${UUID.randomUUID()}"
        val draftB = "debug-ui-b-${UUID.randomUUID()}"
        val attemptA = UUID.randomUUID().toString()
        val attemptB = UUID.randomUUID().toString()
        val recorderB = store.claim(draftB, attemptB, "5011733110725", "instrumentation", 1L, "Qwen3-0.6B-Q8_0", allowNextLookup = true)
            ?: error("Second eligible on-device test attempt should be captured")
        recorderB.recordModelResponse("On-device Qwen3", "Qwen3-0.6B-Q8_0", "second bottle marker", generationFinishReason = "END_OF_SEQUENCE")
        recorderB.finish("COMPLETE")
        val recorderA = store.claim(draftA, attemptA, "5011333150725", "instrumentation", 1L, "Qwen3-0.6B-Q8_0", allowNextLookup = true)
            ?: error("First eligible on-device test attempt should be captured")
        recorderA.recordValidatorInput("On-device Qwen3", "5011333150725", "Public product source excerpt.", "Product", emptyList(), null)
        recorderA.recordModelResponse("On-device Qwen3", "Qwen3-0.6B-Q8_0", "first bottle token-limit marker", generationFinishReason = "TOKEN_LIMIT", outputTokenLimitReached = true)
        recorderA.finish("FAILED")
        composeRule.waitUntil(5_000) { store.reportContents().size == 2 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Detailed captures included: 2", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("detailed-capture-inclusion-status").assertTextContains("Detailed captures included: 2", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-capture-diagnostics-with-report.png")
        composeRule.onNodeWithTag("copy-diagnostics").performClick()

        val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
        composeRule.waitUntil(5_000) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains(draftA) == true
        }
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains(draftA))
        assertTrue(copied.contains(draftB))
        assertTrue(copied.contains(attemptA))
        assertTrue(copied.contains(attemptB))
        assertTrue(copied.contains("first bottle token-limit marker"))
        assertTrue(copied.contains("second bottle marker"))
        assertTrue(copied.contains("TOKEN_LIMIT"))
        val captureA = store.captureStatuses().single { it.draftId == draftA }.captureId!!
        assertTrue(store.deleteCapture(captureA))
        composeRule.waitUntil(5_000) { store.reportContents().size == 1 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Detailed captures included: 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("detailed-capture-inclusion-status").assertTextContains("Detailed captures included: 1", substring = true)
        composeRule.onNodeWithTag("copy-diagnostics").performClick()
        composeRule.waitUntil(5_000) {
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            !current.contains(draftA) && current.contains(draftB)
        }
        val refreshedCopy = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertFalse("Deleted report must not be copied from the previously opened dialog snapshot", refreshedCopy.contains(draftA))
        assertTrue(refreshedCopy.contains(draftB))
        assertTrue(store.disableDebugMode())
        assertTrue(store.deleteAll())
    }

    @Test
    fun diagnosticsCopyIncludesAvailableDetailedCaptureAndHasOnlyCopyExportActions() {
        val detailed = """{"draftId":"draft-a","attemptToken":"attempt-a","modelResponse":"partial output","generationFinishReason":"TOKEN_LIMIT"}"""
        val copied = mutableStateOf(false)
        val exportRequested = mutableStateOf(false)
        setContent {
            CocktailsTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    DiagnosticsDialogHost(detailed, onCopied = { copied.value = true }, onExport = { exportRequested.value = true })
                }
            }
        }

        composeRule.onNodeWithTag("detailed-capture-inclusion-status").assertTextContains("Detailed captures included: 1", substring = true)
        composeRule.onAllNodesWithText("Copy standard lookup summary").assertCountEquals(0)
        composeRule.onNodeWithTag("copy-diagnostics").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { copied.value }
        val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
        val copiedText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copiedText.contains("partial output"))
        assertTrue(copiedText.contains("TOKEN_LIMIT"))
        assertTrue(copiedText.contains("\"detailedCapturesIncluded\": true"))
        composeRule.onNodeWithTag("export-diagnostics").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { exportRequested.value }
        composeRule.onNodeWithTag("latest-lookup-report-preview").assertIsDisplayed()
    }

    @Test
    fun diagnosticsActionsStaySeparateAndReachableWithLargeText() {
        val density = composeRule.activity.resources.displayMetrics.density
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                CocktailsTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                        DiagnosticsDialogHost(
                            detailed = "{\"modelResponse\":\"${"long diagnostic text ".repeat(120)}\"}",
                            onCopied = {},
                            onExport = {},
                        )
                    }
                }
            }
        }
        val copy = composeRule.onNodeWithTag("copy-diagnostics").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val export = composeRule.onNodeWithTag("export-diagnostics").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse("Copy and Export actions must have separate bounds", copy.overlaps(export))
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-capture-diagnostics-large-text.png")
    }

    @Test
    fun largeTextSettingsDisclosureAndToggleRemainReachable() {
        val density = composeRule.activity.resources.displayMetrics.density
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                DebugSettingsHost(isOn = true, onChange = {})
            }
        }
        composeRule.onNodeWithTag("detailed-debug-mode-disclosure").assertIsDisplayed()
        composeRule.onNodeWithTag("detailed-debug-mode-toggle").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("detailed-debug-mode-status").assertTextContains("ON", substring = true)
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-debug-mode-large-text.png")
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

        assertFalse("unreadable data must not be reported as deleted", store.deleteForDraft("draft-test"))
        assertTrue(file.exists())
        assertTrue(store.deleteAll())
        assertFalse(file.exists())
    }

    private fun assertExpiredTypeDenied(request: () -> String?) {
        var denied = false
        try { denied = request() == null } catch (_: IllegalArgumentException) { denied = true }
        assertTrue("expired share MIME metadata should be denied", denied)
    }

    private fun assertExpiredQueryDenied(request: () -> Cursor?) {
        var denied = false
        try {
            val cursor = request()
            denied = cursor == null || cursor.use { !it.moveToFirst() }
        } catch (_: IllegalArgumentException) { denied = true }
        assertTrue("expired share query must not return metadata", denied)
    }

    private fun assertOpenDenied(request: () -> Unit) {
        var denied = false
        try { request() } catch (_: FileNotFoundException) { denied = true }
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
    private fun DebugSettingsHost(isOn: Boolean, onChange: (Boolean) -> Unit) {
        CocktailsTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DetailedCaptureDebugModeSettings(
                        status = DetailedCaptureStatus(if (isOn) "ACTIVE" else "OFF", null, null, 0, null, null, sessionActive = isOn, captureCount = if (isOn) 2 else 0),
                        message = null,
                        enabled = true,
                        onEnable = { onChange(true) },
                        onDisable = { onChange(false) },
                    )
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsDialogHost(detailed: String, onCopied: () -> Unit, onExport: () -> Unit) {
        DiagnosticsDialog(
            preview = "{}",
            lookupPreview = "Latest bottle lookup report",
            lookupSubject = "Latest bottle lookup report",
            lookupMissingMessage = "No lookup report yet.",
            lookupCopyMessage = null,
            detailedCaptureCount = 1,
            actionsEnabled = true,
            onCopy = {
                val content = com.davidhayesbc.cocktails.android.product.combineBottleDiagnostics("{}", listOf(detailed))
                val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CocktAIls diagnostics", content))
                onCopied()
            },
            onExport = onExport,
            onDismiss = {},
        )
    }

    private fun ViewGroup.findComposeView(): ComposeView? {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is ComposeView) return child
            if (child is ViewGroup) child.findComposeView()?.let { return it }
        }
        return null
    }
}
