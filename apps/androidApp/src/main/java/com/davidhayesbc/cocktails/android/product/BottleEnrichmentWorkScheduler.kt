package com.davidhayesbc.cocktails.android.product

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager input contains opaque local IDs only; barcode and provider data stay in app storage. */
internal object BottleEnrichmentWorkScheduler {
    private const val SESSION_TAG_PREFIX = "cocktails-bottle-session-"

    fun workName(draftId: String): String = "cocktails-bottle-$draftId"

    fun enqueue(context: Context, sessionId: String, draftId: String, requiresNetwork: Boolean): Boolean {
        if (!sessionId.matches(Regex("[A-Za-z0-9-]{1,100}")) ||
            !draftId.matches(Regex("[A-Za-z0-9-]{1,100}"))
        ) return false
        return runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                .build()
            val request = OneTimeWorkRequestBuilder<BottleScanEnrichmentWorker>()
                .setInputData(Data.Builder().putString(KEY_SESSION_ID, sessionId).putString(KEY_DRAFT_ID, draftId).build())
                .setConstraints(constraints)
                .addTag(workName(draftId))
                .addTag(SESSION_TAG_PREFIX + sessionId)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(draftId), ExistingWorkPolicy.KEEP, request)
            true
        }.getOrDefault(false)
    }

    fun cancelDraft(context: Context, draftId: String) {
        if (draftId.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(draftId)) }
        }
    }

    fun cancelSession(context: Context, sessionId: String) {
        if (sessionId.matches(Regex("[A-Za-z0-9-]{1,100}"))) {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(SESSION_TAG_PREFIX + sessionId) }
        }
    }

    suspend fun reconcile(context: Context, queue: BottleEnrichmentQueue, requiresNetwork: Boolean): List<BottleEnrichmentJob> =
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            val before = queue.jobs()
            val active = before.asSequence()
                .filter { it.status == BottleEnrichmentStatus.RUNNING }
                .mapNotNull { job ->
                    val hasActiveWork = runCatching {
                        workManager.getWorkInfosForUniqueWork(workName(job.draftId)).get().any { !it.state.isFinished }
                    }.getOrDefault(false)
                    job.draftId.takeIf { hasActiveWork }
                }
                .toSet()
            val recovered = queue.recoverInterrupted(active)
            recovered.filter { it.status == BottleEnrichmentStatus.QUEUED }.forEach { job ->
                enqueue(context, job.sessionId, job.draftId, requiresNetwork)
            }
            queue.jobs()
        }

    internal const val KEY_SESSION_ID = "session_id"
    internal const val KEY_DRAFT_ID = "draft_id"
}
