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
    val inventoryId: String,
    val ingredientId: IngredientId,
    val name: String,
    val category: String,
    val level: InventoryLevel,
    val productId: String?,
    val productName: String?,
    val brand: String?,
    val barcode: String?,
    val photoData: ByteArray? = null,
)

data class LocalProductBarcodeMatch(
    val productId: String,
    val ingredientId: IngredientId,
    val ingredientName: String,
    val category: String,
    val brand: String?,
    val productName: String,
    val barcode: String,
    val quantity: String? = null,
    val abvPercent: String? = null,
)

enum class BottleIntakeSessionStatus { OPEN, CONFIRMED }

data class BottleIntakeDraft(
    val id: String,
    val sessionId: String,
    val position: Long,
    val barcode: String?,
    val productId: String?,
    val ingredientName: String,
    val category: String,
    val brand: String?,
    val productName: String?,
    val photoData: ByteArray?,
    val photoSha256: String?,
    val quantity: String? = null,
    val abvPercent: String? = null,
    val quantityEdited: Boolean = false,
    val abvPercentEdited: Boolean = false,
    val locationId: String,
    val level: InventoryLevel,
    val createdAt: Long,
)

data class BottleIntakeSession(
    val id: String,
    val status: BottleIntakeSessionStatus,
    val locationId: String,
    val defaultLevel: InventoryLevel,
    val createdAt: Long,
    val updatedAt: Long,
    val confirmedCount: Int,
    val drafts: List<BottleIntakeDraft>,
)

/** A validated extractor fact awaiting explicit acceptance with its bottle draft. */
data class ProductFactSourceDraft(
    val retrievalProvider: String,
    val extractionProvider: String,
    val extractionModel: String,
    val sourceUrl: String,
    val fieldName: String,
    val fieldValue: String,
    val evidenceQuote: String,
    val licenseName: String? = null,
)

/** Minimal source trail for an accepted product fact; raw page text and credentials are never stored. */
data class ProductFactSource(
    val id: String,
    val productId: String,
    val retrievalProvider: String,
    val extractionProvider: String,
    val extractionModel: String,
    val sourceUrl: String,
    val fieldName: String,
    val fieldValue: String,
    val evidenceQuote: String,
    val licenseName: String? = null,
    val createdAt: Long,
)

sealed interface BottleIntakeCommitResult {
    data class Accepted(val inventoryId: String, val remainingDraftCount: Int) : BottleIntakeCommitResult
    data class AlreadyAccepted(val inventoryId: String, val remainingDraftCount: Int) : BottleIntakeCommitResult
    data class Confirmed(val bottleCount: Int) : BottleIntakeCommitResult
    data class AlreadyConfirmed(val bottleCount: Int) : BottleIntakeCommitResult
    data class Invalid(val reason: String) : BottleIntakeCommitResult
}

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
    val sources: List<RecipeSourceInfo> = emptyList(),
    val sourceKind: String = "HOUSEHOLD",
)

data class RecipeSourceInfo(
    val id: String,
    val name: String,
    val recordId: String?,
    val url: String?,
    val license: String?,
    val attribution: String?,
    val linked: Boolean,
    val canReassociate: Boolean,
    val historyUrl: String? = null,
    val modifications: String? = null,
    val originalIngredients: String? = null,
    val licenseUrl: String? = null,
    val repositoryUrl: String? = null,
    val repositoryRevision: String? = null,
)

data class RecipeLine(
    val ingredientId: IngredientId,
    val name: String,
    val amount: Double?,
    val unit: String?,
    val optional: Boolean,
    val preparation: String? = null,
)

data class RecipeIngredientDraft(
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val preparation: String? = null,
    val optional: Boolean = false,
)

data class ShopSuggestion(val spiritName: String, val newlyMakeableFamilies: Int)

data class TastingNote(
    val id: String,
    val memberName: String,
    val rating: Int?,
    val note: String,
    val createdAt: Long,
)

data class RecentDrink(
    val eventId: String,
    val recipeId: RecipeVariantId,
    val title: String,
    val madeBy: String,
    val madeAt: Long,
    val locationName: String?,
)
