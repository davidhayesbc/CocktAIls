package com.davidhayesbc.cocktails.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.core.data.CatalogInstallResult
import com.davidhayesbc.cocktails.core.matching.HomeLocationArea
import com.davidhayesbc.cocktails.core.matching.HomeLocationMatch
import com.davidhayesbc.cocktails.core.matching.matchHomeLocation
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
import com.davidhayesbc.cocktails.core.interchange.MAX_RECIPE_IMPORT_BYTES
import com.davidhayesbc.cocktails.core.interchange.RecipeImportMapping
import com.davidhayesbc.cocktails.core.interchange.RecipeImportPreview
import com.davidhayesbc.cocktails.core.interchange.RecipeImportProvenance
import com.davidhayesbc.cocktails.core.interchange.RecipeImportTable
import com.davidhayesbc.cocktails.core.interchange.RecipeTableParseResult
import com.davidhayesbc.cocktails.core.interchange.parseRecipeImportTable
import com.davidhayesbc.cocktails.core.interchange.previewRecipeImport
import com.davidhayesbc.cocktails.core.interchange.hasSameRecipeFormulation
import com.davidhayesbc.cocktails.core.interchange.isProductPhotoJpeg
import com.davidhayesbc.cocktails.core.interchange.MAX_PRODUCT_PHOTO_BYTES
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
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.core.model.RecipeCard
import com.davidhayesbc.cocktails.core.model.RecipeVariantId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.davidhayesbc.cocktails.android.location.ApproximateCoordinates
import com.davidhayesbc.cocktails.android.location.requestApproximateLocation
import com.davidhayesbc.cocktails.android.LocalDiagnostics
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

private enum class Page(val label: String) {
    MAKE("Make"),
    MY_BAR("My Bar"),
    RECIPES("Recipes"),
    MORE("More"),
}

private const val MAX_SELECTED_PHOTO_BYTES = 20 * 1024 * 1024
private const val MAX_PRODUCT_PHOTO_DIMENSION = 1440

/** Reads only the user-selected URI and re-encodes pixels, which removes embedded metadata. */
private fun readAndCompressProductPhoto(context: Context, uri: Uri): ByteArray {
    val sourceBytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_SELECTED_PHOTO_BYTES) { "The selected image is larger than 20 MiB." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Could not read the selected image.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image." }
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_PRODUCT_PHOTO_DIMENSION * 2) sampleSize *= 2
    val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: error("The selected image could not be decoded.")
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(sourceBytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    var prepared = if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) bitmap else {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    val scale = minOf(1.0, MAX_PRODUCT_PHOTO_DIMENSION.toDouble() / maxOf(prepared.width, prepared.height))
    if (scale < 1.0) {
        val resized = Bitmap.createScaledBitmap(prepared, (prepared.width * scale).toInt().coerceAtLeast(1), (prepared.height * scale).toInt().coerceAtLeast(1), true)
        if (resized !== prepared) prepared.recycle()
        prepared = resized
    }
    try {
        for (quality in listOf(82, 74, 66, 58, 50)) {
            val output = ByteArrayOutputStream()
            prepared.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val result = output.toByteArray()
            if (result.size <= MAX_PRODUCT_PHOTO_BYTES && isProductPhotoJpeg(result)) return result
        }
    } finally {
        prepared.recycle()
    }
    error("The compressed image exceeds the 1 MiB photo limit.")
}

private sealed interface LocationAction {
    data object EnableAutomaticSelection : LocationAction
    data object ResumeAutomaticSelection : LocationAction
    data class SaveHomeArea(val locationId: String, val radiusMeters: Double) : LocationAction
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
    var automaticHomeSelectionEnabled by remember(repository) { mutableStateOf(repository.automaticHomeSelectionEnabled()) }
    var automaticHomeSelectionOverridden by remember(repository) { mutableStateOf(repository.automaticHomeSelectionOverridden()) }
    var homeLocationAreas by remember(repository) { mutableStateOf(repository.homeLocationAreas()) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var showLocationSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var pendingLocationAction by remember { mutableStateOf<LocationAction?>(null) }
    var shopDestinationId by remember { mutableStateOf<String?>(null) }
    var shopPantry by remember { mutableStateOf(emptyList<PantryEntry>()) }
    var selectedMemberId by remember(repository) {
        mutableStateOf(repository.preferredMemberId()?.takeIf { preferred -> members.any { it.id == preferred } } ?: members.firstOrNull()?.id.orEmpty())
    }
    var selectedPage by remember { mutableStateOf(Page.MAKE) }
    var showShop by remember { mutableStateOf(false) }
    var showAddIngredient by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var barcodeInput by remember { mutableStateOf("") }
    var barcodePermissionMessage by remember { mutableStateOf<String?>(null) }
    var selectedProductPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var productPhotoMessage by remember { mutableStateOf<String?>(null) }
    var showAddRecipe by remember { mutableStateOf(false) }
    var recipeToEdit by remember { mutableStateOf<RecipeDetail?>(null) }
    var showAddLocation by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var selectedRecipe by remember { mutableStateOf<RecipeDetail?>(null) }
    var showAddNote by remember { mutableStateOf(false) }
    var recipes by remember { mutableStateOf(repository.recipes()) }
    var starterCatalogInstalled by remember(repository) { mutableStateOf(repository.hasStarterCatalog()) }
    var catalogMessage by remember { mutableStateOf<String?>(null) }
    var recipeFamilies by remember { mutableStateOf(repository.recipeFamilies()) }
    var recentDrinks by remember { mutableStateOf(repository.recentlyMade()) }
    var favoritesVersion by remember { mutableStateOf(0) }
    val favoriteFamilyIds = remember(repository, selectedMemberId, favoritesVersion) { repository.favoriteFamilyIds(selectedMemberId) }
    var pantry by remember { mutableStateOf(emptyList<PantryEntry>()) }
    var shareMessage by remember { mutableStateOf<String?>(null) }
    var stagedBundle by remember { mutableStateOf<HouseholdBundle?>(null) }
    var stagedPreview by remember { mutableStateOf<HouseholdImportPreview?>(null) }
    var stagedRecipeTable by remember { mutableStateOf<RecipeImportTable?>(null) }
    var stagedRecipeFileName by remember { mutableStateOf("") }
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

    val diagnosticsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { it.write(LocalDiagnostics.export().encodeToByteArray()) }
                ?: error("Could not open the selected file for writing.")
        }.onFailure { shareMessage = "Diagnostics export failed. The local copy is still available." }
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

    val recipeImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            shareMessage = "Recipe import canceled. Your data was not changed."
        } else {
            coroutineScope.launch {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_RECIPE_IMPORT_BYTES) { "Recipe file exceeds the 10 MiB limit." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Could not open the selected file for reading.")
                    }
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "recipes.csv"
                    when (val parsed = parseRecipeImportTable(bytes.decodeToString(), name)) {
                        is RecipeTableParseResult.Invalid -> shareMessage = parsed.reason
                        is RecipeTableParseResult.Valid -> {
                            stagedRecipeFileName = name
                            stagedRecipeTable = parsed.table
                            shareMessage = null
                        }
                    }
                }.onFailure { shareMessage = "Recipe preview failed: ${it.message ?: "file could not be read"}" }
            }
        }
    }

    val productPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching { withContext(Dispatchers.IO) { readAndCompressProductPhoto(context, uri) } }
                    .onSuccess {
                        selectedProductPhoto = it
                        productPhotoMessage = "Photo ready · ${it.size / 1024} KiB · resized and metadata removed on this phone."
                    }
                    .onFailure {
                        selectedProductPhoto = null
                        productPhotoMessage = "Photo could not be prepared: ${it.message ?: "choose a smaller image"}"
                    }
            }
        }
    }

    LaunchedEffect(selectedLocationId) {
        pantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
    }

    fun refreshPantry() {
        pantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
    }

    fun selectHome(locationId: String, automatic: Boolean = false) {
        selectedLocationId = locationId
        repository.setPreferredHomeId(locationId)
        pantry = if (locationId.isEmpty()) emptyList() else repository.pantry(locationId)
        if (!automatic && automaticHomeSelectionEnabled) {
            repository.setAutomaticHomeSelectionOverridden(true)
            automaticHomeSelectionOverridden = true
            locationMessage = "Automatic home selection is paused after your manual choice."
        }
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

    fun updateAutomaticHomeSelection() {
        if (!automaticHomeSelectionEnabled || automaticHomeSelectionOverridden || showShop) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationMessage = "Location access is off. Keeping ${locations.firstOrNull { it.id == selectedLocationId }?.name ?: "your selected home"}."
            return
        }
        requestApproximateLocation(context) { coordinates ->
            if (!automaticHomeSelectionEnabled || repository.automaticHomeSelectionOverridden() || showShop) return@requestApproximateLocation
            if (coordinates == null) {
                locationMessage = "Couldn't get an approximate location. Keeping your selected home."
                return@requestApproximateLocation
            }
            when (val match = matchHomeLocation(coordinates.latitude, coordinates.longitude, repository.homeLocationAreas())) {
                is HomeLocationMatch.Matched -> {
                    val matchedHome = repository.locations().firstOrNull { it.id == match.locationId }
                    if (matchedHome == null) return@requestApproximateLocation
                    if (selectedLocationId != matchedHome.id) selectHome(matchedHome.id, automatic = true)
                    locationMessage = "Using ${matchedHome.name} from this phone's approximate location."
                }
                is HomeLocationMatch.Ambiguous -> locationMessage = "Your approximate location matches more than one home. Choose a home manually."
                HomeLocationMatch.NoMatch -> locationMessage = "Outside your saved home areas. Keeping your selected home."
            }
        }
    }

    fun performLocationAction(action: LocationAction) {
        when (action) {
            LocationAction.EnableAutomaticSelection,
            LocationAction.ResumeAutomaticSelection -> {
                repository.setAutomaticHomeSelectionEnabled(true)
                repository.setAutomaticHomeSelectionOverridden(false)
                automaticHomeSelectionEnabled = true
                automaticHomeSelectionOverridden = false
                locationMessage = "Checking this phone's approximate location while the app is open."
                updateAutomaticHomeSelection()
            }
            is LocationAction.SaveHomeArea -> requestApproximateLocation(context) { coordinates ->
                if (coordinates == null) {
                    locationMessage = "Couldn't get an approximate location. Your saved area was not changed."
                } else if (repository.setHomeLocationArea(action.locationId, coordinates.latitude, coordinates.longitude, action.radiusMeters)) {
                    homeLocationAreas = repository.homeLocationAreas()
                    val homeName = locations.firstOrNull { it.id == action.locationId }?.name ?: "Home"
                    locationMessage = "Saved this phone's approximate area for $homeName."
                } else {
                    locationMessage = "The home area was invalid and wasn't saved."
                }
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingLocationAction
        pendingLocationAction = null
        if (granted && action != null) performLocationAction(action)
        else if (!granted) locationMessage = "Location permission wasn't granted. Manual home selection still works."
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocalDiagnostics.recordEvent(if (granted) "CAMERA_PERMISSION_GRANTED" else "CAMERA_PERMISSION_DENIED")
        if (granted && showAddIngredient) {
            barcodePermissionMessage = null
            showBarcodeScanner = true
        } else if (!granted && showAddIngredient) {
            barcodePermissionMessage = "Camera access wasn't granted. Enter the barcode manually instead."
        }
    }

    fun beginBarcodeScan() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showBarcodeScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun beginLocationAction(action: LocationAction) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            performLocationAction(action)
        } else {
            pendingLocationAction = action
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun disableAutomaticHomeSelection() {
        repository.setAutomaticHomeSelectionEnabled(false)
        automaticHomeSelectionEnabled = false
        automaticHomeSelectionOverridden = false
        locationMessage = "Automatic home selection is off. Your selected home stays unchanged."
    }

    fun openShop() {
        shopDestinationId = selectedLocationId
        shopPantry = if (selectedLocationId.isEmpty()) emptyList() else repository.pantry(selectedLocationId)
        showShop = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, automaticHomeSelectionEnabled, automaticHomeSelectionOverridden) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updateAutomaticHomeSelection()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { page ->
                    NavigationBarItem(
                        selected = selectedPage == page && !showShop,
                        onClick = {
                            showShop = false
                            shopDestinationId = null
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
                location = locations.firstOrNull { it.id == shopDestinationId },
                pantry = shopPantry,
                recommendations = repository.shopSuggestions(shopPantry),
                onBack = { showShop = false; shopDestinationId = null },
            )
            selectedPage == Page.MAKE -> MakeScreen(
                modifier = Modifier.padding(contentPadding),
                locations = locations,
                selectedLocationId = selectedLocationId,
                onLocationSelected = ::selectHome,
                locationMessage = locationMessage,
                pantry = pantry,
                onAddIngredients = {
                    selectedPage = Page.MY_BAR
                    showAddIngredient = true
                },
                onOpenShop = ::openShop,
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
                onOpenShop = ::openShop,
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
                starterCatalogInstalled = starterCatalogInstalled,
                catalogMessage = catalogMessage,
                onInstallStarterCatalog = {
                    coroutineScope.launch {
                        catalogMessage = "Adding the offline starter recipes…"
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val pack = context.assets.open("iba-wikipedia-starter/recipes.json").bufferedReader().use { it.readText() }
                                repository.installCatalogPack(pack, System.currentTimeMillis())
                            }
                        }
                        result.onSuccess { installResult ->
                            when (installResult) {
                                is CatalogInstallResult.Installed -> {
                                    recipes = repository.recipes()
                                    recipeFamilies = repository.recipeFamilies()
                                    starterCatalogInstalled = repository.hasStarterCatalog()
                                    catalogMessage = "Added ${installResult.added} recipes; ${installResult.alreadyPresent} were already in your collection."
                                }
                                is CatalogInstallResult.Invalid -> catalogMessage = installResult.reason
                            }
                        }.onFailure { catalogMessage = "Could not add the starter recipes: ${it.message ?: "the bundled pack could not be read"}." }
                    }
                },
            ) else RecipeDetailScreen(
                modifier = Modifier.padding(contentPadding),
                recipe = selectedRecipe!!,
                otherRecipes = recipes,
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
                onEdit = {
                    val current = selectedRecipe!!
                    if (current.sourceKind == "CATALOG") {
                        repository.duplicateRecipeVariant(current.id, System.currentTimeMillis())?.let { copyId ->
                            recipes = repository.recipes()
                            recipeFamilies = repository.recipeFamilies()
                            val copy = recipes.firstOrNull { it.id == copyId }
                            if (copy != null) {
                                selectedRecipe = copy
                                recipeToEdit = copy
                                showAddRecipe = true
                            }
                        }
                    } else {
                        recipeToEdit = current
                        showAddRecipe = true
                    }
                },
                onChangeSourceAssociation = { sourceId, targetId ->
                    val selectedId = selectedRecipe!!.id
                    if (repository.reassociateRecipeSource(sourceId, targetId, System.currentTimeMillis())) {
                        recipes = repository.recipes()
                        selectedRecipe = recipes.firstOrNull { it.id == selectedId }
                        true
                    } else false
                },
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
                onImportRecipes = { recipeImportLauncher.launch(arrayOf("text/csv", "application/json", "text/*", "*/*")) },
                shareMessage = shareMessage,
                onOpenLocationSettings = { showLocationSettings = true },
                onOpenDiagnostics = { showDiagnostics = true },
            )
        }
    }

    if (showLocationSettings) {
        HomeLocationSettingsDialog(
            locations = locations,
            areas = homeLocationAreas,
            automaticEnabled = automaticHomeSelectionEnabled,
            manuallyOverridden = automaticHomeSelectionOverridden,
            statusMessage = locationMessage,
            onEnableAutomatic = { beginLocationAction(LocationAction.EnableAutomaticSelection) },
            onResumeAutomatic = { beginLocationAction(LocationAction.ResumeAutomaticSelection) },
            onDisableAutomatic = ::disableAutomaticHomeSelection,
            onSetHomeArea = { locationId, radius -> beginLocationAction(LocationAction.SaveHomeArea(locationId, radius)) },
            onRemoveHomeArea = { locationId ->
                repository.clearHomeLocationArea(locationId)
                homeLocationAreas = repository.homeLocationAreas()
                locationMessage = "Removed the saved approximate area for this home."
            },
            onDismiss = { showLocationSettings = false },
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            preview = LocalDiagnostics.preview(),
            onExport = { diagnosticsExportLauncher.launch("CocktAIls-Diagnostics.json") },
            onClear = { LocalDiagnostics.clear() },
            onDismiss = { showDiagnostics = false },
        )
    }

    if (showAddIngredient) {
        AddIngredientDialog(
            barcode = barcodeInput,
            barcodeMessage = barcodePermissionMessage,
            productPhotoBytes = selectedProductPhoto,
            productPhotoMessage = productPhotoMessage,
            onBarcodeChanged = { barcodeInput = it; barcodePermissionMessage = null },
            onScanBarcode = ::beginBarcodeScan,
            onPickPhoto = { productPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemovePhoto = { selectedProductPhoto = null; productPhotoMessage = null },
            onDismiss = { showAddIngredient = false; barcodeInput = ""; barcodePermissionMessage = null; selectedProductPhoto = null; productPhotoMessage = null },
            onSave = { name, category, level, barcode, brand, productName, productPhoto ->
                val saved = repository.addIngredient(
                    name = name,
                    category = category,
                    level = level,
                    locationId = selectedLocationId,
                    nowMillis = System.currentTimeMillis(),
                    barcode = barcode,
                    brand = brand,
                    productName = productName,
                    productPhotoJpeg = productPhoto,
                )
                if (saved) {
                    refreshPantry()
                    showAddIngredient = false
                    barcodeInput = ""
                    barcodePermissionMessage = null
                    selectedProductPhoto = null
                    productPhotoMessage = null
                    null
                } else if (barcode.isNotBlank() && normalizeGtinForLookup(barcode) == null) {
                    "That isn't a valid UPC or EAN barcode. Edit it or clear the field to save the ingredient without a product code."
                } else if (barcode.isNotBlank()) {
                    "This barcode is already linked to a different ingredient. Edit it or clear the field to save the ingredient without it."
                } else if (productPhoto != null) {
                    "The label photo could not be attached. Remove it or choose a different image and try again."
                } else {
                    "Enter an ingredient name before saving."
                }
            },
        )
    }

    if (showBarcodeScanner) {
        BarcodeScannerDialog(
            onDetected = { scanned ->
                if (normalizeGtinForLookup(scanned) != null) {
                    barcodeInput = scanned
                    barcodePermissionMessage = "Barcode captured on this phone. Confirm the ingredient and product details before saving."
                    showBarcodeScanner = false
                } else {
                    barcodePermissionMessage = "The scanned code isn't a supported UPC/EAN. You can enter a valid code manually."
                }
            },
            onDismiss = { showBarcodeScanner = false },
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
                    repository.editRecipeVariant(
                        current.id, title, ingredientLines, instructions, servings, style, now,
                        familyId?.let(::CocktailFamilyId),
                    )
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

    stagedRecipeTable?.let { table ->
        RecipeFileImportDialog(
            fileName = stagedRecipeFileName,
            table = table,
            existingFamilies = recipeFamilies,
            existingRecipes = recipes,
            onCancel = {
                stagedRecipeTable = null
                shareMessage = "Recipe import canceled. Your data was not changed."
            },
            onApply = { preview, provenance, familyAssociations, existingVariantAssociations ->
                coroutineScope.launch {
                    when (val result = withContext(Dispatchers.IO) {
                        repository.applyRecipeImport(preview, provenance, familyAssociations, System.currentTimeMillis(), existingVariantAssociations)
                    }) {
                        is com.davidhayesbc.cocktails.core.interchange.RecipeImportApplyResult.Invalid -> shareMessage = result.reason
                        is com.davidhayesbc.cocktails.core.interchange.RecipeImportApplyResult.Applied -> {
                            stagedRecipeTable = null
                            recipes = repository.recipes()
                            recipeFamilies = repository.recipeFamilies()
                            shareMessage = "Imported ${result.imported} recipes; skipped ${result.skippedDuplicates} duplicates."
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
    locationMessage: String?,
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
                TextButton(
                    onClick = { locationMenuExpanded = true },
                    modifier = Modifier.testTag("home-location-selector"),
                ) {
                    Text("${locations.firstOrNull { it.id == selectedLocationId }?.name ?: "Choose a home"}  ▾", style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(expanded = locationMenuExpanded, onDismissRequest = { locationMenuExpanded = false }) {
                    locations.forEach { location ->
                        DropdownMenuItem(text = { Text(location.name) }, onClick = { onLocationSelected(location.id); locationMenuExpanded = false })
                    }
                }
                if (locationMessage != null) {
                    Text(locationMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val photo = remember(entry.photoData) {
        entry.photoData?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (photo != null) {
                    Image(photo, contentDescription = "${entry.name} product photo", modifier = Modifier.height(52.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(entry.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    barcode: String,
    barcodeMessage: String?,
    productPhotoBytes: ByteArray?,
    productPhotoMessage: String?,
    onBarcodeChanged: (String) -> Unit,
    onScanBarcode: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, InventoryLevel, String, String?, String?, ByteArray?) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var productName by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("Spirit") }
    var level by remember { mutableStateOf(InventoryLevel.FULL) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var levelMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to your bar") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("ingredient-form-scroll"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Ingredient", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; saveError = null },
                    label = { Text("Ingredient name") },
                    singleLine = true,
                    supportingText = { Text("Example: London dry gin") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("ingredient-name"),
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { categoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(category) }
                DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                    CATEGORIES.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { category = option; categoryMenuExpanded = false })
                    }
                }
                Text("Stock at selected home", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { levelMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(level.label) }
                DropdownMenu(expanded = levelMenuExpanded, onDismissRequest = { levelMenuExpanded = false }) {
                    InventoryLevel.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { level = option; levelMenuExpanded = false })
                    }
                }
                Text("Product details · optional", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { onBarcodeChanged(it); saveError = null },
                    label = { Text("UPC / EAN barcode") },
                    supportingText = { Text("Scan the label or enter the digits. This works offline.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ingredient-barcode"),
                )
                OutlinedButton(onClick = onScanBarcode, modifier = Modifier.fillMaxWidth()) { Text("Scan barcode") }
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("Brand (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = { Text("Bottle / product name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
                    Text(if (productPhotoBytes == null) "Add optional bottle label photo" else "Replace bottle label photo")
                }
                if (productPhotoBytes != null) {
                    TextButton(onClick = onRemovePhoto) { Text("Remove selected photo") }
                }
                if (productPhotoMessage != null) {
                    Text(productPhotoMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Photos are optional and picked locally. They are resized, re-encoded without EXIF metadata, and included in household backups only when you select them. No photo service or product lookup is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (barcodeMessage != null) Text(barcodeMessage, style = MaterialTheme.typography.bodySmall)
                if (saveError != null) Text(saveError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = { saveError = onSave(name, category, level, barcode, brand.takeIf(String::isNotBlank), productName.takeIf(String::isNotBlank), productPhotoBytes) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inventory is kept separately for each home or bar.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Location name") }, singleLine = true,
                    supportingText = { Text("Example: Home, cabin, or travel bar") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("location-name"),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save location") } },
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
    starterCatalogInstalled: Boolean,
    catalogMessage: String?,
    onInstallStarterCatalog: () -> Unit,
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
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp).testTag("recipe-browser-scroll"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "recipe-browser-heading") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Recipes", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onAddRecipe) { Text("＋ Add recipe") }
            }
        }
        item(key = "recipe-browser-search") {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search recipes or ingredients") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "recipe-browser-filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = selectedStyle == null, onClick = { selectedStyle = null }, label = { Text("All styles") })
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("★ Favorites") })
                RecipeAvailabilityFilter.entries.forEach { filter ->
                    FilterChip(selected = availabilityFilter == filter, onClick = { availabilityFilter = filter }, label = { Text(filter.label) })
                }
                listOf(RecipeStyle.SHORT, RecipeStyle.LONG, RecipeStyle.SOUR, RecipeStyle.BITTER, RecipeStyle.SWEET, RecipeStyle.HOT, RecipeStyle.COLD).forEach { style ->
                    FilterChip(selected = selectedStyle == style, onClick = { selectedStyle = if (selectedStyle == style) null else style }, label = { Text(style.label) })
                }
            }
        }
        if (!starterCatalogInstalled) {
            item(key = "offline-starter-catalog") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Offline starter collection", style = MaterialTheme.typography.titleLarge)
                        Text("Add eight classics: Daiquiri, Manhattan, Margarita, Dry Martini, Negroni, Old Fashioned, Sidecar, and Whiskey Sour. These are stored on this phone and work offline. Existing recipes and notes are left as they are.")
                        Text("Recipe facts are shared under CC BY-SA 3.0. Each recipe includes source and revision history; methods were written for CocktAIls.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onInstallStarterCatalog) { Text("Add 8 starter recipes") }
                        catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else catalogMessage?.let { message -> item(key = "catalog-status") { Text(message, style = MaterialTheme.typography.bodySmall) } }
        if (recipes.isEmpty()) {
            item(key = "empty-recipes") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your recipe book starts here", style = MaterialTheme.typography.titleLarge)
                        Text("Add your own drinks or browse the offline starter collection above.")
                        Button(onClick = onAddRecipe) { Text("Add a recipe") }
                    }
                }
            }
        } else if (matching.isEmpty()) {
            item(key = "no-matching-recipes") { Text("No recipes match these filters.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            if (recentDrinks.isNotEmpty()) {
                item(key = "recently-made-heading") { Text("Recently made", style = MaterialTheme.typography.titleLarge) }
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
                            Text(variants.first().title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("recipe-family-title"))
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

@Composable
private fun RecipeDetailScreen(
    modifier: Modifier,
    recipe: RecipeDetail,
    otherRecipes: List<RecipeDetail>,
    notes: List<TastingNote>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    onMarkMade: () -> Unit,
    onEdit: () -> Unit,
    onChangeSourceAssociation: (String, RecipeVariantId?) -> Boolean,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
) {
    var confirmArchive by remember(recipe.id) { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var sourceToManage by remember(recipe.id) { mutableStateOf<com.davidhayesbc.cocktails.core.model.RecipeSourceInfo?>(null) }
    var sourceTargetId by remember(recipe.id) { mutableStateOf(recipe.id.value) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var sourceAssociationMessage by remember(recipe.id) { mutableStateOf<String?>(null) }
    var targetServings by remember(recipe.id) { mutableStateOf(recipe.servings.coerceAtLeast(0.5)) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("‹  Recipes") } }
        item { Text(recipe.title, style = MaterialTheme.typography.headlineMedium) }
        item { Text(recipe.style.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onToggleFavorite) { Text(if (isFavorite) "★ Favorite" else "☆ Favorite") }
                TextButton(onClick = onEdit) { Text(if (recipe.sourceKind == "CATALOG") "Make editable copy" else "Edit") }
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
        if (recipe.sources.isNotEmpty()) {
            item { Text("SOURCES", style = MaterialTheme.typography.labelLarge) }
            items(recipe.sources, key = { "source-${it.id}" }) { source ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.name + if (source.linked) " · linked" else " · unlinked", style = MaterialTheme.typography.titleSmall)
                        source.recordId?.let { Text("Source record: $it", style = MaterialTheme.typography.bodySmall) }
                        source.license?.let { Text("License: $it", style = MaterialTheme.typography.bodySmall) }
                        source.licenseUrl?.let { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("Read license terms") } }
                        source.attribution?.let { Text("Attribution: $it", style = MaterialTheme.typography.bodySmall) }
                        source.url?.let { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("View source revision") } }
                        source.historyUrl?.let { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("View article history") } }
                        source.repositoryUrl?.let { url ->
                            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("View source dataset") }
                            source.repositoryRevision?.let { Text("Pinned dataset revision: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        source.originalIngredients?.let { Text("Original source measures: $it", style = MaterialTheme.typography.bodySmall) }
                        source.modifications?.takeIf(String::isNotBlank)?.let { Text("Modifications: $it", style = MaterialTheme.typography.bodySmall) }
                        if (source.canReassociate) TextButton(onClick = {
                            sourceToManage = source
                            sourceTargetId = recipe.id.value
                            sourceAssociationMessage = null
                        }) { Text(if (source.linked) "Change or unlink association" else "Associate with a recipe") }
                        else Text("This source has no checked formulation for reassociation.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
    sourceToManage?.let { source ->
        val target = otherRecipes.firstOrNull { it.id.value == sourceTargetId }
        AlertDialog(
            onDismissRequest = { sourceToManage = null },
            title = { Text(if (source.linked) "Change source association" else "Associate this source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${source.name}${source.recordId?.let { " · $it" } ?: ""}")
                    OutlinedButton(onClick = { sourceMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${target?.title ?: "Choose a recipe"} ▾")
                    }
                    DropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                        otherRecipes.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.title) },
                                onClick = { sourceTargetId = candidate.id.value; sourceMenuExpanded = false },
                            )
                        }
                    }
                    Text("The source name, license, attribution, imported details, and association history stay in household backups.", style = MaterialTheme.typography.bodySmall)
                    sourceAssociationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target != null && onChangeSourceAssociation(source.id, target.id)) sourceToManage = null
                    else sourceAssociationMessage = "That recipe has a different measured formulation or is no longer available. Choose a compatible version."
                }) { Text("Associate") }
            },
            dismissButton = {
                Row {
                    if (source.linked) TextButton(onClick = {
                        if (onChangeSourceAssociation(source.id, null)) sourceToManage = null
                        else sourceAssociationMessage = "The source could not be unlinked. Try again."
                    }) { Text("Unlink") }
                    TextButton(onClick = { sourceToManage = null }) { Text("Cancel") }
                }
            },
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
            val amount = if (amountText.isBlank()) null else amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
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
    val dialogContentMaxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.62f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialRecipe == null) "Add a recipe" else "Edit recipe") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = dialogContentMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (initialRecipe == null) "Recipe details" else "Update this household version", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    title, { title = it; error = null }, label = { Text("Drink name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("recipe-title"),
                )
                OutlinedTextField(
                    ingredients, { ingredients = it; error = null },
                    label = { Text("Ingredients") }, minLines = 3,
                    supportingText = { Text("One per line: name | amount | unit | prep | optional. Example: Gin | 45 | ml") },
                    modifier = Modifier.fillMaxWidth().testTag("recipe-ingredients"),
                )
                OutlinedTextField(
                    servings, { servings = it; error = null }, label = { Text("Original yield (servings)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("recipe-servings"),
                )
                OutlinedTextField(
                    instructions, { instructions = it }, label = { Text("Method · optional") }, minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("recipe-method"),
                )
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Recipe grouping", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { familyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    val familyName = families.firstOrNull { it.familyId.value == familyId }?.name
                    Text(if (initialRecipe == null && familyName == null) "New cocktail family" else "Family: ${familyName ?: "Select family"}  ▾")
                }
                DropdownMenu(expanded = familyMenu, onDismissRequest = { familyMenu = false }) {
                    if (initialRecipe == null) {
                        DropdownMenuItem(text = { Text("New family (keep separate)") }, onClick = { familyId = null; familyMenu = false })
                    }
                    families.forEach { family ->
                        val action = if (initialRecipe == null) "Add as a version of" else "Move to"
                        DropdownMenuItem(text = { Text("$action ${family.name}") }, onClick = { familyId = family.familyId.value; familyMenu = false })
                    }
                }
                OutlinedButton(onClick = { styleMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Style: ${style.label} ▾") }
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
                    val parsedServings = servings.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
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
    onOpenLocationSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onImportRecipes: () -> Unit,
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
        Text("Location", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Automatic home selection")
        }
        Text("Manual sharing", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export household backup") }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import household backup") }
        OutlinedButton(onClick = onImportRecipes, modifier = Modifier.fillMaxWidth()) { Text("Import recipes from CSV or JSON") }
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("View or export diagnostics") }
        Text("Import previews never change your data. Each phone can share a backup using Nearby Share, Drive, or another file app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (shareMessage != null) Text(shareMessage, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DiagnosticsDialog(preview: String, onExport: () -> Unit, onClear: () -> Boolean, onDismiss: () -> Unit) {
    var clearError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local diagnostics") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stored only on this phone. Nothing is uploaded automatically. Export saves a file you choose to share.")
                Text("Includes app/device version, camera event categories, and crash stack frames. Excludes recipes, household/member data, photos, barcode values, and exception messages.")
                Text("This captures uncaught Java/Kotlin exceptions. It does not collect logcat and may not capture native crashes or ANRs.", style = MaterialTheme.typography.bodySmall)
                Text(preview, style = MaterialTheme.typography.bodySmall)
                clearError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = onExport) { Text("Export diagnostics") } },
        dismissButton = {
            Row {
                TextButton(onClick = { if (onClear()) onDismiss() else clearError = "Could not remove every local diagnostics file." }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}

@Composable
private fun RecipeFileImportDialog(
    fileName: String,
    table: RecipeImportTable,
    existingFamilies: List<RecipeCard>,
    existingRecipes: List<RecipeDetail>,
    onCancel: () -> Unit,
    onApply: (RecipeImportPreview, RecipeImportProvenance, Map<String, CocktailFamilyId>, Map<Int, RecipeVariantId>) -> Unit,
) {
    var titleField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("title", true) || it.contains("name", true) }.orEmpty()) }
    var ingredientsField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("ingredient", true) }.orEmpty()) }
    var instructionsField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("instruction", true) || it.contains("method", true) }.orEmpty()) }
    var servingsField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("yield", true) || it.contains("serving", true) }.orEmpty()) }
    var styleField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("style", true) }.orEmpty()) }
    var familyField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("family", true) || it.contains("type", true) }.orEmpty()) }
    var sourceIdField by remember(table) { mutableStateOf(table.headers.firstOrNull { it.contains("id", true) }.orEmpty()) }
    var sourceName by remember(fileName) { mutableStateOf(fileName.substringBeforeLast('.').ifBlank { "Imported recipes" }) }
    var sourceUrl by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    var attribution by remember { mutableStateOf("") }
    var associations by remember(table) { mutableStateOf<Map<String, CocktailFamilyId>>(emptyMap()) }
    var variantAssociations by remember(table) { mutableStateOf<Map<Int, RecipeVariantId>>(emptyMap()) }
    val mapping = RecipeImportMapping(
        titleField = titleField,
        ingredientsField = ingredientsField,
        instructionsField = instructionsField.ifBlank { null },
        servingsField = servingsField.ifBlank { null },
        styleField = styleField.ifBlank { null },
        familyField = familyField.ifBlank { null },
        sourceRecordIdField = sourceIdField.ifBlank { null },
    )
    val provenance = RecipeImportProvenance(sourceName, sourceUrl.ifBlank { null }, license, attribution)
    val preview = previewRecipeImport(table, mapping, provenance)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Review recipe import") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("$fileName · ${table.rows.size} rows", style = MaterialTheme.typography.bodySmall)
                Text("Map each file column. Leaving family unmapped keeps every row in a separate drink group.", style = MaterialTheme.typography.bodySmall)
                RecipeImportFieldPicker("Title *", titleField, table.headers, false) { titleField = it }
                RecipeImportFieldPicker("Ingredients *", ingredientsField, table.headers, false) { ingredientsField = it }
                RecipeImportFieldPicker("Instructions", instructionsField, table.headers) { instructionsField = it }
                RecipeImportFieldPicker("Yield / servings", servingsField, table.headers) { servingsField = it }
                RecipeImportFieldPicker("Style", styleField, table.headers) { styleField = it }
                RecipeImportFieldPicker("Drink family", familyField, table.headers) { familyField = it; associations = emptyMap() }
                RecipeImportFieldPicker("Stable source record ID", sourceIdField, table.headers) { sourceIdField = it }
                Text("Source details", style = MaterialTheme.typography.titleMedium)
                Text("Enter the terms and credit that apply to this file. CocktAIls does not verify third-party reuse rights.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(sourceName, { sourceName = it }, label = { Text("Source name *") }, singleLine = true)
                OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("Source URL") }, singleLine = true)
                OutlinedTextField(license, { license = it }, label = { Text("License or terms *") }, singleLine = true)
                OutlinedTextField(attribution, { attribution = it }, label = { Text("Attribution *") }, singleLine = true)
                Text("Ingredient text format: name | amount | unit | optional | preparation. JSON can use ingredient objects with name, amount, unit, optional, and preparation.", style = MaterialTheme.typography.bodySmall)
                if (preview.recipes.isNotEmpty() && familyField.isNotBlank()) {
                    Text("Explicitly link an imported family to an existing family (optional)", style = MaterialTheme.typography.titleSmall)
                    preview.recipes.map { it.familyGroupKey to it.familyName }.distinctBy { it.first }.forEach { (key, name) ->
                        RecipeFamilyAssociationPicker(
                            familyName = name,
                            families = existingFamilies,
                            selected = associations[key],
                            onSelected = { value -> associations = if (value == null) associations - key else associations + (key to value) },
                        )
                    }
                }
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                if (preview.canApply && existingRecipes.isNotEmpty()) {
                    Text("For equivalent formulations from another source, choose the existing variant explicitly. No match is made from a title alone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                preview.recipes.forEach { recipe ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${recipe.title} · ${recipe.ingredientLines.size} ingredients · yield ${recipe.servings}", style = MaterialTheme.typography.bodySmall)
                        val compatible = existingRecipes.filter { candidate ->
                            hasSameRecipeFormulation(
                                recipe,
                                candidate.servings,
                                candidate.style,
                                candidate.ingredients.map { RecipeIngredientDraft(it.name, it.amount, it.unit, it.preparation, it.optional) },
                            )
                        }
                        if (compatible.isNotEmpty()) {
                            ExistingVariantAssociationPicker(
                                recipeTitle = recipe.title,
                                candidates = compatible,
                                families = existingFamilies,
                                selected = variantAssociations[recipe.rowNumber],
                                onSelected = { value -> variantAssociations = if (value == null) variantAssociations - recipe.rowNumber else variantAssociations + (recipe.rowNumber to value) },
                            )
                        }
                    }
                }
                preview.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (preview.errors.isEmpty() && preview.recipes.isNotEmpty()) {
                    Text("No data is written until you choose Import. Cancel leaves the household unchanged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(enabled = preview.canApply, onClick = { onApply(preview, provenance, associations, variantAssociations) }) { Text("Import ${preview.recipes.size} recipes") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ExistingVariantAssociationPicker(
    recipeTitle: String,
    candidates: List<RecipeDetail>,
    families: List<RecipeCard>,
    selected: RecipeVariantId?,
    onSelected: (RecipeVariantId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            val selectedRecipe = candidates.firstOrNull { it.id == selected }
            val familyName = selectedRecipe?.familyId?.let { id -> families.firstOrNull { it.familyId == id }?.name }
            Text("Source match · ${selectedRecipe?.let { "${it.title} · ${familyName ?: "family"}" } ?: "Create a separate variant"} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Create a separate $recipeTitle variant") }, onClick = { onSelected(null); expanded = false })
            candidates.forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        val familyName = families.firstOrNull { it.familyId == candidate.familyId }?.name ?: "Unknown family"
                        Text("${candidate.title} · $familyName · ${candidate.ingredients.joinToString { ingredient -> "${ingredient.name} ${ingredient.amount ?: ""}${ingredient.unit.orEmpty()}" }}")
                    },
                    onClick = { onSelected(candidate.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun RecipeImportFieldPicker(label: String, value: String, fields: List<String>, optional: Boolean = true, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · ${value.ifBlank { if (optional) "Skip" else "Select field" }}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (optional) DropdownMenuItem(text = { Text("Skip") }, onClick = { onSelected(""); expanded = false })
            fields.forEach { field -> DropdownMenuItem(text = { Text(field) }, onClick = { onSelected(field); expanded = false }) }
        }
    }
}

@Composable
private fun RecipeFamilyAssociationPicker(
    familyName: String,
    families: List<RecipeCard>,
    selected: CocktailFamilyId?,
    onSelected: (CocktailFamilyId?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$familyName · ${families.firstOrNull { it.familyId == selected }?.name ?: "Create separate family"} ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Create separate family") }, onClick = { onSelected(null); expanded = false })
            families.forEach { family ->
                DropdownMenuItem(text = { Text(family.name) }, onClick = { onSelected(family.familyId); expanded = false })
            }
        }
    }
}

@Composable
private fun HomeLocationSettingsDialog(
    locations: List<HomeLocation>,
    areas: List<HomeLocationArea>,
    automaticEnabled: Boolean,
    manuallyOverridden: Boolean,
    statusMessage: String?,
    onEnableAutomatic: () -> Unit,
    onResumeAutomatic: () -> Unit,
    onDisableAutomatic: () -> Unit,
    onSetHomeArea: (String, Double) -> Unit,
    onRemoveHomeArea: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location selection") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Approximate location is checked while CocktAIls is open. We don't request precise or background access. Saved home areas stay on this phone and aren't included in household backups.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    !automaticEnabled -> Button(onClick = onEnableAutomatic, modifier = Modifier.fillMaxWidth()) {
                        Text("Enable automatic home selection")
                    }
                    manuallyOverridden -> {
                        Text("Automatic selection is paused after your manual home choice.")
                        Button(onClick = onResumeAutomatic, modifier = Modifier.fillMaxWidth()) { Text("Resume automatic selection") }
                        TextButton(onClick = onDisableAutomatic) { Text("Turn off automatic selection") }
                    }
                    else -> {
                        Text("Automatic selection is on while the app is open.")
                        TextButton(onClick = onDisableAutomatic) { Text("Turn off automatic selection") }
                    }
                }
                Text("Set a private area for each home", style = MaterialTheme.typography.titleMedium)
                locations.forEach { location ->
                    HomeLocationAreaEditor(
                        location = location,
                        area = areas.firstOrNull { it.locationId == location.id },
                        onSetArea = { radius -> onSetHomeArea(location.id, radius) },
                        onRemoveArea = { onRemoveHomeArea(location.id) },
                    )
                }
                if (statusMessage != null) {
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun HomeLocationAreaEditor(
    location: HomeLocation,
    area: HomeLocationArea?,
    onSetArea: (Double) -> Unit,
    onRemoveArea: () -> Unit,
) {
    var radiusInput by remember(location.id, area?.radiusMeters) {
        mutableStateOf((area?.radiusMeters ?: DEFAULT_HOME_RADIUS_METERS).toInt().toString())
    }
    val radius = radiusInput.toDoubleOrNull()
    val radiusIsValid = radius != null && radius.isFinite() && radius in MIN_HOME_RADIUS_METERS..MAX_HOME_RADIUS_METERS

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(location.name, style = MaterialTheme.typography.titleSmall)
        Text(
            if (area == null) "No area saved on this phone yet." else "Saved area · ${area.radiusMeters.toInt()} m radius",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = radiusInput,
            onValueChange = { radiusInput = it },
            label = { Text("Match radius (meters)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = !radiusIsValid,
            supportingText = { Text("Choose ${MIN_HOME_RADIUS_METERS.toInt()}–${MAX_HOME_RADIUS_METERS.toInt()} meters") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSetArea(radius!!) }, enabled = radiusIsValid, modifier = Modifier.fillMaxWidth()) {
            Text("Set this phone's current area for ${location.name}")
        }
        if (area != null) TextButton(onClick = onRemoveArea) { Text("Forget this phone's area") }
    }
}

private const val MIN_HOME_RADIUS_METERS = 50.0
private const val MAX_HOME_RADIUS_METERS = 10_000.0
private const val DEFAULT_HOME_RADIUS_METERS = 250.0

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
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Tasting notes will be saved under the selected member.")
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Using ${members.firstOrNull { it.id == selectedMemberId }?.name ?: "member"}  ▾")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    members.forEach { member ->
                        DropdownMenuItem(text = { Text(member.name) }, onClick = { onSelectMember(member.id); expanded = false })
                    }
                }
                OutlinedTextField(
                    name, { name = it }, label = { Text("Add another member") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("member-name"),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name.trim()); name = "" }, enabled = name.isNotBlank()) { Text("Add member") }
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
