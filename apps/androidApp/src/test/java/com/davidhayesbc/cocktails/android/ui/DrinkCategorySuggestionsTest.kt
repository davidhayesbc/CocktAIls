package com.davidhayesbc.cocktails.android.ui

import org.junit.Assert.*
import org.junit.Test

class DrinkCategorySuggestionsTest {
    @Test fun searchIsCaseAccentAndWhiskeySpellingTolerant() {
        assertEquals(listOf("White Rum"), drinkCategorySuggestions(" RUM white "))
        assertEquals(listOf("Tequila Añejo"), drinkCategorySuggestions("anejo"))
        assertTrue(drinkCategorySuggestions("rye whiskey").contains("Rye Whisky"))
        assertEquals(listOf("Cachaça"), drinkCategorySuggestions("cachaca"))
        assertTrue(drinkCategorySuggestions("Custom house infusion").isEmpty())
        assertEquals(DRINK_CATEGORIES.size, DRINK_CATEGORIES.distinct().size)
        assertEquals(DRINK_CATEGORIES, drinkCategorySuggestions(""))
    }
}
