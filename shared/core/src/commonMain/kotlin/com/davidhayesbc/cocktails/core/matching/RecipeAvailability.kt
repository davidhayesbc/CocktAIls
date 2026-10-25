package com.davidhayesbc.cocktails.core.matching

import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.RecipeLine

enum class RecipeAvailabilityFilter(val label: String) {
    ANY("Any availability"),
    READY("Ready with my bar"),
    MISSING_ONE("Missing one"),
    MISSING_UP_TO_TWO("Missing up to two"),
}

/** Counts distinct required ingredients that are not available; optional lines never block a drink. */
fun countMissingRequiredIngredients(
    ingredients: List<RecipeLine>,
    availableIngredients: Set<IngredientId>,
): Int = ingredients.asSequence()
    .filterNot { it.optional || it.ingredientId in availableIngredients }
    .map { it.ingredientId }
    .distinct()
    .count()

fun RecipeAvailabilityFilter.matchesMissingCount(missingCount: Int): Boolean = when (this) {
    RecipeAvailabilityFilter.ANY -> true
    RecipeAvailabilityFilter.READY -> missingCount == 0
    RecipeAvailabilityFilter.MISSING_ONE -> missingCount == 1
    RecipeAvailabilityFilter.MISSING_UP_TO_TWO -> missingCount in 1..2
}
