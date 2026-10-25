package com.davidhayesbc.cocktails.core.model

import kotlin.jvm.JvmInline

@JvmInline
value class IngredientId(val value: String)

@JvmInline
value class CocktailFamilyId(val value: String)

@JvmInline
value class RecipeVariantId(val value: String)

enum class RecipeStyle(val label: String) {
    SHORT("Short"), LONG("Long"), SOUR("Sour"), BITTER("Bitter"), SWEET("Sweet"), HOT("Hot"), COLD("Cold"), OTHER("Other"),
}

enum class InventoryLevel {
    FULL,
    MOSTLY_FULL,
    HALF,
    LOW,
    OUT,
}

data class RecipeIngredient(
    val ingredientId: IngredientId,
    val optional: Boolean = false,
)

data class RecipeVariant(
    val id: RecipeVariantId,
    val familyId: CocktailFamilyId,
    val title: String,
    val ingredients: List<RecipeIngredient>,
)

data class CocktailFamily(
    val id: CocktailFamilyId,
    val canonicalName: String,
    val variants: List<RecipeVariant>,
)

data class SpiritUnlock(
    val spiritId: IngredientId,
    val newlyMakeableFamilies: Int,
    val exampleFamilyIds: List<CocktailFamilyId>,
)

data class PantryEntry(
    val ingredientId: IngredientId,
    val name: String,
    val category: String,
    val level: InventoryLevel,
)

data class HomeLocation(
    val id: String,
    val name: String,
)

data class MemberProfile(val id: String, val name: String)

data class RecipeCard(
    val familyId: CocktailFamilyId,
    val name: String,
    val variantCount: Int,
)

data class RecipeDetail(
    val id: RecipeVariantId,
    val familyId: CocktailFamilyId,
    val title: String,
    val instructions: String,
    val preparation: String,
    val servings: Double,
    val style: RecipeStyle,
    val ingredients: List<RecipeLine>,
)

data class RecipeLine(
    val ingredientId: IngredientId,
    val name: String,
    val amount: Double?,
    val unit: String?,
    val optional: Boolean,
)

data class ShopSuggestion(val spiritName: String, val newlyMakeableFamilies: Int)

data class TastingNote(
    val id: String,
    val memberName: String,
    val rating: Int?,
    val note: String,
    val createdAt: Long,
)
