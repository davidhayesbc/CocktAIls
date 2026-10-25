package com.davidhayesbc.cocktails.android

import android.Manifest
import android.os.SystemClock
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
import org.junit.Assert.fail

@RunWith(AndroidJUnit4::class)
class BarcodeScannerLifecycleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deniedCameraPermissionKeepsManualBarcodeEntryAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.revokeRuntimePermission(
            composeRule.activity.packageName,
            Manifest.permission.CAMERA,
        )
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
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithText("＋ Add").performClick()
        composeRule.onNodeWithText("Scan barcode").performClick()
        clickPermissionPrompt("while using the app", "only this time", "allow")
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
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
            val root = automation.rootInActiveWindow
            findText(root, acceptedLabels)?.let { node ->
                assertTrue("Permission prompt action was not clickable", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            SystemClock.sleep(100)
        }
        fail("Camera permission prompt did not show an action matching ${acceptedLabels.joinToString()}")
    }

    private fun findText(node: AccessibilityNodeInfo?, acceptedLabels: Array<out String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString().orEmpty()
        if (acceptedLabels.any { text.contains(it, ignoreCase = true) }) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), acceptedLabels)?.let { return it }
        }
        return null
    }
}
