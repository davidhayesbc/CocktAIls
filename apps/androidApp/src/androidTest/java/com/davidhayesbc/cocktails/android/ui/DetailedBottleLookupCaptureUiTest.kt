package com.davidhayesbc.cocktails.android.ui

import android.content.ClipboardManager
import android.database.Cursor
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davidhayesbc.cocktails.android.MainActivity
import com.davidhayesbc.cocktails.android.saveTestScreenshot
import com.davidhayesbc.cocktails.android.product.DetailedCaptureArmResult
import com.davidhayesbc.cocktails.android.product.DetailedCaptureStatus
import com.davidhayesbc.cocktails.android.product.DetailedBottleLookupCaptureStore
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
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
        composeRule.onNodeWithTag("copy-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { copied.value }

        val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(capture, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        composeRule.onAllNodes(hasTestTag("detailed-lookup-capture-preview")).assertCountEquals(0)
    }

    @Test
    fun captureIsNotArmedUntilConsentIsConfirmed() {
        setContent { ConsentDialogHost() }

        composeRule.onAllNodes(hasTestTag("detailed-capture-status")).assertCountEquals(0)
        composeRule.onNodeWithTag("arm-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Capture one lookup attempt?").assertIsDisplayed()
        saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "detailed-lookup-capture-consent.png")
        composeRule.onAllNodes(hasTestTag("detailed-capture-status")).assertCountEquals(0)
        composeRule.onNodeWithTag("confirm-arm-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detailed-capture-status").performScrollTo().assertIsDisplayed().assertTextContains("Capture armed")
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
    private fun CaptureDialogHost(content: String, onCopied: () -> Unit) {
        val preview = androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
        val status = androidx.compose.runtime.remember {
            DetailedCaptureStatus("COMPLETE", "draft-ui-test", "00000000-0000-0000-0000-000000000099", 2_000_000L, 1_000_000L, "Qwen3-0.6B-Q8_0")
        }
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
                    onArmDetailedCapture = {},
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

    @Composable
    private fun ConsentDialogHost() {
        val consent = androidx.compose.runtime.remember { mutableStateOf(false) }
        val armed = androidx.compose.runtime.remember { mutableStateOf(false) }
        CocktailsTheme(darkTheme = true) {
            DiagnosticsDialog(
                preview = "Local diagnostics",
                lookupPreview = "Latest report",
                lookupSubject = "Lookup report for this bottle",
                lookupMissingMessage = "No lookup report",
                lookupCopyMessage = null,
                detailedCaptureStatus = if (armed.value) {
                    DetailedCaptureStatus("ARMED", "draft-ui-test", null, 2_000_000L, null, null)
                } else null,
                detailedCaptureMessage = null,
                onArmDetailedCapture = { consent.value = true },
                onPreviewDetailedCapture = {},
                onDeleteDetailedCapture = {},
                onCopyLookup = {},
                onExport = {},
                onClear = { true },
                onDismiss = {},
            )
            if (consent.value) {
                DetailedLookupCaptureConsentDialog(
                    onConfirm = { consent.value = false; armed.value = true },
                    onDismiss = { consent.value = false },
                )
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
