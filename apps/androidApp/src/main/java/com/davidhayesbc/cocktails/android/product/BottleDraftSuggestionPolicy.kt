package com.davidhayesbc.cocktails.android.product

internal data class BottleDraftSuggestionFields(
    val ingredientName: String,
    val productName: String,
    val brand: String,
)

/** Fills only untouched fields; an explicitly cleared field stays cleared across retries. */
internal fun mergeBottleSuggestionFields(
    current: BottleDraftSuggestionFields,
    suggestedName: String,
    suggestedBrand: String,
    editedFields: Set<BottleDraftField>,
): BottleDraftSuggestionFields = BottleDraftSuggestionFields(
    ingredientName = if (
        BottleDraftField.INGREDIENT_NAME !in editedFields &&
        (current.ingredientName.isBlank() || current.ingredientName == "Unidentified bottle")
    ) suggestedName.ifBlank { current.ingredientName } else current.ingredientName,
    productName = if (BottleDraftField.PRODUCT_NAME !in editedFields) current.productName.ifBlank { suggestedName } else current.productName,
    brand = if (BottleDraftField.BRAND !in editedFields) current.brand.ifBlank { suggestedBrand } else current.brand,
)
