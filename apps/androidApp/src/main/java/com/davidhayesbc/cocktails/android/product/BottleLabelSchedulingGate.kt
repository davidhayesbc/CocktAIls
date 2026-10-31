package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft

/** Serialize the authoritative re-read, request-token write and WorkManager replacement.
 * If a photo/attempt changes during this section, its newer scheduler runs after this one.
 * If it changed before this section, the obsolete caller cannot replace newer work. */
internal class BottleLabelSchedulingGate {
    private val lock = Any()

    fun <T> serialized(action: () -> T): T = synchronized(lock) { action() }

    fun scheduleIfCurrent(
        requested: BottleIntakeDraft,
        attemptToken: String?,
        currentState: () -> Pair<BottleIntakeDraft?, BottleEnrichmentJob?>,
        schedule: () -> Boolean,
    ): Boolean = serialized {
        val (current, job) = currentState()
        if (current == null || current.id != requested.id || current.sessionId != requested.sessionId ||
            current.photoData == null || current.photoSha256 == null || current.photoSha256 != requested.photoSha256 ||
            current.barcode != requested.barcode || job?.attemptToken != attemptToken ||
            job != null && (job.sessionId != current.sessionId || job.draftId != current.id) ||
            job?.status in setOf(BottleEnrichmentStatus.CANCELLED, BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.REVIEWED)
        ) false else schedule()
    }

    companion object { val processWide = BottleLabelSchedulingGate() }
}
