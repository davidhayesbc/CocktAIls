package com.davidhayesbc.cocktails.android.product

internal data class BottleDraftSuggestionFields(
    val ingredientName: String,
    val productName: String,
    val brand: String,
    val quantity: String = "",
    val abvPercent: String = "",
)

/** Fills only untouched fields; an explicitly cleared field stays cleared across retries. */
internal fun mergeBottleSuggestionFields(
    current: BottleDraftSuggestionFields,
    suggestedName: String,
    suggestedBrand: String,
    editedFields: Set<BottleDraftField>,
    suggestedQuantity: String = "",
    suggestedAbvPercent: String = "",
): BottleDraftSuggestionFields = BottleDraftSuggestionFields(
    ingredientName = if (
        BottleDraftField.INGREDIENT_NAME !in editedFields &&
        (current.ingredientName.isBlank() || current.ingredientName == "Unidentified bottle")
    ) suggestedName.ifBlank { current.ingredientName } else current.ingredientName,
    productName = if (BottleDraftField.PRODUCT_NAME !in editedFields) current.productName.ifBlank { suggestedName } else current.productName,
    brand = if (BottleDraftField.BRAND !in editedFields) current.brand.ifBlank { suggestedBrand } else current.brand,
    quantity = if (BottleDraftField.QUANTITY !in editedFields) current.quantity.ifBlank { suggestedQuantity } else current.quantity,
    abvPercent = if (BottleDraftField.ABV_PERCENT !in editedFields) current.abvPercent.ifBlank { suggestedAbvPercent } else current.abvPercent,
)
