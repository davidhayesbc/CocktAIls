package com.davidhayesbc.cocktails.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestName

@RunWith(AndroidJUnit4::class)
class BarcodeScannerLifecycleTest {
    @get:Rule
    val testName = TestName()

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun denialKeepsManualEntryAvailableThenGrantOpensScannerAcrossLifecycle() {
        try {
            assertEquals(PackageManager.PERMISSION_DENIED, composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA))
            composeRule.onNodeWithText("My Bar").performClick()
            composeRule.onAllNodesWithTag("inventory-add-ingredient").assertCountEquals(0)
            composeRule.onNodeWithTag("start-bottle-intake").performClick()
            composeRule.onNodeWithTag("intake-close").assertIsDisplayed()
            saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "onboarding-integrated-setup.png")
            composeRule.onNodeWithTag("intake-allow-camera").performClick()
            clickPermissionPrompt("Don't allow", "Do not allow", "Deny")
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-manual-entry"))
            composeRule.onNodeWithTag("batch-manual-entry").performClick()
            composeRule.onNodeWithTag("batch-manual-name").performTextInput("CI Manual Barcode Gin")
            composeRule.onNodeWithTag("batch-manual-barcode").performTextInput("036000291452")
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-add-manual-bottle"))
            composeRule.onNodeWithTag("batch-add-manual-bottle").performClick()
            assertEquals(PackageManager.PERMISSION_DENIED, composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA))
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasTestTag("batch-scan-bottles"))
            composeRule.onNodeWithTag("batch-scan-bottles").performClick()
            clickPermissionPrompt("While using the app", "Allow only while using the app", "Only this time", "Allow")
            awaitCameraPermissionGranted()
            awaitIntakeScanner()
            composeRule.onNodeWithTag("barcode-intake-scanner-title").assertTextEquals("Scan the next bottle").assertIsDisplayed()
            composeRule.onNodeWithTag("scanner-queue-summary").assertIsDisplayed()
            composeRule.onNodeWithTag("scanner-ready-count").assertTextEquals("0 ready")
            composeRule.onNodeWithTag("scanner-exit").assertIsDisplayed()
            val title = composeRule.onNodeWithTag("barcode-intake-scanner-title").fetchSemanticsNode().boundsInRoot
            val frame = composeRule.onNodeWithTag("barcode-camera-frame").fetchSemanticsNode().boundsInRoot
            assertTrue("Camera must not cover navigation", frame.top >= title.bottom)
            composeRule.waitUntil(timeoutMillis = 30_000) {
                composeRule.onAllNodesWithText("Camera is live and barcode frames are being analyzed.").fetchSemanticsNodes().isNotEmpty()
            }
            saveTestScreenshot(InstrumentationRegistry.getInstrumentation(), "onboarding-barcode-scanner.png")
            // Queue access is available even when no results are ready.
            composeRule.onNodeWithTag("scanner-review-queue").performClick()
            composeRule.onNodeWithTag("bottle-intake-route-title").assertTextEquals("Your bottle queue")
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("CI Manual Barcode Gin", substring = true))
            composeRule.onAllNodesWithText("CI Manual Barcode Gin", substring = true).assertCountEquals(1)
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("Resume scanner"))
            composeRule.onNodeWithText("Resume scanner").performClick()
            awaitIntakeScanner()
            composeRule.onNodeWithTag("scanner-exit").performClick()
            composeRule.onNodeWithTag("my-bar-review-queue").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("Resume scanner"))
            composeRule.onNodeWithText("Resume scanner").performClick()
            awaitIntakeScanner()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            composeRule.onNodeWithTag("my-bar-review-queue").assertIsDisplayed()
            composeRule.activityRule.scenario.recreate()
            composeRule.onNodeWithText("My Bar").performClick()
            composeRule.onNodeWithTag("my-bar-review-queue").performClick()
            composeRule.onNodeWithTag("bottle-intake-scroll").performScrollToNode(hasText("CI Manual Barcode Gin", substring = true))
            composeRule.onAllNodesWithText("CI Manual Barcode Gin", substring = true).assertCountEquals(1)
            composeRule.onNodeWithTag("intake-exit").performClick()
            composeRule.onNodeWithTag("my-bar-exit").performClick()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, composeRule.activityRule.scenario.state)
        } finally {
            removeIntakeTestDraft("CI Manual Barcode Gin", "036000291452")
        }
    }

    private fun clickPermissionPrompt(vararg acceptedLabels: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = automation.windows.mapNotNull { runCatching { it.root }.getOrNull() } + listOfNotNull(automation.rootInActiveWindow)
            val node = findPermissionAction(candidates, acceptedLabels, targetResourceIds(acceptedLabels))
            node?.let { target ->
                if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    failWithCameraUiDiagnostics(
                        "Permission action was not clickable: id=${target.viewIdResourceName} text=${target.text}",
                    )
                }
                return
            }
            SystemClock.sleep(100)
        }
        failWithCameraUiDiagnostics("Camera permission prompt action not found for ${acceptedLabels.joinToString()}")
    }

    private fun awaitScannerDialog() {
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithText("Scan a bottle").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: AssertionError) {
            failWithCameraUiDiagnostics("Scanner dialog did not appear after the camera action: ${failure.message}")
        }
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
    }

    private fun awaitIntakeScanner() {
        try {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag("barcode-camera-frame").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: AssertionError) {
            failWithCameraUiDiagnostics("Full-screen intake scanner did not appear: ${failure.message}")
        }
        composeRule.onNodeWithTag("barcode-camera-frame").assertIsDisplayed()
    }

    private fun awaitCameraPermissionGranted() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline &&
            composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            SystemClock.sleep(100)
        }
        if (composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failWithCameraUiDiagnostics("Android did not grant CAMERA after the selected permission action")
        }
    }

    private fun targetResourceIds(labels: Array<out String>): Set<String> = when {
        labels.any { it.contains("deny", ignoreCase = true) || it.contains("don't allow", ignoreCase = true) } -> denialResourceIds()
        else -> setOf(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_button",
        )
    }

    private fun findPermissionAction(
        roots: List<AccessibilityNodeInfo>,
        acceptedLabels: Array<out String>,
        acceptedResourceIds: Set<String>,
    ): AccessibilityNodeInfo? {
        val nodes = buildList {
            fun collect(current: AccessibilityNodeInfo?) {
                if (current == null) return
                add(current)
                for (index in 0 until current.childCount) collect(current.getChild(index))
            }
            roots.forEach(::collect)
        }
        val denyIds = denialResourceIds()
        nodes.firstOrNull { candidate ->
            val id = candidate.viewIdResourceName.orEmpty()
            candidate.isClickable && id in acceptedResourceIds && id !in denyIds && !isDenyLabel(candidate.text?.toString().orEmpty())
        }?.let { return it }
        return nodes.firstOrNull { candidate ->
            candidate.isClickable && isAcceptedLabel(candidate.text?.toString().orEmpty(), acceptedLabels)
        }
    }

    private fun isAcceptedLabel(text: String, acceptedLabels: Array<out String>): Boolean {
        val normalized = normalizePermissionLabel(text)
        val accepted = acceptedLabels.map(::normalizePermissionLabel)
        val wantsDeny = acceptedLabels.any { it.contains("deny", true) || it.contains("don't allow", true) }
        val isDeny = isDenyLabel(normalized)
        if (wantsDeny) return isDeny && normalized in accepted
        return !isDeny && normalized in accepted
    }

    private fun isDenyLabel(text: String): Boolean {
        val normalized = normalizePermissionLabel(text)
        return normalized == "deny" || normalized == "don't allow" || normalized == "do not allow" || normalized == "never allow"
    }

    private fun denialResourceIds(): Set<String> = setOf(
        "com.android.permissioncontroller:id/permission_deny_button",
        "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
    )

    private fun normalizePermissionLabel(value: String): String = value
        .lowercase()
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun failWithCameraUiDiagnostics(message: String): Nothing {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val hierarchy = automation.windows.joinToString("\n--- window ---\n") { window ->
            dumpNode(runCatching { window.root }.getOrNull(), 0)
        }.ifBlank { dumpNode(automation.rootInActiveWindow, 0) }
        val screenshot = runCatching {
            val safeTestName = testName.methodName.replace(Regex("[^A-Za-z0-9_-]"), "_")
            saveTestScreenshot(instrumentation, "camera-permission-$safeTestName.png")
        }.getOrDefault("unavailable")
        Log.e("CocktAIlsPermissionTest", "$message\nScreenshot: $screenshot\nHierarchy:\n$hierarchy")
        throw AssertionError("$message. Accessibility hierarchy was logged; screenshot: $screenshot")
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > 12) return ""
        val bounds = Rect().also(node::getBoundsInScreen)
        return buildString {
            append("  ".repeat(depth))
            append("${node.className} text=${node.text} desc=${node.contentDescription} id=${node.viewIdResourceName} clickable=${node.isClickable} bounds=$bounds\n")
            for (index in 0 until node.childCount) append(dumpNode(node.getChild(index), depth + 1))
        }
    }
}
