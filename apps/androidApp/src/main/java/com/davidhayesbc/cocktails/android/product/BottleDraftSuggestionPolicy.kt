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
    conflictingFields: Set<BottleFactField> = emptySet(),
): BottleDraftSuggestionFields = BottleDraftSuggestionFields(
    ingredientName = if (BottleFactField.PRODUCT_NAME in conflictingFields && BottleDraftField.INGREDIENT_NAME !in editedFields) "Unidentified bottle" else if (
        BottleDraftField.INGREDIENT_NAME !in editedFields &&
        (current.ingredientName.isBlank() || current.ingredientName == "Unidentified bottle")
    ) suggestedName.ifBlank { current.ingredientName } else current.ingredientName,
    productName = if (BottleFactField.PRODUCT_NAME in conflictingFields && BottleDraftField.PRODUCT_NAME !in editedFields) "" else if (BottleDraftField.PRODUCT_NAME !in editedFields) current.productName.ifBlank { suggestedName } else current.productName,
    brand = if (BottleFactField.BRAND in conflictingFields && BottleDraftField.BRAND !in editedFields) "" else if (BottleDraftField.BRAND !in editedFields) current.brand.ifBlank { suggestedBrand } else current.brand,
    quantity = if (BottleFactField.QUANTITY in conflictingFields && BottleDraftField.QUANTITY !in editedFields) "" else if (BottleDraftField.QUANTITY !in editedFields) current.quantity.ifBlank { suggestedQuantity } else current.quantity,
    abvPercent = if (BottleFactField.ABV_PERCENT in conflictingFields && BottleDraftField.ABV_PERCENT !in editedFields) "" else if (BottleDraftField.ABV_PERCENT !in editedFields) current.abvPercent.ifBlank { suggestedAbvPercent } else current.abvPercent,
)
