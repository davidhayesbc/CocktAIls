package com.davidhayesbc.cocktails.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/** Suggestions, not a closed taxonomy. Custom spelling and values are stored verbatim (trimmed). */
internal val DRINK_CATEGORIES = listOf(
    "White Rum", "Dark Rum", "Aged Rum", "Spiced Rum", "Overproof Rum", "Flavoured Rum",
    "Vodka", "Flavoured Vodka", "Gin", "Old Tom Gin", "Sloe Gin",
    "Rye Whisky", "Bourbon", "Scotch Whisky", "Irish Whiskey", "Canadian Whisky", "Japanese Whisky", "Other Whisky",
    "Brandy", "Cognac", "Armagnac", "Pisco", "Calvados", "Grappa", "Fruit Brandy",
    "Tequila Blanco", "Tequila Reposado", "Tequila Añejo", "Mezcal", "Absinthe", "Aquavit", "Cachaça",
    "Orange Liqueur", "Coffee Liqueur", "Cream Liqueur", "Herbal Liqueur", "Fruit Liqueur", "Nut Liqueur",
    "Chocolate Liqueur", "Floral Liqueur", "Other Liqueur", "Amaro", "Bitter Aperitif",
    "Sweet Vermouth", "Dry Vermouth", "Blanc Vermouth", "Sherry", "Port", "Madeira", "Other Fortified Wine",
    "Wine", "Sparkling Wine", "Beer", "Cider", "Cocktail Bitters",
    "Simple Syrup", "Rich Syrup", "Orgeat", "Grenadine", "Other Syrup", "Honey", "Agave Syrup",
    "Juice", "Soda Water", "Tonic Water", "Ginger Beer", "Ginger Ale", "Cola", "Other Mixer",
    "Non-alcoholic Spirit", "Non-alcoholic Aperitif", "Other",
)

internal fun drinkCategorySuggestions(query: String): List<String> {
    fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace("whiskey", "whisky")
    val terms = key(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    return DRINK_CATEGORIES.filter { option -> terms.all { it in key(option) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrinkCategoryField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(value) { drinkCategorySuggestions(value) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text("Drink category · optional") },
            supportingText = { Text("Choose a suggestion or type your own. Clear to leave unset.") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && options.isNotEmpty(), onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 260.dp)) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false },
                    modifier = Modifier.testTag("drink-category-option-$option"))
            }
        }
    }
}

/** Existing broad recipe classification remains independent from the bottle's drink category. */
@Composable
internal fun IngredientGroupField(value: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Ingredient group: $value") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf("Spirit", "Liqueur", "Bitters", "Mixer", "Fruit", "Other").forEach { group ->
                DropdownMenuItem(text = { Text(group) }, onClick = { onValueChange(group); expanded = false })
            }
        }
    }
}
