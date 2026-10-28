package com.davidhayesbc.cocktails.android.ui

import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davidhayesbc.cocktails.android.MainActivity
import com.davidhayesbc.cocktails.android.saveTestScreenshot
import com.davidhayesbc.cocktails.android.product.DetailedCaptureStatus
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import org.junit.Assert.assertEquals
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
        composeRule.onAllNodes(hasTestTag("detailed-capture-status")).assertCountEquals(0)
        composeRule.onNodeWithTag("confirm-arm-detailed-lookup-capture").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("detailed-capture-status").assertIsDisplayed().assertTextContains("Capture armed")
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
