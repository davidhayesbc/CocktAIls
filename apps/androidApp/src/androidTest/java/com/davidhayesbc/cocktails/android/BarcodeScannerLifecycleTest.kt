package com.davidhayesbc.cocktails.android

import android.Manifest
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
        composeRule.onNodeWithText("Camera access wasn't granted. Enter the barcode manually instead.").assertIsDisplayed()
        composeRule.onNodeWithTag("ingredient-barcode").assertIsDisplayed()
        composeRule.onAllNodesWithText("Scan a bottle").assertCountEquals(0)
    }

    @Test
    fun scannerCanBeOpenedClosedReopenedAndActivityRecreated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            composeRule.activity.packageName,
            Manifest.permission.CAMERA,
        )
        composeRule.onNodeWithText("My Bar").performClick()
        composeRule.onNodeWithText("＋ Add").performClick()
        composeRule.onNodeWithText("Scan barcode").performClick()
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
        composeRule.onNodeWithText("Enter manually").performClick()
        composeRule.onNodeWithText("Scan barcode").performClick()
        composeRule.onNodeWithText("Scan a bottle").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Scan a bottle").assertCountEquals(0)
    }
}
