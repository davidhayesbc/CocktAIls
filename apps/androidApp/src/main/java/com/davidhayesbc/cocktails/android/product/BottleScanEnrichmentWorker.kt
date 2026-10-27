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

        val queue = BottleEnrichmentQueue(SharedPreferencesBottleEnrichmentQueueStorage(applicationContext))
        val queuedJob = queue.status(draftId) ?: return@withContext Result.success()
        if (isStopped || queuedJob.sessionId != sessionId || queuedJob.status != BottleEnrichmentStatus.QUEUED) {
            if (queuedJob.sessionId == sessionId && queuedJob.status == BottleEnrichmentStatus.RUNNING) {
                queue.pause(queuedJob.id, System.currentTimeMillis())
            }
            return@withContext Result.success()
        }

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
            nowMillis = System.currentTimeMillis(),
        ) ?: return@withContext Result.success()
        val repositories = try {
            runtime.openRepository()
        } catch (cancelled: CancellationException) {
            queue.status(draftId)?.takeIf { it.status == BottleEnrichmentStatus.RUNNING }
                ?.let { queue.pause(it.id, System.currentTimeMillis()) }
            throw cancelled
        } catch (_: Exception) {
            queue.finish(draftId, BottleEnrichmentStatus.NEEDS_ATTENTION, "LOCAL_STORAGE_ERROR", System.currentTimeMillis())
            return@withContext Result.success()
        }
        try {
            val draft = repositories.repository.openBottleIntakeSession()?.takeIf { it.id == sessionId }
                ?.drafts?.firstOrNull { it.id == draftId }
            val barcode = draft?.barcode?.takeIf(String::isNotBlank)
            if (draft == null || barcode == null) {
                queue.finish(draftId, BottleEnrichmentStatus.NEEDS_ATTENTION, "DRAFT_NOT_AVAILABLE", System.currentTimeMillis())
                return@withContext Result.success()
            }

            val plan = runtime.plan()
            val localMatch = repositories.repository.localProductForBarcode(barcode) != null
            ensureDiagnostics()
            val report = runtime.newDebugReport(barcode, localMatch, plan)
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
                    runtime.process(barcode, plan, report) { stage, sourceUrl, _ ->
                        if (isStopped || queue.status(draftId)?.status != BottleEnrichmentStatus.RUNNING) {
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
                                    nowMillis = System.currentTimeMillis(),
                                ) == null
                            ) throw IllegalStateException("Could not persist work stage")
                        }
                        if (stage == ScannedBottleStage.DETAIL_EXTRACTION && plan.extractor == AutomaticBottleExtractor.ON_DEVICE) {
                            setForeground(createForegroundInfo())
                        }
                        setProgress(androidx.work.workDataOf("stage" to stage.name))
                    }
                }
            } catch (cancelled: CancellationException) {
                queue.status(draftId)?.takeIf { it.status == BottleEnrichmentStatus.RUNNING }
                    ?.let { queue.pause(it.id, System.currentTimeMillis()) }
                report.finish("CANCELLED")
                report.setWorkerElapsedMillis(SystemClock.elapsedRealtime() - workerStartedElapsed)
                LocalDiagnostics.recordBottleLookup(report)
                throw cancelled
            } catch (_: Exception) {
                report.record(BottleLookupDebugEvent(
                    stage = BottleLookupDebugStage.COMPLETE,
                    provider = "APP",
                    result = "UNEXPECTED_ERROR",
                    reasonCodes = listOf("UNEXPECTED_EXCEPTION"),
                ))
                report.finish("FAILED")
                LocalDiagnostics.recordBottleLookup(report)
            }

            val outcome = finalOutcome
            val currentJob = queue.status(draftId)
            if (!mayPersistBottleEnrichmentResult(isStopped, currentJob?.status)) {
                report.record(BottleLookupDebugEvent(
                    stage = BottleLookupDebugStage.REVIEW_PERSISTENCE,
                    provider = "APP",
                    result = "SKIPPED",
                    reasonCodes = listOf("REQUEST_CANCELLED"),
                ))
                report.finish("CANCELLED")
                LocalDiagnostics.recordBottleLookup(report)
                return@withContext Result.success()
            }
            val candidate = (outcome?.extraction as? BottleDetailExtractionResult.Found)?.suggestions
            var saved = false
            var resultCode = "REVIEW_NEEDED"
            var finalStatus = BottleEnrichmentStatus.NEEDS_ATTENTION
            val finalized = queue.completeIfRunning(draftId, System.currentTimeMillis()) {
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
            }
            if (!finalized) {
                report.record(BottleLookupDebugEvent(
                    stage = BottleLookupDebugStage.REVIEW_PERSISTENCE,
                    provider = "APP",
                    result = "SKIPPED",
                    reasonCodes = listOf("REQUEST_CANCELLED"),
                ))
                report.finish("CANCELLED")
                report.setWorkerElapsedMillis(SystemClock.elapsedRealtime() - workerStartedElapsed)
                LocalDiagnostics.recordBottleLookup(report)
                return@withContext Result.success()
            }
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
            LocalDiagnostics.recordBottleLookup(report)
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

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Bottle processing",
                NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val openAppIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle("Preparing bottle suggestions")
            .setContentText("On-device extraction is running. Your label photos stay on this phone.")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_CHANNEL = "bottle-background-processing"
        const val NOTIFICATION_ID = 8107
    }
}
