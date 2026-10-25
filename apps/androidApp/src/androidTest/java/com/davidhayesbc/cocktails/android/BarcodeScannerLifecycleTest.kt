package com.davidhayesbc.cocktails.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.rules.TestName
import java.io.File

@RunWith(AndroidJUnit4::class)
class BarcodeScannerLifecycleTest {
    @get:Rule
    val testName = TestName()

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deniedCameraPermissionKeepsManualBarcodeEntryAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.revokeRuntimePermission(
            composeRule.activity.packageName,
            Manifest.permission.CAMERA,
        )
        assertEquals(PackageManager.PERMISSION_DENIED, composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA))
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithText("＋ Add").performClick()
        composeRule.onNodeWithText("Scan barcode").performClick()
        clickPermissionPrompt("don't allow", "deny")
        composeRule.onNodeWithText("Camera access wasn't granted. Enter the barcode manually instead.").assertIsDisplayed()
        composeRule.onNodeWithTag("ingredient-barcode").assertIsDisplayed()
        composeRule.onAllNodesWithText("Scan a bottle").assertCountEquals(0)
    }

    @Test
    fun scannerCanBeOpenedClosedReopenedAndActivityRecreated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.revokeRuntimePermission(
            composeRule.activity.packageName,
            Manifest.permission.CAMERA,
        )
        assertEquals(PackageManager.PERMISSION_DENIED, composeRule.activity.checkSelfPermission(Manifest.permission.CAMERA))
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithText("＋ Add").performClick()
        composeRule.onNodeWithText("Scan barcode").performClick()
        clickPermissionPrompt("while using the app", "only this time", "allow")
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
        composeRule.onNodeWithTag("barcode-camera-preview").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Camera is live and barcode frames are being analyzed.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Scan a bottle").assertCountEquals(0)
        composeRule.onNodeWithText("Scan barcode").performClick()
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
        composeRule.onNodeWithText("Enter manually").performClick()
        composeRule.onNodeWithText("Scan barcode").performClick()
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Scan a bottle").assertCountEquals(0)
    }

    private fun clickPermissionPrompt(vararg acceptedLabels: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = automation.windows.mapNotNull { runCatching { it.root }.getOrNull() } + listOfNotNull(automation.rootInActiveWindow)
            val node = candidates.firstNotNullOfOrNull { root -> findPermissionAction(root, acceptedLabels, targetResourceIds(acceptedLabels)) }
            node?.let { target ->
                assertTrue("Permission prompt action was not clickable", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            SystemClock.sleep(100)
        }
        failWithPermissionPromptDiagnostics("Camera permission prompt action not found for ${acceptedLabels.joinToString()}")
    }

    private fun targetResourceIds(labels: Array<out String>): Set<String> = when {
        labels.any { it.contains("deny", ignoreCase = true) || it.contains("don't allow", ignoreCase = true) } -> setOf(
            "com.android.permissioncontroller:id/permission_deny_button",
            "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button",
        )
        else -> setOf(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_button",
        )
    }

    private fun findPermissionAction(
        node: AccessibilityNodeInfo?,
        acceptedLabels: Array<out String>,
        acceptedResourceIds: Set<String>,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString().orEmpty()
        val resourceId = node.viewIdResourceName.orEmpty()
        if (resourceId in acceptedResourceIds || (node.isClickable && isAcceptedLabel(text, acceptedLabels))) return node
        for (index in 0 until node.childCount) {
            findPermissionAction(node.getChild(index), acceptedLabels, acceptedResourceIds)?.let { return it }
        }
        return null
    }

    private fun isAcceptedLabel(text: String, acceptedLabels: Array<out String>): Boolean {
        val normalized = text.lowercase()
        val wantsDeny = acceptedLabels.any { it.contains("deny", true) || it.contains("don't allow", true) }
        val isDeny = normalized.contains("deny") || normalized.contains("don't allow") || normalized.contains("do not allow")
        if (wantsDeny) return isDeny
        return !isDeny && acceptedLabels.any { normalized.contains(it.lowercase()) }
    }

    private fun failWithPermissionPromptDiagnostics(message: String): Nothing {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val hierarchy = automation.windows.joinToString("\n--- window ---\n") { window ->
            dumpNode(runCatching { window.root }.getOrNull(), 0)
        }.ifBlank { dumpNode(automation.rootInActiveWindow, 0) }
        val screenshot = runCatching {
            val bitmap = automation.takeScreenshot()
            val safeTestName = testName.methodName.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val target = File(requireNotNull(composeRule.activity.externalCacheDir), "camera-permission-$safeTestName.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            target.absolutePath
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
