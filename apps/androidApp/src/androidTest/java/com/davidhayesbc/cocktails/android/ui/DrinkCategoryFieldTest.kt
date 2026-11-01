package com.davidhayesbc.cocktails.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DrinkCategoryFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingFilteringSelectionCustomValueAndClear() {
        var value by mutableStateOf("")
        compose.setContent { MaterialTheme { DrinkCategoryField(value, { value = it }, Modifier.testTag("category")) } }
        compose.onNodeWithTag("category").performTextInput("white r")
        compose.onNodeWithTag("drink-category-option-White Rum").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("White Rum", value) }
        compose.onNodeWithTag("category").performTextReplacement("House infused rum")
        compose.runOnIdle { assertEquals("House infused rum", value) }
        compose.onNodeWithTag("category").performTextClearance()
        compose.runOnIdle { assertEquals("", value) }
    }
}
