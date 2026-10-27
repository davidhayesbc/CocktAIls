package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Test

class BottleDraftSuggestionPolicyTest {
    @Test
    fun explicitlyClearedBrandAndProductStayEmptyWhenRetryReturnsLateSuggestions() {
        val edited = preserveEditedBottleFields(
            current = emptySet(),
            persisted = setOf(BottleDraftField.BRAND, BottleDraftField.PRODUCT_NAME),
        )

        val afterLateResult = mergeBottleSuggestionFields(
            current = BottleDraftSuggestionFields("Unidentified bottle", "", ""),
            suggestedName = "Auchentoshan Three Wood",
            suggestedBrand = "Auchentoshan",
            editedFields = edited,
        )

        assertEquals("Auchentoshan Three Wood", afterLateResult.ingredientName)
        assertEquals("", afterLateResult.productName)
        assertEquals("", afterLateResult.brand)
    }

    @Test
    fun untouchedFieldsCanStillReceiveSuggestionsAfterUserRetriesLookup() {
        val result = mergeBottleSuggestionFields(
            current = BottleDraftSuggestionFields("Unidentified bottle", "", ""),
            suggestedName = "Auchentoshan Three Wood",
            suggestedBrand = "Auchentoshan",
            editedFields = emptySet(),
        )

        assertEquals(BottleDraftSuggestionFields("Auchentoshan Three Wood", "Auchentoshan Three Wood", "Auchentoshan"), result)
    }
}
