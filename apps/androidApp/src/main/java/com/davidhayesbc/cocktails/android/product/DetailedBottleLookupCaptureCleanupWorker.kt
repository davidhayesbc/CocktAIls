package com.davidhayesbc.cocktails.android.product

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/** Best-effort local cleanup; Android may defer this work while the app is stopped. */
internal class DetailedBottleLookupCaptureCleanupWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val store = DetailedBottleLookupCaptureStore(
            File(applicationContext.noBackupFilesDir, "detailed-lookup-capture.json"),
            shareCacheDirectory = applicationContext.cacheDir,
        )
        return if (store.cleanupExpired(System.currentTimeMillis())) Result.success() else Result.retry()
    }
}

internal object DetailedBottleLookupCaptureCleanupScheduler {
    private const val UNIQUE_WORK_NAME = "detailed-bottle-lookup-capture-cleanup"

    fun schedule(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<DetailedBottleLookupCaptureCleanupWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
