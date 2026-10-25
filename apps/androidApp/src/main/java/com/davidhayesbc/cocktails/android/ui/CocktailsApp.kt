package com.davidhayesbc.cocktails.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.core.data.ImportApplyResult
import com.davidhayesbc.cocktails.core.data.ImportPreviewResult
import com.davidhayesbc.cocktails.core.data.HouseholdImportPreview
import com.davidhayesbc.cocktails.core.data.BundleRecordChange
import com.davidhayesbc.cocktails.core.data.ConflictChoice
import com.davidhayesbc.cocktails.core.data.ConflictResolution
import com.davidhayesbc.cocktails.core.interchange.BundleDecodeResult
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundle
import com.davidhayesbc.cocktails.core.interchange.HouseholdBundleCodec
import com.davidhayesbc.cocktails.core.interchange.MAX_HOUSEHOLD_BUNDLE_BYTES
import com.davidhayesbc.cocktails.core.model.HomeLocation
import com.davidhayesbc.cocktails.core.model.CocktailFamilyId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.PantryEntry
import com.davidhayesbc.cocktails.core.model.RecipeDetail
import com.davidhayesbc.cocktails.core.model.RecipeStyle
import com.davidhayesbc.cocktails.core.model.RecipeIngredientDraft
import com.davidhayesbc.cocktails.core.model.RecentDrink
import com.davidhayesbc.cocktails.core.matching.RecipeAvailabilityFilter
import com.davidhayesbc.cocktails.core.matching.countMissingRequiredIngredients
import com.davidhayesbc.cocktails.core.matching.matchesMissingCount
import com.davidhayesbc.cocktails.core.matching.scaleRecipeAmount
import com.davidhayesbc.cocktails.core.model.TastingNote
import com.davidhayesbc.cocktails.core.model.ShopSuggestion
import com.davidhayesbc.cocktails.core.model.MemberProfile
import com.davidhayesbc.cocktails.core.model.RecipeCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

private enum class Page(val label: String) {
    MAKE("Make"),
    MY_BAR("My Bar"),
    RECIPES("Recipes"),
    MORE("More"),
}

@Composable
fun CocktailsApp(repository: LocalCocktailRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var locations by remember(repository) { mutableStateOf(repository.locations()) }
    var members by remember(repository) { mutableStateOf(repository.members()) }
    var selectedLocationId by remember(repository) {
        mutableStateOf(repository.preferredHomeId()?.takeIf { preferred -> locations.any { it.id == preferred } } ?: locations.firstOrNull()?.id.orEmpty())
    }
    var selectedMemberId by remember(repository) {
        mutableStateOf(repository.preferredMemberId()?.takeIf { preferred -> members.any { it.id == preferred } } ?: members.firstOrNull()?.id.orEmpty())
    }
    var selectedPage by remember { mutableStateOf(Page.MAKE) }
    var showShop by remember { mutableStateOf(false) }
    var showAddIngredient by remember { mutableStateOf(false) }
    var showAddRecipe by remember { mutableStateOf(false) }
    var recipeToEdit by remember { mutableStateOf<RecipeDetail?>(null) }
    var showAddLocation by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var selectedRecipe by remember { mutableStateOf<RecipeDetail?>(null) }
    var showAddNote by remember { mutableStateOf(false) }
    var recipes by remember { mutableStateOf(repository.recipes()) }
    var recipeFamilies by remember { mutableStateOf(repository.recipeFamilies()) }
    var recentDrinks by remember { mutableStateOf(repository.recentlyMade()) }
    var favoritesVersion by remember { mutableStateOf(0) }
    val favoriteFamilyIds = remember(repository, selectedMemberId, favoritesVersion) { repository.favoriteFamilyIds(selectedMemberId) }
    var pantry by remember { mutableStateOf(emptyList<PantryEntry>()) }
    var shareMessage by remember { mutableStateOf<String?>(null) }
    var stagedBundle by remember { mutableStateOf<HouseholdBundle?>(null) }
    var stagedPreview by remember { mutableStateOf<HouseholdImportPreview?>(null) }
    var conflictResolutions by remember { mutableStateOf<Map<String, ConflictResolution>>(emptyMap()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            shareMessage = "Export canceled. No file was written."
        } else {
            coroutineScope.launch {
                shareMessage = runCatching {
                    val contents = withContext(Dispatchers.IO) { repository.exportHouseholdBundle(System.currentTimeMillis()) }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { it.write(contents.encodeToByteArray()) }
                            ?: error("Could not open the selected file for writing.")
                    }
                    "Household backup saved. Keep it somewhere both phones can access."
                }.getOrElse { "Export failed: ${it.message ?: "could not write the backup"}" }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            shareMessage = "Import canceled. Your data was not changed."
        } else {
            coroutineScope.launch {
                val result = runCatching {
                    val contents = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_HOUSEHOLD_BUNDLE_BYTES) { "Bundle exceeds the 10 MiB limit." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray().decodeToString()
                        } ?: error("Could not open the selected file for reading.")
                    }
                    when (val decoded = HouseholdBundleCodec.decode(contents)) {
                        is BundleDecodeResult.Invalid -> shareMessage = decoded.reason
                        is BundleDecodeResult.Valid -> when (val preview = withContext(Dispatchers.IO) { repository.previewHouseholdBundle(decoded.bundle) }) {
                            is ImportPreviewResult.Invalid -> shareMessage = preview.reason
                            is ImportPreviewResult.Valid -> {
                                stagedBundle = decoded.bundle
                                stagedPreview = preview.preview
                                conflictResolutions = emptyMap()
                                shareMessage = null
                            }
                        }
                    }
                }
                result.exceptionOrNull()?.let { shareMessage = "Import preview failed: ${it.message ?: "file could not be read"}" }
            }
        }
    }

    LaunchedEffect(selectedLocationId) {
        pantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
    }

    fun refreshPantry() {
        pantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
    }

    fun selectHome(locationId: String) {
        selectedLocationId = locationId
        repository.setPreferredHomeId(locationId)
    }

    fun selectMember(memberId: String) {
        selectedMemberId = memberId
        repository.setPreferredMemberId(memberId)
    }

    fun refreshHousehold() {
        locations = repository.locations()
        members = repository.members()
        recipes = repository.recipes()
        recipeFamilies = repository.recipeFamilies()
        recentDrinks = repository.recentlyMade()
        selectedLocationId = repository.preferredHomeId()?.takeIf { id -> locations.any { it.id == id } } ?: locations.firstOrNull()?.id.orEmpty()
        selectedMemberId = repository.preferredMemberId()?.takeIf { id -> members.any { it.id == id } } ?: members.firstOrNull()?.id.orEmpty()
        refreshPantry()
        selectedRecipe = null
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
                recommendations = repository.shopSuggestions(pantry),
                onBack = { showShop = false },
            )
            selectedPage == Page.MAKE -> MakeScreen(
                modifier = Modifier.padding(contentPadding),
                locations = locations,
                selectedLocationId = selectedLocationId,
                onLocationSelected = ::selectHome,
                pantry = pantry,
                onAddIngredients = {
                    selectedPage = Page.MY_BAR
                    showAddIngredient = true
                },
                onOpenShop = { showShop = true },
                recipes = recipes,
                onBrowseRecipes = { selectedPage = Page.RECIPES },
            )
            selectedPage == Page.MY_BAR -> MyBarScreen(
                modifier = Modifier.padding(contentPadding),
                locations = locations,
                selectedLocationId = selectedLocationId,
                pantry = pantry,
                onLocationSelected = ::selectHome,
                onAddIngredient = { showAddIngredient = true },
                onAddLocation = { showAddLocation = true },
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
            selectedPage == Page.RECIPES -> if (selectedRecipe == null) RecipeBrowser(
                modifier = Modifier.padding(contentPadding),
                recipes = recipes,
                pantry = pantry,
                favoriteFamilyIds = favoriteFamilyIds,
                recentDrinks = recentDrinks,
                onAddRecipe = { recipeToEdit = null; showAddRecipe = true },
                onSelectRecipe = { selectedRecipe = it },
                onOpenRecent = { id -> selectedRecipe = recipes.firstOrNull { it.id == id } },
                onToggleFavorite = { familyId, favorite ->
                    if (repository.setFamilyFavorite(selectedMemberId, familyId, favorite, System.currentTimeMillis())) favoritesVersion++
                },
            ) else RecipeDetailScreen(
                modifier = Modifier.padding(contentPadding),
                recipe = selectedRecipe!!,
                notes = repository.tastingNotes(selectedRecipe!!.id),
                isFavorite = selectedRecipe!!.familyId in favoriteFamilyIds,
                onToggleFavorite = {
                    val newValue = selectedRecipe!!.familyId !in favoriteFamilyIds
                    if (repository.setFamilyFavorite(selectedMemberId, selectedRecipe!!.familyId, newValue, System.currentTimeMillis())) favoritesVersion++
                },
                onBack = { selectedRecipe = null },
                onAddNote = { showAddNote = true },
                onMarkMade = {
                    if (repository.markRecipeMade(selectedRecipe!!.id, selectedMemberId, selectedLocationId.ifEmpty { null }, System.currentTimeMillis())) {
                        recentDrinks = repository.recentlyMade()
                    }
                },
                onEdit = { recipeToEdit = selectedRecipe; showAddRecipe = true },
                onDuplicate = {
                    repository.duplicateRecipeVariant(selectedRecipe!!.id, System.currentTimeMillis())?.let { duplicateId ->
                        recipes = repository.recipes()
                        recipeFamilies = repository.recipeFamilies()
                        selectedRecipe = recipes.firstOrNull { it.id == duplicateId }
                    }
                },
                onArchive = {
                    repository.archiveRecipeVariant(selectedRecipe!!.id, System.currentTimeMillis())
                    recipes = repository.recipes()
                    recipeFamilies = repository.recipeFamilies()
                    selectedRecipe = null
                },
            )
            else -> HouseholdScreen(
                modifier = Modifier.padding(contentPadding),
                members = members,
                selectedMemberId = selectedMemberId,
                onManageMembers = { showAddMember = true },
                onExport = { exportLauncher.launch("CocktAIls-Household-Backup.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/json", "*/*")) },
                shareMessage = shareMessage,
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

    if (showAddRecipe) {
        AddRecipeDialog(
            onDismiss = { showAddRecipe = false; recipeToEdit = null },
            families = recipeFamilies,
            initialRecipe = recipeToEdit,
            onSave = { title, ingredientLines, instructions, servings, style, familyId ->
                val now = System.currentTimeMillis()
                val saved = recipeToEdit?.let { current ->
                    repository.editRecipeVariant(current.id, title, ingredientLines, instructions, servings, style, now)
                } ?: repository.addRecipeLines(
                    title = title, ingredientLines = ingredientLines, instructions = instructions, style = style,
                    existingFamilyId = familyId?.let(::CocktailFamilyId), servings = servings, nowMillis = now,
                )
                if (saved) {
                    recipes = repository.recipes()
                    recipeFamilies = repository.recipeFamilies()
                    recipeToEdit?.let { edited -> selectedRecipe = recipes.firstOrNull { it.id == edited.id } }
                    recipeToEdit = null
                    showAddRecipe = false
                }
                saved
            },
        )
    }

    if (showAddLocation) {
        AddLocationDialog(
            onDismiss = { showAddLocation = false },
            onSave = { name ->
                repository.addLocation(name, System.currentTimeMillis())?.let { location ->
                    locations = repository.locations()
                    selectHome(location.id)
                    showAddLocation = false
                }
            },
        )
    }

    if (showAddMember) {
        AddMemberDialog(
            members = members,
            selectedMemberId = selectedMemberId,
            onSelectMember = ::selectMember,
            onDismiss = { showAddMember = false },
            onAdd = { name ->
                repository.addMember(name, System.currentTimeMillis())?.let { member ->
                    members = repository.members()
                    selectMember(member.id)
                }
            },
        )
    }

    if (showAddNote && selectedRecipe != null) {
        AddNoteDialog(
            onDismiss = { showAddNote = false },
            onSave = { rating, note ->
                repository.addTastingNote(
                    variantId = selectedRecipe!!.id,
                    memberId = selectedMemberId,
                    rating = rating,
                    note = note,
                    locationId = selectedLocationId.ifEmpty { null },
                    nowMillis = System.currentTimeMillis(),
                )
                showAddNote = false
            },
        )
    }

    val preview = stagedPreview
    val bundle = stagedBundle
    if (preview != null && bundle != null) {
        HouseholdImportDialog(
            preview = preview,
            resolutions = conflictResolutions,
            onResolution = { key, resolution -> conflictResolutions = conflictResolutions + (key to resolution) },
            onCancel = {
                stagedPreview = null
                stagedBundle = null
                conflictResolutions = emptyMap()
                shareMessage = "Import canceled. Your data was not changed."
            },
            onApply = {
                coroutineScope.launch {
                    when (val result = withContext(Dispatchers.IO) {
                        repository.applyHouseholdBundle(bundle, preview, conflictResolutions, System.currentTimeMillis())
                    }) {
                        is ImportApplyResult.Invalid -> shareMessage = result.reason
                        is ImportApplyResult.Applied -> {
                            stagedPreview = null
                            stagedBundle = null
                            conflictResolutions = emptyMap()
                            refreshHousehold()
                            shareMessage = "Imported ${result.additions} new and ${result.updates} updated records; resolved ${result.resolvedConflicts} conflicts."
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MakeScreen(
    modifier: Modifier,
    locations: List<HomeLocation>,
    selectedLocationId: String,
    onLocationSelected: (String) -> Unit,
    pantry: List<PantryEntry>,
    onAddIngredients: () -> Unit,
    onOpenShop: () -> Unit,
    recipes: List<RecipeDetail>,
    onBrowseRecipes: () -> Unit,
) {
    val availableIngredients = pantry.filter { it.level != InventoryLevel.OUT }.map { it.name.lowercase() }.toSet()
    val readyRecipes = recipes.filter { recipe -> recipe.ingredients.filterNot { it.optional }.all { it.name.lowercase() in availableIngredients } }
        .distinctBy { it.familyId }
    var locationMenuExpanded by remember { mutableStateOf(false) }
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
                TextButton(onClick = { locationMenuExpanded = true }) {
                    Text("${locations.firstOrNull { it.id == selectedLocationId }?.name ?: "Choose a home"}  ▾", style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(expanded = locationMenuExpanded, onDismissRequest = { locationMenuExpanded = false }) {
                    locations.forEach { location ->
                        DropdownMenuItem(text = { Text(location.name) }, onClick = { onLocationSelected(location.id); locationMenuExpanded = false })
                    }
                }
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
            if (readyRecipes.isEmpty()) {
                Text("No saved recipes are ready with this bar yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Ready to make", style = MaterialTheme.typography.titleLarge)
                readyRecipes.take(4).forEach { recipe ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(recipe.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Text(recipe.style.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            TextButton(onClick = onBrowseRecipes) { Text("Browse all recipes →") }
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
    onAddLocation: () -> Unit,
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
            Row {
                TextButton(onClick = onAddLocation) { Text("＋ Home") }
                TextButton(onClick = onAddIngredient) { Text("＋ Add") }
            }
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
private fun AddLocationDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a home") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Home or bar name") }, singleLine = true)
        },
        confirmButton = { Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShopScreen(
    modifier: Modifier,
    location: HomeLocation?,
    pantry: List<PantryEntry>,
    recommendations: List<ShopSuggestion>,
    onBack: () -> Unit,
) {
    val restock = pantry.filter { it.level == InventoryLevel.LOW || it.level == InventoryLevel.OUT || it.level == InventoryLevel.HALF }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TextButton(onClick = onBack) { Text("‹  Back") } }
        item { Text("At the shop", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Shopping for ${location?.name ?: "a home"}", style = MaterialTheme.typography.titleMedium) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Spirits that unlock the most drinks", style = MaterialTheme.typography.titleMedium)
                    if (recommendations.isEmpty()) {
                        Text("Add recipes and mark their spirit ingredients to see what would unlock new families.", style = MaterialTheme.typography.bodyMedium)
                    } else recommendations.forEachIndexed { index, item ->
                        Text("${index + 1}. ${item.spiritName} · ${item.newlyMakeableFamilies} new drink families", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item { Text("RESTOCK SOON", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        if (restock.isEmpty()) {
            item { Text("Nothing marked low or out.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(restock) { entry ->
                Text("${entry.level.label} · ${entry.name}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun RecipeBrowser(
    modifier: Modifier,
    recipes: List<RecipeDetail>,
    pantry: List<PantryEntry>,
    favoriteFamilyIds: Set<CocktailFamilyId>,
    recentDrinks: List<RecentDrink>,
    onAddRecipe: () -> Unit,
    onSelectRecipe: (RecipeDetail) -> Unit,
    onOpenRecent: (com.davidhayesbc.cocktails.core.model.RecipeVariantId) -> Unit,
    onToggleFavorite: (CocktailFamilyId, Boolean) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf<RecipeStyle?>(null) }
    var availabilityFilter by remember { mutableStateOf(RecipeAvailabilityFilter.ANY) }
    var favoritesOnly by remember { mutableStateOf(false) }
    val availableIngredients = pantry.filter { it.level != InventoryLevel.OUT }.map { it.ingredientId }.toSet()
    fun missingCount(recipe: RecipeDetail) = countMissingRequiredIngredients(recipe.ingredients, availableIngredients)
    val matching = recipes.filter { recipe ->
        (!favoritesOnly || recipe.familyId in favoriteFamilyIds) &&
            (selectedStyle == null || recipe.style == selectedStyle) &&
            availabilityFilter.matchesMissingCount(missingCount(recipe)) &&
            (search.isBlank() || recipe.title.contains(search, ignoreCase = true) || recipe.ingredients.any { it.name.contains(search, true) })
    }
    val families = matching.groupBy { it.familyId }
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Recipes", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onAddRecipe) { Text("＋ Add recipe") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search recipes or ingredients") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = selectedStyle == null, onClick = { selectedStyle = null }, label = { Text("All styles") })
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("★ Favorites") })
            RecipeAvailabilityFilter.entries.forEach { filter ->
                FilterChip(
                    selected = availabilityFilter == filter,
                    onClick = { availabilityFilter = filter },
                    label = { Text(filter.label) },
                )
            }
            listOf(RecipeStyle.SHORT, RecipeStyle.LONG, RecipeStyle.SOUR, RecipeStyle.BITTER, RecipeStyle.SWEET, RecipeStyle.HOT, RecipeStyle.COLD).forEach { style ->
                FilterChip(selected = selectedStyle == style, onClick = { selectedStyle = if (selectedStyle == style) null else style }, label = { Text(style.label) })
            }
        }
        if (recipes.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your recipe book starts here", style = MaterialTheme.typography.titleLarge)
                    Text("Add your own drinks now. An attributed offline recipe pack can be added once its source records are cleared.")
                    Button(onClick = onAddRecipe) { Text("Add a recipe") }
                }
            }
        } else if (matching.isEmpty()) {
            Text("No recipes match these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recentDrinks.isNotEmpty()) {
                    item { Text("Recently made", style = MaterialTheme.typography.titleLarge) }
                    items(recentDrinks.take(5), key = { "recent-${it.eventId}" }) { recent ->
                        TextButton(onClick = { onOpenRecent(recent.recipeId) }) {
                            val location = recent.locationName?.let { " · $it" }.orEmpty()
                            Text("${recent.title} · ${recent.madeBy}$location")
                        }
                    }
                }
                families.forEach { (_, variants) ->
                    item(key = variants.first().familyId.value) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(variants.first().title, style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { onToggleFavorite(variants.first().familyId, variants.first().familyId !in favoriteFamilyIds) }) {
                                    Text(if (variants.first().familyId in favoriteFamilyIds) "★ Favorite" else "☆ Add favorite")
                                }
                                if (variants.size > 1) Text("${variants.size} versions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    variants.minOf(::missingCount).let { if (it == 0) "Ready to make" else "Missing $it ingredient${if (it == 1) "" else "s"}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                variants.forEach { variant ->
                                    TextButton(onClick = { onSelectRecipe(variant) }) {
                                        Text(if (variants.size == 1) "Open recipe" else variant.title)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeDetailScreen(
    modifier: Modifier,
    recipe: RecipeDetail,
    notes: List<TastingNote>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    onMarkMade: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
) {
    var confirmArchive by remember(recipe.id) { mutableStateOf(false) }
    var targetServings by remember(recipe.id) { mutableStateOf(recipe.servings.coerceAtLeast(0.5)) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("‹  Recipes") } }
        item { Text(recipe.title, style = MaterialTheme.typography.headlineMedium) }
        item { Text(recipe.style.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onToggleFavorite) { Text(if (isFavorite) "★ Favorite" else "☆ Favorite") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onMarkMade) { Text("✓ Made it") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Servings", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { targetServings = (targetServings - 0.5).coerceAtLeast(0.5) }) { Text("−") }
                Text(formatRecipeQuantity(targetServings), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { targetServings = (targetServings + 0.5).coerceAtMost(24.0) }) { Text("+") }
            }
        }
        item { Text("INGREDIENTS", style = MaterialTheme.typography.labelLarge) }
        items(recipe.ingredients) { line ->
            val scaledAmount = scaleRecipeAmount(line.amount, recipe.servings, targetServings)
            val amountText = scaledAmount?.let(::formatRecipeQuantity)
            val parts = listOfNotNull(amountText, line.unit, line.name)
            Text("• ${parts.joinToString(" ")}${if (line.optional) " (optional)" else ""}", style = MaterialTheme.typography.bodyLarge)
        }
        if (recipe.instructions.isNotBlank()) {
            item { Text("METHOD", style = MaterialTheme.typography.labelLarge) }
            item { Text(recipe.instructions, style = MaterialTheme.typography.bodyLarge) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Tasting notes", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onAddNote) { Text("＋ Add note") }
            }
        }
        if (notes.isEmpty()) item { Text("No notes yet. Add how it tasted for next time.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(notes, key = { it.id }) { note ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${note.memberName}${note.rating?.let { " · $it/5" } ?: ""}", style = MaterialTheme.typography.titleSmall)
                    if (note.note.isNotBlank()) Text(note.note)
                }
            }
        }
        item {
            TextButton(onClick = { confirmArchive = true }) { Text("Archive this recipe version") }
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive this version?") },
            text = { Text("It will disappear from your recipe lists. Its history is kept for sharing and conflict recovery.") },
            confirmButton = {
                Button(onClick = { confirmArchive = false; onArchive() }) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Keep it") } },
        )
    }
}

private fun formatRecipeQuantity(value: Double): String = java.text.DecimalFormat("0.##").format(value)

private fun parseRecipeIngredientDrafts(text: String): List<RecipeIngredientDraft>? {
    val entries = text.lines().filter { it.isNotBlank() }
    if (entries.isEmpty()) return null
    return entries.map { raw ->
        val parts = raw.split('|').map(String::trim)
        if (parts.size !in 1..5 || parts.first().isBlank()) return null
        if (parts.size == 1) RecipeIngredientDraft(parts.first())
        else {
            val amountText = parts.getOrNull(1).orEmpty()
            val amount = if (amountText.isBlank()) null else amountText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val unit = parts.getOrNull(2)?.takeIf(String::isNotBlank)
            val preparation = parts.getOrNull(3)?.takeIf(String::isNotBlank)
            val optionalText = parts.getOrNull(4)?.lowercase().orEmpty()
            if (optionalText.isNotEmpty() && optionalText !in setOf("optional", "true", "yes")) return null
            RecipeIngredientDraft(parts.first(), amount, unit, preparation, optionalText.isNotEmpty())
        }
    }
}

@Composable
private fun AddRecipeDialog(
    onDismiss: () -> Unit,
    families: List<RecipeCard>,
    initialRecipe: RecipeDetail? = null,
    onSave: (String, List<RecipeIngredientDraft>, String, Double, RecipeStyle, String?) -> Boolean,
) {
    var title by remember(initialRecipe?.id) { mutableStateOf(initialRecipe?.title.orEmpty()) }
    var ingredients by remember(initialRecipe?.id) {
        mutableStateOf(initialRecipe?.ingredients.orEmpty().joinToString("\n") { line ->
            if (line.amount == null && line.unit == null && line.preparation == null && !line.optional) line.name
            else listOf(line.name, line.amount?.toString().orEmpty(), line.unit.orEmpty(), line.preparation.orEmpty(), if (line.optional) "optional" else "")
                .joinToString(" | ").trimEnd { it == ' ' || it == '|' }
        })
    }
    var instructions by remember(initialRecipe?.id) { mutableStateOf(initialRecipe?.instructions.orEmpty()) }
    var servings by remember(initialRecipe?.id) { mutableStateOf(initialRecipe?.servings?.toString() ?: "1") }
    var style by remember(initialRecipe?.id) { mutableStateOf(initialRecipe?.style ?: RecipeStyle.OTHER) }
    var styleMenu by remember { mutableStateOf(false) }
    var familyId by remember(initialRecipe?.id) { mutableStateOf(initialRecipe?.familyId?.value) }
    var familyMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRecipe == null) "Add a recipe" else "Edit recipe") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Drink name") }, singleLine = true, modifier = Modifier.testTag("recipe-title"))
                OutlinedTextField(ingredients, { ingredients = it; error = null }, label = { Text("Ingredient · name | amount | unit | prep | optional") }, minLines = 3, modifier = Modifier.testTag("recipe-ingredients"))
                OutlinedTextField(servings, { servings = it; error = null }, label = { Text("Original servings") }, singleLine = true, modifier = Modifier.testTag("recipe-servings"))
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Method (optional)") }, minLines = 2)
                if (initialRecipe == null) {
                    TextButton(onClick = { familyMenu = true }) {
                        val familyName = families.firstOrNull { it.familyId.value == familyId }?.name
                        Text(if (familyName == null) "New cocktail family" else "Version of $familyName  ▾")
                    }
                    DropdownMenu(expanded = familyMenu, onDismissRequest = { familyMenu = false }) {
                        DropdownMenuItem(text = { Text("New family (keep separate)") }, onClick = { familyId = null; familyMenu = false })
                        families.forEach { family ->
                            DropdownMenuItem(text = { Text("Add as a version of ${family.name}") }, onClick = { familyId = family.familyId.value; familyMenu = false })
                        }
                    }
                } else {
                    Text("Family: ${families.firstOrNull { it.familyId.value == familyId }?.name ?: "Unknown"}; use Duplicate to create another version.")
                }
                TextButton(onClick = { styleMenu = true }) { Text("Style: ${style.label} ▾") }
                DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                    RecipeStyle.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { style = option; styleMenu = false })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = parseRecipeIngredientDrafts(ingredients)
                    val parsedServings = servings.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    if (parsed == null) error = "Enter each ingredient as a name or name | amount | unit | prep | optional. Amounts must be positive numbers."
                    else if (parsedServings == null) error = "Servings must be a positive number."
                    else if (!onSave(title, parsed, instructions, parsedServings, style, familyId)) error = "Check the recipe details and try again."
                },
                enabled = title.isNotBlank() && ingredients.lines().any { it.isNotBlank() },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddNoteDialog(onDismiss: () -> Unit, onSave: (Int?, String) -> Unit) {
    var note by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How was it?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rating")
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (1..5).forEach { value ->
                        TextButton(onClick = { rating = value }) { Text(if (rating != null && value <= rating!!) "★" else "☆") }
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Tasting note") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onSave(rating, note) }) { Text("Save note") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HouseholdScreen(
    modifier: Modifier,
    members: List<MemberProfile>,
    selectedMemberId: String,
    onManageMembers: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    shareMessage: String?,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Household", style = MaterialTheme.typography.headlineMedium)
        Text("Shared at home", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Tasting notes are saved under the selected member. Backups contain recipes, pantry, member notes, and revision history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onManageMembers) {
            Text("Member · ${members.firstOrNull { it.id == selectedMemberId }?.name ?: "Choose a member"}  ▾")
        }
        Text("Manual sharing", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export household backup") }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import household backup") }
        Text("Import previews never change your data. Each phone can share a backup using Nearby Share, Drive, or another file app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shareMessage != null) Text(shareMessage, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HouseholdImportDialog(
    preview: HouseholdImportPreview,
    resolutions: Map<String, ConflictResolution>,
    onResolution: (String, ConflictResolution) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val canApply = preview.conflicts.all { conflict -> resolutions[conflict.key] != null }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Review household import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.additions} new · ${preview.updates} updated · ${preview.unchanged} unchanged · ${preview.conflicts.size} conflicts")
                Text("Nothing has been written yet. Cancel leaves this phone unchanged.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preview.conflicts.isNotEmpty()) {
                    Text("Choose a version for each conflict. Every choice preserves both histories in a new merge revision.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(preview.conflicts, key = { it.key }) { conflict ->
                            ImportConflictChooser(conflict, resolutions[conflict.key], onResolution)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onApply, enabled = canApply) { Text("Import") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ImportConflictChooser(
    conflict: BundleRecordChange,
    resolution: ConflictResolution?,
    onResolution: (String, ConflictResolution) -> Unit,
) {
    val local = conflict.local?.data ?: JsonObject(emptyMap())
    val incoming = conflict.incoming.data
    val fields = (local.keys + incoming.keys).distinct().sorted()
    var mergeFields by remember(conflict.key) { mutableStateOf(false) }
    var incomingFields by remember(conflict.key) { mutableStateOf<Set<String>>(emptySet()) }
    var mergedDeleted by remember(conflict.key) { mutableStateOf(conflict.localDeleted) }

    fun publishMerge() {
        val merged = buildJsonObject {
            fields.forEach { field ->
                val value = if (field in incomingFields) incoming[field] ?: local[field] else local[field] ?: incoming[field]
                if (value != null) put(field, value)
            }
        }
        onResolution(conflict.key, ConflictResolution(ConflictChoice.MERGE_FIELDS, merged, mergedDeleted))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${conflict.type} · ${conflict.id.take(12)}", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    mergeFields = false
                    onResolution(conflict.key, ConflictResolution(ConflictChoice.KEEP_LOCAL))
                }, modifier = Modifier.weight(1f)) { Text("Keep local") }
                OutlinedButton(onClick = {
                    mergeFields = false
                    onResolution(conflict.key, ConflictResolution(ConflictChoice.USE_INCOMING))
                }, modifier = Modifier.weight(1f)) { Text("Use backup") }
            }
            OutlinedButton(onClick = {
                mergeFields = true
                incomingFields = fields.filterTo(mutableSetOf()) { it !in local && it in incoming }
                mergedDeleted = conflict.localDeleted
                publishMerge()
            }, modifier = Modifier.fillMaxWidth()) { Text("Merge selected fields") }
            if (mergeFields && resolution?.choice == ConflictChoice.MERGE_FIELDS) {
                fields.filter { local[it] != incoming[it] }.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(field, style = MaterialTheme.typography.labelLarge)
                        Text("Local: ${displayJson(local[field])}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("Backup: ${displayJson(incoming[field])}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        TextButton(onClick = {
                            incomingFields = if (field in incomingFields) incomingFields - field else incomingFields + field
                            publishMerge()
                        }) { Text(if (field in incomingFields) "Use local value" else "Use backup value") }
                    }
                }
                if (conflict.localDeleted != conflict.incomingDeleted) {
                    TextButton(onClick = {
                        mergedDeleted = !mergedDeleted
                        publishMerge()
                    }) { Text(if (mergedDeleted) "Archived · tap to keep active" else "Active · tap to archive") }
                }
            }
        }
    }
}

private fun displayJson(value: JsonElement?): String = when (value) {
    null, JsonNull -> "—"
    is JsonPrimitive -> if (value.isString) value.content else value.toString()
    else -> value.toString()
}

@Composable
private fun PlaceholderScreen(
    modifier: Modifier,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun AddMemberDialog(
    members: List<MemberProfile>,
    selectedMemberId: String,
    onSelectMember: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Household members") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tasting notes will be saved under the selected member.")
                OutlinedButton(onClick = { expanded = true }) {
                    Text("Using ${members.firstOrNull { it.id == selectedMemberId }?.name ?: "member"}  ▾")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    members.forEach { member ->
                        DropdownMenuItem(text = { Text(member.name) }, onClick = { onSelectMember(member.id); expanded = false })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Add another member") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name); name = "" }, enabled = name.isNotBlank()) { Text("Add member") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
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
