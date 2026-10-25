package com.davidhayesbc.cocktails.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.core.model.HomeLocation
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.PantryEntry

private enum class Page(val label: String) {
    MAKE("Make"),
    MY_BAR("My Bar"),
    RECIPES("Recipes"),
    MORE("More"),
}

@Composable
fun CocktailsApp(repository: LocalCocktailRepository) {
    val locations = remember(repository) { repository.locations() }
    var selectedLocationId by remember(locations) {
        mutableStateOf(locations.firstOrNull()?.id.orEmpty())
    }
    var selectedPage by remember { mutableStateOf(Page.MAKE) }
    var showShop by remember { mutableStateOf(false) }
    var showAddIngredient by remember { mutableStateOf(false) }
    var pantry by remember { mutableStateOf(emptyList<PantryEntry>()) }

    LaunchedEffect(selectedLocationId) {
        pantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
    }

    fun refreshPantry() {
        pantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page && !showShop,
                        onClick = {
                            showShop = false
                            selectedPage = page
                        },
                        icon = { Text(page.label.take(1)) },
                        label = { Text(page.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when {
            showShop -> ShopScreen(
                modifier = Modifier.padding(contentPadding),
                location = locations.firstOrNull { it.id == selectedLocationId },
                pantry = pantry,
                onBack = { showShop = false },
            )
            selectedPage == Page.MAKE -> MakeScreen(
                modifier = Modifier.padding(contentPadding),
                location = locations.firstOrNull { it.id == selectedLocationId },
                pantry = pantry,
                onAddIngredients = {
                    selectedPage = Page.MY_BAR
                    showAddIngredient = true
                },
                onOpenShop = { showShop = true },
            )
            selectedPage == Page.MY_BAR -> MyBarScreen(
                modifier = Modifier.padding(contentPadding),
                locations = locations,
                selectedLocationId = selectedLocationId,
                pantry = pantry,
                onLocationSelected = { selectedLocationId = it },
                onAddIngredient = { showAddIngredient = true },
                onLevelSelected = { entry, level ->
                    repository.updateInventoryLevel(
                        ingredientId = entry.ingredientId,
                        locationId = selectedLocationId,
                        level = level,
                        nowMillis = System.currentTimeMillis(),
                    )
                    refreshPantry()
                },
                onOpenShop = { showShop = true },
            )
            selectedPage == Page.RECIPES -> PlaceholderScreen(
                modifier = Modifier.padding(contentPadding),
                title = "Recipes",
                message = "Your recipe collection will live here. The first local catalog is being prepared.",
            )
            else -> PlaceholderScreen(
                modifier = Modifier.padding(contentPadding),
                title = "More",
                message = "Household sharing, import/export, and assistant settings are planned for upcoming slices.",
            )
        }
    }

    if (showAddIngredient) {
        AddIngredientDialog(
            onDismiss = { showAddIngredient = false },
            onSave = { name, category, level ->
                val saved = repository.addIngredient(
                    name = name,
                    category = category,
                    level = level,
                    locationId = selectedLocationId,
                    nowMillis = System.currentTimeMillis(),
                )
                if (saved) {
                    refreshPantry()
                    showAddIngredient = false
                }
            },
        )
    }
}

@Composable
private fun MakeScreen(
    modifier: Modifier,
    location: HomeLocation?,
    pantry: List<PantryEntry>,
    onAddIngredients: () -> Unit,
    onOpenShop: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Good evening", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("YOUR BAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(location?.name ?: "Choose a home", style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAddIngredients) { Text("＋  Add bottles") }
            OutlinedButton(onClick = onOpenShop) { Text("At the shop  →") }
        }
        Text("What can you make?", style = MaterialTheme.typography.headlineSmall)
        if (pantry.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Start with your bar", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Add a few bottles and mixers. Recipe recommendations appear as the local catalog is added.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Button(onClick = onAddIngredients) { Text("Add your first ingredient") }
                }
            }
        } else {
            Text("${pantry.size} ingredients tracked here", style = MaterialTheme.typography.bodyLarge)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    "The recipe catalog is not installed yet. Your pantry is saved on this device.",
                    modifier = Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MyBarScreen(
    modifier: Modifier,
    locations: List<HomeLocation>,
    selectedLocationId: String,
    pantry: List<PantryEntry>,
    onLocationSelected: (String) -> Unit,
    onAddIngredient: () -> Unit,
    onLevelSelected: (PantryEntry, InventoryLevel) -> Unit,
    onOpenShop: () -> Unit,
) {
    var locationMenuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("My Bar", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onAddIngredient) { Text("＋ Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { locationMenuExpanded = true }) {
                Text("⌂  ${locations.firstOrNull { it.id == selectedLocationId }?.name ?: "Choose a home"}  ▾")
            }
            DropdownMenu(expanded = locationMenuExpanded, onDismissRequest = { locationMenuExpanded = false }) {
                locations.forEach { location ->
                    DropdownMenuItem(
                        text = { Text(location.name) },
                        onClick = {
                            onLocationSelected(location.id)
                            locationMenuExpanded = false
                        },
                    )
                }
            }
            OutlinedButton(onClick = onOpenShop) { Text("At the shop") }
        }
        if (pantry.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing in this bar yet", style = MaterialTheme.typography.titleMedium)
                    Text("Add an ingredient and choose an approximate stock level.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pantry, key = { it.ingredientId.value }) { entry ->
                    PantryRow(entry, onLevelSelected)
                }
            }
        }
    }
}

@Composable
private fun PantryRow(entry: PantryEntry, onLevelSelected: (PantryEntry, InventoryLevel) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(entry.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { menuExpanded = true }) { Text(entry.level.label) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    InventoryLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.label) },
                            onClick = {
                                onLevelSelected(entry, level)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddIngredientDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, InventoryLevel) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Spirit") }
    var level by remember { mutableStateOf(InventoryLevel.FULL) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var levelMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to your bar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ingredient name") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        TextButton(onClick = { categoryMenuExpanded = true }) { Text(category) }
                        DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                            CATEGORIES.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        category = option
                                        categoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Column {
                        TextButton(onClick = { levelMenuExpanded = true }) { Text(level.label) }
                        DropdownMenu(expanded = levelMenuExpanded, onDismissRequest = { levelMenuExpanded = false }) {
                            InventoryLevel.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        level = option
                                        levelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, category, level) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShopScreen(
    modifier: Modifier,
    location: HomeLocation?,
    pantry: List<PantryEntry>,
    onBack: () -> Unit,
) {
    val restock = pantry.filter { it.level == InventoryLevel.LOW || it.level == InventoryLevel.OUT || it.level == InventoryLevel.HALF }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹  Back") }
        Text("At the shop", style = MaterialTheme.typography.headlineMedium)
        Text("Shopping for ${location?.name ?: "a home"}", style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Spirits that unlock the most drinks", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Rankings will appear when the local recipe catalog is available. Counts will include only cocktail families you can fully make.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text("RESTOCK SOON", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (restock.isEmpty()) {
            Text("Nothing marked low or out.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            restock.forEach { entry ->
                Text("${entry.level.label} · ${entry.name}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(modifier: Modifier, title: String, message: String) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val InventoryLevel.label: String
    get() = when (this) {
        InventoryLevel.FULL -> "Full"
        InventoryLevel.MOSTLY_FULL -> "Mostly full"
        InventoryLevel.HALF -> "Half"
        InventoryLevel.LOW -> "Low"
        InventoryLevel.OUT -> "Out"
    }

private val CATEGORIES = listOf("Spirit", "Liqueur", "Bitters", "Mixer", "Fruit", "Other")
