package com.davidhayesbc.cocktails.core.matching

import com.davidhayesbc.cocktails.core.model.CocktailFamily
import com.davidhayesbc.cocktails.core.model.CocktailFamilyId
import com.davidhayesbc.cocktails.core.model.IngredientId
import com.davidhayesbc.cocktails.core.model.RecipeIngredient
import com.davidhayesbc.cocktails.core.model.RecipeVariant
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopInsightsTest {
    @Test
    fun countsEachFamilyOnceAndOnlyWhenEveryRequiredIngredientIsAvailable() {
        val base = IngredientId("base")
        val missingSpirit = IngredientId("spirit")
        val alternateBase = IngredientId("alternate-base")
        val stillMissing = IngredientId("still-missing")
        val families = listOf(
            family("new-family", listOf(recipe("v1", "new-family", listOf(base, missingSpirit)), recipe("v2", "new-family", listOf(alternateBase, missingSpirit)))),
            family("already-ready", listOf(recipe("v3", "already-ready", listOf(base)), recipe("v4", "already-ready", listOf(base, missingSpirit)))),
            family("still-missing", listOf(recipe("v5", "still-missing", listOf(base, missingSpirit, stillMissing)))),
        )

        val suggestions = rankSpiritUnlocks(
            families = families,
            availableIngredients = setOf(base, alternateBase),
            spiritCandidates = setOf(missingSpirit),
        )

        assertEquals(1, suggestions.single().newlyMakeableFamilies)
        assertEquals(CocktailFamilyId("new-family"), suggestions.single().exampleFamilyIds.single())
    }

    @Test
    fun optionalIngredientDoesNotBlockUnlock() {
        val base = IngredientId("base")
        val spirit = IngredientId("spirit")
        val optionalGarnish = IngredientId("garnish")
        val families = listOf(family("sour", listOf(recipe("sour-v1", "sour", listOf(base, spirit), optional = optionalGarnish))))

        val suggestions = rankSpiritUnlocks(families, setOf(base), setOf(spirit))

        assertEquals(1, suggestions.single().newlyMakeableFamilies)
    }

    private fun family(id: String, variants: List<RecipeVariant>) =
        CocktailFamily(CocktailFamilyId(id), id, variants)

    private fun recipe(id: String, familyId: String, required: List<IngredientId>, optional: IngredientId? = null) =
        RecipeVariant(
            RecipeVariantId(id),
            CocktailFamilyId(familyId),
            id,
            required.map(::RecipeIngredient) + listOfNotNull(optional?.let { RecipeIngredient(it, optional = true) }),
        )
}
