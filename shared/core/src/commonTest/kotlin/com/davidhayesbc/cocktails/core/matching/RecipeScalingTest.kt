package com.davidhayesbc.cocktails.core.matching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeScalingTest {
    @Test
    fun scalesKnownAmountsAndLeavesUnspecifiedAmountsBlank() {
        assertEquals(4.5, scaleRecipeAmount(3.0, 2.0, 3.0))
        assertEquals(0.75, scaleRecipeAmount(0.5, 2.0, 3.0))
        assertNull(scaleRecipeAmount(null, 2.0, 3.0))
    }

    @Test
    fun rejectsInvalidServingCountsAndNonFiniteQuantities() {
        assertNull(scaleRecipeAmount(1.0, 0.0, 2.0))
        assertNull(scaleRecipeAmount(1.0, 2.0, -1.0))
        assertNull(scaleRecipeAmount(Double.NaN, 1.0, 2.0))
        assertNull(scaleRecipeAmount(Double.POSITIVE_INFINITY, 1.0, 2.0))
    }
}
