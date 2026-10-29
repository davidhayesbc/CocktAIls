package com.davidhayesbc.cocktails.android.product

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.davidhayesbc.cocktails.android.LocalDiagnostics
import com.davidhayesbc.cocktails.android.MainActivity
import com.davidhayesbc.cocktails.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A per-bottle durable task. A queued row may start after intake closes. A row already marked RUNNING
 * is never replayed: WorkManager may be redelivering it after process death while a provider might
 * already have received the request, so the user must explicitly resume it.
 */
internal class BottleScanEnrichmentWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString(BottleEnrichmentWorkScheduler.KEY_SESSION_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,100}")) } ?: return@withContext Result.failure()
        val draftId = inputData.getString(BottleEnrichmentWorkScheduler.KEY_DRAFT_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,100}")) } ?: return@withContext Result.failure()
        val attemptToken = inputData.getString(BottleEnrichmentWorkScheduler.KEY_ATTEMPT_TOKEN)
            ?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: return@withContext Result.failure()

        val queue = BottleEnrichmentQueue(SharedPreferencesBottleEnrichmentQueueStorage(applicationContext))
        val queuedJob = queue.status(draftId) ?: return@withContext Result.success()
        if (queuedJob.sessionId != sessionId || queuedJob.attemptToken != attemptToken) {
            return@withContext Result.success()
        }
        if (queuedJob.status == BottleEnrichmentStatus.RUNNING) {
            // WorkManager redelivery after process death must not repeat a request that
            // may already have reached its provider. The attempt token fences retries.
            DetailedBottleLookupDebugSessionStore(applicationContext.noBackupFilesDir)
                .pendingAttemptWasMissed(draftId, attemptToken)
            queue.pauseAttempt(draftId, attemptToken, System.currentTimeMillis())
            return@withContext Result.success()
        }
        if (isStopped || queuedJob.status != BottleEnrichmentStatus.QUEUED) return@withContext Result.success()

        val runtime = BottleScanEnrichmentRuntime(applicationContext)
        val workerStartedElapsed = SystemClock.elapsedRealtime()
        val currentBootCount = runCatching {
            Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        val queueWaitMillis = queuedJob.enqueuedElapsedRealtimeMillis
            ?.takeIf { queuedJob.enqueuedBootCount != null && queuedJob.enqueuedBootCount == currentBootCount && workerStartedElapsed >= it }
            ?.let { workerStartedElapsed - it }
        val ownedRun = queue.beginQueued(
            sessionId = sessionId,
            draftId = draftId,
            operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
            attemptToken = attemptToken,
            nowMillis = System.currentTimeMillis(),
        ) ?: return@withContext Result.success()
        val repositories = try {
            runtime.openRepository()
        } catch (cancelled: CancellationException) {
            queue.pauseAttempt(draftId, attemptToken, System.currentTimeMillis())
            throw cancelled
        } catch (_: Exception) {
            queue.finishAttempt(draftId, attemptToken, BottleEnrichmentStatus.NEEDS_ATTENTION, "LOCAL_STORAGE_ERROR", System.currentTimeMillis())
            return@withContext Result.success()
        }
        try {
            val draft = repositories.repository.openBottleIntakeSession()?.takeIf { it.id == sessionId }
                ?.drafts?.firstOrNull { it.id == draftId }
            val barcode = draft?.barcode?.takeIf(String::isNotBlank)
            if (draft == null || barcode == null) {
                queue.finishAttempt(draftId, attemptToken, BottleEnrichmentStatus.NEEDS_ATTENTION, "DRAFT_NOT_AVAILABLE", System.currentTimeMillis())
                return@withContext Result.success()
            }

            val plan = runtime.plan()
            val localMatch = repositories.repository.localProductForBarcode(barcode) != null
            ensureDiagnostics()
            val report = runtime.newDebugReport(barcode, localMatch, plan)
            @Suppress("DEPRECATION")
            val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            val appVersionName = packageInfo.versionName ?: "unknown"
            @Suppress("DEPRECATION")
            val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
            val detailedCaptureStore = DetailedBottleLookupDebugSessionStore(applicationContext.noBackupFilesDir, shareCacheDirectory = applicationContext.cacheDir)
            val captureModel = when (plan.extractor) {
                AutomaticBottleExtractor.ON_DEVICE -> LOCAL_MODEL_LABEL
                AutomaticBottleExtractor.OLLAMA_CLOUD -> applicationContext
                    .getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
                    .getString("ollama_cloud_model", null)
                null -> null
            }
            val detailedCapture = claimDetailedLookupCaptureForWorker(
                store = detailedCaptureStore,
                plan = plan,
                draftId = draftId,
                attemptToken = attemptToken,
                barcode = barcode,
                appVersion = appVersionName,
                appVersionCode = appVersionCode,
                model = captureModel,
            )
            report.setQueueWaitMillis(queueWaitMillis)
            report.record(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.QUEUE_WAIT,
                provider = "APP",
                result = if (queueWaitMillis != null) "COMPLETE" else "SKIPPED",
                durationMillis = queueWaitMillis,
            ))
            var finalOutcome: ScannedBottleOutcome? = null
            try {
                finalOutcome = BottleEnrichmentSerialGate.processWide.withSlot {
                    runtime.process(barcode, plan, report, detailedCapture) { stage, sourceUrl, _ ->
                        if (isStopped || queue.status(draftId)?.let { it.status == BottleEnrichmentStatus.RUNNING && it.attemptToken == attemptToken } != true) {
                            throw CancellationException("Work was stopped or canceled")
                        }
                        val operation = when (stage) {
                            ScannedBottleStage.BARCODE_LOOKUP -> null
                            ScannedBottleStage.WEB_SEARCH -> BottleEnrichmentOperation.FIRECRAWL_SEARCH
                            ScannedBottleStage.SOURCE_FETCH -> BottleEnrichmentOperation.FIRECRAWL_FETCH
                            ScannedBottleStage.DETAIL_EXTRACTION -> when (plan.extractor) {
                                AutomaticBottleExtractor.ON_DEVICE -> BottleEnrichmentOperation.LOCAL_EXTRACTION
                                AutomaticBottleExtractor.OLLAMA_CLOUD -> BottleEnrichmentOperation.OLLAMA_EXTRACTION
                                null -> null
                            }
                        }
                        operation?.let { op ->
                            val model = when (op) {
                                BottleEnrichmentOperation.LOCAL_EXTRACTION -> LOCAL_MODEL_LABEL
                                BottleEnrichmentOperation.OLLAMA_EXTRACTION -> applicationContext.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
                                    .getString("ollama_cloud_model", null)
                                else -> null
                            }
                            if (queue.updateRunning(
                                    sessionId = sessionId,
                                    draftId = draftId,
                                    operation = op,
                                    sourceUrl = sourceUrl,
                                    model = model,
                                    attemptToken = attemptToken,
                                    nowMillis = System.currentTimeMillis(),
                                ) == null
                            ) throw IllegalStateException("Could not persist work stage")
                        }
                        if (stage == ScannedBottleStage.DETAIL_EXTRACTION && plan.extractor == AutomaticBottleExtractor.ON_DEVICE) {
                            setForeground(createBottleProcessingForegroundInfo(applicationContext))
                        }
                        setProgress(androidx.work.workDataOf("stage" to stage.name))
                    }
                }
            } catch (cancelled: CancellationException) {
                detailedCapture?.finish("CANCELLED")
                report.finish("CANCELLED")
                report.setWorkerElapsedMillis(SystemClock.elapsedRealtime() - workerStartedElapsed)
                // Publish under the same queue lock that fences correction/retry. A
                // stale attempt cannot restore its old barcode report after correction.
                queue.pauseAttempt(draftId, attemptToken, System.currentTimeMillis()) {
                    LocalDiagnostics.recordBottleLookup(report, draftId)
                }
                throw cancelled
            } catch (_: Exception) {
                detailedCapture?.finish("FAILED")
                report.record(BottleLookupDebugEvent(
                    stage = BottleLookupDebugStage.COMPLETE,
                    provider = "APP",
                    result = "UNEXPECTED_ERROR",
                    reasonCodes = listOf("UNEXPECTED_EXCEPTION"),
                ))
                report.finish("FAILED")
            }

            val outcome = finalOutcome
            val currentJob = queue.status(draftId)
            if (!mayPersistBottleEnrichmentResult(isStopped, currentJob?.status)) {
                detailedCapture?.finish("CANCELLED")
                report.record(BottleLookupDebugEvent(
                    stage = BottleLookupDebugStage.REVIEW_PERSISTENCE,
                    provider = "APP",
                    result = "SKIPPED",
                    reasonCodes = listOf("REQUEST_CANCELLED"),
                ))
                report.finish("CANCELLED")
                return@withContext Result.success()
            }
            val candidate = (outcome?.extraction as? BottleDetailExtractionResult.Found)?.suggestions
            var saved = false
            var resultCode = "REVIEW_NEEDED"
            var finalStatus = BottleEnrichmentStatus.NEEDS_ATTENTION
            val providerOutcomes = bottleProviderOutcomes(plan, outcome, currentJob?.operation)
            val finalized = queue.completeIfRunning(
                draftId = draftId,
                attemptToken = attemptToken,
                nowMillis = System.currentTimeMillis(),
                providerOutcomes = providerOutcomes,
                finalize = {
                    saved = candidate?.let {
                        PendingBottleEvidenceStore(SharedPreferencesPendingBottleEvidenceStorage(applicationContext)).save(draftId, it)
                    } == true
                    resultCode = when {
                        saved -> "CANDIDATES_READY"
                        outcome == null -> "UNEXPECTED_EXCEPTION"
                        outcome.search == WebSearchResult.QuotaExceeded || outcome.page == WebPageResult.QuotaExceeded -> "PROVIDER_QUOTA_EXCEEDED"
                        outcome.search == WebSearchResult.AuthenticationRequired || outcome.page == WebPageResult.AuthenticationRequired -> "PROVIDER_AUTH_REQUIRED"
                        outcome.search == WebSearchResult.RateLimited || outcome.page == WebPageResult.RateLimited -> "PROVIDER_RATE_LIMITED"
                        outcome.search is WebSearchResult.Found && outcome.selectedHit == null -> "NO_SOURCE_FOUND"
                        outcome.extraction is BottleDetailExtractionResult.LocalFailure -> outcome.extraction.code.wireValue
                        !plan.openFoodFactsEnabled && !plan.firecrawlEnabled -> "PROVIDERS_DISABLED"
                        else -> "REVIEW_NEEDED"
                    }
                    finalStatus = if (saved) BottleEnrichmentStatus.READY_FOR_REVIEW else BottleEnrichmentStatus.NEEDS_ATTENTION
                    finalStatus to resultCode
                },
                afterPersist = {
                    report.record(BottleLookupDebugEvent(
                        stage = BottleLookupDebugStage.REVIEW_PERSISTENCE,
                        provider = "APP",
                        result = when {
                            candidate == null -> "SKIPPED"
                            saved -> "PERSISTED"
                            else -> "PERSISTENCE_FAILED"
                        },
                        reasonCodes = if (candidate != null && !saved) listOf("OTHER") else emptyList(),
                    ))
                    report.finish(when {
                        saved -> "SUCCESS"
                        outcome == null -> "FAILED"
                        resultCode == "NO_SOURCE_FOUND" -> "NOT_FOUND"
                        resultCode == "PROVIDERS_DISABLED" -> "DISABLED"
                        else -> "PARTIAL"
                    })
                    report.setWorkerElapsedMillis(SystemClock.elapsedRealtime() - workerStartedElapsed)
                    LocalDiagnostics.recordBottleLookup(report, draftId)
                },
            )
            if (!finalized) {
                detailedCapture?.finish("CANCELLED")
                report.record(BottleLookupDebugEvent(
                    stage = BottleLookupDebugStage.REVIEW_PERSISTENCE,
                    provider = "APP",
                    result = "SKIPPED",
                    reasonCodes = listOf("REQUEST_CANCELLED"),
                ))
                report.finish("CANCELLED")
                report.setWorkerElapsedMillis(SystemClock.elapsedRealtime() - workerStartedElapsed)
                return@withContext Result.success()
            }
            detailedCapture?.finish(if (finalStatus == BottleEnrichmentStatus.READY_FOR_REVIEW) "COMPLETE" else "FAILED")
            Result.success()
        } finally {
            repositories.close()
        }
    }

    private fun ensureDiagnostics() {
        @Suppress("DEPRECATION")
        val info = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        LocalDiagnostics.initializeIfNeeded(applicationContext.filesDir, info.versionName ?: "unknown", code)
    }

    private companion object {
        const val NOTIFICATION_ID = 8107
    }
}

internal fun claimDetailedLookupCaptureForWorker(
    store: DetailedBottleLookupDebugSessionStore,
    plan: ScannedBottlePlan,
    draftId: String,
    attemptToken: String,
    barcode: String,
    appVersion: String,
    appVersionCode: Long,
    model: String?,
    now: Long = System.currentTimeMillis(),
): DetailedBottleLookupRecorder? = store.claim(
    draftId = draftId,
    attemptToken = attemptToken,
    barcode = barcode,
    appVersion = appVersion,
    appVersionCode = appVersionCode,
    model = model,
    now = now,
    allowNextLookup = plan.extractor == AutomaticBottleExtractor.ON_DEVICE,
)

internal fun createBottleProcessingForegroundInfo(context: Context): ForegroundInfo {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(NotificationChannel(
            BOTTLE_PROCESSING_NOTIFICATION_CHANNEL,
            "Bottle processing",
            NotificationManager.IMPORTANCE_LOW,
        ))
    }
    val openAppIntent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
        context,
        8107,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, BOTTLE_PROCESSING_NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_bottle)
        .setContentTitle("Preparing bottle suggestions")
        .setContentText("On-device extraction is running. Your label photos stay on this phone.")
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(pendingIntent)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(8107, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(8107, notification)
    }
}

private const val BOTTLE_PROCESSING_NOTIFICATION_CHANNEL = "bottle-background-processing"
