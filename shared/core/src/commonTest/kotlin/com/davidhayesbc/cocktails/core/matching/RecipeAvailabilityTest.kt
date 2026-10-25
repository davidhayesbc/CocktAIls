package com.davidhayesbc.cocktails.core.matching

import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.RecipeLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipeAvailabilityTest {
    @Test
    fun missingIngredientFiltersCountDistinctRequiredIngredientsAndIgnoreOptionalLines() {
        val gin = IngredientId("gin")
        val lime = IngredientId("lime")
        val syrup = IngredientId("syrup")
        val garnish = IngredientId("mint")
        val ingredients = listOf(
            line(gin),
            line(lime),
            line(lime), // Repeated use is still one item to shop for.
            line(syrup),
            line(garnish, optional = true),
        )

        assertEquals(0, countMissingRequiredIngredients(ingredients, setOf(gin, lime, syrup)))
        assertEquals(1, countMissingRequiredIngredients(ingredients, setOf(gin, lime)))
        assertEquals(2, countMissingRequiredIngredients(ingredients, setOf(gin)))
        assertEquals(3, countMissingRequiredIngredients(ingredients, emptySet()))
        assertTrue(RecipeAvailabilityFilter.READY.matchesMissingCount(0))
        assertTrue(RecipeAvailabilityFilter.MISSING_ONE.matchesMissingCount(1))
        assertFalse(RecipeAvailabilityFilter.MISSING_ONE.matchesMissingCount(2))
        assertTrue(RecipeAvailabilityFilter.MISSING_UP_TO_TWO.matchesMissingCount(2))
        assertFalse(RecipeAvailabilityFilter.MISSING_UP_TO_TWO.matchesMissingCount(3))
    }

    private fun line(id: IngredientId, optional: Boolean = false) = RecipeLine(id, id.value, null, null, optional)
}
