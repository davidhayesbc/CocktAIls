package com.davidhayesbc.cocktails.android.product

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.davidhayesbc.cocktails.android.LocalDiagnostics
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.davidhayesbc.cocktails.core.data.LocalCocktailRepository
import com.davidhayesbc.cocktails.shared.db.CocktailsDatabase
import java.io.File

/** Creates the same provider pipeline for the visible intake and durable WorkManager execution. */
internal class BottleScanEnrichmentRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
    private val appVersion = run {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.legacyVersionCode()
        AppVersion(info.versionName ?: "unknown", code)
    }

    private val firecrawlCredentials = FirecrawlCredentialStore(appContext)
    private val ollamaCredentials = OllamaCloudCredentialStore(appContext)
    private val localModelStore = LocalModelFileStore(File(appContext.filesDir, "local-models"))

    internal data class RuntimeRepository(
        val driver: AndroidSqliteDriver,
        val repository: LocalCocktailRepository,
    ) : AutoCloseable {
        override fun close() = driver.close()
    }

    fun openRepository(): RuntimeRepository {
        val driver = AndroidSqliteDriver(CocktailsDatabase.Schema, appContext, "cocktails.db")
        val repository = LocalCocktailRepository(CocktailsDatabase(driver)).also { it.initialize(System.currentTimeMillis()) }
        return RuntimeRepository(driver, repository)
    }

    fun plan(): ScannedBottlePlan = ScannedBottlePlan(
        openFoodFactsEnabled = preferences.getBoolean("open_food_facts_enabled", false),
        firecrawlEnabled = preferences.getBoolean("firecrawl_search_enabled", false) && firecrawlCredentials.readApiKey() != null,
        extractor = automaticBottleExtractor(
            onDeviceEnabled = preferences.getBoolean("local_ai_enabled", false) && localModelStore.installedModel() != null,
            ollamaCloudEnabled = preferences.getBoolean("ollama_cloud_enabled", false) &&
                ollamaCredentials.readApiKey() != null &&
                isValidOllamaModelIdentifier(preferences.getString("ollama_cloud_model", "").orEmpty()),
        ),
    )

    fun newDebugReport(barcode: String, localMatch: Boolean, plan: ScannedBottlePlan): BottleLookupDebugReport {
        val firecrawlConfigured = firecrawlCredentials.hasSavedKey() && firecrawlCredentials.readApiKey() != null
        val ollamaConfigured = ollamaCredentials.hasSavedKey() && ollamaCredentials.readApiKey() != null
        val localInstalled = localModelStore.installedModel() != null
        val ollamaModel = preferences.getString("ollama_cloud_model", "").orEmpty()
        val extractor = plan.extractor
        val report = BottleLookupDebugReport(
            createdAtMillis = System.currentTimeMillis(),
            versionName = appVersion.name,
            versionCode = appVersion.code,
            originalBarcode = barcode,
            normalizedBarcode = normalizeGtinForLookup(barcode),
            openFoodFactsEnabled = plan.openFoodFactsEnabled,
            openFoodFactsConfigured = true,
            firecrawlEnabled = preferences.getBoolean("firecrawl_search_enabled", false),
            firecrawlConfigured = firecrawlConfigured,
            extractorProvider = when (extractor) {
                AutomaticBottleExtractor.ON_DEVICE -> "On-device Qwen3"
                AutomaticBottleExtractor.OLLAMA_CLOUD -> "Ollama Cloud"
                null -> null
            },
            modelIdentifier = when (extractor) {
                AutomaticBottleExtractor.ON_DEVICE -> LOCAL_MODEL_LABEL
                AutomaticBottleExtractor.OLLAMA_CLOUD -> ollamaModel
                null -> null
            },
            query = scannedBottleSearchQuery(barcode),
            onDeviceAiEnabled = preferences.getBoolean("local_ai_enabled", false),
            onDeviceModelInstalled = localInstalled,
            ollamaCloudEnabled = preferences.getBoolean("ollama_cloud_enabled", false),
            ollamaCloudConfigured = ollamaConfigured,
            ollamaCloudModelIdentifier = ollamaModel,
            localModelIdentifier = LOCAL_MODEL_LABEL.takeIf { localInstalled },
        )
        report.record(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.LOCAL_MATCH,
            provider = "Local database",
            result = if (localMatch) "MATCH" else "NO_MATCH",
        ))
        LocalDiagnostics.recordBottleLookup(report)
        return report
    }

    suspend fun process(
        barcode: String,
        plan: ScannedBottlePlan,
        report: BottleLookupDebugReport,
        onStage: suspend (ScannedBottleStage, String?, String?) -> Unit,
    ): ScannedBottleOutcome {
        fun record(event: BottleLookupDebugEvent) {
            report.record(event)
            LocalDiagnostics.recordBottleLookup(report)
        }

        val offCache = OpenFoodFactsLocalCache(SharedPreferencesLookupStorage(appContext))
        val offClient = OpenFoodFactsLookupClient(offCache)
        val firecrawl = FirecrawlSearchProvider({ firecrawlCredentials.readApiKey() })
        val ollama = OllamaCloudBottleDetailExtractor({ ollamaCredentials.readApiKey() })
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { code ->
                var status: Int? = null
                val value = offClient.lookup(code, appVersion.name) { status = it }
                BottleProviderCall(value, status)
            },
            search = { query -> firecrawl.searchDetailed(query, 5) },
            fetch = firecrawl::fetchDetailed,
            extract = { page, provider, requiredBarcode ->
                when (provider) {
                    AutomaticBottleExtractor.OLLAMA_CLOUD -> ollama.extractDetailed(
                        page,
                        preferences.getString("ollama_cloud_model", "").orEmpty(),
                        requiredBarcode,
                    )
                    AutomaticBottleExtractor.ON_DEVICE -> {
                        val modelFile = localModelStore.installedModel()
                        if (modelFile == null) BottleProviderCall(BottleDetailExtractionResult.SetupRequired)
                        else BottleProviderCall(
                            LocalBottleDetailExtractor(
                                { modelFile.absolutePath },
                                LazyLlamaCppLocalInferenceRunner(appContext),
                            ).extractWithDiagnostics(page, LOCAL_MODEL_LABEL, requiredBarcode, ::record),
                        )
                    }
                }
            },
        )
        return coordinator.processScannedBarcode(barcode, plan, onStage, ::record)
    }

    private data class AppVersion(val name: String, val code: Long)

    private fun PackageInfo.legacyVersionCode(): Long {
        @Suppress("DEPRECATION")
        return versionCode.toLong()
    }

}
