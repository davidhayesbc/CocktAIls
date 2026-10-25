package com.davidhayesbc.cocktails.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineRecipeSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
        composeRule.onNodeWithText(title).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("Recipes").performClick()
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }
}
