package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Test

class BottleDraftSuggestionPolicyTest {
    @Test
    fun reprocessPreservesAllExistingManualValuesAndExplicitlyClearedScalars() {
        val manual = BottleDraftSuggestionFields("My bottle", "My product", "My brand", "700 mL", "41")
        assertEquals(manual, mergeBottleSuggestionFields(manual, "Provider product", "Provider brand",
            emptySet(), "1 L", "50"))
        val cleared = manual.copy(quantity = "", abvPercent = "")
        assertEquals(cleared, mergeBottleSuggestionFields(cleared, "Provider product", "Provider brand",
            setOf(BottleDraftField.QUANTITY, BottleDraftField.ABV_PERCENT), "1 L", "50"))
    }

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
