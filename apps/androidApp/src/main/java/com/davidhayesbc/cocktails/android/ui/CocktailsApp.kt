package com.davidhayesbc.cocktails.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.FileProvider
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
import com.davidhayesbc.cocktails.core.interchange.photoSha256
import com.davidhayesbc.cocktails.core.model.HomeLocation
import com.davidhayesbc.cocktails.core.model.CocktailFamilyId
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.model.PantryEntry
import com.davidhayesbc.cocktails.core.model.LocalProductBarcodeMatch
import com.davidhayesbc.cocktails.core.model.BottleIntakeSession
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.BottleIntakeCommitResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import com.davidhayesbc.cocktails.android.location.ApproximateCoordinates
import com.davidhayesbc.cocktails.android.location.requestApproximateLocation
import com.davidhayesbc.cocktails.android.LocalDiagnostics
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsLocalCache
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsLookupClient
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsLookupResult
import com.davidhayesbc.cocktails.android.product.OpenFoodFactsSuggestion
import com.davidhayesbc.cocktails.android.product.SharedPreferencesLookupStorage
import com.davidhayesbc.cocktails.android.product.lookupResultMatchesActiveRequest
import com.davidhayesbc.cocktails.android.product.OPEN_FOOD_FACTS_HOME
import com.davidhayesbc.cocktails.android.product.OPEN_FOOD_FACTS_LICENSE
import com.davidhayesbc.cocktails.android.product.ODBL_LICENSE_URL
import com.davidhayesbc.cocktails.android.product.DBCL_LICENSE_URL
import com.davidhayesbc.cocktails.android.product.ScannedBottleEnrichmentCoordinator
import com.davidhayesbc.cocktails.android.product.ScannedBottlePlan
import com.davidhayesbc.cocktails.android.product.ScannedBottleStage
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugReport
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugEvent
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugStage
import com.davidhayesbc.cocktails.android.product.BottleProviderCall
import com.davidhayesbc.cocktails.android.ui.theme.CocktailsTheme
import com.davidhayesbc.cocktails.android.product.AutomaticBottleExtractor
import com.davidhayesbc.cocktails.android.product.automaticBottleExtractor
import com.davidhayesbc.cocktails.android.product.scannedBottleSearchQuery
import com.davidhayesbc.cocktails.android.product.sourceMentionsScannedBarcode
import com.davidhayesbc.cocktails.android.product.structuredBottleIdentityForPage
import com.davidhayesbc.cocktails.android.product.resolveStructuredBottleIdentityForPage
import com.davidhayesbc.cocktails.android.product.normalizePublicHttpsUrl
import com.davidhayesbc.cocktails.android.product.FirecrawlCredentialStore
import com.davidhayesbc.cocktails.android.product.OllamaCloudCredentialStore
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentJob
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentOperation
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentQueue
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentSerialGate
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentStatus
import com.davidhayesbc.cocktails.android.product.BottleProviderOutcome
import com.davidhayesbc.cocktails.android.product.effectiveBottleProviderOutcome
import com.davidhayesbc.cocktails.android.product.BottleEnrichmentWorkScheduler
import com.davidhayesbc.cocktails.android.product.BottleScanEnrichmentRuntime
import com.davidhayesbc.cocktails.android.product.BottleCaptureToken
import com.davidhayesbc.cocktails.android.product.PendingBottlePhoto
import com.davidhayesbc.cocktails.android.product.bottleCaptureTempFileName
import com.davidhayesbc.cocktails.android.product.bottleCaptureDraftIsStillOpen
import com.davidhayesbc.cocktails.android.product.pendingBottlePhotoAfterCapture
import com.davidhayesbc.cocktails.android.product.processingBottleCaptureAfterCompletion
import com.davidhayesbc.cocktails.android.product.isCurrentBottleCapture
import com.davidhayesbc.cocktails.android.product.restoreBottleCaptureToken
import com.davidhayesbc.cocktails.android.product.retainPendingBottleValueForOwner
import com.davidhayesbc.cocktails.android.product.bottleLabelRestoreBelongsToCurrentDraft
import com.davidhayesbc.cocktails.android.product.correctedForBarcode
import com.davidhayesbc.cocktails.android.product.bottleFieldsToPreserveOnBarcodeCorrection
import com.davidhayesbc.cocktails.android.product.BottleLabelCropper
import com.davidhayesbc.cocktails.android.product.stripJpegMetadata
import com.davidhayesbc.cocktails.android.product.BottleLabelCropEditorState
import com.davidhayesbc.cocktails.android.product.NormalizedLabelCrop
import com.davidhayesbc.cocktails.android.product.SharedPreferencesBottleEnrichmentQueueStorage
import com.davidhayesbc.cocktails.android.product.FirecrawlSearchProvider
import com.davidhayesbc.cocktails.android.product.OllamaCloudBottleDetailExtractor
import com.davidhayesbc.cocktails.android.product.BottleDetailExtractionResult
import com.davidhayesbc.cocktails.android.product.BottleDetailSuggestions
import com.davidhayesbc.cocktails.android.product.acceptedProductSourcesForBottle
import com.davidhayesbc.cocktails.android.product.BottleFactField
import com.davidhayesbc.cocktails.android.product.unresolvedBottleSourceFields
import com.davidhayesbc.cocktails.android.product.PendingBottleEvidenceStore
import com.davidhayesbc.cocktails.android.product.SharedPreferencesPendingBottleEvidenceStorage
import com.davidhayesbc.cocktails.android.product.BottleDraftField
import com.davidhayesbc.cocktails.android.product.BottleDraftSuggestionFields
import com.davidhayesbc.cocktails.android.product.mergeBottleSuggestionFields
import com.davidhayesbc.cocktails.android.product.preserveEditedBottleFields
import com.davidhayesbc.cocktails.android.product.isValidOllamaModelIdentifier
import com.davidhayesbc.cocktails.android.product.LocalModelFileStore
import com.davidhayesbc.cocktails.android.product.DetailedBottleLookupDebugSessionStore
import com.davidhayesbc.cocktails.android.product.DetailedCaptureStatus
import com.davidhayesbc.cocktails.android.product.combineBottleDiagnostics
import com.davidhayesbc.cocktails.android.product.LocalModelInstallResult
import com.davidhayesbc.cocktails.android.product.LocalBottleDetailExtractor
import com.davidhayesbc.cocktails.android.product.LazyLlamaCppLocalInferenceRunner
import com.davidhayesbc.cocktails.android.product.LocalInferenceFailureCode
import com.davidhayesbc.cocktails.android.product.LOCAL_MODEL_LABEL
import com.davidhayesbc.cocktails.android.product.QWEN3_06B_MODEL_PAGE
import com.davidhayesbc.cocktails.android.product.WebSearchHit
import com.davidhayesbc.cocktails.android.product.WebPageText
import com.davidhayesbc.cocktails.android.product.WebSearchResult
import com.davidhayesbc.cocktails.android.product.WebPageResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private enum class Page(val label: String) {
    MAKE("Make"),
    MY_BAR("My Bar"),
    RECIPES("Recipes"),
    MORE("More"),
}

internal enum class BottleIntakeRoute { SETUP, SCANNING, CAPTURE_LABEL, REVIEW, QUEUE, PAUSE, MANUAL_ENTRY }

internal enum class BottleBarcodeCorrection { INVALID, UNCHANGED, CHANGED }

internal data class DebugSettingsRouteState(
    val selectedPage: String,
    val bottleIntakeVisible: Boolean,
    val diagnosticsVisible: Boolean,
    val diagnosticsDraftId: String?,
    val intakeSessionId: String?,
    val intakeDraftIds: List<String>,
)

internal fun routeFromDiagnosticsToDebugSettings(state: DebugSettingsRouteState) = state.copy(
    selectedPage = "MORE",
    bottleIntakeVisible = false,
    diagnosticsVisible = false,
    diagnosticsDraftId = null,
)

internal fun validateBottleBarcodeCorrection(current: String?, proposed: String): BottleBarcodeCorrection {
    val normalizedProposed = normalizeGtinForLookup(proposed)
        ?: return BottleBarcodeCorrection.INVALID
    val normalizedCurrent = current?.let(::normalizeGtinForLookup)
    return if (normalizedCurrent == normalizedProposed) BottleBarcodeCorrection.UNCHANGED else BottleBarcodeCorrection.CHANGED
}

private const val MAX_SELECTED_PHOTO_BYTES = 20 * 1024 * 1024
private const val MAX_PRODUCT_PHOTO_DIMENSION = 1440

private data class InstalledAppVersion(
    val name: String,
    val code: Long,
    val buildStatus: String,
)

internal data class BottleWebSearchState(
    val searching: Boolean = false,
    val fetchingUrl: String? = null,
    val extracting: Boolean = false,
    val extractingWith: String? = null,
    val hits: List<WebSearchHit> = emptyList(),
    val page: WebPageText? = null,
    val extractedSuggestions: BottleDetailSuggestions? = null,
    val openFoodFactsResult: OpenFoodFactsLookupResult? = null,
    val localMatch: Boolean = false,
    val automatedScan: Boolean = false,
    val userEditedFields: Set<BottleDraftField> = emptySet(),
    val automaticStage: String? = null,
    val barcodeVerifiedInPage: Boolean = false,
    val barcodeVerifiedByStructuredProduct: Boolean = false,
    val message: String? = null,
)

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
            val result = stripJpegMetadata(output.toByteArray()) ?: continue
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
    val openFoodFactsClient = remember(context) {
        OpenFoodFactsLookupClient(OpenFoodFactsLocalCache(SharedPreferencesLookupStorage(context)))
    }
    val firecrawlCredentialStore = remember(context) { FirecrawlCredentialStore(context) }
    val firecrawlSearchProvider = remember(firecrawlCredentialStore) {
        FirecrawlSearchProvider(keyProvider = firecrawlCredentialStore::readApiKey)
    }
    val ollamaCloudCredentialStore = remember(context) { OllamaCloudCredentialStore(context) }
    val ollamaCloudExtractor = remember(ollamaCloudCredentialStore) {
        OllamaCloudBottleDetailExtractor(keyProvider = ollamaCloudCredentialStore::readApiKey)
    }
    val localModelFileStore = remember(context) { LocalModelFileStore(File(context.filesDir, "local-models")) }
    val bottleEnrichmentQueue = remember(context) {
        BottleEnrichmentQueue(SharedPreferencesBottleEnrichmentQueueStorage(context))
    }
    val pendingBottleEvidenceStore = remember(context) {
        PendingBottleEvidenceStore(SharedPreferencesPendingBottleEvidenceStorage(context))
    }
    val detailedLookupCaptureStore = remember(context) {
        DetailedBottleLookupDebugSessionStore(context.noBackupFilesDir, shareCacheDirectory = context.cacheDir)
    }
    var detailedCaptureStatus by remember { mutableStateOf<DetailedCaptureStatus?>(null) }
    var settingsDetailedCaptureStatus by remember { mutableStateOf(detailedLookupCaptureStore.sessionStatus()) }
    var detailedDebugModeMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticsExportContents by remember { mutableStateOf<String?>(null) }

    var pendingBottleEnrichmentJobs by remember(bottleEnrichmentQueue) {
        mutableStateOf(bottleEnrichmentQueue.jobs().associateBy { it.draftId })
    }
    var openFoodFactsEnabled by remember(context) {
        mutableStateOf(context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).getBoolean("open_food_facts_enabled", false))
    }
    var firecrawlKeySaved by remember(context) { mutableStateOf(firecrawlCredentialStore.hasSavedKey()) }
    var firecrawlKeyNeedsReplacement by remember(context) {
        mutableStateOf(firecrawlCredentialStore.hasSavedKey() && firecrawlCredentialStore.readApiKey() == null)
    }
    var firecrawlCredentialMessage by remember { mutableStateOf<String?>(null) }
    var firecrawlSearchEnabled by remember(context) {
        mutableStateOf(context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).getBoolean("firecrawl_search_enabled", false) && firecrawlCredentialStore.readApiKey() != null)
    }
    var ollamaCloudKeySaved by remember(context) { mutableStateOf(ollamaCloudCredentialStore.hasSavedKey()) }
    var ollamaCloudKeyNeedsReplacement by remember(context) {
        mutableStateOf(ollamaCloudCredentialStore.hasSavedKey() && ollamaCloudCredentialStore.readApiKey() == null)
    }
    var ollamaCloudEnabled by remember(context) {
        val preferences = context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
        val savedModel = preferences.getString("ollama_cloud_model", "").orEmpty()
        mutableStateOf(preferences.getBoolean("ollama_cloud_enabled", false) && ollamaCloudCredentialStore.readApiKey() != null && isValidOllamaModelIdentifier(savedModel))
    }
    var ollamaCredentialMessage by remember { mutableStateOf<String?>(null) }
    var ollamaCloudModel by remember(context) {
        mutableStateOf(context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).getString("ollama_cloud_model", "").orEmpty())
    }
    var localModelFile by remember(context) { mutableStateOf(localModelFileStore.installedModel()) }
    var localAiEnabled by remember(context) {
        mutableStateOf(context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).getBoolean("local_ai_enabled", false) && localModelFileStore.installedModel() != null)
    }
    var localModelMessage by remember { mutableStateOf<String?>(null) }
    var localModelImporting by remember { mutableStateOf(false) }
    var localModelImportProgressBytes by remember { mutableStateOf(0L) }
    val localModelImportCancelled = remember { AtomicBoolean(false) }
    val localModelImportProgress = remember { AtomicLong(0L) }
    var showFirecrawlCredentialDialog by remember { mutableStateOf(false) }
    var showOllamaCloudSettingsDialog by remember { mutableStateOf(false) }
    var bottleWebSearchStates by remember { mutableStateOf<Map<String, BottleWebSearchState>>(emptyMap()) }
    val bottleWebSearchJobs = remember { mutableMapOf<String, Job>() }
    val bottleWebSearchRequestTokens = remember { mutableMapOf<String, Int>() }
    val bottleExtractionJobs = remember { mutableMapOf<String, Job>() }
    val bottleExtractionRequestTokens = remember { mutableMapOf<String, Int>() }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDiagnosticsForDraftId by remember { mutableStateOf<String?>(null) }
    var lookupReportCopyMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showDiagnostics, showDiagnosticsForDraftId) {
        while (showDiagnostics) {
            val draftId = showDiagnosticsForDraftId
            detailedCaptureStatus = draftId?.let { detailedLookupCaptureStore.statusForDraft(it) }
            delay(500)
        }
        detailedCaptureStatus = null
    }

    LaunchedEffect(localModelImporting) {
        while (localModelImporting) {
            localModelImportProgressBytes = localModelImportProgress.get()
            delay(250)
        }
    }

    fun refreshPendingBottleEnrichmentJobs() {
        pendingBottleEnrichmentJobs = bottleEnrichmentQueue.jobs().associateBy { it.draftId }
    }

    LaunchedEffect(bottleEnrichmentQueue, context) {
        val plan = BottleScanEnrichmentRuntime(context).plan()
        BottleEnrichmentWorkScheduler.reconcile(
            context = context,
            queue = bottleEnrichmentQueue,
            requiresNetwork = plan.openFoodFactsEnabled || plan.firecrawlEnabled,
        )
        refreshPendingBottleEnrichmentJobs()
    }

    fun persistBottleLookupDebug(report: BottleLookupDebugReport) {
        LocalDiagnostics.recordBottleLookup(report)
    }

    fun cancelAllBottleEnrichmentRequests(sessionId: String?, keepPaused: Boolean) {
        val ids = bottleWebSearchJobs.keys + bottleExtractionJobs.keys
        ids.forEach { id ->
            bottleWebSearchRequestTokens[id] = (bottleWebSearchRequestTokens[id] ?: 0) + 1
            bottleExtractionRequestTokens[id] = (bottleExtractionRequestTokens[id] ?: 0) + 1
        }
        bottleWebSearchJobs.values.forEach(Job::cancel)
        bottleWebSearchJobs.clear()
        bottleExtractionJobs.values.forEach(Job::cancel)
        bottleExtractionJobs.clear()
        if (keepPaused && sessionId != null) {
            bottleEnrichmentQueue.jobs().filter { it.sessionId == sessionId && it.draftId in ids }.forEach { job ->
                if (job.status == BottleEnrichmentStatus.RUNNING || job.status == BottleEnrichmentStatus.QUEUED) {
                    bottleEnrichmentQueue.pause(job.id, System.currentTimeMillis())
                }
            }
        } else if (sessionId != null) {
            BottleEnrichmentWorkScheduler.cancelSession(context, sessionId)
            bottleEnrichmentQueue.removeSession(sessionId)
        }
        refreshPendingBottleEnrichmentJobs()
    }
    val installedAppVersion = remember(context) {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "Unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val buildStatus = when {
            isDebuggable -> "Debug build"
            versionName.contains("preview", ignoreCase = true) -> "Preview build"
            else -> "Installed build"
        }
        InstalledAppVersion(versionName, versionCode, buildStatus)
    }
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
    var pendingLocationAction by remember { mutableStateOf<LocationAction?>(null) }
    var shopDestinationId by remember { mutableStateOf<String?>(null) }
    var shopPantry by remember { mutableStateOf(emptyList<PantryEntry>()) }
    var selectedMemberId by remember(repository) {
        mutableStateOf(repository.preferredMemberId()?.takeIf { preferred -> members.any { it.id == preferred } } ?: members.firstOrNull()?.id.orEmpty())
    }
    var selectedPage by remember { mutableStateOf(Page.MAKE) }
    LaunchedEffect(selectedPage, detailedLookupCaptureStore) {
        while (selectedPage == Page.MORE) {
            settingsDetailedCaptureStatus = detailedLookupCaptureStore.sessionStatus()
            delay(500)
        }
    }
    var showShop by remember { mutableStateOf(false) }
    var showAddIngredient by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showBottleIntake by remember { mutableStateOf(false) }
    var showBottleScanner by remember { mutableStateOf(false) }
    var activeScannedDraftId by remember(context, repository) {
        val preferences = context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE)
        val rememberedId = preferences.getString("active_draft_id", null)
        val openDrafts = repository.openBottleIntakeSession()?.drafts.orEmpty()
        mutableStateOf(rememberedId?.takeIf { id -> openDrafts.any { it.id == id } })
    }
    var bottleIntakeCameraMessage by remember { mutableStateOf<String?>(null) }
    var bottleIntakeSession by remember(repository) { mutableStateOf(repository.openBottleIntakeSession()) }
    var bottleIntakeRoute by remember(repository) {
        mutableStateOf(if (repository.openBottleIntakeSession() == null) BottleIntakeRoute.SETUP else BottleIntakeRoute.SCANNING)
    }
    LaunchedEffect(showBottleIntake, bottleIntakeSession?.id, bottleIntakeSession?.drafts?.size, pendingBottleEnrichmentJobs) {
        val session = bottleIntakeSession
        if (showBottleIntake && session != null) {
            val restored = bottleWebSearchStates.toMutableMap()
            session.drafts.forEach { draft ->
                val draftBarcode = draft.barcode ?: return@forEach
                val prior = restored[draft.id]
                val job = pendingBottleEnrichmentJobs[draft.id]
                val savedSuggestions = pendingBottleEvidenceStore.read(draft.id)?.takeIf { saved ->
                    saved.targetBarcode?.let { normalizeGtinForLookup(it) } == normalizeGtinForLookup(draftBarcode) &&
                        saved.identityEvidence?.let { sourceMentionsScannedBarcode(draftBarcode, it) } == true
                }
                if (job != null || savedSuggestions != null || prior?.automatedScan == true) {
                    restored[draft.id] = (prior ?: BottleWebSearchState()).copy(
                        automatedScan = true,
                        userEditedFields = preserveEditedBottleFields(
                            prior?.userEditedFields.orEmpty(),
                            pendingBottleEvidenceStore.editedFields(draft.id),
                        ),
                        localMatch = repository.localProductForBarcode(draftBarcode) != null,
                        barcodeVerifiedInPage = savedSuggestions?.targetBarcode != null || prior?.barcodeVerifiedInPage == true,
                        barcodeVerifiedByStructuredProduct = savedSuggestions?.identityEvidence?.startsWith("Structured product fields:") == true || prior?.barcodeVerifiedByStructuredProduct == true,
                        page = savedSuggestions?.let { WebPageText("Verified source", it.sourceUrl, "", it.provider) } ?: prior?.page,
                        extractedSuggestions = savedSuggestions ?: prior?.extractedSuggestions,
                        message = when {
                            savedSuggestions != null -> "Restored the verified source and suggestions for this unaccepted bottle. Review them; no provider request restarted."
                            job?.labelCaptureRequired == true -> "Capture this bottle's label or skip the photo. Lookup runs separately while you do that."
                            job?.status == BottleEnrichmentStatus.CAPTURE_REQUIRED -> "Capture this bottle's label or skip the photo before lookup is queued."
                            job?.status == BottleEnrichmentStatus.QUEUED -> "Queued for background processing. You can keep scanning; results will wait for review."
                            job?.status == BottleEnrichmentStatus.RUNNING -> "Processing in the background. You can leave intake; results will wait for review."
                            job?.status == BottleEnrichmentStatus.PAUSED -> "This scan was interrupted. Resume only when you choose; no provider request restarted automatically."
                            job?.status == BottleEnrichmentStatus.READY_FOR_REVIEW -> "Suggestions are ready. Select this bottle to review them."
                            job?.status == BottleEnrichmentStatus.NEEDS_ATTENTION -> "Lookup needs attention. Review the bottle or retry when ready."
                            else -> prior?.message ?: "This bottle draft was kept locally. No provider request restarted automatically."
                        },
                    )
                }
            }
            if (restored != bottleWebSearchStates) bottleWebSearchStates = restored
        }
    }

    LaunchedEffect(showBottleIntake, bottleIntakeSession?.id) {
        while (showBottleIntake && bottleIntakeSession != null) {
            delay(750)
            refreshPendingBottleEnrichmentJobs()
        }
    }
    var pendingBottleLabelPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var pendingBottleLabelOriginalPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var pendingBottleLabelCropSuggestion by remember { mutableStateOf<NormalizedLabelCrop?>(null) }
    var bottleLabelCropBusy by remember { mutableStateOf(false) }
    var bottleLabelCaptureMessage by remember { mutableStateOf<String?>(null) }
    val pendingLabelPreferences = remember(context) { context.getSharedPreferences("cocktails_pending_label_capture", Context.MODE_PRIVATE) }
    val restoredBottleCameraToken = remember(context) {
        restoreBottleCaptureToken(
            launchPending = pendingLabelPreferences.getBoolean("camera_launch_pending", false),
            generation = pendingLabelPreferences.getLong("capture_generation", 0),
            draftId = pendingLabelPreferences.getString("camera_draft_id", null),
            manualCapture = pendingLabelPreferences.getBoolean("camera_manual_capture", false),
            fileName = pendingLabelPreferences.getString("camera_file_name", null),
        )
    }
    var bottleCaptureGeneration by remember { mutableStateOf(maxOf(restoredBottleCameraToken?.generation ?: 0, pendingLabelPreferences.getLong("capture_generation", 0))) }
    var activeBottleCaptureToken by remember { mutableStateOf(restoredBottleCameraToken) }
    var launchedBottleCameraToken by remember { mutableStateOf(restoredBottleCameraToken) }
    var processingBottleCameraToken by remember { mutableStateOf<BottleCaptureToken?>(null) }
    var pendingBottleLabelDraftId by remember(context, repository) {
        val captureRequiredIds = bottleEnrichmentQueue.jobs()
            .filter { it.labelCaptureRequired }
            .map { it.draftId }
            .toSet()
        val storedId = pendingLabelPreferences.getString("draft_id", null)
            ?.takeIf { it in captureRequiredIds }
            ?: captureRequiredIds.firstOrNull()
        val draftExists = repository.openBottleIntakeSession()?.drafts?.any { it.id == storedId } == true
        mutableStateOf(storedId?.takeIf { draftExists })
    }
    var finishScannedBottleLabel: ((String, ByteArray?) -> Boolean)? = null

    fun beginBottleCaptureOperation(draftId: String?): BottleCaptureToken {
        bottleCaptureGeneration += 1
        pendingLabelPreferences.edit().putLong("capture_generation", bottleCaptureGeneration).apply()
        return BottleCaptureToken(bottleCaptureGeneration, draftId).also { activeBottleCaptureToken = it }
    }

    fun clearPersistedCameraLaunch(token: BottleCaptureToken) {
        val stored = restoreBottleCaptureToken(
            launchPending = pendingLabelPreferences.getBoolean("camera_launch_pending", false),
            generation = pendingLabelPreferences.getLong("capture_generation", 0),
            draftId = pendingLabelPreferences.getString("camera_draft_id", null),
            manualCapture = pendingLabelPreferences.getBoolean("camera_manual_capture", false),
            fileName = pendingLabelPreferences.getString("camera_file_name", null),
        )
        if (stored == token) pendingLabelPreferences.edit()
            .remove("camera_launch_pending")
            .remove("camera_result_ready")
            .remove("camera_draft_id")
            .remove("camera_manual_capture")
            .remove("camera_file_name")
            .commit()
    }

    fun persistCameraLaunch(token: BottleCaptureToken): Boolean = pendingLabelPreferences.edit()
        .putLong("capture_generation", token.generation)
        .putBoolean("camera_launch_pending", true)
        .putBoolean("camera_manual_capture", token.draftId == null)
        .putString("camera_file_name", bottleCaptureTempFileName(token))
        .apply { if (token.draftId == null) remove("camera_draft_id") else putString("camera_draft_id", token.draftId) }
        .commit()

    LaunchedEffect(repository, launchedBottleCameraToken, processingBottleCameraToken, pendingBottleLabelDraftId) {
        val directory = File(context.cacheDir, "intake-labels")
        val openIds = repository.openBottleIntakeSession()?.drafts.orEmpty().map { it.id }.toSet()
        val token = processingBottleCameraToken ?: launchedBottleCameraToken
        if (token == null && pendingLabelPreferences.getBoolean("camera_launch_pending", false)) {
            val orphanName = pendingLabelPreferences.getString("camera_file_name", null)
                ?.takeIf { it.matches(Regex("capture-[0-9]{1,20}-(?:manual|[A-Za-z0-9-]{1,100})\\.jpg")) }
            orphanName?.let { File(directory, it).delete() }
            pendingLabelPreferences.edit()
                .remove("camera_launch_pending")
                .remove("camera_result_ready")
                .remove("camera_draft_id")
                .remove("camera_manual_capture")
                .remove("camera_file_name")
                .commit()
        }
        if (token != null && !bottleCaptureDraftIsStillOpen(token, openIds)) {
            File(directory, bottleCaptureTempFileName(token)).delete()
            clearPersistedCameraLaunch(token)
            if (launchedBottleCameraToken == token) launchedBottleCameraToken = null
            if (processingBottleCameraToken == token) processingBottleCameraToken = null
            if (activeBottleCaptureToken == token) activeBottleCaptureToken = null
        }
        val keepFileName = token?.takeIf { bottleCaptureDraftIsStillOpen(it, openIds) }?.let(::bottleCaptureTempFileName)
        directory.listFiles()
            ?.filter { it.name.startsWith("capture-") && it.name.endsWith(".jpg") && it.name != keepFileName }
            ?.forEach(File::delete)
    }

    fun invalidateBottleCaptureOperation() {
        listOfNotNull(launchedBottleCameraToken, processingBottleCameraToken).distinct().forEach { token ->
            File(File(context.cacheDir, "intake-labels"), bottleCaptureTempFileName(token)).delete()
            clearPersistedCameraLaunch(token)
        }
        bottleCaptureGeneration += 1
        activeBottleCaptureToken = null
        launchedBottleCameraToken = null
        processingBottleCameraToken = null
        bottleLabelCropBusy = false
    }

    fun isCurrentBottleCaptureOperation(token: BottleCaptureToken): Boolean = isCurrentBottleCapture(
        token,
        bottleCaptureGeneration,
        pendingBottleLabelDraftId,
    ) && activeBottleCaptureToken == token

    fun setPendingBottleLabelDraftId(draftId: String?): Boolean {
        if (pendingBottleLabelDraftId != draftId) {
            invalidateBottleCaptureOperation()
            // The in-memory preview belongs to the old draft. Clear it before the
            // keyed restore effect loads the selected draft's own private photo.
            pendingBottleLabelPhoto = retainPendingBottleValueForOwner(pendingBottleLabelDraftId, draftId, pendingBottleLabelPhoto)
            pendingBottleLabelOriginalPhoto = retainPendingBottleValueForOwner(pendingBottleLabelDraftId, draftId, pendingBottleLabelOriginalPhoto)
            pendingBottleLabelCropSuggestion = retainPendingBottleValueForOwner(pendingBottleLabelDraftId, draftId, pendingBottleLabelCropSuggestion)
            bottleLabelCropBusy = false
            bottleLabelCaptureMessage = null
        }
        pendingBottleLabelDraftId = draftId
        return pendingLabelPreferences.edit().apply {
            if (draftId == null) remove("draft_id") else putString("draft_id", draftId)
        }.commit()
    }

    LaunchedEffect(showBottleIntake, pendingBottleLabelDraftId) {
        if (!showBottleIntake) return@LaunchedEffect
        val draftId = pendingBottleLabelDraftId ?: return@LaunchedEffect
        if (!draftId.matches(Regex("[A-Za-z0-9-]{1,100}")) || pendingBottleLabelPhoto != null) return@LaunchedEffect
        val directory = File(context.cacheDir, "intake-labels")
        val preview = File(directory, "pending-$draftId.jpg")
        val original = File(directory, "original-$draftId.jpg")
        if (preview.isFile && preview.length() in 1..MAX_PRODUCT_PHOTO_BYTES.toLong()) {
            val selectedBytes = try {
                withContext(Dispatchers.IO) { preview.readBytes() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!bottleLabelRestoreBelongsToCurrentDraft(draftId, pendingBottleLabelDraftId)) return@LaunchedEffect
            val originalBytes = if (original.isFile && original.length() in 1..MAX_PRODUCT_PHOTO_BYTES.toLong()) {
                try {
                    withContext(Dispatchers.IO) { original.readBytes() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else selectedBytes
            if (!bottleLabelRestoreBelongsToCurrentDraft(draftId, pendingBottleLabelDraftId)) return@LaunchedEffect
            val crop = originalBytes?.let { bytes ->
                try {
                    withContext(Dispatchers.Default) { BottleLabelCropper.suggest(bytes) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (!bottleLabelRestoreBelongsToCurrentDraft(draftId, pendingBottleLabelDraftId)) return@LaunchedEffect
            pendingBottleLabelOriginalPhoto = originalBytes
            pendingBottleLabelPhoto = selectedBytes
            pendingBottleLabelCropSuggestion = crop
        }
    }
    var barcodeInput by remember { mutableStateOf("") }
    var barcodePermissionMessage by remember { mutableStateOf<String?>(null) }
    var localProductMatch by remember { mutableStateOf<LocalProductBarcodeMatch?>(null) }
    var productLookupResult by remember { mutableStateOf<OpenFoodFactsLookupResult?>(null) }
    var productLookupInProgress by remember { mutableStateOf(false) }
    var productLookupRequestToken by remember { mutableStateOf(0L) }
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

    fun setBarcodeInput(value: String) {
        productLookupRequestToken += 1
        barcodeInput = value
        localProductMatch = repository.localProductForBarcode(value)
        productLookupResult = null
    }

    fun lookupProductDetails(value: String) {
        if (!openFoodFactsEnabled || productLookupInProgress) return
        productLookupInProgress = true
        productLookupResult = null
        val requestToken = productLookupRequestToken + 1
        productLookupRequestToken = requestToken
        coroutineScope.launch {
            try {
                val result = openFoodFactsClient.lookup(value, installedAppVersion.name)
                if (lookupResultMatchesActiveRequest(value, barcodeInput, requestToken, productLookupRequestToken)) {
                    productLookupResult = result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (lookupResultMatchesActiveRequest(value, barcodeInput, requestToken, productLookupRequestToken)) {
                    productLookupResult = OpenFoodFactsLookupResult.Unavailable
                }
            } finally {
                productLookupInProgress = false
            }
        }
    }

    var triggerVerifiedPageExtraction: ((String, AutomaticBottleExtractor) -> Unit)? = null

    fun searchBottleSources(draft: BottleIntakeDraft) {
        if (!firecrawlSearchEnabled || !firecrawlKeySaved || firecrawlKeyNeedsReplacement) return
        val terms = draft.barcode?.let(::scannedBottleSearchQuery) ?: listOfNotNull(draft.brand, draft.productName, draft.ingredientName)
            .map(String::trim).filter(String::isNotEmpty).distinct().joinToString(" ").take(256)
        if (terms.isNullOrBlank()) return
        bottleWebSearchJobs.remove(draft.id)?.cancel()
        bottleExtractionRequestTokens[draft.id] = (bottleExtractionRequestTokens[draft.id] ?: 0) + 1
        bottleExtractionJobs.remove(draft.id)?.cancel()
        val requestToken = (bottleWebSearchRequestTokens[draft.id] ?: 0) + 1
        bottleWebSearchRequestTokens[draft.id] = requestToken
        val sessionId = draft.sessionId
        val queueJob = bottleEnrichmentQueue.begin(
            sessionId, draft.id, BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = System.currentTimeMillis(),
        )
        if (queueJob == null) {
            bottleWebSearchStates = bottleWebSearchStates + (draft.id to BottleWebSearchState(message = "The local enrichment queue is full or could not be saved. Your draft is unchanged."))
            return
        }
        refreshPendingBottleEnrichmentJobs()
        val previousState = bottleWebSearchStates[draft.id] ?: BottleWebSearchState()
        bottleWebSearchStates = bottleWebSearchStates + (draft.id to previousState.copy(searching = true, message = null))
        bottleWebSearchJobs[draft.id] = coroutineScope.launch {
            val result = try {
                firecrawlSearchProvider.search(terms, 5)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WebSearchResult.Unavailable
            }
            if (result is WebSearchResult.Found) bottleEnrichmentQueue.remove(queueJob.id)
            else bottleEnrichmentQueue.pause(queueJob.id, System.currentTimeMillis())
            refreshPendingBottleEnrichmentJobs()
            if (bottleWebSearchRequestTokens[draft.id] == requestToken && showBottleIntake && bottleIntakeSession?.id == sessionId && bottleIntakeSession?.drafts?.any { it.id == draft.id } == true) {
                bottleWebSearchStates = bottleWebSearchStates + (draft.id to when (result) {
                    is WebSearchResult.Found -> previousState.copy(searching = false, hits = result.hits, message = if (result.hits.isEmpty()) "No usable sources were found. You can continue manually." else null)
                    WebSearchResult.SetupRequired -> previousState.copy(searching = false, message = "Add a Firecrawl key and enable search in More before making requests.")
                    WebSearchResult.AuthenticationRequired -> previousState.copy(searching = false, message = "Firecrawl did not accept this key. Replace it in More or continue manually.")
                    WebSearchResult.InvalidQuery -> previousState.copy(searching = false, message = "This bottle does not have a valid UPC/EAN code to search.")
                    WebSearchResult.QuotaExceeded -> previousState.copy(searching = false, message = "The Firecrawl plan quota is exhausted. No retry was made; continue manually.")
                    WebSearchResult.RateLimited -> previousState.copy(searching = false, message = "Firecrawl is rate limiting requests. Wait before trying again.")
                    WebSearchResult.Unavailable -> previousState.copy(searching = false, message = "Firecrawl could not be reached. The local draft is unchanged.")
                    WebSearchResult.InvalidResponse -> previousState.copy(searching = false, message = "Firecrawl returned a response this version could not read.")
                })
            }
            if (bottleWebSearchRequestTokens[draft.id] == requestToken) bottleWebSearchJobs.remove(draft.id)
        }
    }

    fun fetchBottleSource(draft: BottleIntakeDraft, hit: WebSearchHit) {
        if (!firecrawlSearchEnabled || !firecrawlKeySaved || firecrawlKeyNeedsReplacement) return
        val sessionId = draft.sessionId
        bottleWebSearchJobs.remove(draft.id)?.cancel()
        bottleExtractionRequestTokens[draft.id] = (bottleExtractionRequestTokens[draft.id] ?: 0) + 1
        bottleExtractionJobs.remove(draft.id)?.cancel()
        val requestToken = (bottleWebSearchRequestTokens[draft.id] ?: 0) + 1
        bottleWebSearchRequestTokens[draft.id] = requestToken
        val queueJob = bottleEnrichmentQueue.begin(
            sessionId, draft.id, BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = hit.url, nowMillis = System.currentTimeMillis(),
        )
        if (queueJob == null) {
            bottleWebSearchStates = bottleWebSearchStates + (draft.id to (bottleWebSearchStates[draft.id] ?: BottleWebSearchState()).copy(message = "The local enrichment queue is full or could not be saved. Your draft is unchanged."))
            return
        }
        refreshPendingBottleEnrichmentJobs()
        bottleWebSearchStates = bottleWebSearchStates + (draft.id to (bottleWebSearchStates[draft.id] ?: BottleWebSearchState()).copy(fetchingUrl = hit.url, extracting = false, page = null, extractedSuggestions = null, message = null))
        bottleWebSearchJobs[draft.id] = coroutineScope.launch {
            val result = try {
                firecrawlSearchProvider.fetch(hit.url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WebPageResult.Unavailable
            }
            if (result is WebPageResult.Found) bottleEnrichmentQueue.remove(queueJob.id)
            else bottleEnrichmentQueue.pause(queueJob.id, System.currentTimeMillis())
            refreshPendingBottleEnrichmentJobs()
            if (bottleWebSearchRequestTokens[draft.id] == requestToken && showBottleIntake && bottleIntakeSession?.id == sessionId && bottleIntakeSession?.drafts?.any { it.id == draft.id } == true) {
                val previous = bottleWebSearchStates[draft.id] ?: BottleWebSearchState()
                val updated = when (result) {
                    is WebPageResult.Found -> {
                        val verified = draft.barcode?.let { sourceMentionsScannedBarcode(it, result.page.markdown) } == true
                        val structured = !verified && draft.barcode?.let { structuredBottleIdentityForPage(it, result.page) } != null
                        previous.copy(
                            fetchingUrl = null,
                            page = result.page,
                            barcodeVerifiedInPage = verified || structured,
                            barcodeVerifiedByStructuredProduct = structured,
                            message = if (previous.automatedScan && !verified && !structured) "This source page does not show the barcode in its text or structured product data. No model extraction was run; choose another source or enter details manually." else null,
                        )
                    }
                    WebPageResult.SetupRequired -> previous.copy(fetchingUrl = null, message = "Add a Firecrawl key in More before fetching a selected source.")
                    WebPageResult.AuthenticationRequired -> previous.copy(fetchingUrl = null, message = "Firecrawl did not accept this key. Replace it in More.")
                    WebPageResult.InvalidUrl -> previous.copy(fetchingUrl = null, message = "That source URL cannot be fetched safely.")
                    WebPageResult.QuotaExceeded -> previous.copy(fetchingUrl = null, message = "The Firecrawl plan quota is exhausted. No retry was made.")
                    WebPageResult.RateLimited -> previous.copy(fetchingUrl = null, message = "Firecrawl is rate limiting requests. Wait before trying again.")
                    WebPageResult.Unavailable -> previous.copy(fetchingUrl = null, message = "Firecrawl could not fetch that page. The local draft is unchanged.")
                    WebPageResult.InvalidResponse -> previous.copy(fetchingUrl = null, message = "Firecrawl returned page data this version could not read.")
                }
                bottleWebSearchStates = bottleWebSearchStates + (draft.id to updated)
                if (result is WebPageResult.Found && previous.automatedScan && updated.barcodeVerifiedInPage) {
                    when (automaticBottleExtractor(
                        onDeviceEnabled = localAiEnabled && localModelFile != null,
                        ollamaCloudEnabled = ollamaCloudEnabled && ollamaCloudKeySaved && !ollamaCloudKeyNeedsReplacement && isValidOllamaModelIdentifier(ollamaCloudModel),
                    )) {
                        AutomaticBottleExtractor.ON_DEVICE -> triggerVerifiedPageExtraction?.invoke(draft.id, AutomaticBottleExtractor.ON_DEVICE)
                        AutomaticBottleExtractor.OLLAMA_CLOUD -> triggerVerifiedPageExtraction?.invoke(draft.id, AutomaticBottleExtractor.OLLAMA_CLOUD)
                        null -> bottleWebSearchStates = bottleWebSearchStates + (draft.id to updated.copy(message = "The barcode matched this source. Enable a model provider in More to get suggestions, or review the source manually."))
                    }
                }
            }
            if (bottleWebSearchRequestTokens[draft.id] == requestToken) bottleWebSearchJobs.remove(draft.id)
        }
    }

    fun cancelBottleSourceSearch(draftId: String) {
        bottleWebSearchRequestTokens[draftId] = (bottleWebSearchRequestTokens[draftId] ?: 0) + 1
        bottleWebSearchJobs.remove(draftId)?.cancel()
        bottleExtractionRequestTokens[draftId] = (bottleExtractionRequestTokens[draftId] ?: 0) + 1
        bottleExtractionJobs.remove(draftId)?.cancel()
        bottleEnrichmentQueue.finish(draftId, BottleEnrichmentStatus.CANCELLED, "CANCELLED_BY_USER", System.currentTimeMillis())
        BottleEnrichmentWorkScheduler.cancelDraft(context, draftId)
        refreshPendingBottleEnrichmentJobs()
        bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(
            searching = false,
            fetchingUrl = null,
            extracting = false,
            automaticStage = null,
            message = "Canceled locally. If a request had already reached its provider, its quota may still be charged; the bottle draft is unchanged.",
        ))
    }

    var resumeAutomaticScan: ((BottleIntakeDraft) -> Unit)? = null

    fun resumeBottleEnrichment(draft: BottleIntakeDraft, job: BottleEnrichmentJob) {
        if (job.status !in setOf(BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.NEEDS_ATTENTION, BottleEnrichmentStatus.CANCELLED) ||
            job.draftId != draft.id || job.sessionId != draft.sessionId
        ) return
        when (job.operation) {
            BottleEnrichmentOperation.FIRECRAWL_SEARCH -> {
                if (bottleWebSearchStates[draft.id]?.automatedScan == true) resumeAutomaticScan?.invoke(draft)
                else searchBottleSources(draft)
            }
            BottleEnrichmentOperation.FIRECRAWL_FETCH -> {
                val url = job.sourceUrl ?: return
                fetchBottleSource(draft, WebSearchHit("Previously selected source", url, null))
            }
            BottleEnrichmentOperation.OLLAMA_EXTRACTION -> {
                val url = job.sourceUrl ?: return
                bottleWebSearchStates = bottleWebSearchStates + (draft.id to (bottleWebSearchStates[draft.id] ?: BottleWebSearchState()).copy(
                    message = "The interrupted page text was not saved. The source will be fetched again, using Firecrawl quota; then choose extraction again.",
                ))
                fetchBottleSource(draft, WebSearchHit("Previously selected source", url, null))
            }
            BottleEnrichmentOperation.LOCAL_EXTRACTION -> {
                val url = job.sourceUrl ?: return
                bottleWebSearchStates = bottleWebSearchStates + (draft.id to (bottleWebSearchStates[draft.id] ?: BottleWebSearchState()).copy(
                    message = "The interrupted local extraction was not saved. Re-fetch the selected source, then choose local extraction again.",
                ))
                fetchBottleSource(draft, WebSearchHit("Previously selected source", url, null))
            }
        }
    }

    fun extractBottleDetails(draftId: String) {
        if (!ollamaCloudEnabled || !ollamaCloudKeySaved || ollamaCloudKeyNeedsReplacement || !isValidOllamaModelIdentifier(ollamaCloudModel)) return
        val page = bottleWebSearchStates[draftId]?.page ?: return
        bottleExtractionJobs.remove(draftId)?.cancel()
        val requestToken = (bottleExtractionRequestTokens[draftId] ?: 0) + 1
        bottleExtractionRequestTokens[draftId] = requestToken
        val currentDraft = bottleIntakeSession?.drafts?.firstOrNull { it.id == draftId } ?: return
        val pageUrl = bottleWebSearchStates[draftId]?.page?.sourceUrl ?: return
        val queueJob = bottleEnrichmentQueue.begin(
            currentDraft.sessionId, draftId, BottleEnrichmentOperation.OLLAMA_EXTRACTION,
            sourceUrl = pageUrl, model = ollamaCloudModel, nowMillis = System.currentTimeMillis(),
        )
        if (queueJob == null) {
            bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(message = "The local enrichment queue is full or could not be saved. Your draft is unchanged."))
            return
        }
        refreshPendingBottleEnrichmentJobs()
        bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(extracting = true, extractingWith = "Ollama Cloud", extractedSuggestions = null, message = null))
        bottleExtractionJobs[draftId] = coroutineScope.launch {
            val extracted = try {
                val requiredBarcode = bottleWebSearchStates[draftId]?.takeIf { it.automatedScan }?.let { currentDraft.barcode }
                ollamaCloudExtractor.extract(page, ollamaCloudModel, requiredBarcode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BottleDetailExtractionResult.Unavailable
            }
            val result = when (extracted) {
                is BottleDetailExtractionResult.Found -> if (pendingBottleEvidenceStore.save(draftId, extracted.suggestions)) extracted else BottleDetailExtractionResult.InvalidResponse
                else -> extracted
            }
            if (result is BottleDetailExtractionResult.Found) bottleEnrichmentQueue.remove(queueJob.id)
            else bottleEnrichmentQueue.pause(queueJob.id, System.currentTimeMillis())
            refreshPendingBottleEnrichmentJobs()
            if (bottleExtractionRequestTokens[draftId] == requestToken && showBottleIntake && bottleIntakeSession?.drafts?.any { it.id == draftId } == true) {
                val previous = bottleWebSearchStates[draftId] ?: BottleWebSearchState()
                bottleWebSearchStates = bottleWebSearchStates + (draftId to when (result) {
                    is BottleDetailExtractionResult.Found -> previous.copy(extracting = false, extractedSuggestions = result.suggestions)
                    BottleDetailExtractionResult.SetupRequired -> previous.copy(extracting = false, message = "Add an Ollama Cloud key in More before extracting.")
                    BottleDetailExtractionResult.InvalidModel -> previous.copy(extracting = false, message = "Enter a valid Ollama Cloud model identifier in More.")
                    BottleDetailExtractionResult.AuthenticationRequired -> previous.copy(extracting = false, message = "Ollama Cloud did not accept this key. Replace it in More.")
                    BottleDetailExtractionResult.QuotaOrRateLimited -> previous.copy(extracting = false, message = "Ollama Cloud reported a quota or rate limit. No retry was made.")
                    BottleDetailExtractionResult.Unavailable -> previous.copy(extracting = false, message = "Ollama Cloud could not be reached. Your draft is unchanged.")
                    is BottleDetailExtractionResult.Rejected -> previous.copy(extracting = false, message = "The source did not support usable suggestions. Check it manually or continue without them.")
                    BottleDetailExtractionResult.InvalidResponse -> previous.copy(extracting = false, message = "Ollama Cloud returned suggestions without usable source evidence.")
                    is BottleDetailExtractionResult.LocalFailure -> previous.copy(extracting = false, message = "The local model failed (${result.code.wireValue}). Your draft is unchanged.")
                })
            }
            if (bottleExtractionRequestTokens[draftId] == requestToken) bottleExtractionJobs.remove(draftId)
        }
    }

    fun extractBottleDetailsOnDevice(draftId: String) {
        if (!localAiEnabled) return
        val page = bottleWebSearchStates[draftId]?.page ?: return
        val modelFile = localModelFileStore.installedModel() ?: run {
            localModelFile = null
            localAiEnabled = false
            localModelMessage = "The installed model file is missing. Select it again in More."
            return
        }
        bottleExtractionJobs.remove(draftId)?.cancel()
        val requestToken = (bottleExtractionRequestTokens[draftId] ?: 0) + 1
        bottleExtractionRequestTokens[draftId] = requestToken
        val currentDraft = bottleIntakeSession?.drafts?.firstOrNull { it.id == draftId } ?: return
        val queueJob = bottleEnrichmentQueue.begin(
            currentDraft.sessionId,
            draftId,
            BottleEnrichmentOperation.LOCAL_EXTRACTION,
            sourceUrl = page.sourceUrl,
            model = LOCAL_MODEL_LABEL,
            nowMillis = System.currentTimeMillis(),
        )
        if (queueJob == null) {
            bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(message = "The local enrichment queue is full or could not be saved. Your draft is unchanged."))
            return
        }
        val captureVersion = installedAppVersion
        val detailedCapture = queueJob.attemptToken?.let { attemptToken -> detailedLookupCaptureStore.claim(
            draftId = draftId,
            attemptToken = attemptToken,
            barcode = currentDraft.barcode.orEmpty(),
            appVersion = captureVersion.name,
            appVersionCode = captureVersion.code,
            model = LOCAL_MODEL_LABEL,
            allowNextLookup = true,
        ) }
        if (detailedCapture != null) {
            val state = bottleWebSearchStates[draftId] ?: BottleWebSearchState()
            val selectedIndex = state.hits.indexOfFirst { normalizePublicHttpsUrl(it.url) == normalizePublicHttpsUrl(page.sourceUrl) }
                .takeIf { it >= 0 }
            detailedCapture.recordSearchSelection(
                state.hits,
                selectedIndex,
                if (selectedIndex != null) "SELECTED_EXACT_MATCH" else "SELECTED_FALLBACK",
            )
            val identityResolution = currentDraft.barcode?.let { resolveStructuredBottleIdentityForPage(it, page) }
            detailedCapture.recordFetchedPage(
                0,
                page.sourceUrl,
                page.title,
                when {
                    identityResolution?.identity != null -> "STRUCTURED_PRODUCT_MATCH"
                    state.barcodeVerifiedInPage -> "VERIFIED"
                    else -> "MISSING"
                },
                page.structuredBottleIdentities,
                identityResolution?.status,
            )
        }
        refreshPendingBottleEnrichmentJobs()
        bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(
            extracting = true,
            extractingWith = "On-device Qwen3",
            extractedSuggestions = null,
            message = "Running the selected page through the local model. Source text stays on this phone.",
        ))
        bottleExtractionJobs[draftId] = coroutineScope.launch {
            val extracted = try {
                val runner = LazyLlamaCppLocalInferenceRunner(context)
                val requiredBarcode = bottleWebSearchStates[draftId]?.takeIf { it.automatedScan }?.let { currentDraft.barcode }
                BottleEnrichmentSerialGate.processWide.withSlot {
                    LocalBottleDetailExtractor({ modelFile.absolutePath }, runner)
                        .extractWithDetailedCapture(page, LOCAL_MODEL_LABEL, requiredBarcode, detailedCapture)
                }
            } catch (cancelled: CancellationException) {
                detailedCapture?.finish("CANCELLED")
                throw cancelled
            } catch (_: Exception) {
                BottleDetailExtractionResult.LocalFailure(LocalInferenceFailureCode.INFERENCE_FAILED)
            }
            val result = when (extracted) {
                is BottleDetailExtractionResult.Found -> if (pendingBottleEvidenceStore.save(draftId, extracted.suggestions)) extracted else BottleDetailExtractionResult.InvalidResponse
                else -> extracted
            }
            detailedCapture?.finish(if (result is BottleDetailExtractionResult.Found) "COMPLETE" else "FAILED")
            if (result is BottleDetailExtractionResult.Found) bottleEnrichmentQueue.remove(queueJob.id)
            else bottleEnrichmentQueue.pause(queueJob.id, System.currentTimeMillis())
            refreshPendingBottleEnrichmentJobs()
            if (bottleExtractionRequestTokens[draftId] == requestToken && showBottleIntake && bottleIntakeSession?.drafts?.any { it.id == draftId } == true) {
                val previous = bottleWebSearchStates[draftId] ?: BottleWebSearchState()
                bottleWebSearchStates = bottleWebSearchStates + (draftId to when (result) {
                    is BottleDetailExtractionResult.Found -> previous.copy(extracting = false, extractedSuggestions = result.suggestions)
                    BottleDetailExtractionResult.SetupRequired -> previous.copy(extracting = false, message = "Install the reviewed Qwen3 model file from More before local extraction.")
                    BottleDetailExtractionResult.InvalidModel -> previous.copy(extracting = false, message = "The selected on-device model is not supported.")
                    BottleDetailExtractionResult.AuthenticationRequired -> previous.copy(extracting = false, message = "Unexpected authentication response from the on-device model.")
                    BottleDetailExtractionResult.QuotaOrRateLimited -> previous.copy(extracting = false, message = "The on-device model is busy. Try again later.")
                    BottleDetailExtractionResult.Unavailable -> previous.copy(extracting = false, message = "On-device extraction did not finish. Your draft is unchanged; check free memory and try again.")
                    is BottleDetailExtractionResult.Rejected -> previous.copy(extracting = false, message = "The source did not support usable suggestions. Check it manually or continue without them.")
                    BottleDetailExtractionResult.InvalidResponse -> previous.copy(extracting = false, message = "The model returned no suggestions with usable source evidence.")
                    is BottleDetailExtractionResult.LocalFailure -> previous.copy(extracting = false, message = "On-device extraction failed (${result.code.wireValue}). Your draft is unchanged; check the lookup report for stage details.")
                })
            }
            if (bottleExtractionRequestTokens[draftId] == requestToken) bottleExtractionJobs.remove(draftId)
        }
    }

    triggerVerifiedPageExtraction = { draftId, provider ->
        when (provider) {
            AutomaticBottleExtractor.ON_DEVICE -> extractBottleDetailsOnDevice(draftId)
            AutomaticBottleExtractor.OLLAMA_CLOUD -> extractBottleDetails(draftId)
        }
    }

    fun startScannedBottleEnrichment(draft: BottleIntakeDraft, localMatch: Boolean) {
        val existingJob = bottleEnrichmentQueue.status(draft.id)
        if (existingJob?.status in setOf(BottleEnrichmentStatus.READY_FOR_REVIEW, BottleEnrichmentStatus.REVIEWED, BottleEnrichmentStatus.RUNNING)) return
        val runtime = BottleScanEnrichmentRuntime(context)
        val plan = runtime.plan()
        val queued = bottleEnrichmentQueue.enqueue(
            sessionId = draft.sessionId,
            draftId = draft.id,
            nowMillis = System.currentTimeMillis(),
            enqueuedElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            enqueuedBootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull(),
        )
        if (queued == null) {
            bottleWebSearchStates = bottleWebSearchStates + (draft.id to (bottleWebSearchStates[draft.id] ?: BottleWebSearchState(automatedScan = true)).copy(
                automatedScan = true,
                message = "The local enrichment queue could not be saved. The bottle draft is safe; retry lookup when ready.",
            ))
            refreshPendingBottleEnrichmentJobs()
            return
        }
        LocalDiagnostics.clearBottleLookupForDraft(draft.id)
        val previous = bottleWebSearchStates[draft.id] ?: BottleWebSearchState(automatedScan = true)
        val scheduled = BottleEnrichmentWorkScheduler.enqueue(
            context = context,
            sessionId = draft.sessionId,
            draftId = draft.id,
            attemptToken = queued.attemptToken,
            requiresNetwork = plan.openFoodFactsEnabled || plan.firecrawlEnabled,
            replaceExisting = existingJob != null,
        )
        bottleWebSearchStates = bottleWebSearchStates + (draft.id to previous.copy(
            automatedScan = true,
            localMatch = localMatch,
            searching = false,
            fetchingUrl = null,
            extracting = false,
            automaticStage = null,
            userEditedFields = preserveEditedBottleFields(
                previous.userEditedFields,
                pendingBottleEvidenceStore.editedFields(draft.id),
            ),
            message = if (scheduled) {
                "Queued for background processing. Results will wait here for your review; interrupted requests are not restarted automatically."
            } else {
                "The bottle draft is safe, but background work could not be scheduled. Try the lookup again while intake is open."
            },
        ))
        if (!scheduled) {
            bottleEnrichmentQueue.finish(draft.id, BottleEnrichmentStatus.NEEDS_ATTENTION, "WORK_SCHEDULE_FAILED", System.currentTimeMillis())
        }
        refreshPendingBottleEnrichmentJobs()
    }

    fun completeBottleLabelStep(draftId: String, photo: ByteArray?): Boolean {
        if (pendingBottleLabelDraftId != draftId) return false
        val draft = bottleIntakeSession?.drafts?.firstOrNull { it.id == draftId } ?: return false
        var photoWasSaved = false
        if (photo != null) {
            if (!repository.updateBottleIntakeDraft(draft.copy(photoData = photo, photoSha256 = photoSha256(photo)))) {
                LocalDiagnostics.recordEvent("LABEL_PHOTO_PROCESSING_FAILED")
                bottleLabelCaptureMessage = "The label photo could not be saved. Retake it or skip; the bottle draft is still safe."
                return false
            }
            photoWasSaved = true
            LocalDiagnostics.recordEvent("LABEL_PHOTO_APPLIED")
        } else {
            LocalDiagnostics.recordEvent("LABEL_PHOTO_SKIPPED")
        }
        val queueJob = bottleEnrichmentQueue.status(draft.id)
        if (queueJob?.labelCaptureRequired == true && !bottleEnrichmentQueue.completeLabelCapture(draft.id, System.currentTimeMillis())) {
            LocalDiagnostics.recordEvent("LABEL_PHOTO_PROCESSING_FAILED")
            if (photoWasSaved) repository.updateBottleIntakeDraft(draft.copy(photoData = null, photoSha256 = null))
            bottleLabelCaptureMessage = "The local lookup queue could not be saved. The photo remains available; retry or skip the lookup step."
            bottleIntakeSession = repository.openBottleIntakeSession()
            return false
        }

        setPendingBottleLabelDraftId(null)
        pendingBottleLabelPhoto = null
        pendingBottleLabelOriginalPhoto = null
        pendingBottleLabelCropSuggestion = null
        bottleLabelCaptureMessage = null
        val directory = File(context.cacheDir, "intake-labels")
        if (draftId.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
            directory.listFiles()?.filter { it.name.startsWith("capture-") && it.name.endsWith("-$draftId.jpg") }?.forEach(File::delete)
            File(directory, "pending-$draftId.jpg").delete()
            File(directory, "original-$draftId.jpg").delete()
        }
        bottleIntakeSession = repository.openBottleIntakeSession()
        refreshPendingBottleEnrichmentJobs()
        val lookupState = bottleEnrichmentQueue.status(draftId)?.status
        val lookupMessage = when (lookupState) {
            BottleEnrichmentStatus.RUNNING -> "Lookup is continuing while you scan."
            BottleEnrichmentStatus.QUEUED -> "Lookup is queued while another bottle is processed."
            BottleEnrichmentStatus.READY_FOR_REVIEW -> "Suggestions are ready for review."
            BottleEnrichmentStatus.NEEDS_ATTENTION -> "Lookup needs attention; review the bottle or retry when ready."
            BottleEnrichmentStatus.PAUSED -> "Lookup is paused; resume it when ready."
            BottleEnrichmentStatus.CANCELLED -> "Lookup was canceled; resume it when ready."
            else -> "Lookup status is saved for this bottle."
        }
        bottleIntakeCameraMessage = if (photoWasSaved) {
            "Label saved on this phone. $lookupMessage"
        } else {
            "Photo skipped. $lookupMessage"
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bottleIntakeRoute = BottleIntakeRoute.SCANNING
            showBottleScanner = true
        } else {
            bottleIntakeRoute = BottleIntakeRoute.SCANNING
        }
        return true
    }

    fun applyBottleLabelCrop(crop: NormalizedLabelCrop, rotationDegrees: Int = 0) {
        val original = pendingBottleLabelOriginalPhoto ?: return
        val targetDraftId = pendingBottleLabelDraftId
        val token = beginBottleCaptureOperation(targetDraftId)
        bottleLabelCropBusy = true
        coroutineScope.launch {
            val cropped = try {
                withContext(Dispatchers.Default) { BottleLabelCropper.crop(original, crop, rotationDegrees) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isCurrentBottleCaptureOperation(token)) return@launch
            if (cropped == null) {
                bottleLabelCaptureMessage = "This crop could not be prepared. The original label is still available; adjust it or keep the full photo."
            } else {
                val previewSaved = if (targetDraftId != null && targetDraftId.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
                    val staged = File(File(context.cacheDir, "intake-labels"), "pending-$targetDraftId-${token.generation}.tmp")
                    val written = runCatching {
                        withContext(Dispatchers.IO) {
                            staged.writeBytes(cropped)
                        }
                    }.isSuccess
                    val promoted = written && isCurrentBottleCaptureOperation(token) &&
                        staged.renameTo(File(File(context.cacheDir, "intake-labels"), "pending-$targetDraftId.jpg"))
                    if (!promoted) staged.delete()
                    promoted
                } else true
                if (!isCurrentBottleCaptureOperation(token)) return@launch
                if (previewSaved) {
                    pendingBottleLabelPhoto = cropped
                    bottleLabelCaptureMessage = "Crop ready. The original remains available until you use this label."
                } else {
                    bottleLabelCaptureMessage = "The crop preview could not be saved. The original label is still available; retake or keep the full photo."
                }
            }
            if (isCurrentBottleCaptureOperation(token)) bottleLabelCropBusy = false
        }
    }

    fun restoreOriginalBottleLabel() {
        val original = pendingBottleLabelOriginalPhoto ?: return
        val targetDraftId = pendingBottleLabelDraftId
        val token = beginBottleCaptureOperation(targetDraftId)
        pendingBottleLabelPhoto = original
        if (targetDraftId == null || !targetDraftId.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
            bottleLabelCaptureMessage = "Showing the original photo."
            return
        }
        coroutineScope.launch {
            val staged = File(File(context.cacheDir, "intake-labels"), "pending-$targetDraftId-${token.generation}.tmp")
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    staged.writeBytes(original)
                }
            }.isSuccess
            val stored = written && isCurrentBottleCaptureOperation(token) &&
                staged.renameTo(File(File(context.cacheDir, "intake-labels"), "pending-$targetDraftId.jpg"))
            if (!stored) staged.delete()
            if (!isCurrentBottleCaptureOperation(token)) return@launch
            bottleLabelCaptureMessage = if (stored) "Showing the original photo. You can adjust the crop or use the full label."
            else "The original is showing now, but its resume preview could not be updated. Keep this screen open until you use or retake it."
        }
    }

    finishScannedBottleLabel = ::completeBottleLabelStep

    resumeAutomaticScan = { draft ->
        startScannedBottleEnrichment(draft, localMatch = repository.localProductForBarcode(draft.barcode.orEmpty()) != null)
    }

    fun saveOllamaCloudSettings(apiKey: String, model: String): Boolean {
        val cleanedModel = model.trim()
        if (!isValidOllamaModelIdentifier(cleanedModel)) return false
        val preferences = context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
        if (!preferences.edit().putString("ollama_cloud_model", cleanedModel).commit()) return false
        if (!ollamaCloudCredentialStore.saveApiKey(apiKey)) return false
        ollamaCloudModel = cleanedModel
        ollamaCloudKeySaved = true
        ollamaCloudKeyNeedsReplacement = false
        ollamaCredentialMessage = null
        return true
    }

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
            val contents = diagnosticsExportContents ?: error("No diagnostics export was prepared.")
            context.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { it.write(contents.encodeToByteArray()) }
                ?: error("Could not open the selected file for writing.")
            diagnosticsExportContents = null
            lookupReportCopyMessage = "Diagnostics exported. The file includes the available local detailed captures."
        }.onFailure {
            diagnosticsExportContents = null
            lookupReportCopyMessage = "Diagnostics export failed. The local copy remains on this phone."
        } else {
            diagnosticsExportContents = null
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

    val localModelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            localModelMessage = "Model selection canceled. No local file changed."
        } else {
            localModelImportCancelled.set(false)
            localModelImportProgress.set(0L)
            localModelImportProgressBytes = 0L
            localModelImporting = true
            localModelMessage = "Reading and verifying the selected model. The previous model stays installed until verification finishes."
            coroutineScope.launch {
                try {
                    val result = try {
                        withContext(Dispatchers.IO) {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: return@withContext LocalModelInstallResult.StorageFailure
                            val importJob = currentCoroutineContext()[Job]
                            localModelFileStore.import(
                                input,
                                isCancelled = { localModelImportCancelled.get() || importJob?.isActive == false },
                                onProgress = { bytes -> localModelImportProgress.set(bytes) },
                            )
                        }
                    } catch (_: CancellationException) {
                        LocalModelInstallResult.Cancelled
                    } catch (_: Exception) {
                        LocalModelInstallResult.StorageFailure
                    }
                    when (result) {
                        is LocalModelInstallResult.Installed -> {
                            localModelFile = result.file
                            localModelMessage = "Verified Qwen3 model installed on this phone. It is not enabled until you turn it on below."
                        }
                        LocalModelInstallResult.InvalidGguf -> localModelMessage = "That file is not a GGUF model. Nothing was installed."
                        LocalModelInstallResult.WrongModel -> localModelMessage = "The file is not the reviewed Qwen3 0.6B Q8 model (SHA-256 mismatch). Nothing was installed."
                        LocalModelInstallResult.TooLarge -> localModelMessage = "The selected file exceeds the 900 MiB safety limit. Nothing was installed."
                        LocalModelInstallResult.Cancelled -> localModelMessage = "Model import canceled. The partial file was removed and any previous model was kept."
                        LocalModelInstallResult.StorageFailure -> localModelMessage = "The model could not be installed atomically. Check free storage and try again; any previous model was kept."
                    }
                } finally {
                    localModelImporting = false
                    localModelImportProgressBytes = localModelImportProgress.get()
                }
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

    fun handleBottleLabelCameraResult(
        token: BottleCaptureToken,
        captured: Boolean,
        liveDetectedCrop: NormalizedLabelCrop? = null,
    ) {
        if (processingBottleCameraToken == token) return
        if (!isCurrentBottleCaptureOperation(token)) {
            File(File(context.cacheDir, "intake-labels"), bottleCaptureTempFileName(token)).delete()
            clearPersistedCameraLaunch(token)
            if (launchedBottleCameraToken == token) launchedBottleCameraToken = null
            return
        }
        val targetDraftId = token.draftId
        val directory = File(context.cacheDir, "intake-labels")
        val file = File(directory, bottleCaptureTempFileName(token))
        launchedBottleCameraToken = null
        if (!captured) {
            file.delete()
            clearPersistedCameraLaunch(token)
            if (activeBottleCaptureToken == token) activeBottleCaptureToken = null
            bottleLabelCaptureMessage = "Photo capture canceled. Retry the label photo or skip it; this bottle stays in the queue."
            return
        }
        pendingLabelPreferences.edit().putBoolean("camera_result_ready", true).commit()
        processingBottleCameraToken = token
        LocalDiagnostics.recordEvent("LABEL_PHOTO_PROCESSING_STARTED")
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            file.delete()
            clearPersistedCameraLaunch(token)
            processingBottleCameraToken = processingBottleCaptureAfterCompletion(processingBottleCameraToken, token)
            LocalDiagnostics.recordEvent("LABEL_PHOTO_PROCESSING_FAILED")
            bottleLabelCaptureMessage = "The captured photo could not be opened. Retry or skip; your bottle draft is safe."
            return
        }
        coroutineScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { readAndCompressProductPhoto(context, uri) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
                val originalPhoto = result.getOrNull()
                val suggestion = liveDetectedCrop ?: originalPhoto?.let { bytes ->
                    withContext(Dispatchers.Default) { runCatching { BottleLabelCropper.suggest(bytes) }.getOrNull() }
                }
                if (!isCurrentBottleCaptureOperation(token)) {
                    file.delete()
                    clearPersistedCameraLaunch(token)
                    processingBottleCameraToken = processingBottleCaptureAfterCompletion(processingBottleCameraToken, token)
                    return@launch
                }
                val stagedPreview = targetDraftId?.let { File(directory, "pending-$it-${token.generation}.tmp") }
                val stagedOriginal = targetDraftId?.let { File(directory, "original-$it-${token.generation}.tmp") }
                val staged = if (targetDraftId != null && originalPhoto != null && stagedPreview != null && stagedOriginal != null) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            stagedOriginal.writeBytes(originalPhoto)
                            stagedPreview.writeBytes(originalPhoto)
                        }
                    }.isSuccess
                } else true
                if (!isCurrentBottleCaptureOperation(token)) {
                    stagedPreview?.delete()
                    stagedOriginal?.delete()
                    file.delete()
                    clearPersistedCameraLaunch(token)
                    processingBottleCameraToken = processingBottleCaptureAfterCompletion(processingBottleCameraToken, token)
                    return@launch
                }
                val stored = if (staged && targetDraftId != null && stagedPreview != null && stagedOriginal != null) {
                    stagedOriginal.renameTo(File(directory, "original-$targetDraftId.jpg")) &&
                        stagedPreview.renameTo(File(directory, "pending-$targetDraftId.jpg"))
                } else staged
                if (!stored) {
                    stagedPreview?.delete()
                    stagedOriginal?.delete()
                }
                if (!isCurrentBottleCaptureOperation(token)) {
                    file.delete()
                    clearPersistedCameraLaunch(token)
                    processingBottleCameraToken = processingBottleCaptureAfterCompletion(processingBottleCameraToken, token)
                    return@launch
                }
                val updatedPhoto = pendingBottlePhotoAfterCapture(
                    previous = PendingBottlePhoto(pendingBottleLabelPhoto, pendingBottleLabelOriginalPhoto, pendingBottleLabelCropSuggestion),
                    decodedPhoto = originalPhoto,
                    suggestedCrop = suggestion,
                )
                pendingBottleLabelPhoto = updatedPhoto.selected
                pendingBottleLabelOriginalPhoto = updatedPhoto.original
                pendingBottleLabelCropSuggestion = updatedPhoto.suggestedCrop
                if (result.isSuccess && stored) LocalDiagnostics.recordEvent("LABEL_PHOTO_READY_FOR_REVIEW")
                else LocalDiagnostics.recordEvent("LABEL_PHOTO_PROCESSING_FAILED")
                bottleLabelCaptureMessage = when {
                    targetDraftId != null && originalPhoto != null && !stored -> "The label is captured, but its preview could not be saved. Retake it or skip; the bottle draft is safe."
                    result.isSuccess -> null
                    pendingBottleLabelPhoto != null -> "The retake could not be prepared. Your previous label is still here; retry or keep it."
                    else -> "The camera photo could not be prepared. The draft was not changed; try again or skip the photo."
                }
                file.delete()
                clearPersistedCameraLaunch(token)
                processingBottleCameraToken = processingBottleCaptureAfterCompletion(processingBottleCameraToken, token)
        }
    }

    LaunchedEffect(launchedBottleCameraToken, processingBottleCameraToken) {
        val token = launchedBottleCameraToken ?: return@LaunchedEffect
        if (processingBottleCameraToken == null && pendingLabelPreferences.getBoolean("camera_result_ready", false)) {
            handleBottleLabelCameraResult(token, captured = true)
        }
    }

    fun clearPendingBottleCapture() {
        invalidateBottleCaptureOperation()
        val directory = File(context.cacheDir, "intake-labels")
        File(directory, "intake-label-capture.jpg").delete()
        pendingBottleLabelDraftId?.let { draftId ->
            if (draftId.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
                directory.listFiles()?.filter { it.name.startsWith("capture-") && it.name.endsWith("-$draftId.jpg") }?.forEach(File::delete)
                File(directory, "pending-$draftId.jpg").delete()
                File(directory, "original-$draftId.jpg").delete()
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
        } else if (granted && showBottleIntake) {
            bottleIntakeCameraMessage = null
            bottleIntakeRoute = BottleIntakeRoute.SCANNING
            showBottleScanner = true
        } else if (!granted && showAddIngredient) {
            barcodePermissionMessage = "Camera access wasn't granted. Enter the barcode manually instead."
        } else if (!granted && showBottleIntake) {
            bottleIntakeCameraMessage = "Camera access wasn't granted. Your draft is safe; add bottles manually or try again in Android settings."
        }
    }

    fun beginBarcodeScan() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showBarcodeScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun beginBottleBarcodeScan() {
        bottleIntakeCameraMessage = null
        bottleIntakeRoute = BottleIntakeRoute.SCANNING
        activeScannedDraftId = null
        context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().remove("active_draft_id").apply()
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showBottleScanner = true
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
            if (!showBottleIntake) {
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
                            modifier = Modifier.testTag("navigation-${page.name.lowercase()}"),
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        if (showBottleIntake && bottleIntakeSession != null) {
            val cameraTokenForScreen = launchedBottleCameraToken
            CocktailsTheme(darkTheme = true) {
                BottleIntakeScreen(
                modifier = Modifier.fillMaxSize(),
                session = bottleIntakeSession!!,
                route = bottleIntakeRoute,
                onRouteChange = { bottleIntakeRoute = it },
                homes = locations,
                capturePhoto = pendingBottleLabelPhoto,
                originalCapturePhoto = pendingBottleLabelOriginalPhoto,
                suggestedLabelCrop = pendingBottleLabelCropSuggestion,
                labelCropBusy = bottleLabelCropBusy,
                captureMessage = bottleLabelCaptureMessage,
                permissionMessage = bottleIntakeCameraMessage,
                pendingLabelDraftId = pendingBottleLabelDraftId,
                labelCameraToken = cameraTokenForScreen,
                labelCameraOutputFile = cameraTokenForScreen?.let { token ->
                    File(File(context.cacheDir, "intake-labels"), bottleCaptureTempFileName(token))
                },
                labelCaptureProcessing = processingBottleCameraToken != null,
                onLabelPhotoSaved = { token, crop -> handleBottleLabelCameraResult(token, captured = true, liveDetectedCrop = crop) },
                onLabelCameraCancel = { token -> handleBottleLabelCameraResult(token, captured = false) },
                webSearchEnabled = firecrawlSearchEnabled && firecrawlKeySaved && !firecrawlKeyNeedsReplacement,
                webSearchStates = bottleWebSearchStates,
                onOpenLookupReport = { draftId ->
                    lookupReportCopyMessage = null
                    showDiagnosticsForDraftId = draftId
                    showDiagnostics = true
                },
                pendingEnrichmentJobs = pendingBottleEnrichmentJobs,
                ollamaCloudEnabled = ollamaCloudEnabled && ollamaCloudKeySaved && !ollamaCloudKeyNeedsReplacement && isValidOllamaModelIdentifier(ollamaCloudModel),
                localAiEnabled = localAiEnabled && localModelFile != null,
                onSearchDraft = ::searchBottleSources,
                onFetchDraftSource = ::fetchBottleSource,
                onExtractDraftDetails = ::extractBottleDetails,
                onExtractDraftDetailsOnDevice = ::extractBottleDetailsOnDevice,
                onResumeEnrichment = ::resumeBottleEnrichment,
                onCancelDraftSearch = ::cancelBottleSourceSearch,
                onCaptureLabel = {
                    bottleLabelCaptureMessage = null
                    val directory = File(context.cacheDir, "intake-labels")
                    directory.mkdirs()
                    val targetId = pendingBottleLabelDraftId?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,100}")) }
                    val token = beginBottleCaptureOperation(targetId)
                    launchedBottleCameraToken = token
                    if (!persistCameraLaunch(token)) {
                        launchedBottleCameraToken = null
                        invalidateBottleCaptureOperation()
                        bottleLabelCaptureMessage = "The camera session could not be saved. The bottle is still queued; try again or skip the photo."
                    }
                },
                onCaptureBottleLabel = { draftId ->
                    val exists = bottleIntakeSession?.drafts?.any { it.id == draftId } == true
                    if (exists && setPendingBottleLabelDraftId(draftId)) {
                        activeScannedDraftId = draftId
                        context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE)
                            .edit().putString("active_draft_id", draftId).apply()
                        bottleLabelCaptureMessage = null
                        bottleIntakeRoute = BottleIntakeRoute.CAPTURE_LABEL
                    } else {
                        bottleIntakeCameraMessage = "This bottle is no longer available for label capture. Its saved details are unchanged."
                    }
                },
                onUsePendingLabel = { draftId -> finishScannedBottleLabel?.invoke(draftId, pendingBottleLabelPhoto) == true },
                onSkipPendingLabel = { draftId -> finishScannedBottleLabel?.invoke(draftId, null) == true },
                onUseSuggestedCrop = { crop -> applyBottleLabelCrop(crop) },
                onApplyManualCrop = { crop, rotation -> applyBottleLabelCrop(crop, rotation) },
                onKeepOriginalLabel = ::restoreOriginalBottleLabel,
                onDiscardCapturedLabel = {
                    invalidateBottleCaptureOperation()
                    pendingBottleLabelPhoto = null
                    pendingBottleLabelOriginalPhoto = null
                    pendingBottleLabelCropSuggestion = null
                    bottleLabelCaptureMessage = null
                    File(File(context.cacheDir, "intake-labels"), "intake-label-capture.jpg").delete()
                },
                onAttachCapturedLabel = { draft ->
                    val photo = pendingBottleLabelPhoto
                    if (photo != null) {
                        if (repository.updateBottleIntakeDraft(draft.copy(photoData = photo, photoSha256 = photoSha256(photo)))) {
                            invalidateBottleCaptureOperation()
                            pendingBottleLabelPhoto = null
                            pendingBottleLabelOriginalPhoto = null
                            pendingBottleLabelCropSuggestion = null
                            File(File(context.cacheDir, "intake-labels"), "intake-label-capture.jpg").delete()
                            bottleLabelCaptureMessage = null
                            bottleIntakeSession = repository.openBottleIntakeSession()
                        } else {
                            bottleLabelCaptureMessage = "The label photo could not be attached. It is still available here; try again or discard it."
                        }
                    }
                },
                onScan = ::beginBottleBarcodeScan,
                onPauseQueue = {
                    showBottleScanner = false
                    launchedBottleCameraToken?.let { handleBottleLabelCameraResult(it, captured = false) }
                    cancelAllBottleEnrichmentRequests(bottleIntakeSession?.id, keepPaused = true)
                    refreshPendingBottleEnrichmentJobs()
                    bottleIntakeRoute = BottleIntakeRoute.PAUSE
                },
                onResumeScanning = {
                    val labelDraftStillNeeded = pendingBottleLabelDraftId?.let { id ->
                        bottleEnrichmentQueue.status(id)?.labelCaptureRequired == true
                    } == true
                    if (labelDraftStillNeeded) {
                        bottleIntakeRoute = BottleIntakeRoute.CAPTURE_LABEL
                    } else {
                        bottleIntakeRoute = BottleIntakeRoute.SCANNING
                        beginBottleBarcodeScan()
                    }
                },
                activeReviewDraftId = activeScannedDraftId,
                onReviewBottle = { draftId ->
                    activeScannedDraftId = draftId
                    bottleIntakeRoute = BottleIntakeRoute.REVIEW
                    context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().putString("active_draft_id", draftId).apply()
                },
                onAcceptAndScanNext = { draftId ->
                    val draft = bottleIntakeSession?.drafts?.firstOrNull { it.id == draftId }
                    if (draft == null || draftId != activeScannedDraftId) {
                        BottleIntakeCommitResult.Invalid("This bottle is no longer the active review.")
                    } else {
                        val suggestions = bottleWebSearchStates[draftId]?.extractedSuggestions ?: pendingBottleEvidenceStore.read(draftId)
                        val sources = acceptedProductSourcesForBottle(draft, suggestions)
                        when (val result = repository.acceptBottleIntakeDraft(draft.sessionId, draftId, System.currentTimeMillis(), sources)) {
                            is BottleIntakeCommitResult.Accepted, is BottleIntakeCommitResult.AlreadyAccepted -> {
                                bottleEnrichmentQueue.finish(draftId, BottleEnrichmentStatus.REVIEWED, "USER_ACCEPTED", System.currentTimeMillis())
                                pendingBottleEvidenceStore.remove(draftId)
                                bottleWebSearchStates = bottleWebSearchStates - draftId
                                refreshPantry()
                                bottleIntakeSession = repository.openBottleIntakeSession()
                                refreshPendingBottleEnrichmentJobs()
                                val nextReview = bottleIntakeSession?.drafts?.firstOrNull { next ->
                                    pendingBottleEnrichmentJobs[next.id]?.status in setOf(
                                        BottleEnrichmentStatus.READY_FOR_REVIEW,
                                        BottleEnrichmentStatus.NEEDS_ATTENTION,
                                    )
                                }
                                if (nextReview != null) {
                                    activeScannedDraftId = nextReview.id
                                    bottleIntakeRoute = BottleIntakeRoute.REVIEW
                                    context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE)
                                        .edit().putString("active_draft_id", nextReview.id).apply()
                                } else {
                                    activeScannedDraftId = null
                                    bottleIntakeRoute = BottleIntakeRoute.SCANNING
                                    context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE)
                                        .edit().remove("active_draft_id").apply()
                                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showBottleScanner = true
                                }
                                result
                            }
                            is BottleIntakeCommitResult.Invalid -> result
                            else -> result
                        }
                    }
                },
                onAddManual = { name, category, barcode, brand, productName, level, photo ->
                    val created = repository.addBottleIntakeDraft(
                        sessionId = bottleIntakeSession!!.id,
                        ingredientName = name,
                        category = category,
                        locationId = bottleIntakeSession!!.locationId,
                        level = level,
                        nowMillis = System.currentTimeMillis(),
                        barcode = barcode,
                        brand = brand,
                        productName = productName,
                        photoJpeg = photo,
                    )
                    if (created != null) bottleIntakeSession = repository.openBottleIntakeSession()
                    created != null
                },
                onRemove = { draft ->
                    cancelBottleSourceSearch(draft.id)
                    bottleEnrichmentQueue.removeDraft(draft.id)
                    pendingBottleEvidenceStore.remove(draft.id)
                    bottleWebSearchStates = bottleWebSearchStates - draft.id
                    repository.removeBottleIntakeDraft(draft.sessionId, draft.id)
                    if (pendingBottleLabelDraftId == draft.id) {
                        clearPendingBottleCapture()
                        setPendingBottleLabelDraftId(null)
                        pendingBottleLabelPhoto = null
                        pendingBottleLabelOriginalPhoto = null
                        pendingBottleLabelCropSuggestion = null
                        bottleLabelCaptureMessage = null
                    }
                    if (activeScannedDraftId == draft.id) {
                        activeScannedDraftId = null
                        context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().remove("active_draft_id").apply()
                    }
                    bottleIntakeSession = repository.openBottleIntakeSession()
                },
                onUpdate = { draft ->
                    val prior = bottleIntakeSession?.drafts?.firstOrNull { it.id == draft.id }
                    if (prior != null && bottleWebSearchStates[draft.id]?.automatedScan != true &&
                        (prior.ingredientName != draft.ingredientName || prior.brand != draft.brand || prior.productName != draft.productName)
                    ) {
                        cancelBottleSourceSearch(draft.id)
                        bottleWebSearchStates = bottleWebSearchStates - draft.id
                    }
                    val saved = repository.updateBottleIntakeDraft(draft)
                    if (saved) bottleIntakeSession = repository.openBottleIntakeSession()
                    saved
                },
                onUpdateBarcode = barcodeUpdate@{ draftId, rawBarcode ->
                    val cleaned = rawBarcode.trim()
                    val draft = bottleIntakeSession?.drafts?.firstOrNull { it.id == draftId }
                    if (draft == null || validateBottleBarcodeCorrection(draft.barcode, cleaned) != BottleBarcodeCorrection.CHANGED) {
                        false
                    } else {
                        // Invalidate the attempt before changing identity. If queue persistence
                        // fails, keep the saved barcode and source evidence untouched.
                        val edited = bottleFieldsToPreserveOnBarcodeCorrection(
                            draft = draft,
                            persistedEditedFields = pendingBottleEvidenceStore.editedFields(draftId) +
                                bottleWebSearchStates[draftId]?.userEditedFields.orEmpty(),
                            isAutomatedScan = bottleWebSearchStates[draftId]?.automatedScan == true,
                        )
                        cancelBottleSourceSearch(draftId)
                        if (!bottleEnrichmentQueue.resetForBarcodeChange(draftId, System.currentTimeMillis())) {
                            bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(
                                message = "The old lookup could not be safely stopped. The barcode and source evidence were left unchanged; try again later.",
                            ))
                            refreshPendingBottleEnrichmentJobs()
                            return@barcodeUpdate false
                        }
                        val saved = repository.updateBottleIntakeDraft(
                            draft.correctedForBarcode(cleaned, edited),
                        )
                        if (saved) {
                            pendingBottleEvidenceStore.remove(draftId)
                            edited.forEach { pendingBottleEvidenceStore.markEdited(draftId, it) }
                            LocalDiagnostics.clearBottleLookupForDraft(draftId)
                            bottleWebSearchStates = bottleWebSearchStates + (draftId to BottleWebSearchState(
                                automatedScan = true,
                                userEditedFields = edited,
                                message = "Barcode updated. Previous lookup evidence was cleared. Check the digits, then retry lookup when you choose; no request was started.",
                            ))
                            bottleIntakeSession = repository.openBottleIntakeSession()
                            refreshPendingBottleEnrichmentJobs()
                        } else {
                            bottleWebSearchStates = bottleWebSearchStates + (draftId to (bottleWebSearchStates[draftId] ?: BottleWebSearchState()).copy(
                                message = "The lookup was stopped, but the corrected barcode could not be saved. Your bottle details and photo remain; check the barcode and try again.",
                            ))
                            refreshPendingBottleEnrichmentJobs()
                        }
                        saved
                    }
                },
                onFieldEdited = { draftId, field ->
                    val state = bottleWebSearchStates[draftId]
                    if (field !in state?.userEditedFields.orEmpty()) {
                        pendingBottleEvidenceStore.markEdited(draftId, field)
                        if (state != null) {
                            bottleWebSearchStates = bottleWebSearchStates + (draftId to state.copy(userEditedFields = state.userEditedFields + field))
                        }
                    }
                },
                onHomeChanged = { home -> repository.setBottleIntakeHome(bottleIntakeSession!!.id, home, System.currentTimeMillis()); bottleIntakeSession = repository.openBottleIntakeSession() },
                onLevelChanged = { level -> repository.setBottleIntakeDefaultLevel(bottleIntakeSession!!.id, level, System.currentTimeMillis()); bottleIntakeSession = repository.openBottleIntakeSession() },
                onConfirm = {
                    val session = bottleIntakeSession!!
                    if (pendingBottleLabelDraftId != null) {
                        BottleIntakeCommitResult.Invalid("Capture or skip the pending label before confirming this batch.")
                    } else {
                        val nowMillis = System.currentTimeMillis()
                        val sourcesByDraftId = session.drafts.mapNotNull { draft ->
                            val suggestions = bottleWebSearchStates[draft.id]?.extractedSuggestions
                                ?: pendingBottleEvidenceStore.read(draft.id)
                            acceptedProductSourcesForBottle(draft, suggestions)
                                .takeIf { it.isNotEmpty() }?.let { draft.id to it }
                        }.toMap()
                        val result = repository.confirmBottleIntakeSession(session.id, nowMillis, sourcesByDraftId)
                        when (result) {
                            is BottleIntakeCommitResult.Confirmed, is BottleIntakeCommitResult.AlreadyConfirmed -> {
                                cancelAllBottleEnrichmentRequests(bottleIntakeSession?.id, keepPaused = false)
                                bottleIntakeSession?.drafts?.forEach { pendingBottleEvidenceStore.remove(it.id) }
                                bottleWebSearchStates = emptyMap()
                                refreshPantry()
                                bottleIntakeSession = null
                                showBottleIntake = false
                                activeScannedDraftId = null
                                context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().remove("active_draft_id").apply()
                                pendingBottleLabelPhoto = null
                                pendingBottleLabelOriginalPhoto = null
                                pendingBottleLabelCropSuggestion = null
                                bottleLabelCaptureMessage = null
                            }
                            is BottleIntakeCommitResult.Accepted, is BottleIntakeCommitResult.AlreadyAccepted -> Unit
                            is BottleIntakeCommitResult.Invalid -> Unit
                        }
                        result
                    }
                },
                onCancel = {
                    cancelAllBottleEnrichmentRequests(bottleIntakeSession?.id, keepPaused = false)
                    bottleIntakeSession?.drafts?.forEach { pendingBottleEvidenceStore.remove(it.id) }
                    bottleWebSearchStates = emptyMap()
                    repository.cancelBottleIntakeSession(bottleIntakeSession!!.id)
                    clearPendingBottleCapture()
                    setPendingBottleLabelDraftId(null)
                    bottleIntakeSession = null
                    activeScannedDraftId = null
                    context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().remove("active_draft_id").apply()
                    pendingBottleLabelPhoto = null
                    pendingBottleLabelOriginalPhoto = null
                    pendingBottleLabelCropSuggestion = null
                    bottleLabelCaptureMessage = null
                    showBottleIntake = false
                },
                onDismiss = {
                    invalidateBottleCaptureOperation()
                    cancelAllBottleEnrichmentRequests(bottleIntakeSession?.id, keepPaused = true)
                    bottleWebSearchStates = emptyMap()
                    showBottleIntake = false
                    bottleIntakeSession = repository.openBottleIntakeSession()
                    pendingBottleLabelPhoto = null
                    pendingBottleLabelOriginalPhoto = null
                    pendingBottleLabelCropSuggestion = null
                    bottleLabelCaptureMessage = null
                },
                )
            }
        } else {
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
                    setBarcodeInput("")
                    barcodePermissionMessage = null
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
                onAddIngredient = {
                    showAddIngredient = true
                    setBarcodeInput("")
                    barcodePermissionMessage = null
                },
                onAddBottles = {
                    val session = repository.startBottleIntakeSession(selectedLocationId, InventoryLevel.FULL, System.currentTimeMillis())
                    bottleIntakeSession = repository.openBottleIntakeSession() ?: session
                    bottleIntakeRoute = if (session.drafts.isEmpty()) BottleIntakeRoute.SETUP else BottleIntakeRoute.SCANNING
                    showBottleIntake = true
                },
                onAddLocation = { showAddLocation = true },
                onLevelSelected = { entry, level ->
                    repository.updateBottleLevel(
                        inventoryId = entry.inventoryId,
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
                onOpenDiagnostics = {
                    lookupReportCopyMessage = null
                    showDiagnosticsForDraftId = null
                    showDiagnostics = true
                },
                installedAppVersion = installedAppVersion,
                detailedCaptureStatus = settingsDetailedCaptureStatus,
                detailedCaptureMessage = detailedDebugModeMessage,
                detailedCaptureEnabled = true,
                onEnableDetailedCapture = {
                    val result = detailedLookupCaptureStore.enableDebugMode(installedAppVersion.name)
                    settingsDetailedCaptureStatus = detailedLookupCaptureStore.sessionStatus()
                    detailedDebugModeMessage = when (result) {
                        com.davidhayesbc.cocktails.android.product.DetailedCaptureArmResult.ARMED -> "Debug mode is on. New eligible on-device lookup attempts will be captured locally."
                        com.davidhayesbc.cocktails.android.product.DetailedCaptureArmResult.ALREADY_ARMED -> "Debug mode is already on."
                        else -> "Debug mode could not be saved in private app storage."
                    }
                },
                onDisableDetailedCapture = {
                    val stopped = detailedLookupCaptureStore.disableDebugMode()
                    settingsDetailedCaptureStatus = detailedLookupCaptureStore.sessionStatus()
                    detailedDebugModeMessage = if (stopped) "Debug mode is off. Existing reports remain available and will expire under the normal local retention policy." else "Debug mode could not be turned off. Try again."
                },
                openFoodFactsEnabled = openFoodFactsEnabled,
                onOpenFoodFactsEnabledChanged = { enabled ->
                    context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("open_food_facts_enabled", enabled).apply()
                    openFoodFactsEnabled = enabled
                },
                firecrawlSearchEnabled = firecrawlSearchEnabled,
                firecrawlKeySaved = firecrawlKeySaved,
                firecrawlKeyNeedsReplacement = firecrawlKeyNeedsReplacement,
                firecrawlCredentialMessage = firecrawlCredentialMessage,
                onConfigureFirecrawl = { showFirecrawlCredentialDialog = true },
                onRemoveFirecrawlKey = {
                    val removed = firecrawlCredentialStore.clear()
                    firecrawlKeySaved = firecrawlCredentialStore.hasSavedKey()
                    firecrawlKeyNeedsReplacement = firecrawlKeySaved && firecrawlCredentialStore.readApiKey() == null
                    firecrawlSearchEnabled = false
                    context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("firecrawl_search_enabled", false).apply()
                    firecrawlCredentialMessage = if (removed) "Firecrawl key removed from this phone." else "Key removal could not be fully confirmed. Search has been disabled; try removing the key again."
                },
                onFirecrawlEnabledChanged = { enabled ->
                    val allowed = enabled && firecrawlKeySaved && !firecrawlKeyNeedsReplacement
                    context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("firecrawl_search_enabled", allowed).apply()
                    firecrawlSearchEnabled = allowed
                },
                ollamaCloudEnabled = ollamaCloudEnabled,
                ollamaCloudKeySaved = ollamaCloudKeySaved,
                ollamaCloudKeyNeedsReplacement = ollamaCloudKeyNeedsReplacement,
                ollamaCloudModel = ollamaCloudModel,
                ollamaCredentialMessage = ollamaCredentialMessage,
                onConfigureOllamaCloud = { showOllamaCloudSettingsDialog = true; ollamaCredentialMessage = null },
                onRemoveOllamaCloudKey = {
                    val removed = ollamaCloudCredentialStore.clear()
                    ollamaCloudKeySaved = ollamaCloudCredentialStore.hasSavedKey()
                    ollamaCloudKeyNeedsReplacement = ollamaCloudKeySaved && ollamaCloudCredentialStore.readApiKey() == null
                    ollamaCloudEnabled = false
                    context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("ollama_cloud_enabled", false).apply()
                    ollamaCredentialMessage = if (removed) "Ollama Cloud key removed from this phone." else "Key removal could not be fully confirmed. Cloud extraction has been disabled. Replace the key or try removal again."
                },
                onOllamaCloudEnabledChanged = { enabled ->
                    val allowed = enabled && ollamaCloudKeySaved && !ollamaCloudKeyNeedsReplacement && isValidOllamaModelIdentifier(ollamaCloudModel)
                    context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("ollama_cloud_enabled", allowed).apply()
                    ollamaCloudEnabled = allowed
                },
                localAiEnabled = localAiEnabled,
                localModelInstalled = localModelFile != null,
                localModelMessage = localModelMessage,
                localModelImporting = localModelImporting,
                localModelImportProgressBytes = localModelImportProgressBytes,
                onChooseLocalModel = { localModelMessage = null; localModelImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onCancelLocalModelImport = {
                    localModelImportCancelled.set(true)
                    localModelMessage = "Canceling model import…"
                },
                onOpenLocalModelSource = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(QWEN3_06B_MODEL_PAGE)))
                },
                onDeleteLocalModel = {
                    if (localModelFileStore.delete()) {
                        localModelFile = null
                        localAiEnabled = false
                        context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("local_ai_enabled", false).apply()
                        localModelMessage = "The local model and interrupted import files were removed from this phone."
                    } else {
                        localModelMessage = "The local model could not be fully removed. Try again."
                    }
                },
                onLocalAiEnabledChanged = { enabled ->
                    val allowed = enabled && localModelFileStore.installedModel() != null
                    context.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE).edit().putBoolean("local_ai_enabled", allowed).apply()
                    localAiEnabled = allowed
                },
            )
            }
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
        val selectedDraftId = showDiagnosticsForDraftId
        val lookupPreview = selectedDraftId?.let(LocalDiagnostics::latestBottleLookupPreviewForDraft)
            ?: if (selectedDraftId == null) LocalDiagnostics.latestBottleLookupPreview() else null
        val detailedReports = detailedLookupCaptureStore.reportContents(selectedDraftId)
        DiagnosticsDialog(
            preview = LocalDiagnostics.preview(),
            lookupPreview = lookupPreview,
            lookupSubject = if (selectedDraftId == null) "Latest bottle lookup report" else "Lookup report for this bottle",
            lookupMissingMessage = if (selectedDraftId == null) "No barcode lookup report yet." else "No lookup report has been saved for this bottle yet.",
            lookupCopyMessage = lookupReportCopyMessage,
            detailedCaptureCount = detailedReports.size,
            onCopy = {
                val content = combineBottleDiagnostics(LocalDiagnostics.export(), detailedReports)
                copyDiagnosticsToClipboard(context, content)
                lookupReportCopyMessage = if (detailedReports.isEmpty()) {
                    "Diagnostics copied. No detailed capture is available for this selection."
                } else {
                    "Diagnostics copied with ${detailedReports.size} detailed capture${if (detailedReports.size == 1) "" else "s"}. Review before sharing."
                }
            },
            onExport = {
                diagnosticsExportContents = combineBottleDiagnostics(LocalDiagnostics.export(), detailedReports)
                diagnosticsExportLauncher.launch("CocktAIls-Diagnostics.json")
            },
            onDismiss = { showDiagnostics = false; showDiagnosticsForDraftId = null },
        )
    }

    if (showFirecrawlCredentialDialog) {
        FirecrawlCredentialDialog(
            keyNeedsReplacement = firecrawlKeyNeedsReplacement,
            onSave = { key ->
                val saved = firecrawlCredentialStore.saveApiKey(key)
                if (saved) {
                    firecrawlKeySaved = true
                    firecrawlKeyNeedsReplacement = false
                    firecrawlCredentialMessage = "Firecrawl key encrypted and saved on this phone."
                }
                saved
            },
            onDismiss = { showFirecrawlCredentialDialog = false },
        )
    }

    if (showOllamaCloudSettingsDialog) {
        OllamaCloudSettingsDialog(
            currentModel = ollamaCloudModel,
            keyNeedsReplacement = ollamaCloudKeyNeedsReplacement,
            onSave = { key, model -> saveOllamaCloudSettings(key, model) },
            onDismiss = { showOllamaCloudSettingsDialog = false },
        )
    }

    if (showAddIngredient) {
        AddIngredientDialog(
            barcode = barcodeInput,
            barcodeMessage = barcodePermissionMessage,
            localProductMatch = localProductMatch,
            productLookupResult = productLookupResult,
            productLookupInProgress = productLookupInProgress,
            onlineLookupEnabled = openFoodFactsEnabled,
            productPhotoBytes = selectedProductPhoto,
            productPhotoMessage = productPhotoMessage,
            onBarcodeChanged = { setBarcodeInput(it); barcodePermissionMessage = null },
            onLookupProduct = ::lookupProductDetails,
            onScanBarcode = ::beginBarcodeScan,
            onPickPhoto = { productPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemovePhoto = { selectedProductPhoto = null; productPhotoMessage = null },
            onDismiss = { showAddIngredient = false; setBarcodeInput(""); barcodePermissionMessage = null; selectedProductPhoto = null; productPhotoMessage = null },
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
                    setBarcodeInput("")
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


    if (showBottleScanner && bottleIntakeSession != null) {
        BarcodeScannerDialog(
            continuous = false,
            fullScreen = true,
            batchBottleCount = bottleIntakeSession!!.confirmedCount + bottleIntakeSession!!.drafts.size,
            readyCount = bottleIntakeSession!!.drafts.count { draft ->
                pendingBottleEnrichmentJobs[draft.id]?.status in setOf(BottleEnrichmentStatus.READY_FOR_REVIEW, BottleEnrichmentStatus.NEEDS_ATTENTION)
            },
            runningCount = bottleIntakeSession!!.drafts.count { draft -> pendingBottleEnrichmentJobs[draft.id]?.status == BottleEnrichmentStatus.RUNNING },
            pendingCount = bottleIntakeSession!!.drafts.count { draft -> pendingBottleEnrichmentJobs[draft.id]?.status in setOf(BottleEnrichmentStatus.QUEUED, BottleEnrichmentStatus.CAPTURE_REQUIRED) },
            onReviewReady = {
                val readyDraft = bottleIntakeSession!!.drafts.firstOrNull { draft ->
                    pendingBottleEnrichmentJobs[draft.id]?.status in setOf(BottleEnrichmentStatus.READY_FOR_REVIEW, BottleEnrichmentStatus.NEEDS_ATTENTION)
                }
                if (readyDraft != null) {
                    showBottleScanner = false
                    activeScannedDraftId = readyDraft.id
                    bottleIntakeRoute = BottleIntakeRoute.REVIEW
                    context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().putString("active_draft_id", readyDraft.id).apply()
                }
            },
            onPause = {
                showBottleScanner = false
                cancelAllBottleEnrichmentRequests(bottleIntakeSession?.id, keepPaused = true)
                refreshPendingBottleEnrichmentJobs()
                bottleIntakeRoute = BottleIntakeRoute.PAUSE
            },
            onDetected = { scanned ->
                if (normalizeGtinForLookup(scanned) != null) {
                    val local = repository.localProductForBarcode(scanned)
                    val draft = repository.addBottleIntakeDraft(
                        sessionId = bottleIntakeSession!!.id,
                        ingredientName = local?.ingredientName ?: "Unidentified bottle",
                        category = local?.category ?: "Spirit",
                        locationId = bottleIntakeSession!!.locationId,
                        level = bottleIntakeSession!!.defaultLevel,
                        nowMillis = System.currentTimeMillis(),
                        barcode = scanned,
                        brand = local?.brand,
                        productName = local?.productName,
                        quantity = local?.quantity,
                        abvPercent = local?.abvPercent,
                    )
                    showBottleScanner = false
                    bottleIntakeSession = repository.openBottleIntakeSession()
                    if (draft != null) {
                        bottleIntakeRoute = BottleIntakeRoute.CAPTURE_LABEL
                        activeScannedDraftId = null
                        context.getSharedPreferences("cocktails_local_scan_review", Context.MODE_PRIVATE).edit().remove("active_draft_id").apply()
                        pendingBottleLabelPhoto = null
                        pendingBottleLabelOriginalPhoto = null
                        pendingBottleLabelCropSuggestion = null
                        bottleLabelCaptureMessage = null
                        val captureQueued = bottleEnrichmentQueue.requireLabel(draft.sessionId, draft.id, System.currentTimeMillis()) != null
                        setPendingBottleLabelDraftId(draft.id)
                        refreshPendingBottleEnrichmentJobs()
                        bottleWebSearchStates = bottleWebSearchStates + (draft.id to BottleWebSearchState(
                            automatedScan = true,
                            localMatch = local != null,
                            message = if (captureQueued) {
                                "Capture this label or skip the photo. Lookup starts now and continues during capture; scan the next bottle after this step."
                            } else {
                                "The bottle was saved locally, but its capture step could not be queued. You can enter details manually."
                            },
                        ))
                        if (captureQueued) {
                            startScannedBottleEnrichment(draft, localMatch = local != null)
                            val token = beginBottleCaptureOperation(draft.id)
                            if (persistCameraLaunch(token)) {
                                launchedBottleCameraToken = token
                            } else {
                                bottleLabelCaptureMessage = "The bottle is queued, but the camera session could not be saved. Retry the label photo or skip it."
                            }
                        }
                        else bottleIntakeCameraMessage = "This bottle is safe as a local draft; the label queue could not be saved."
                    } else {
                        bottleIntakeCameraMessage = "The scanned bottle could not be added. Try scanning again or enter it manually."
                    }
                }
            },
            onDismiss = { showBottleScanner = false; bottleIntakeSession = repository.openBottleIntakeSession() },
        )
    }

    if (showBarcodeScanner) {
        BarcodeScannerDialog(
            onDetected = { scanned ->
                if (normalizeGtinForLookup(scanned) != null) {
                    setBarcodeInput(scanned)
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
internal fun BottleIntakeScreen(
    modifier: Modifier,
    session: BottleIntakeSession,
    route: BottleIntakeRoute,
    onRouteChange: (BottleIntakeRoute) -> Unit,
    homes: List<HomeLocation>,
    capturePhoto: ByteArray?,
    originalCapturePhoto: ByteArray? = capturePhoto,
    suggestedLabelCrop: NormalizedLabelCrop? = null,
    labelCropBusy: Boolean = false,
    captureMessage: String?,
    permissionMessage: String?,
    pendingLabelDraftId: String? = null,
    labelCameraOutputFile: File? = null,
    labelCaptureProcessing: Boolean = false,
    labelCameraToken: BottleCaptureToken? = null,
    onLabelPhotoSaved: (BottleCaptureToken, NormalizedLabelCrop?) -> Unit = { _, _ -> },
    onLabelCameraCancel: (BottleCaptureToken) -> Unit = {},
    webSearchEnabled: Boolean,
    webSearchStates: Map<String, BottleWebSearchState>,
    onOpenLookupReport: (String) -> Unit = {},
    pendingEnrichmentJobs: Map<String, BottleEnrichmentJob>,
    activeReviewDraftId: String?,
    onReviewBottle: (String) -> Unit = {},
    ollamaCloudEnabled: Boolean,
    localAiEnabled: Boolean,
    onSearchDraft: (BottleIntakeDraft) -> Unit,
    onFetchDraftSource: (BottleIntakeDraft, WebSearchHit) -> Unit,
    onExtractDraftDetails: (String) -> Unit,
    onExtractDraftDetailsOnDevice: (String) -> Unit,
    onResumeEnrichment: (BottleIntakeDraft, BottleEnrichmentJob) -> Unit,
    onCancelDraftSearch: (String) -> Unit,
    onCaptureLabel: () -> Unit,
    onCaptureBottleLabel: (String) -> Unit = {},
    onUsePendingLabel: (String) -> Boolean = { false },
    onSkipPendingLabel: (String) -> Boolean = { false },
    onUseSuggestedCrop: (NormalizedLabelCrop) -> Unit = {},
    onApplyManualCrop: (NormalizedLabelCrop, Int) -> Unit = { _, _ -> },
    onKeepOriginalLabel: () -> Unit = {},
    onDiscardCapturedLabel: () -> Unit,
    onAttachCapturedLabel: (BottleIntakeDraft) -> Unit,
    onScan: () -> Unit,
    onAcceptAndScanNext: (String) -> BottleIntakeCommitResult,
    onAddManual: (String, String, String?, String?, String?, InventoryLevel, ByteArray?) -> Boolean,
    onRemove: (BottleIntakeDraft) -> Unit,
    onUpdate: (BottleIntakeDraft) -> Boolean,
    onUpdateBarcode: (String, String) -> Boolean = { _, _ -> false },
    onFieldEdited: (String, BottleDraftField) -> Unit,
    onHomeChanged: (String) -> Unit,
    onLevelChanged: (InventoryLevel) -> Unit,
    onConfirm: () -> BottleIntakeCommitResult,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onPauseQueue: () -> Unit,
    onResumeScanning: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    BottleIntakeFlowScreen(
        modifier = modifier,
        route = route,
        session = session,
        homes = homes,
        capturePhoto = capturePhoto,
        originalCapturePhoto = originalCapturePhoto,
        suggestedLabelCrop = suggestedLabelCrop,
        labelCropBusy = labelCropBusy,
        captureMessage = captureMessage,
        permissionMessage = permissionMessage,
        pendingLabelDraftId = pendingLabelDraftId,
        labelCameraOutputFile = labelCameraOutputFile,
        labelCaptureProcessing = labelCaptureProcessing,
        labelCameraToken = labelCameraToken,
        onLabelPhotoSaved = onLabelPhotoSaved,
        onLabelCameraCancel = onLabelCameraCancel,
        webSearchEnabled = webSearchEnabled,
        webSearchStates = webSearchStates,
        onOpenLookupReport = onOpenLookupReport,
        pendingEnrichmentJobs = pendingEnrichmentJobs,
        activeReviewDraftId = activeReviewDraftId,
        ollamaCloudEnabled = ollamaCloudEnabled,
        localAiEnabled = localAiEnabled,
        onSearchDraft = onSearchDraft,
        onFetchDraftSource = onFetchDraftSource,
        onExtractDraftDetails = onExtractDraftDetails,
        onExtractDraftDetailsOnDevice = onExtractDraftDetailsOnDevice,
        onResumeEnrichment = onResumeEnrichment,
        onCancelDraftSearch = onCancelDraftSearch,
        onCaptureLabel = onCaptureLabel,
        onCaptureBottleLabel = onCaptureBottleLabel,
        onUsePendingLabel = onUsePendingLabel,
        onSkipPendingLabel = onSkipPendingLabel,
        onUseSuggestedCrop = onUseSuggestedCrop,
        onApplyManualCrop = onApplyManualCrop,
        onKeepOriginalLabel = onKeepOriginalLabel,
        onDiscardCapturedLabel = onDiscardCapturedLabel,
        onAttachCapturedLabel = onAttachCapturedLabel,
        onScan = onScan,
        onReviewBottle = onReviewBottle,
        onAcceptAndScanNext = onAcceptAndScanNext,
        onAddManual = onAddManual,
        onRemove = onRemove,
        onUpdate = onUpdate,
        onUpdateBarcode = onUpdateBarcode,
        onFieldEdited = onFieldEdited,
        onHomeChanged = onHomeChanged,
        onLevelChanged = onLevelChanged,
        onConfirm = onConfirm,
        onCancel = onCancel,
        onDismiss = onDismiss,
        onPauseQueue = onPauseQueue,
        onResumeScanning = onResumeScanning,
        onRouteChange = onRouteChange,
    )

}

@Composable
private fun BottleIntakeFlowScreen(
    modifier: Modifier,
    route: BottleIntakeRoute,
    session: BottleIntakeSession,
    homes: List<HomeLocation>,
    capturePhoto: ByteArray?,
    originalCapturePhoto: ByteArray?,
    suggestedLabelCrop: NormalizedLabelCrop?,
    labelCropBusy: Boolean,
    captureMessage: String?,
    permissionMessage: String?,
    pendingLabelDraftId: String?,
    labelCameraOutputFile: File?,
    labelCaptureProcessing: Boolean,
    labelCameraToken: BottleCaptureToken?,
    onLabelPhotoSaved: (BottleCaptureToken, NormalizedLabelCrop?) -> Unit,
    onLabelCameraCancel: (BottleCaptureToken) -> Unit,
    webSearchEnabled: Boolean,
    webSearchStates: Map<String, BottleWebSearchState>,
    onOpenLookupReport: (String) -> Unit,
    pendingEnrichmentJobs: Map<String, BottleEnrichmentJob>,
    activeReviewDraftId: String?,
    ollamaCloudEnabled: Boolean,
    localAiEnabled: Boolean,
    onSearchDraft: (BottleIntakeDraft) -> Unit,
    onFetchDraftSource: (BottleIntakeDraft, WebSearchHit) -> Unit,
    onExtractDraftDetails: (String) -> Unit,
    onExtractDraftDetailsOnDevice: (String) -> Unit,
    onResumeEnrichment: (BottleIntakeDraft, BottleEnrichmentJob) -> Unit,
    onCancelDraftSearch: (String) -> Unit,
    onCaptureLabel: () -> Unit,
    onCaptureBottleLabel: (String) -> Unit,
    onUsePendingLabel: (String) -> Boolean,
    onSkipPendingLabel: (String) -> Boolean,
    onUseSuggestedCrop: (NormalizedLabelCrop) -> Unit,
    onApplyManualCrop: (NormalizedLabelCrop, Int) -> Unit,
    onKeepOriginalLabel: () -> Unit,
    onDiscardCapturedLabel: () -> Unit,
    onAttachCapturedLabel: (BottleIntakeDraft) -> Unit,
    onScan: () -> Unit,
    onReviewBottle: (String) -> Unit,
    onAcceptAndScanNext: (String) -> BottleIntakeCommitResult,
    onAddManual: (String, String, String?, String?, String?, InventoryLevel, ByteArray?) -> Boolean,
    onRemove: (BottleIntakeDraft) -> Unit,
    onUpdate: (BottleIntakeDraft) -> Boolean,
    onUpdateBarcode: (String, String) -> Boolean,
    onFieldEdited: (String, BottleDraftField) -> Unit,
    onHomeChanged: (String) -> Unit,
    onLevelChanged: (InventoryLevel) -> Unit,
    onConfirm: () -> BottleIntakeCommitResult,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onPauseQueue: () -> Unit,
    onResumeScanning: () -> Unit,
    onRouteChange: (BottleIntakeRoute) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        error = null
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    val drafts = session.drafts
    val jobs = drafts.mapNotNull { pendingEnrichmentJobs[it.id] }
    val ready = drafts.filter { pendingEnrichmentJobs[it.id]?.status == BottleEnrichmentStatus.READY_FOR_REVIEW }
    val needsHelp = drafts.filter { pendingEnrichmentJobs[it.id]?.status == BottleEnrichmentStatus.NEEDS_ATTENTION }
    val runningCount = drafts.count { pendingEnrichmentJobs[it.id]?.status == BottleEnrichmentStatus.RUNNING }
    val pendingCount = drafts.count { pendingEnrichmentJobs[it.id]?.status in setOf(BottleEnrichmentStatus.QUEUED, BottleEnrichmentStatus.CAPTURE_REQUIRED) }
    val reviewedCount = session.confirmedCount + drafts.count { pendingEnrichmentJobs[it.id]?.status == BottleEnrichmentStatus.REVIEWED }
    var cropEditorOpen by remember(session.id, pendingLabelDraftId) { mutableStateOf(false) }
    var name by remember(session.id) { mutableStateOf("") }
    var category by remember(session.id) { mutableStateOf("Spirit") }
    var barcode by remember(session.id) { mutableStateOf("") }
    var brand by remember(session.id) { mutableStateOf("") }
    var productName by remember(session.id) { mutableStateOf("") }
    Column(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        if (route == BottleIntakeRoute.SETUP && labelCameraOutputFile == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CocktAIls", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("intake-close")) { Text("Close") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        labelCameraOutputFile != null -> "Now capture the label"
                        else -> when (route) {
                            BottleIntakeRoute.SETUP -> "Build your shelf"
                            BottleIntakeRoute.CAPTURE_LABEL -> "Now capture the label"
                            BottleIntakeRoute.SCANNING -> "Scan the next bottle"
                            BottleIntakeRoute.REVIEW -> "Review your bottle"
                            BottleIntakeRoute.QUEUE -> "Your bottle queue"
                            BottleIntakeRoute.PAUSE -> "Pause for now"
                            BottleIntakeRoute.MANUAL_ENTRY -> "Add a bottle manually"
                        }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).testTag("bottle-intake-route-title"),
                )
                if (route !in setOf(BottleIntakeRoute.SETUP, BottleIntakeRoute.MANUAL_ENTRY)) {
                    TextButton(onClick = onPauseQueue, modifier = Modifier.testTag("intake-pause")) { Text("Pause") }
                }
            }
        }

        if (labelCameraOutputFile != null && labelCameraToken != null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                val draft = drafts.firstOrNull { it.id == pendingLabelDraftId }
                draft?.let { Text("Bottle #${it.position + 1} · ${it.barcode.orEmpty()}", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }
                if (capturePhoto != null) Text("Retaking label · your previous photo stays safe until you use the new one.", style = MaterialTheme.typography.bodySmall)
                BottleLabelCameraScreen(
                    outputFile = labelCameraOutputFile,
                    captureToken = labelCameraToken,
                    onPhotoSaved = onLabelPhotoSaved,
                    onCancel = onLabelCameraCancel,
                )
            }
        } else if (labelCaptureProcessing) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (capturePhoto == null) "Preparing the label photo…" else "Preparing the retaken label photo…", style = MaterialTheme.typography.titleLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (capturePhoto == null) "This stays on your phone. You can skip the photo and continue scanning."
                    else "Your previous label is still safe. Keep it or wait for the retake to finish.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                pendingLabelDraftId?.let { id ->
                    if (capturePhoto != null) TextButton(onClick = { onUsePendingLabel(id) }) { Text("Keep previous label & scan next") }
                    else TextButton(onClick = { onSkipPendingLabel(id) }) { Text("Skip photo & scan next") }
                }
            }
        } else when (route) {
            BottleIntakeRoute.SETUP -> Column(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .testTag("bottle-intake-scroll").padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        "Build your shelf",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp),
                        modifier = Modifier.testTag("intake-setup-title"),
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("intake-setup-card-capture"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Capture now, confirm later", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Scan a bottle, take a label photo, and keep going. Review or edit each bottle before accepting it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("intake-setup-card-background-work"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Web search + AI", style = MaterialTheme.typography.titleMedium)
                                Text("Background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "Enabled providers process the barcode while you capture labels. Nothing is sent unless its provider is enabled and configured.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "Camera access is used to scan barcodes and capture labels you choose. Label photos stay on this phone and are not sent to lookup or AI providers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 10.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onScan,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("intake-allow-camera"),
                    ) { Text("Allow camera & continue") }
                    OutlinedButton(
                        onClick = { onRouteChange(BottleIntakeRoute.MANUAL_ENTRY) },
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("intake-manual-start"),
                    ) { Text("Start with manual entry") }
                }
            }

            BottleIntakeRoute.CAPTURE_LABEL -> {
                val draft = drafts.firstOrNull { it.id == pendingLabelDraftId }
                if (draft == null) {
                    EmptyIntakeState("This bottle is no longer in the batch.", "Return to scanning", onScan)
                } else Column(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .testTag("bottle-intake-scroll").padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Barcode captured · Bottle #${draft.position + 1}", style = MaterialTheme.typography.titleMedium)
                        Text(draft.barcode.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Take a label photo for your own reference. It stays on this phone and is not sent to lookup providers.", style = MaterialTheme.typography.bodyMedium)
                        capturePhoto?.let { photo ->
                            val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
                            if (bitmap != null) Image(bitmap, contentDescription = "Preview label for bottle ${draft.position + 1}", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("label-photo-preview-${draft.id}"))
                            val showingOriginal = originalCapturePhoto == null || photo.contentEquals(originalCapturePhoto)
                            if (suggestedLabelCrop != null && originalCapturePhoto != null && showingOriginal) {
                                Text("Possible label edges found. Check the crop before using it.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { onUseSuggestedCrop(suggestedLabelCrop) }, enabled = !labelCropBusy, modifier = Modifier.fillMaxWidth()) { Text("Check suggested crop") }
                            }
                            if (originalCapturePhoto != null) OutlinedButton(onClick = { cropEditorOpen = true }, enabled = !labelCropBusy, modifier = Modifier.fillMaxWidth().testTag("adjust-bottle-label-crop")) { Text("Adjust crop") }
                        }
                        if (labelCropBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        captureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (capturePhoto == null) {
                            Button(onClick = onCaptureLabel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("capture-label-${draft.id}")) { Text("Capture label") }
                            TextButton(onClick = { onSkipPendingLabel(draft.id) }, enabled = !labelCropBusy, modifier = Modifier.fillMaxWidth().testTag("skip-label-${draft.id}")) { Text("Skip photo & scan next") }
                        } else {
                            Button(onClick = { onUsePendingLabel(draft.id) }, enabled = !labelCropBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("use-label-${draft.id}")) { Text("Use photo & scan next") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onCaptureLabel, enabled = !labelCropBusy, modifier = Modifier.weight(1f).testTag("retake-label-${draft.id}")) { Text("Retake") }
                                TextButton(onClick = { onSkipPendingLabel(draft.id) }, enabled = !labelCropBusy, modifier = Modifier.weight(1f).testTag("skip-label-${draft.id}")) { Text("Skip photo & scan next") }
                            }
                        }
                    }
                }
                if (cropEditorOpen && originalCapturePhoto != null) BottleLabelCropEditorDialog(
                    originalJpeg = originalCapturePhoto,
                    suggestedCrop = suggestedLabelCrop,
                    onKeepOriginal = onKeepOriginalLabel,
                    onApply = { crop, rotation -> onApplyManualCrop(crop, rotation); cropEditorOpen = false },
                    onDismiss = { cropEditorOpen = false },
                )
            }

            BottleIntakeRoute.SCANNING -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("bottle-intake-scroll").padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Point the camera at one bottle barcode. Search starts as soon as it is captured; take its label photo next, then keep scanning.", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntakeCountPill("${ready.size} ready")
                    IntakeCountPill("$runningCount running")
                    IntakeCountPill("$pendingCount pending")
                }
                if (ready.isNotEmpty() || needsHelp.isNotEmpty()) {
                    Button(onClick = { onReviewBottle((ready.firstOrNull() ?: needsHelp.first()).id) }, modifier = Modifier.fillMaxWidth().testTag("review-ready-bottles")) { Text("Review ready") }
                }
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("batch-scan-bottles")) { Text(if (drafts.isEmpty()) "Scan a bottle" else "Scan next bottle") }
                OutlinedButton(onClick = { onRouteChange(BottleIntakeRoute.QUEUE) }, modifier = Modifier.fillMaxWidth().testTag("open-bottle-queue")) { Text("Your bottle queue · ${drafts.size}") }
                OutlinedButton(onClick = { onRouteChange(BottleIntakeRoute.MANUAL_ENTRY) }, modifier = Modifier.fillMaxWidth().testTag("batch-manual-entry")) { Text("Add a bottle manually") }
                permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            BottleIntakeRoute.REVIEW -> {
                val draft = drafts.firstOrNull { it.id == activeReviewDraftId }
                if (draft == null) EmptyIntakeState("This bottle is no longer in the batch.", "Return to scanning", { onRouteChange(BottleIntakeRoute.SCANNING) })
                else {
                    val webState = webSearchStates[draft.id] ?: BottleWebSearchState()
                    val job = pendingEnrichmentJobs[draft.id]
                    val hasAnotherReadyBottle = ready.any { it.id != draft.id } || needsHelp.any { it.id != draft.id }
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                                .testTag("bottle-intake-scroll").padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BottleReviewCard(
                                draft = draft,
                                homes = homes,
                                onUpdate = onUpdate,
                                onUpdateBarcode = onUpdateBarcode,
                                onFieldEdited = onFieldEdited,
                                onValidityChanged = { _, _ -> },
                                onRemove = onRemove,
                                webSearchEnabled = webSearchEnabled,
                                webSearchState = webState,
                                onOpenLookupReport = { onOpenLookupReport(draft.id) },
                                pendingEnrichmentJob = job,
                                ollamaCloudEnabled = ollamaCloudEnabled,
                                localAiEnabled = localAiEnabled,
                                onSearch = { onSearchDraft(draft) },
                                onFetchSource = { onFetchDraftSource(draft, it) },
                                onExtractDetails = { onExtractDraftDetails(draft.id) },
                                onExtractDetailsOnDevice = { onExtractDraftDetailsOnDevice(draft.id) },
                                onResumeEnrichment = { onResumeEnrichment(draft, it) },
                                onCancelSearch = { onCancelDraftSearch(draft.id) },
                            )
                        }
                        error?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    when (val result = onAcceptAndScanNext(draft.id)) {
                                        is BottleIntakeCommitResult.Accepted, is BottleIntakeCommitResult.AlreadyAccepted -> error = null
                                        is BottleIntakeCommitResult.Invalid -> error = result.reason
                                        else -> error = "This bottle could not be accepted. It is still in your draft queue."
                                    }
                                },
                                enabled = draft.ingredientName.isNotBlank() && draft.ingredientName != "Unidentified bottle" &&
                                    !webState.searching && webState.fetchingUrl == null && !webState.extracting && webState.automaticStage == null,
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("accept-scanned-bottle-${draft.id}"),
                            ) { Text(if (hasAnotherReadyBottle) "Accept & review next" else "Accept & scan next") }
                            OutlinedButton(
                                onClick = { onRouteChange(BottleIntakeRoute.SCANNING) },
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("keep-bottle-review-draft"),
                            ) { Text("Keep draft & return to scanner") }
                        }
                    }
                }
            }

            BottleIntakeRoute.QUEUE -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("bottle-intake-scroll").padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${reviewedCount} reviewed · ${drafts.size} draft bottles", style = MaterialTheme.typography.bodyLarge)
                drafts.forEach { draft ->
                    val job = pendingEnrichmentJobs[draft.id]
                    val status = when (job?.status) {
                        BottleEnrichmentStatus.CAPTURE_REQUIRED -> "Photo needed"
                        BottleEnrichmentStatus.QUEUED -> "Pending"
                        BottleEnrichmentStatus.RUNNING -> "Running"
                        BottleEnrichmentStatus.PAUSED -> "Paused"
                        BottleEnrichmentStatus.READY_FOR_REVIEW -> "Ready"
                        BottleEnrichmentStatus.NEEDS_ATTENTION -> "Needs help"
                        BottleEnrichmentStatus.REVIEWED -> "Reviewed"
                        BottleEnrichmentStatus.CANCELLED -> "Canceled"
                        null -> "Manual"
                    }
                    val labelImage = remember(draft.id, draft.photoData) {
                        draft.photoData?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    }
                    Card(
                        onClick = { onReviewBottle(draft.id) },
                        modifier = Modifier.fillMaxWidth().testTag("queue-bottle-${draft.id}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.width(58.dp).height(76.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .testTag("queue-label-photo-frame-${draft.id}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (labelImage != null) {
                                    Image(
                                        bitmap = labelImage,
                                        contentDescription = "Bottle ${draft.position + 1} label photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().testTag("queue-label-photo-${draft.id}"),
                                    )
                                } else {
                                    Text("No label\nphoto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Bottle ${draft.position + 1} · ${draft.productName ?: draft.ingredientName}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "Web · ${providerOutcomeLabel(job, web = true)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("queue-web-outcome-${draft.id}"),
                                )
                                Text(
                                    "AI · ${providerOutcomeLabel(job, web = false)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("queue-ai-outcome-${draft.id}"),
                                )
                                if (labelImage == null) {
                                    TextButton(
                                        onClick = { onCaptureBottleLabel(draft.id) },
                                        modifier = Modifier.testTag("capture-queue-label-${draft.id}"),
                                    ) { Text("Capture label") }
                                }
                            }
                        }
                    }
                }
                if (drafts.isNotEmpty()) Button(onClick = {
                    when (val result = onConfirm()) {
                        is BottleIntakeCommitResult.Invalid -> error = result.reason
                        else -> Unit
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("batch-confirm")) { Text("Save ${drafts.size} bottles to My Bar") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = onResumeScanning, modifier = Modifier.fillMaxWidth()) { Text("Resume scanner") }
            }

            BottleIntakeRoute.PAUSE -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("$reviewedCount reviewed · ${drafts.size} draft bottles", style = MaterialTheme.typography.titleLarge)
                Text("Your drafts and label photos stay on this phone. Queued lookups are paused; already accepted My Bar bottles are unchanged.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("keep-bottle-drafts-exit")) { Text("Keep drafts & exit") }
                OutlinedButton(onClick = onResumeScanning, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Resume") }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("discard-bottle-drafts")) { Text("Discard unsaved drafts") }
            }

            BottleIntakeRoute.MANUAL_ENTRY -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("bottle-intake-scroll").padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Bottle or ingredient name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("batch-manual-name"))
                OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(barcode, { barcode = it; error = null }, label = { Text("Barcode · optional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(brand, { brand = it }, label = { Text("Brand · optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(productName, { productName = it }, label = { Text("Product · optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCaptureLabel, modifier = Modifier.fillMaxWidth()) { Text(if (capturePhoto == null) "Capture label photo · optional" else "Retake label photo") }
                capturePhoto?.let { bytes ->
                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    if (bitmap != null) Image(bitmap, "Captured bottle label", Modifier.fillMaxWidth().heightIn(max = 220.dp))
                    TextButton(onClick = onDiscardCapturedLabel, modifier = Modifier.fillMaxWidth()) { Text("Remove photo") }
                }
                captureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val cleaned = barcode.trim().ifEmpty { null }
                    when {
                        name.isBlank() -> error = "Enter a bottle name."
                        cleaned != null && normalizeGtinForLookup(cleaned) == null -> error = "That barcode is invalid. Correct it or clear it; your details are still here."
                        !onAddManual(name, category, cleaned, brand.trim().ifEmpty { null }, productName.trim().ifEmpty { null }, session.defaultLevel, capturePhoto) -> error = "This bottle could not be saved. Your fields and photo are still here."
                        else -> { name = ""; barcode = ""; brand = ""; productName = ""; error = null; onRouteChange(BottleIntakeRoute.SCANNING) }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("batch-add-manual-bottle")) { Text("Add bottle to draft") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("batch-manual-error")) }
                TextButton(onClick = { onRouteChange(BottleIntakeRoute.SCANNING) }, modifier = Modifier.fillMaxWidth()) { Text("Return to scanner") }
            }
        }
    }
}

@Composable
private fun IntakeCountPill(label: String) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

private fun providerOutcomeLabel(job: BottleEnrichmentJob?, web: Boolean): String {
    val stored = effectiveBottleProviderOutcome(if (web) job?.webOutcome else job?.aiOutcome, job?.status)
    if (stored != null) return when (stored) {
        BottleProviderOutcome.QUEUED -> "Queued"
        BottleProviderOutcome.RUNNING -> "Running"
        BottleProviderOutcome.COMPLETE -> "Complete · result found"
        BottleProviderOutcome.NO_RESULTS -> "Complete · no results"
        BottleProviderOutcome.NO_MATCH -> "Complete · no barcode match"
        BottleProviderOutcome.FAILED -> "Failed"
        BottleProviderOutcome.QUOTA -> "Stopped · quota or rate limit"
        BottleProviderOutcome.AUTHENTICATION_REQUIRED -> "Needs authentication"
        BottleProviderOutcome.SKIPPED_NO_SOURCE -> "Not run · no verified source"
        BottleProviderOutcome.NOT_CONFIGURED -> "Not run · not configured"
        BottleProviderOutcome.DISABLED -> "Off"
        BottleProviderOutcome.PAUSED -> "Paused"
        BottleProviderOutcome.CANCELLED -> "Canceled"
        BottleProviderOutcome.NOT_RECORDED -> "Not recorded"
    }
    val status = job?.status ?: return "Not run · manual bottle"
    return when (status) {
        BottleEnrichmentStatus.CAPTURE_REQUIRED -> "Waiting for label"
        BottleEnrichmentStatus.QUEUED -> "Queued"
        BottleEnrichmentStatus.RUNNING -> when {
            web && job.operation in setOf(BottleEnrichmentOperation.FIRECRAWL_SEARCH, BottleEnrichmentOperation.FIRECRAWL_FETCH) -> "Running"
            !web && job.operation in setOf(BottleEnrichmentOperation.LOCAL_EXTRACTION, BottleEnrichmentOperation.OLLAMA_EXTRACTION) -> "Running"
            else -> "Waiting"
        }
        BottleEnrichmentStatus.PAUSED -> if (
            (web && job.operation in setOf(BottleEnrichmentOperation.FIRECRAWL_SEARCH, BottleEnrichmentOperation.FIRECRAWL_FETCH)) ||
            (!web && job.operation in setOf(BottleEnrichmentOperation.LOCAL_EXTRACTION, BottleEnrichmentOperation.OLLAMA_EXTRACTION))
        ) "Paused" else "Not recorded"
        BottleEnrichmentStatus.READY_FOR_REVIEW,
        BottleEnrichmentStatus.NEEDS_ATTENTION,
        BottleEnrichmentStatus.REVIEWED,
        BottleEnrichmentStatus.CANCELLED -> "Not recorded"
    }
}

@Composable
private fun EmptyIntakeState(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

private data class PhotoImageBounds(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitPhotoBounds(imageWidth: Int, imageHeight: Int, canvasWidth: Float, canvasHeight: Float): PhotoImageBounds {
    val scale = minOf(canvasWidth / imageWidth.coerceAtLeast(1), canvasHeight / imageHeight.coerceAtLeast(1))
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PhotoImageBounds((canvasWidth - width) / 2f, (canvasHeight - height) / 2f, width, height)
}

@Composable
private fun BottleLabelCropEditorDialog(
    originalJpeg: ByteArray,
    suggestedCrop: NormalizedLabelCrop?,
    onKeepOriginal: () -> Unit,
    onApply: (NormalizedLabelCrop, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val source = remember(originalJpeg) { BitmapFactory.decodeByteArray(originalJpeg, 0, originalJpeg.size) }
    var editorState by remember(originalJpeg, suggestedCrop) {
        mutableStateOf(BottleLabelCropEditorState(suggestedCrop ?: NormalizedLabelCrop.FULL_IMAGE))
    }
    val latestEditorState = rememberUpdatedState(editorState)
    val crop = editorState.crop
    val rotation = editorState.rotationDegrees
    val display = remember(source, rotation) {
        if (source == null || rotation == 0) source
        else Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { setRotate(rotation.toFloat()) }, true)
    }
    DisposableEffect(source) {
        onDispose { source?.recycle() }
    }
    DisposableEffect(display) {
        onDispose { if (display != null && display !== source) display.recycle() }
    }

    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val dialogMaxHeight = maxHeight * 0.92f
            Card(
                Modifier.fillMaxWidth().widthIn(max = 560.dp)
                    .heightIn(max = dialogMaxHeight)
                    .testTag("bottle-label-crop-dialog"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = dialogMaxHeight)) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                Text("Adjust label crop", style = MaterialTheme.typography.titleLarge)
                Text("Drag a corner to resize the crop or drag inside it to move. This stays on your phone; the original remains available until you use the label.", style = MaterialTheme.typography.bodySmall)
                if (display != null) {
                    val imageBitmap = remember(display) { display.asImageBitmap() }
                    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                    Canvas(
                        Modifier.fillMaxWidth().height(240.dp).onSizeChanged { canvasSize = it }
                            .pointerInput(display, canvasSize) {
                                var dragHandle = -1
                                var currentEditorState = latestEditorState.value
                                detectDragGestures(
                                    onDragStart = { point ->
                                        currentEditorState = latestEditorState.value
                                        val bounds = fitPhotoBounds(display.width, display.height, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                        val currentCrop = currentEditorState.crop
                                        val corners = listOf(
                                            Offset(bounds.left + currentCrop.left * bounds.width, bounds.top + currentCrop.top * bounds.height),
                                            Offset(bounds.left + currentCrop.right * bounds.width, bounds.top + currentCrop.top * bounds.height),
                                            Offset(bounds.left + currentCrop.right * bounds.width, bounds.top + currentCrop.bottom * bounds.height),
                                            Offset(bounds.left + currentCrop.left * bounds.width, bounds.top + currentCrop.bottom * bounds.height),
                                        )
                                        val nearest = corners.mapIndexed { index, corner -> index to (corner - point).getDistance() }.minByOrNull { it.second }
                                        dragHandle = when {
                                            nearest != null && nearest.second <= 56.dp.toPx() -> nearest.first
                                            point.x in corners[0].x..corners[2].x && point.y in corners[0].y..corners[2].y -> 4
                                            else -> -1
                                        }
                                    },
                                    onDragEnd = { dragHandle = -1 },
                                    onDragCancel = { dragHandle = -1 },
                                ) { change, amount ->
                                    if (dragHandle >= 0) {
                                        val bounds = fitPhotoBounds(display.width, display.height, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                        currentEditorState = currentEditorState.move(dragHandle, amount.x / bounds.width, amount.y / bounds.height)
                                        editorState = currentEditorState
                                        change.consume()
                                    }
                                }
                            },
                    ) {
                        val bounds = fitPhotoBounds(display.width, display.height, size.width, size.height)
                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset(bounds.left.toInt(), bounds.top.toInt()),
                            dstSize = IntSize(bounds.width.toInt(), bounds.height.toInt()),
                        )
                        val cropLeft = bounds.left + crop.left * bounds.width
                        val cropTop = bounds.top + crop.top * bounds.height
                        val cropRight = bounds.left + crop.right * bounds.width
                        val cropBottom = bounds.top + crop.bottom * bounds.height
                        val shade = Color.Black.copy(alpha = 0.56f)
                        drawRect(shade, Offset(bounds.left, bounds.top), Size(bounds.width, cropTop - bounds.top))
                        drawRect(shade, Offset(bounds.left, cropBottom), Size(bounds.width, bounds.top + bounds.height - cropBottom))
                        drawRect(shade, Offset(bounds.left, cropTop), Size(cropLeft - bounds.left, cropBottom - cropTop))
                        drawRect(shade, Offset(cropRight, cropTop), Size(bounds.left + bounds.width - cropRight, cropBottom - cropTop))
                        drawRect(Color.White, Offset(cropLeft, cropTop), Size(cropRight - cropLeft, cropBottom - cropTop), style = Stroke(width = 3.dp.toPx()))
                        listOf(
                            Offset(cropLeft, cropTop), Offset(cropRight, cropTop),
                            Offset(cropRight, cropBottom), Offset(cropLeft, cropBottom),
                        ).forEach { drawCircle(Color.White, radius = 7.dp.toPx(), center = it) }
                    }
                } else {
                    Text("The original photo could not be opened. Retake the photo or keep the unedited file.", color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editorState = editorState.rotateClockwise() }, modifier = Modifier.weight(1f)) { Text("Rotate 90°") }
                    TextButton(onClick = { editorState = editorState.reset(suggestedCrop) }, modifier = Modifier.weight(1f)) { Text("Reset crop and rotation") }
                }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onKeepOriginal(); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Keep original") }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
                Button(
                    onClick = { onApply(crop, rotation) },
                    enabled = display != null,
                    modifier = Modifier.fillMaxWidth().testTag("apply-bottle-label-crop"),
                ) { Text("Use this crop") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottleReviewCard(
    draft: BottleIntakeDraft,
    homes: List<HomeLocation>,
    onUpdate: (BottleIntakeDraft) -> Boolean,
    onUpdateBarcode: (String, String) -> Boolean,
    onFieldEdited: (String, BottleDraftField) -> Unit,
    onValidityChanged: (String, Boolean) -> Unit,
    onRemove: (BottleIntakeDraft) -> Unit,
    webSearchEnabled: Boolean,
    webSearchState: BottleWebSearchState,
    onOpenLookupReport: () -> Unit,
    pendingEnrichmentJob: BottleEnrichmentJob?,
    ollamaCloudEnabled: Boolean,
    localAiEnabled: Boolean,
    onSearch: () -> Unit,
    onFetchSource: (WebSearchHit) -> Unit,
    onExtractDetails: () -> Unit,
    onExtractDetailsOnDevice: () -> Unit,
    onResumeEnrichment: (BottleEnrichmentJob) -> Unit,
    onCancelSearch: () -> Unit,
) {
    var name by remember(draft.id, draft.barcode) { mutableStateOf(draft.ingredientName) }
    var category by remember(draft.id, draft.category) { mutableStateOf(draft.category) }
    var brand by remember(draft.id, draft.barcode) { mutableStateOf(draft.brand.orEmpty()) }
    var productName by remember(draft.id, draft.barcode) { mutableStateOf(draft.productName.orEmpty()) }
    var quantity by remember(draft.id, draft.barcode) { mutableStateOf(draft.quantity.orEmpty()) }
    var abvPercent by remember(draft.id, draft.barcode) { mutableStateOf(draft.abvPercent.orEmpty()) }
    var barcode by remember(draft.id, draft.barcode) { mutableStateOf(draft.barcode.orEmpty()) }
    var barcodeValidationMessage by remember(draft.id) { mutableStateOf<String?>(null) }
    var nameValidationMessage by remember(draft.id, draft.barcode) {
        mutableStateOf<String?>(if (draft.ingredientName.isBlank()) "Enter a bottle name before accepting this bottle." else null)
    }
    var detailsExpanded by remember(draft.id) { mutableStateOf(false) }
    var evidenceExpanded by remember(draft.id) { mutableStateOf(false) }
    var homeMenu by remember(draft.id) { mutableStateOf(false) }
    var levelMenu by remember(draft.id) { mutableStateOf(false) }

    LaunchedEffect(
        draft.id,
        webSearchState.extractedSuggestions,
        webSearchState.automatedScan,
        webSearchState.barcodeVerifiedInPage,
        webSearchState.userEditedFields,
    ) {
        val suggestions = webSearchState.extractedSuggestions
        if (webSearchState.automatedScan && webSearchState.barcodeVerifiedInPage && suggestions != null) {
            val merged = mergeBottleSuggestionFields(
                current = BottleDraftSuggestionFields(name, productName, brand, quantity, abvPercent),
                suggestedName = suggestions.facts.firstOrNull { it.field == BottleFactField.PRODUCT_NAME }?.value?.trim().orEmpty(),
                suggestedBrand = suggestions.facts.firstOrNull { it.field == BottleFactField.BRAND }?.value?.trim().orEmpty(),
                editedFields = webSearchState.userEditedFields,
                suggestedQuantity = suggestions.facts.firstOrNull { it.field == BottleFactField.QUANTITY }?.value.orEmpty(),
                suggestedAbvPercent = suggestions.facts.firstOrNull { it.field == BottleFactField.ABV_PERCENT }?.value.orEmpty(),
            )
            val nextName = merged.ingredientName
            val nextProductName = merged.productName
            val nextBrand = merged.brand
            if (nextName != name || nextProductName != productName || nextBrand != brand || merged.quantity != quantity || merged.abvPercent != abvPercent) {
                val saved = onUpdate(draft.copy(
                    ingredientName = nextName,
                    productName = nextProductName.ifBlank { null },
                    brand = nextBrand.ifBlank { null },
                    quantity = merged.quantity.ifBlank { null },
                    abvPercent = merged.abvPercent.ifBlank { null },
                ))
                if (saved) {
                    name = nextName
                    productName = nextProductName
                    brand = nextBrand
                    quantity = merged.quantity
                    abvPercent = merged.abvPercent
                    nameValidationMessage = null
                    onValidityChanged(draft.id, nextName.isNotBlank())
                } else {
                    nameValidationMessage = "Suggestions could not be copied into this local draft. Your bottle is still here; edit it manually."
                }
            }
        }
    }

    val labelPhoto = remember(draft.photoData) {
        draft.photoData?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val nameField: @Composable () -> Unit = {
        OutlinedTextField(
            value = name,
            onValueChange = { value ->
                name = value
                onFieldEdited(draft.id, BottleDraftField.INGREDIENT_NAME)
                val saved = onUpdate(draft.copy(
                    ingredientName = value,
                    brand = brand.trim().ifEmpty { null },
                    productName = productName.trim().ifEmpty { null },
                ))
                nameValidationMessage = when {
                    !saved -> "This bottle name could not be saved. Try again."
                    value.isBlank() -> "Enter a bottle name before accepting this bottle."
                    else -> null
                }
                onValidityChanged(draft.id, saved && value.isNotBlank())
            },
            label = { Text("Bottle name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-name-${draft.position}"),
        )
    }
    val categoryField: @Composable () -> Unit = {
        OutlinedTextField(
            value = category,
            onValueChange = { value ->
                category = value
                onUpdate(draft.copy(category = value.trim().ifEmpty { "Spirit" }))
            },
            label = { Text("Category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-category-${draft.position}"),
        )
    }
    val statusText = when (pendingEnrichmentJob?.status) {
        BottleEnrichmentStatus.CAPTURE_REQUIRED -> "Label photo needed"
        BottleEnrichmentStatus.QUEUED -> "Lookup queued"
        BottleEnrichmentStatus.RUNNING -> "Lookup running in background"
        BottleEnrichmentStatus.PAUSED -> "Lookup paused"
        BottleEnrichmentStatus.READY_FOR_REVIEW -> "Suggestions ready to review"
        BottleEnrichmentStatus.NEEDS_ATTENTION -> "Manual review needed"
        BottleEnrichmentStatus.REVIEWED -> "Reviewed"
        BottleEnrichmentStatus.CANCELLED -> "Lookup canceled"
        null -> webSearchState.message ?: "Review and edit before accepting"
    }
    val sourceSummary = when {
        webSearchState.page != null -> "Source: ${runCatching { Uri.parse(webSearchState.page.sourceUrl).host }.getOrNull() ?: "selected page"}"
        webSearchState.hits.isNotEmpty() -> "${webSearchState.hits.size} possible sources found"
        webSearchState.searching -> "Searching enabled providers"
        else -> statusText
    }
    // Manual lookups may offer explicit per-field application only when the extraction result
    // carries the same GTIN and a source passage that ties the product identity to that GTIN.
    // Merely having a product name/brand suggestion on a page is not enough to apply it.
    val canApplyManualSuggestions = remember(draft.barcode, webSearchState.extractedSuggestions, webSearchState.barcodeVerifiedInPage, webSearchState.barcodeVerifiedByStructuredProduct) {
        val barcode = draft.barcode?.let(::normalizeGtinForLookup)
        val suggestions = webSearchState.extractedSuggestions
        barcode != null && suggestions?.targetBarcode?.let(::normalizeGtinForLookup) == barcode &&
            suggestions.identityEvidence?.let { sourceMentionsScannedBarcode(draft.barcode.orEmpty(), it) } == true &&
            (webSearchState.barcodeVerifiedInPage || webSearchState.barcodeVerifiedByStructuredProduct)
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("bottle-review-card-${draft.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.width(112.dp).height(144.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("review-label-photo-frame-${draft.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (labelPhoto != null) {
                        Image(
                            bitmap = labelPhoto,
                            contentDescription = "${draft.ingredientName} label photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().testTag("review-label-photo-${draft.id}"),
                        )
                    } else {
                        Text("Label photo\nnot captured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    nameField()
                    categoryField()
                }
            }
            nameValidationMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bottle-intake-draft-error-${draft.position}"))
            }
            if (draft.barcode != null || webSearchState.automatedScan) {
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { value -> barcode = value.filter(Char::isDigit); barcodeValidationMessage = null },
                    label = { Text("Scanned UPC / EAN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bottle-correct-barcode-${draft.id}"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            when (validateBottleBarcodeCorrection(draft.barcode, barcode)) {
                                BottleBarcodeCorrection.INVALID -> barcodeValidationMessage = "Enter a valid UPC / EAN. The current barcode and lookup stay unchanged."
                                BottleBarcodeCorrection.UNCHANGED -> barcodeValidationMessage = "This is already the saved barcode."
                                BottleBarcodeCorrection.CHANGED -> if (!onUpdateBarcode(draft.id, barcode)) {
                                    barcodeValidationMessage = "The barcode could not be updated. Your draft remains unchanged."
                                } else {
                                    barcodeValidationMessage = "Barcode changed. Previous lookup evidence was cleared; retry only when you choose."
                                }
                            }
                        },
                        modifier = Modifier.testTag("save-corrected-barcode-${draft.id}"),
                    ) { Text("Update barcode") }
                    Text("Correct a misread scan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                barcodeValidationMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("barcode-correction-message-${draft.id}"))
                }
            }
            Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("bottle-queue-status-${draft.id}"))

            OutlinedTextField(
                value = quantity,
                onValueChange = { value ->
                    quantity = value
                    onFieldEdited(draft.id, BottleDraftField.QUANTITY)
                    val saved = onUpdate(draft.copy(quantity = value.trim().ifEmpty { null }, quantityEdited = true))
                    onValidityChanged(draft.id, name.isNotBlank() && saved)
                },
                label = { Text("Bottle size") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-quantity-${draft.position}"),
            )
            OutlinedTextField(
                value = abvPercent,
                onValueChange = { value ->
                    abvPercent = value
                    onFieldEdited(draft.id, BottleDraftField.ABV_PERCENT)
                    val saved = onUpdate(draft.copy(abvPercent = value.trim().ifEmpty { null }, abvPercentEdited = true))
                    onValidityChanged(draft.id, name.isNotBlank() && saved)
                },
                label = { Text("ABV %") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-abv-${draft.position}"),
            )

            TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.testTag("review-more-details")) {
                Text(if (detailsExpanded) "Fewer bottle details" else "More bottle details")
            }
            if (detailsExpanded) {
                OutlinedTextField(
                    value = brand,
                    onValueChange = { value ->
                        brand = value
                        onFieldEdited(draft.id, BottleDraftField.BRAND)
                        onUpdate(draft.copy(brand = value.trim().ifEmpty { null }))
                    },
                    label = { Text("Brand · optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-brand-${draft.position}"),
                )
                OutlinedTextField(
                    value = productName,
                    onValueChange = { value ->
                        productName = value
                        onFieldEdited(draft.id, BottleDraftField.PRODUCT_NAME)
                        onUpdate(draft.copy(productName = value.trim().ifEmpty { null }))
                    },
                    label = { Text("Product · optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-product-${draft.position}"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { homeMenu = true }) { Text(homes.firstOrNull { it.id == draft.locationId }?.name ?: "Home") }
                        DropdownMenu(expanded = homeMenu, onDismissRequest = { homeMenu = false }) {
                            homes.forEach { home -> DropdownMenuItem(text = { Text(home.name) }, onClick = { onUpdate(draft.copy(locationId = home.id)); homeMenu = false }) }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { levelMenu = true }) { Text(draft.level.label) }
                        DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                            InventoryLevel.entries.forEach { level -> DropdownMenuItem(text = { Text(level.label) }, onClick = { onUpdate(draft.copy(level = level)); levelMenu = false }) }
                        }
                    }
                    TextButton(onClick = { onRemove(draft) }, modifier = Modifier.testTag("remove-reviewed-bottle-${draft.id}")) { Text("Remove bottle") }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().testTag("review-evidence-card-${draft.id}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    TextButton(onClick = { evidenceExpanded = !evidenceExpanded }, modifier = Modifier.fillMaxWidth().testTag("review-evidence-toggle")) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Web · AI · ${if (evidenceExpanded) "Hide evidence" else "View evidence"}", style = MaterialTheme.typography.titleSmall)
                            Text(sourceSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    if (evidenceExpanded) {
                        if (webSearchState.localMatch) Text("Matched an existing product in this household before online lookup.", style = MaterialTheme.typography.bodySmall)
                        webSearchState.automaticStage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        webSearchState.openFoodFactsResult?.let { result ->
                            when (result) {
                                is OpenFoodFactsLookupResult.Found -> OpenFoodFactsSuggestionCard(result.suggestion, result.fromCache)
                                OpenFoodFactsLookupResult.NotFound -> Text("Open Food Facts has no matching record for this barcode.")
                                OpenFoodFactsLookupResult.DifferentProductType -> Text("Open Food Facts lists this barcode as another product type.")
                                OpenFoodFactsLookupResult.InvalidBarcode -> Text("The scanned barcode was not accepted as UPC/EAN.")
                                OpenFoodFactsLookupResult.LocallyThrottled -> Text("Open Food Facts is locally rate limited.")
                                OpenFoodFactsLookupResult.ProviderRateLimited -> Text("Open Food Facts is rate limiting this network.")
                                OpenFoodFactsLookupResult.Unavailable -> Text("Open Food Facts is unavailable.")
                                OpenFoodFactsLookupResult.InvalidResponse -> Text("Open Food Facts returned data this app could not validate.")
                            }
                        }
                        if (!webSearchEnabled) Text("Web search is off or not configured. Continue manually or enable it in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = onSearch,
                                enabled = webSearchEnabled && !webSearchState.searching && webSearchState.fetchingUrl == null && webSearchState.automaticStage == null,
                                modifier = Modifier.testTag("search-bottle-${draft.id}"),
                            ) { Text(if (webSearchState.searching) "Searching…" else "Search web") }
                            if (webSearchState.searching || webSearchState.fetchingUrl != null || webSearchState.extracting || webSearchState.automaticStage != null || pendingEnrichmentJob?.status == BottleEnrichmentStatus.RUNNING) {
                                TextButton(onClick = onCancelSearch, modifier = Modifier.testTag("cancel-bottle-enrichment-${draft.id}")) { Text("Cancel") }
                            }
                        }
                        pendingEnrichmentJob?.takeIf { it.status in setOf(BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.NEEDS_ATTENTION, BottleEnrichmentStatus.CANCELLED) }?.let { job ->
                            OutlinedButton(onClick = { onResumeEnrichment(job) }, modifier = Modifier.testTag("resume-enrichment-${draft.id}")) {
                                Text(if (job.status == BottleEnrichmentStatus.PAUSED && job.operation == BottleEnrichmentOperation.FIRECRAWL_SEARCH) "Resume lookup" else "Retry lookup")
                            }
                        }
                        webSearchState.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("review-evidence-message-${draft.id}"))
                        }
                        webSearchState.hits.forEach { hit ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(hit.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                hit.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                OutlinedButton(onClick = { onFetchSource(hit) }, enabled = webSearchState.fetchingUrl == null) { Text("Fetch this source") }
                            }
                        }
                        webSearchState.page?.let { page ->
                            Text(page.title ?: "Selected source", style = MaterialTheme.typography.titleSmall)
                            Text(page.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                when {
                                    webSearchState.barcodeVerifiedByStructuredProduct -> "Structured product data links this barcode to the product. Check every suggested value before accepting."
                                    webSearchState.barcodeVerifiedInPage -> "This page contains the scanned barcode. Review the quoted evidence before accepting."
                                    else -> "This page does not show the scanned barcode. Do not use it to identify this bottle."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (page.markdown.isNotBlank()) Text(page.markdown.take(1_200), style = MaterialTheme.typography.bodySmall)
                            unresolvedBottleSourceFields(page.markdown).forEach { field ->
                                Text("${field.label} has conflicting values on this page. No value was selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onExtractDetails,
                                    enabled = page.markdown.isNotBlank() && ollamaCloudEnabled && !webSearchState.extracting && (!webSearchState.automatedScan || webSearchState.barcodeVerifiedInPage),
                                    modifier = Modifier.testTag("extract-bottle-${draft.id}"),
                                ) { Text(if (webSearchState.extracting && webSearchState.extractingWith != "On-device Qwen3") "Extracting…" else "Suggest with Ollama Cloud") }
                                OutlinedButton(
                                    onClick = onExtractDetailsOnDevice,
                                    enabled = page.markdown.isNotBlank() && localAiEnabled && !webSearchState.extracting && (!webSearchState.automatedScan || webSearchState.barcodeVerifiedInPage),
                                    modifier = Modifier.testTag("extract-bottle-local-${draft.id}"),
                                ) { Text(if (webSearchState.extractingWith == "On-device Qwen3" && webSearchState.extracting) "Running locally…" else "Suggest on device") }
                            }
                            if (webSearchState.extracting) Text("Model output is only a suggestion. Check the evidence and edit before accepting.", style = MaterialTheme.typography.bodySmall)
                        }
                        webSearchState.extractedSuggestions?.let { suggestions ->
                            Text("Suggestions · ${suggestions.provider} · ${suggestions.model}", style = MaterialTheme.typography.titleSmall)
                            Text(suggestions.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            suggestions.unresolvedFields.forEach { field ->
                                Text("${field.label} is unclear in this source. No value was selected; check it manually.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            suggestions.facts.forEach { fact ->
                                Text("${fact.field.label}: ${fact.value}", style = MaterialTheme.typography.bodyMedium)
                                Text("Evidence: “${fact.quote}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!webSearchState.automatedScan && canApplyManualSuggestions) {
                                    TextButton(
                                        onClick = {
                                            when (fact.field) {
                                                BottleFactField.PRODUCT_NAME -> {
                                                    val acceptedName = fact.value.trim()
                                                    val ingredient = if (name == "Unidentified bottle") acceptedName else name
                                                    if (acceptedName.isNotEmpty() && onUpdate(draft.copy(
                                                            ingredientName = ingredient,
                                                            productName = acceptedName,
                                                            brand = brand.trim().ifEmpty { null },
                                                        ))) {
                                                        name = ingredient
                                                        productName = acceptedName
                                                        onFieldEdited(draft.id, BottleDraftField.PRODUCT_NAME)
                                                        if (ingredient != draft.ingredientName) onFieldEdited(draft.id, BottleDraftField.INGREDIENT_NAME)
                                                        nameValidationMessage = null
                                                        onValidityChanged(draft.id, ingredient.isNotBlank())
                                                    }
                                                }
                                                BottleFactField.BRAND -> {
                                                    val acceptedBrand = fact.value.trim()
                                                    if (acceptedBrand.isNotEmpty() && onUpdate(draft.copy(brand = acceptedBrand))) {
                                                        brand = acceptedBrand
                                                        onFieldEdited(draft.id, BottleDraftField.BRAND)
                                                    }
                                                }
                                                BottleFactField.QUANTITY -> {
                                                    val acceptedQuantity = fact.value.trim()
                                                    if (acceptedQuantity.isNotEmpty() && onUpdate(draft.copy(quantity = acceptedQuantity, quantityEdited = true))) {
                                                        quantity = acceptedQuantity
                                                        onFieldEdited(draft.id, BottleDraftField.QUANTITY)
                                                    }
                                                }
                                                BottleFactField.ABV_PERCENT -> {
                                                    val acceptedAbv = fact.value.trim()
                                                    if (acceptedAbv.isNotEmpty() && onUpdate(draft.copy(abvPercent = acceptedAbv, abvPercentEdited = true))) {
                                                        abvPercent = acceptedAbv
                                                        onFieldEdited(draft.id, BottleDraftField.ABV_PERCENT)
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("apply-manual-suggestion-${fact.field.wireValue}-${draft.id}"),
                                    ) { Text("Use ${fact.field.label.lowercase()} suggestion") }
                                }
                            }
                            if (!webSearchState.automatedScan && !canApplyManualSuggestions && suggestions.facts.isNotEmpty()) {
                                Text("These suggestions are not linked to this bottle’s barcode. Copy a value manually only if you can verify the product.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (webSearchState.automatedScan) {
                            TextButton(onClick = onOpenLookupReport, modifier = Modifier.testTag("open-bottle-lookup-report")) { Text("View diagnostics for this bottle") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottleDraftCard(
    draft: BottleIntakeDraft,
    homes: List<HomeLocation>,
    onUpdate: (BottleIntakeDraft) -> Boolean,
    onFieldEdited: (String, BottleDraftField) -> Unit,
    onValidityChanged: (String, Boolean) -> Unit,
    onRemove: (BottleIntakeDraft) -> Unit,
    webSearchEnabled: Boolean,
    webSearchState: BottleWebSearchState,
    onOpenLookupReport: () -> Unit,
    pendingEnrichmentJob: BottleEnrichmentJob?,
    ollamaCloudEnabled: Boolean,
    localAiEnabled: Boolean,
    activeReview: Boolean,
    onReviewBottle: () -> Unit,
    onAcceptAndScanNext: () -> Unit,
    onSearch: () -> Unit,
    onFetchSource: (WebSearchHit) -> Unit,
    onExtractDetails: () -> Unit,
    onExtractDetailsOnDevice: () -> Unit,
    onResumeEnrichment: (BottleEnrichmentJob) -> Unit,
    onCancelSearch: () -> Unit,
) {
    var name by remember(draft.id) { mutableStateOf(draft.ingredientName) }
    var category by remember(draft.id, draft.category) { mutableStateOf(draft.category) }
    var brand by remember(draft.id) { mutableStateOf(draft.brand.orEmpty()) }
    var productName by remember(draft.id) { mutableStateOf(draft.productName.orEmpty()) }
    var quantity by remember(draft.id) { mutableStateOf(draft.quantity.orEmpty()) }
    var abvPercent by remember(draft.id) { mutableStateOf(draft.abvPercent.orEmpty()) }
    var nameValidationMessage by remember(draft.id) {
        mutableStateOf<String?>(if (draft.ingredientName.isBlank()) "Enter a bottle name before confirming this batch." else null)
    }
    var homeMenu by remember(draft.id) { mutableStateOf(false) }
    var levelMenu by remember(draft.id) { mutableStateOf(false) }
    LaunchedEffect(draft.id, activeReview, webSearchState.extractedSuggestions, webSearchState.automatedScan, webSearchState.barcodeVerifiedInPage, webSearchState.userEditedFields) {
        val suggestions = webSearchState.extractedSuggestions
        if (activeReview && webSearchState.automatedScan && webSearchState.barcodeVerifiedInPage && suggestions != null) {
            val suggestedName = suggestions.facts.firstOrNull { it.field == BottleFactField.PRODUCT_NAME }?.value?.trim().orEmpty()
            val suggestedBrand = suggestions.facts.firstOrNull { it.field == BottleFactField.BRAND }?.value?.trim().orEmpty()
            val merged = mergeBottleSuggestionFields(
                current = BottleDraftSuggestionFields(name, productName, brand, quantity, abvPercent),
                suggestedName = suggestedName,
                suggestedBrand = suggestedBrand,
                editedFields = webSearchState.userEditedFields,
                suggestedQuantity = suggestions.facts.firstOrNull { it.field == BottleFactField.QUANTITY }?.value.orEmpty(),
                suggestedAbvPercent = suggestions.facts.firstOrNull { it.field == BottleFactField.ABV_PERCENT }?.value.orEmpty(),
            )
            val nextName = merged.ingredientName
            val nextProductName = merged.productName
            val nextBrand = merged.brand
            if (nextName != name || nextProductName != productName || nextBrand != brand || merged.quantity != quantity || merged.abvPercent != abvPercent) {
                val saved = onUpdate(draft.copy(
                    ingredientName = nextName,
                    productName = nextProductName.ifBlank { null },
                    brand = nextBrand.ifBlank { null },
                    quantity = merged.quantity.ifBlank { null },
                    abvPercent = merged.abvPercent.ifBlank { null },
                ))
                if (saved) {
                    name = nextName
                    productName = nextProductName
                    brand = nextBrand
                    quantity = merged.quantity
                    abvPercent = merged.abvPercent
                    nameValidationMessage = null
                    onValidityChanged(draft.id, nextName.isNotBlank())
                } else {
                    nameValidationMessage = "The suggestions could not be copied into this local draft. Your bottle is still here; edit it manually."
                }
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val nameField: @Composable () -> Unit = {
                OutlinedTextField(
                    name,
                    { value ->
                    name = value
                    onFieldEdited(draft.id, BottleDraftField.INGREDIENT_NAME)
                    if (value.isBlank()) {
                        val saved = onUpdate(draft.copy(
                            ingredientName = value,
                            brand = brand.trim().ifEmpty { null },
                            productName = productName.trim().ifEmpty { null },
                        ))
                        nameValidationMessage = if (saved) {
                            "Enter a bottle name before confirming this batch."
                        } else {
                            "This name could not be saved to the local draft. Try again."
                        }
                        onValidityChanged(draft.id, false)
                    } else {
                        val saved = onUpdate(draft.copy(ingredientName = value, brand = brand.trim().ifEmpty { null }, productName = productName.trim().ifEmpty { null }))
                        nameValidationMessage = if (saved) null else "This name could not be saved to the local draft. Try again."
                        onValidityChanged(draft.id, saved)
                    }
                    },
                    label = { Text("Bottle ${draft.position + 1}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-name-${draft.position}"),
                )
            }
            val reviewPhoto = remember(draft.photoData) {
                draft.photoData?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }
            if (activeReview && reviewPhoto != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Image(reviewPhoto, contentDescription = "${draft.ingredientName} label photo", modifier = Modifier.width(112.dp).height(144.dp).testTag("review-label-photo-${draft.id}"))
                    Box(Modifier.weight(1f)) { nameField() }
                }
            } else nameField()
            nameValidationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("bottle-intake-draft-error-${draft.position}")) }
            draft.barcode?.let { Text("Barcode: $it", style = MaterialTheme.typography.bodySmall) }
            pendingEnrichmentJob?.let { job ->
                val queueStatus = when (job.status) {
                    BottleEnrichmentStatus.CAPTURE_REQUIRED -> "Waiting for label capture"
                    BottleEnrichmentStatus.QUEUED -> "Waiting in lookup queue"
                    BottleEnrichmentStatus.RUNNING -> "Processing in the background · you can leave intake"
                    BottleEnrichmentStatus.PAUSED -> "Paused · resume explicitly"
                    BottleEnrichmentStatus.READY_FOR_REVIEW -> "Suggestions ready · review when convenient"
                    BottleEnrichmentStatus.NEEDS_ATTENTION -> "Lookup finished · manual review needed"
                    BottleEnrichmentStatus.REVIEWED -> "Reviewed · waiting for batch confirmation"
                    BottleEnrichmentStatus.CANCELLED -> "Lookup canceled"
                }
                Text(queueStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("bottle-queue-status-${draft.id}"))
                if (!activeReview && job.status in setOf(BottleEnrichmentStatus.READY_FOR_REVIEW, BottleEnrichmentStatus.NEEDS_ATTENTION)) {
                    OutlinedButton(onClick = onReviewBottle, modifier = Modifier.testTag("review-bottle-${draft.id}")) { Text("Review this bottle") }
                }
            }
            OutlinedTextField(brand, { value ->
                brand = value
                onFieldEdited(draft.id, BottleDraftField.BRAND)
                val saved = onUpdate(draft.copy(ingredientName = name, brand = value.trim().ifEmpty { null }, productName = productName.trim().ifEmpty { null }))
                onValidityChanged(draft.id, name.isNotBlank() && saved)
            }, label = { Text("Brand · optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-brand-${draft.position}"))
            OutlinedTextField(productName, { value ->
                productName = value
                onFieldEdited(draft.id, BottleDraftField.PRODUCT_NAME)
                val saved = onUpdate(draft.copy(ingredientName = name, brand = brand.trim().ifEmpty { null }, productName = value.trim().ifEmpty { null }))
                onValidityChanged(draft.id, name.isNotBlank() && saved)
            }, label = { Text("Product · optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-product-${draft.position}"))
            if (activeReview) OutlinedTextField(
                category,
                { value ->
                    category = value
                    onUpdate(draft.copy(category = value.trim().ifEmpty { "Spirit" }))
                },
                label = { Text("Category") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bottle-intake-draft-category-${draft.position}"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    quantity,
                    { value ->
                        quantity = value
                        onFieldEdited(draft.id, BottleDraftField.QUANTITY)
                        val saved = onUpdate(draft.copy(quantity = value.trim().ifEmpty { null }, quantityEdited = true))
                        onValidityChanged(draft.id, name.isNotBlank() && saved)
                    },
                    label = { Text("Bottle size") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("bottle-intake-draft-quantity-${draft.position}"),
                )
                OutlinedTextField(
                    abvPercent,
                    { value ->
                        abvPercent = value
                        onFieldEdited(draft.id, BottleDraftField.ABV_PERCENT)
                        val saved = onUpdate(draft.copy(abvPercent = value.trim().ifEmpty { null }, abvPercentEdited = true))
                        onValidityChanged(draft.id, name.isNotBlank() && saved)
                    },
                    label = { Text("ABV %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("bottle-intake-draft-abv-${draft.position}"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { homeMenu = true }) { Text(homes.firstOrNull { it.id == draft.locationId }?.name ?: "Home") }
                DropdownMenu(expanded = homeMenu, onDismissRequest = { homeMenu = false }) {
                    homes.forEach { home -> DropdownMenuItem(text = { Text(home.name) }, onClick = { onUpdate(draft.copy(locationId = home.id)); homeMenu = false }) }
                }
                OutlinedButton(onClick = { levelMenu = true }) { Text(draft.level.label) }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    InventoryLevel.entries.forEach { level -> DropdownMenuItem(text = { Text(level.label) }, onClick = { onUpdate(draft.copy(level = level)); levelMenu = false }) }
                }
                TextButton(onClick = { onValidityChanged(draft.id, true); onRemove(draft) }) { Text("Remove") }
            }
            if (!activeReview) draft.photoData?.let { bytes ->
                val photo = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                if (photo != null) Image(photo, contentDescription = "${draft.ingredientName} label photo", modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp))
            }
            Text("Product suggestions", style = MaterialTheme.typography.titleSmall)
            if (webSearchState.localMatch) Text("Matched an existing product in this household before any online lookup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            webSearchState.automaticStage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (webSearchState.automatedScan) {
                webSearchState.openFoodFactsResult?.let { result ->
                    when (result) {
                        is OpenFoodFactsLookupResult.Found -> OpenFoodFactsSuggestionCard(result.suggestion, result.fromCache)
                        OpenFoodFactsLookupResult.NotFound -> Text("Open Food Facts has no matching record for this barcode.")
                        OpenFoodFactsLookupResult.DifferentProductType -> Text("Open Food Facts lists this barcode as another product type. Review sources carefully.")
                        OpenFoodFactsLookupResult.InvalidBarcode -> Text("The scanned code was not accepted as a UPC/EAN by Open Food Facts.")
                        OpenFoodFactsLookupResult.LocallyThrottled -> Text("Open Food Facts is locally rate limited. You can still use the other enabled sources.")
                        OpenFoodFactsLookupResult.ProviderRateLimited -> Text("Open Food Facts is rate limiting this network. No retry was made.")
                        OpenFoodFactsLookupResult.Unavailable -> Text("Open Food Facts is unavailable. You can still review other enabled sources.")
                        OpenFoodFactsLookupResult.InvalidResponse -> Text("Open Food Facts returned data this app could not validate.")
                    }
                }
            }
            Text("An enabled Firecrawl search sends this barcode and normal network metadata. The app checks up to three result pages for a matching barcode; each page fetch uses your plan quota. No home, pantry, member details, label photo, or tasting note is sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val durableWorkActive = pendingEnrichmentJob?.status in setOf(BottleEnrichmentStatus.QUEUED, BottleEnrichmentStatus.RUNNING)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch, enabled = webSearchEnabled && !durableWorkActive && !webSearchState.searching && webSearchState.fetchingUrl == null && webSearchState.automaticStage == null, modifier = Modifier.testTag("search-bottle-${draft.id}")) {
                    Text(if (webSearchState.searching) "Searching…" else "Search web")
                }
                if (durableWorkActive || webSearchState.searching || webSearchState.fetchingUrl != null || webSearchState.extracting || webSearchState.automaticStage != null) TextButton(onClick = onCancelSearch, modifier = Modifier.testTag("cancel-bottle-enrichment-${draft.id}")) { Text("Cancel") }
            }
            pendingEnrichmentJob?.takeIf { it.status in setOf(BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.NEEDS_ATTENTION, BottleEnrichmentStatus.CANCELLED) }?.let { job ->
                Text(
                    when (job.operation) {
                        BottleEnrichmentOperation.FIRECRAWL_SEARCH -> "A previous Firecrawl search was interrupted. It will not restart automatically."
                        BottleEnrichmentOperation.FIRECRAWL_FETCH -> "A selected source fetch was interrupted. It will not restart automatically."
                        BottleEnrichmentOperation.OLLAMA_EXTRACTION -> "An Ollama Cloud extraction was interrupted. Source text and model output were not saved."
                        BottleEnrichmentOperation.LOCAL_EXTRACTION -> "An on-device extraction was interrupted. Source text and model output were not saved."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onResumeEnrichment(job) }, modifier = Modifier.testTag("resume-enrichment-${draft.id}")) {
                    Text(if (job.status == BottleEnrichmentStatus.PAUSED) {
                        if (job.operation == BottleEnrichmentOperation.FIRECRAWL_SEARCH) "Resume lookup" else "Re-fetch selected source"
                    } else "Retry lookup")
                }
            }
            webSearchState.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (webSearchState.automatedScan) {
                TextButton(onClick = onOpenLookupReport, modifier = Modifier.testTag("open-bottle-lookup-report")) {
                    Text("View diagnostics for this bottle")
                }
            }
            webSearchState.hits.forEach { hit ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(hit.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    hit.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { onFetchSource(hit) }, enabled = webSearchState.fetchingUrl == null) { Text("Fetch this source text") }
                }
            }
            webSearchState.fetchingUrl?.let {
                Text(webSearchState.automaticStage ?: "Fetching selected source…", style = MaterialTheme.typography.bodySmall)
            }
            webSearchState.page?.let { page ->
                Text(page.title ?: "Selected source", style = MaterialTheme.typography.titleSmall)
                Text(page.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (webSearchState.barcodeVerifiedByStructuredProduct && webSearchState.automatedScan) "Structured product data on this page links the scanned barcode to the product title. Check every suggested value before accepting."
                    else if (webSearchState.barcodeVerifiedByStructuredProduct) "Structured product data on this page links the barcode to the product title. Suggestions remain separate until you choose to apply them."
                    else if (webSearchState.barcodeVerifiedInPage && webSearchState.automatedScan) "This page text contains the scanned barcode. Evidence-backed suggestions are filled into this review only; edit or reject them before accepting."
                    else if (webSearchState.barcodeVerifiedInPage) "This page text contains the scanned barcode. Suggestions remain separate until you choose to apply them."
                    else "Source page text is untrusted and does not show the scanned barcode. Do not use it to identify this bottle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(page.markdown.take(1_200), style = MaterialTheme.typography.bodySmall)
                if (page.markdown.isBlank()) {
                    Text("The source page text was not retained after the app closed. Search and fetch it again only if you want more suggestions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                unresolvedBottleSourceFields(page.markdown).forEach { field ->
                    Text("${field.label} appears with conflicting values on this page. No value was selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = onExtractDetails,
                    enabled = page.markdown.isNotBlank() && ollamaCloudEnabled && !webSearchState.extracting && (!webSearchState.automatedScan || webSearchState.barcodeVerifiedInPage),
                    modifier = Modifier.testTag("extract-bottle-${draft.id}"),
                ) { Text(if (webSearchState.extracting) "Extracting…" else "Suggest details with Ollama Cloud") }
                if (!ollamaCloudEnabled) Text("Enable Ollama Cloud extraction in More to use this option.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = onExtractDetailsOnDevice,
                    enabled = page.markdown.isNotBlank() && localAiEnabled && !webSearchState.extracting && (!webSearchState.automatedScan || webSearchState.barcodeVerifiedInPage),
                    modifier = Modifier.testTag("extract-bottle-local-${draft.id}"),
                ) { Text(if (webSearchState.extracting && webSearchState.extractingWith == "On-device Qwen3") "Running on this phone…" else "Suggest details on this phone") }
                if (!localAiEnabled) Text("Install the Qwen3 model and enable on-device suggestions in More to use this option.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (webSearchState.extracting) {
                    val provider = webSearchState.extractingWith
                    Text(
                        if (provider == "On-device Qwen3") "Running the selected page text on this phone. It is not sent to an AI service. You can cancel locally; bottle fields are unchanged."
                        else "Sending selected page text to Ollama Cloud after removing links, URLs and control characters. Review the page first; it may still contain personal details. You can cancel locally; a request already sent may still use plan quota. Bottle fields are unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                webSearchState.extractedSuggestions?.let { suggestions ->
                    Text("Suggestions · ${suggestions.provider} · ${suggestions.model}", style = MaterialTheme.typography.titleSmall)
                    Text("Source: ${suggestions.sourceUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        if (webSearchState.automatedScan) "Check each quoted passage. Values are in this uncommitted draft for review; accepting the bottle is the only step that adds it to your bar."
                        else "Check each quoted passage yourself. These suggestions have not changed your bottle draft.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestions.unresolvedFields.forEach { field ->
                        Text("${field.label} is unclear in this source. No value was selected; check it manually.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    suggestions.facts.forEach { fact ->
                        Text("${fact.field.label}: ${fact.value}", style = MaterialTheme.typography.bodyMedium)
                        Text("Evidence: “${fact.quote}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (fact.field) {
                            BottleFactField.PRODUCT_NAME -> if (!webSearchState.automatedScan) OutlinedButton(onClick = {
                                val acceptedName = fact.value.trim()
                                val ingredient = if (name == "Unidentified bottle") acceptedName else name
                                if (acceptedName.isNotEmpty() && onUpdate(draft.copy(ingredientName = ingredient, productName = acceptedName, brand = brand.trim().ifEmpty { null }))) {
                                    name = ingredient
                                    productName = acceptedName
                                    onValidityChanged(draft.id, ingredient.isNotBlank())
                                }
                            }) { Text("Use product suggestion") }
                            BottleFactField.BRAND -> if (!webSearchState.automatedScan) OutlinedButton(onClick = {
                                val acceptedBrand = fact.value.trim()
                                if (acceptedBrand.isNotEmpty() && onUpdate(draft.copy(ingredientName = name, productName = productName.trim().ifEmpty { null }, brand = acceptedBrand))) {
                                    brand = acceptedBrand
                                    onValidityChanged(draft.id, name.isNotBlank())
                                }
                            }) { Text("Use brand suggestion") }
                            BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT -> Text("Check this value before accepting. Bottle size and ABV are saved separately from fullness.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (activeReview) {
                if (name == "Unidentified bottle") Text("Edit the bottle name before accepting this scan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = onAcceptAndScanNext,
                    enabled = name.isNotBlank() && name != "Unidentified bottle" && !webSearchState.searching && webSearchState.fetchingUrl == null && !webSearchState.extracting && webSearchState.automaticStage == null,
                    modifier = Modifier.fillMaxWidth().testTag("accept-scanned-bottle-${draft.id}"),
                ) { Text("Accept bottle details") }
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
    onAddBottles: () -> Unit,
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
            TextButton(onClick = onAddLocation) { Text("＋ Home") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAddIngredient, modifier = Modifier.weight(1f).testTag("inventory-add-ingredient")) { Text("＋ Add one") }
            Button(onClick = onAddBottles, modifier = Modifier.weight(1f).testTag("start-bottle-intake")) { Text("＋ Add bottles") }
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
                items(pantry, key = { it.inventoryId }) { entry ->
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
                    entry.brand?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    entry.productName?.takeIf { it.isNotBlank() && it != entry.name }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    localProductMatch: LocalProductBarcodeMatch?,
    productLookupResult: OpenFoodFactsLookupResult?,
    productLookupInProgress: Boolean,
    productPhotoBytes: ByteArray?,
    productPhotoMessage: String?,
    onBarcodeChanged: (String) -> Unit,
    onLookupProduct: (String) -> Unit,
    onlineLookupEnabled: Boolean,
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
                if (localProductMatch != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Found in your household", style = MaterialTheme.typography.titleSmall)
                            Text("${localProductMatch.ingredientName} · ${localProductMatch.category}")
                            localProductMatch.brand?.let { Text("Brand: $it") }
                            Text("Product: ${localProductMatch.productName}")
                            Text("This match is local. It did not contact Open Food Facts.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "Lookup is optional and starts only when you tap. A saved Open Food Facts result is checked first; on a cache miss the request sends only this barcode and CocktAIls version. Open Food Facts also receives your network IP. No pantry names, home locations, photos, or member details are sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onLookupProduct(barcode) },
                    enabled = onlineLookupEnabled && normalizeGtinForLookup(barcode) != null && !productLookupInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("open-food-facts-lookup"),
                ) { Text(if (productLookupInProgress) "Looking up…" else "Look up on Open Food Facts") }
                if (!onlineLookupEnabled) Text("Turn on Open Food Facts lookup in More → Enrichment to allow optional online requests.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val result = productLookupResult) {
                    is OpenFoodFactsLookupResult.Found -> OpenFoodFactsSuggestionCard(result.suggestion, result.fromCache)
                    OpenFoodFactsLookupResult.InvalidBarcode -> Text("Enter a valid UPC or EAN barcode to look up.", color = MaterialTheme.colorScheme.error)
                    OpenFoodFactsLookupResult.NotFound -> Text("Open Food Facts has no product record for this barcode. You can continue entering details manually.")
                    OpenFoodFactsLookupResult.DifferentProductType -> Text("Open Food Facts reports this code as a different product type. Review it carefully or continue manually.")
                    OpenFoodFactsLookupResult.LocallyThrottled -> Text("You've made several lookups recently. Wait a moment before trying again, or continue manually.")
                    OpenFoodFactsLookupResult.ProviderRateLimited -> Text("Open Food Facts is limiting requests from this network. Try again later or continue manually.")
                    OpenFoodFactsLookupResult.Unavailable -> Text("Open Food Facts couldn't be reached. Check your connection or continue manually.")
                    OpenFoodFactsLookupResult.InvalidResponse -> Text("Open Food Facts returned data this version couldn't read. Continue manually or try later.")
                    null -> Unit
                }
                if (barcodeMessage != null) {
                    Text(barcodeMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = { Text("Brand (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("ingredient-brand"),
                )
                OutlinedTextField(
                    value = productName,
                    onValueChange = { productName = it },
                    label = { Text("Bottle / product name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("ingredient-product-name"),
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
                    "Photos are optional and picked locally. They are resized, re-encoded without EXIF metadata, and included in household backups only when you select them. Photos are never sent to Open Food Facts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun OpenFoodFactsSuggestionCard(suggestion: OpenFoodFactsSuggestion, fromCache: Boolean) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Open Food Facts suggestion", style = MaterialTheme.typography.titleSmall)
            suggestion.productName?.let { Text("Product: $it") }
            suggestion.brands?.let { Text("Brand: $it") }
            suggestion.quantity?.let { Text("Package quantity text: $it") }
            if (suggestion.productName == null && suggestion.brands == null && suggestion.quantity == null) {
                Text("This product record has no name, brand, or quantity details.")
            }
            Text("Open Food Facts barcode: ${suggestion.canonicalCode}", style = MaterialTheme.typography.bodySmall)
            if (suggestion.warningIds.isNotEmpty()) {
                Text("Open Food Facts marked this record with warnings; check the details carefully.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(if (fromCache) "Showing a result saved on this phone." else "Result saved in this phone's local lookup cache.", style = MaterialTheme.typography.bodySmall)
            Text("Data from $OPEN_FOOD_FACTS_LICENSE · ${OPEN_FOOD_FACTS_HOME}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uriHandler.openUri(ODBL_LICENSE_URL) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Open ODbL 1.0 terms")
            }
            TextButton(onClick = { runCatching { uriHandler.openUri(DBCL_LICENSE_URL) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Open DbCL 1.0 terms")
            }
            Text(
                "Provider details stay in the local lookup cache, outside household records and backups. They aren't copied into the ingredient, brand, or product-name fields; category remains manually chosen, and ABV isn't retrieved or stored. Quantity is source text, not a verified bottle volume.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
internal fun DetailedCaptureDebugModeSettings(
    status: DetailedCaptureStatus?,
    message: String?,
    enabled: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    val isOn = status?.sessionActive == true
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().testTag("detailed-debug-mode-settings")) {
        Text("Debug mode", style = MaterialTheme.typography.titleLarge)
        Text(
            "When on, eligible on-device bottle lookups save bounded source excerpts, model output (including partial output), and validation decisions in private app storage. Page and model text can contain unexpected content; best-effort secret-like redaction may miss sensitive strings. The capture excludes API keys, headers, photos, prompts, household data, recipes, and private notes. Nothing is uploaded. Up to ten recent reports are retained; each expires after 24 hours and cleanup runs when the app or lookup worker next runs, which Android may defer while stopped. If all ten reports are still running, new details are skipped; otherwise the oldest completed report is replaced. Copy or Export diagnostics includes any available detailed captures, so review before sharing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("detailed-debug-mode-disclosure"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Record detailed local lookups", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        isOn -> "ON · new eligible on-device attempts will be recorded. ${status?.captureCount ?: 0} report${if (status?.captureCount == 1) "" else "s"} retained${if ((status?.captureCount ?: 0) >= DetailedBottleLookupDebugSessionStore.MAX_RETAINED_CAPTURES) "; oldest completed reports are replaced as needed" else ""}."
                        (status?.captureCount ?: 0) > 0 -> "OFF · existing reports remain until normal local retention cleanup. ${status?.captureCount} report${if (status?.captureCount == 1) "" else "s"} retained."
                        else -> "OFF · no detailed lookup reports are currently available."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("detailed-debug-mode-status"),
                )
            }
            Switch(
                checked = isOn,
                enabled = enabled,
                onCheckedChange = { checked -> if (checked) onEnable() else onDisable() },
                modifier = Modifier.testTag("detailed-debug-mode-toggle"),
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("detailed-debug-mode-message")) }
    }
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
    installedAppVersion: InstalledAppVersion,
    detailedCaptureStatus: DetailedCaptureStatus?,
    detailedCaptureMessage: String?,
    detailedCaptureEnabled: Boolean,
    onEnableDetailedCapture: () -> Unit,
    onDisableDetailedCapture: () -> Unit,
    openFoodFactsEnabled: Boolean,
    onOpenFoodFactsEnabledChanged: (Boolean) -> Unit,
    firecrawlSearchEnabled: Boolean,
    firecrawlKeySaved: Boolean,
    firecrawlKeyNeedsReplacement: Boolean,
    firecrawlCredentialMessage: String?,
    onConfigureFirecrawl: () -> Unit,
    onRemoveFirecrawlKey: () -> Unit,
    onFirecrawlEnabledChanged: (Boolean) -> Unit,
    ollamaCloudEnabled: Boolean,
    ollamaCloudKeySaved: Boolean,
    ollamaCloudKeyNeedsReplacement: Boolean,
    ollamaCloudModel: String,
    ollamaCredentialMessage: String?,
    onConfigureOllamaCloud: () -> Unit,
    onRemoveOllamaCloudKey: () -> Unit,
    onOllamaCloudEnabledChanged: (Boolean) -> Unit,
    localAiEnabled: Boolean,
    localModelInstalled: Boolean,
    localModelMessage: String?,
    localModelImporting: Boolean,
    localModelImportProgressBytes: Long,
    onChooseLocalModel: () -> Unit,
    onCancelLocalModelImport: () -> Unit,
    onOpenLocalModelSource: () -> Unit,
    onDeleteLocalModel: () -> Unit,
    onLocalAiEnabledChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("household-scroll").padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Household", style = MaterialTheme.typography.headlineMedium)
        Text("App version", style = MaterialTheme.typography.titleLarge)
        Text("${installedAppVersion.name} · version code ${installedAppVersion.code}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("app-version"))
        Text(installedAppVersion.buildStatus, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("app-build-status"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Shared at home", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Tasting notes are saved under the selected member. Backups contain recipes, pantry, member notes, and revision history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onManageMembers) {
            Text("Member · ${members.firstOrNull { it.id == selectedMemberId }?.name ?: "Choose a member"}  ▾")
        }
        Text("Location", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Automatic home selection")
        }
        Text("Enrichment", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Open Food Facts barcode lookup", style = MaterialTheme.typography.titleSmall)
                Text("When enabled, each scanned barcode is checked automatically. Sends only the code plus normal network metadata. Off by default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = openFoodFactsEnabled, onCheckedChange = onOpenFoodFactsEnabledChanged, modifier = Modifier.testTag("online-product-lookup-toggle"))
        }
        Text("Web search · Firecrawl", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                firecrawlKeyNeedsReplacement -> "The saved Firecrawl key can no longer be decrypted. Enter it again; it was not stored in plaintext."
                firecrawlKeySaved -> "Your key is encrypted with Android Keystore and stored only on this phone."
                else -> "Add your own Firecrawl API key to enable optional web search on this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (firecrawlKeyNeedsReplacement) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Allow automatic Firecrawl search", style = MaterialTheme.typography.titleSmall)
                Text("When enabled, a user-started bottle scan searches its exact barcode and checks up to three result pages. Each page fetch uses your plan quota.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = firecrawlSearchEnabled,
                enabled = firecrawlKeySaved && !firecrawlKeyNeedsReplacement,
                onCheckedChange = onFirecrawlEnabledChanged,
                modifier = Modifier.testTag("firecrawl-search-toggle"),
            )
        }
        Text("A scanned lookup sends the barcode in its search query and may fetch up to three result URLs, along with normal network metadata. Manual searches send the entered bottle name and brand. No home, pantry list, member name, or photo is sent. Search results and page text stay temporary during review and are not added to shared backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Search credentials are sent only to the fixed Firecrawl HTTPS API. The app does not store or use a shared project key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onConfigureFirecrawl, modifier = Modifier.testTag("configure-firecrawl-key")) { Text(if (firecrawlKeySaved) "Replace Firecrawl key" else "Add Firecrawl key") }
            if (firecrawlKeySaved) TextButton(onClick = onRemoveFirecrawlKey, modifier = Modifier.testTag("remove-firecrawl-key")) { Text("Remove key") }
        }
        firecrawlCredentialMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Bottle detail suggestions · Ollama Cloud", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                ollamaCloudKeyNeedsReplacement -> "The saved Ollama Cloud key could not be decrypted. Enter it again; it was not stored in plaintext."
                ollamaCloudKeySaved -> "Your Ollama Cloud key is encrypted with Android Keystore on this phone. Model: $ollamaCloudModel"
                else -> "Optionally configure your own Ollama Cloud key and model."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (ollamaCloudKeyNeedsReplacement) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Allow automatic Ollama Cloud extraction", style = MaterialTheme.typography.titleSmall)
                Text("When enabled, a source page is sent after it contains the exact scanned barcode. Local AI takes priority when both are enabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = ollamaCloudEnabled,
                enabled = ollamaCloudKeySaved && !ollamaCloudKeyNeedsReplacement && isValidOllamaModelIdentifier(ollamaCloudModel),
                onCheckedChange = onOllamaCloudEnabledChanged,
                modifier = Modifier.testTag("ollama-cloud-extraction-toggle"),
            )
        }
        Text("After a scanned barcode is found in the fetched page, Ollama Cloud receives up to 24,000 characters of that page after links, URLs and control characters are removed, plus your chosen model identifier and normal network metadata. Page text may still contain personal details. The app does not send home, pantry, member, photo, or recipe data. Model output must include exact source quotes; suggestions stay in the review and never change fields automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Ollama Cloud's chat API does not support constrained JSON output. The app asks for JSON, validates it and rejects unsupported claims; this is not a guarantee of complete or correct extraction.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onConfigureOllamaCloud, modifier = Modifier.testTag("configure-ollama-cloud")) { Text(if (ollamaCloudKeySaved) "Replace key / model" else "Add key / model") }
            if (ollamaCloudKeySaved) TextButton(onClick = onRemoveOllamaCloudKey, modifier = Modifier.testTag("remove-ollama-cloud-key")) { Text("Remove key") }
        }
        ollamaCredentialMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("On-device model · Qwen3 0.6B Q8", style = MaterialTheme.typography.titleSmall)
        Text(
            if (localModelInstalled) "The reviewed 639 MiB model is installed in private app storage on this phone."
            else "Not installed. Download the reviewed model from its source, then select the GGUF file here. The app checks its format and SHA-256 before install.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Model weights: Qwen3-0.6B-GGUF · Apache-2.0. Native runtime: llama.cpp · MIT. This is text extraction only; no image recognition.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("When enabled, an exact-barcode-matched page is automatically processed by this phone's local model. Page text stays on-device. Output is not constrained JSON and is rejected unless exact source evidence passes validation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Allow on-device model suggestions", style = MaterialTheme.typography.titleSmall)
                Text(if (localModelInstalled) "Nothing runs until you choose the local extraction action on a selected source." else "Install the model first. Manual intake stays available without it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = localAiEnabled,
                enabled = localModelInstalled,
                onCheckedChange = onLocalAiEnabledChanged,
                modifier = Modifier.testTag("local-ai-toggle"),
            )
        }
        if (localModelImporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("local-model-import-progress"))
            Text("${localModelImportProgressBytes / (1024 * 1024)} MiB read · checking the pinned SHA-256 · 900 MiB maximum", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancelLocalModelImport, modifier = Modifier.testTag("cancel-local-model-import")) { Text("Cancel model import") }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenLocalModelSource, modifier = Modifier.fillMaxWidth().testTag("local-model-source")) { Text("Open model source") }
                OutlinedButton(onClick = onChooseLocalModel, modifier = Modifier.fillMaxWidth().testTag("choose-local-model")) { Text(if (localModelInstalled) "Replace model file" else "Choose GGUF file") }
                if (localModelInstalled) TextButton(onClick = onDeleteLocalModel, modifier = Modifier.fillMaxWidth().testTag("delete-local-model")) { Text("Delete") }
            }
        }
        localModelMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        DetailedCaptureDebugModeSettings(
            status = detailedCaptureStatus,
            message = detailedCaptureMessage,
            enabled = detailedCaptureEnabled,
            onEnable = onEnableDetailedCapture,
            onDisable = onDisableDetailedCapture,
        )
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
private fun FirecrawlCredentialDialog(
    keyNeedsReplacement: Boolean,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (keyNeedsReplacement) "Replace Firecrawl key" else "Add Firecrawl key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The key is encrypted with this phone's Android Keystore. It is sent only in the Authorization header to api.firecrawl.dev and is never included in household backups or diagnostics.")
                Text("Saving the key does not enable searches. Turn on Allow automatic Firecrawl search separately. Scans search by barcode and may fetch up to three result pages to verify a match; each page fetch uses your Firecrawl plan quota. The app will not upgrade your plan or enable paid top-ups.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; error = null },
                    label = { Text("Firecrawl API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("firecrawl-api-key"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (onSave(apiKey)) onDismiss()
                else error = "The key could not be stored securely. No plaintext fallback was used. Try again or continue without web search."
            }, enabled = apiKey.isNotBlank(), modifier = Modifier.testTag("save-firecrawl-key")) { Text("Save securely") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OllamaCloudSettingsDialog(
    currentModel: String,
    keyNeedsReplacement: Boolean,
    onSave: (String, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(currentModel) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (keyNeedsReplacement) "Replace Ollama Cloud settings" else "Configure Ollama Cloud") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This phone encrypts your key with Android Keystore. The key goes only to ollama.com. Saving does not enable cloud extraction.")
                Text("When you explicitly request extraction, up to 24,000 characters of the selected source page (after removing links, URLs and control characters) and this model name are sent to Ollama Cloud. Review the page first; it may still contain personal details. No barcode, home, pantry, member, photo, or recipe data is sent. This consumes your Ollama Cloud plan usage; the app will not enable paid upgrades or automatic retries.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.trim(); error = null },
                    label = { Text("Ollama Cloud model identifier") },
                    supportingText = { Text("Enter a model name supported by your Ollama Cloud account. The app does not list, pull, or select models for you.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ollama-cloud-model"),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; error = null },
                    label = { Text("Ollama Cloud API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("ollama-cloud-api-key"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onSave(apiKey, model)) onDismiss()
                    else error = "Enter a supported model identifier and a key. Secure storage must succeed; no plaintext fallback is used."
                },
                enabled = apiKey.isNotBlank() && isValidOllamaModelIdentifier(model),
                modifier = Modifier.testTag("save-ollama-cloud-settings"),
            ) { Text("Save securely") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DiagnosticsDialog(
    preview: String,
    lookupPreview: String?,
    lookupSubject: String,
    lookupMissingMessage: String,
    lookupCopyMessage: String?,
    detailedCaptureCount: Int,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local diagnostics") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("diagnostics-dialog-scroll"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(lookupSubject, style = MaterialTheme.typography.titleSmall)
                Text(lookupPreview ?: lookupMissingMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("latest-lookup-report-preview"))
                Text("Stored only on this phone. Nothing is uploaded automatically. Copy and Export include the standard diagnostics and any available detailed source/model captures.")
                Text(
                    if (detailedCaptureCount > 0) "Detailed captures included: $detailedCaptureCount. They may contain public page excerpts and model output; review before sharing."
                    else "Detailed captures included: none available for this selection. Turn on Debug mode in More before an eligible on-device lookup to record them.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("detailed-capture-inclusion-status"),
                )
                Text("Crash details include app/device version, camera event categories, and stack frames. Lookup reports include scanned barcode digits, provider/model status, safe source URLs, timings, HTTP statuses, and fixed rejection codes.")
                Text("API keys and headers, photos, prompts, household/member data, recipes, private notes, and location are excluded. Detailed page/model text is locally retained only when Debug mode was on for that attempt.")
                Text("This captures uncaught Java/Kotlin exceptions. It does not collect logcat and may not capture native crashes or ANRs.", style = MaterialTheme.typography.bodySmall)
                Text(preview, style = MaterialTheme.typography.bodySmall)
                lookupCopyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("diagnostics-action-message")) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth().testTag("copy-diagnostics")) { Text("Copy diagnostics") }
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth().testTag("export-diagnostics")) { Text("Export diagnostics") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

internal fun copyDiagnosticsToClipboard(context: Context, content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CocktAIls detailed bottle lookup capture", content))
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
