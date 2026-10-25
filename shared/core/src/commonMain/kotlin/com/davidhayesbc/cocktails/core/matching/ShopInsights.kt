package com.davidhayesbc.cocktails.core.matching

import com.davidhayesbc.cocktails.core.model.CocktailFamily
import com.davidhayesbc.cocktails.core.model.CocktailFamilyId
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.RecipeVariant
import com.davidhayesbc.cocktails.core.model.SpiritUnlock

/** Ranks spirits by how many additional complete cocktail families become makeable. */
fun rankSpiritUnlocks(
    families: List<CocktailFamily>,
    availableIngredients: Set<IngredientId>,
    spiritCandidates: Set<IngredientId>,
): List<SpiritUnlock> = spiritCandidates
    .asSequence()
    .filterNot(availableIngredients::contains)
    .map { spiritId ->
        val unlocked = families
            .asSequence()
            .filterNot { family -> family.isMakeableWith(availableIngredients) }
            .filter { family ->
                family.variants.any { variant -> variant.isMakeableWith(availableIngredients + spiritId) }
            }
            .map(CocktailFamily::id)
            .distinct()
            .toList()

        SpiritUnlock(
            spiritId = spiritId,
            newlyMakeableFamilies = unlocked.size,
            exampleFamilyIds = unlocked.take(MAX_EXAMPLES),
        )
    }
    .sortedWith(compareByDescending<SpiritUnlock> { it.newlyMakeableFamilies }.thenBy { it.spiritId.value })
    .toList()

private fun CocktailFamily.isMakeableWith(available: Set<IngredientId>): Boolean =
    variants.any { it.isMakeableWith(available) }

private fun RecipeVariant.isMakeableWith(available: Set<IngredientId>): Boolean =
    ingredients.filterNot { it.optional }.all { it.ingredientId in available }

private const val MAX_EXAMPLES = 3
