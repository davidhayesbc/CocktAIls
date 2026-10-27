package com.davidhayesbc.cocktails.android.product

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

internal enum class BottleEnrichmentOperation {
    FIRECRAWL_SEARCH,
    FIRECRAWL_FETCH,
    OLLAMA_EXTRACTION,
    LOCAL_EXTRACTION,
}

internal enum class BottleEnrichmentStatus {
    CAPTURE_REQUIRED,
    QUEUED,
    RUNNING,
    PAUSED,
    READY_FOR_REVIEW,
    NEEDS_ATTENTION,
    REVIEWED,
    CANCELLED,
}

internal fun mayPersistBottleEnrichmentResult(workerStopped: Boolean, status: BottleEnrichmentStatus?): Boolean =
    !workerStopped && status == BottleEnrichmentStatus.RUNNING

internal fun measuredQueueWaitMillis(
    enqueuedElapsedRealtimeMillis: Long?,
    enqueuedBootCount: Int?,
    workerStartElapsedRealtimeMillis: Long,
    currentBootCount: Int?,
): Long? = enqueuedElapsedRealtimeMillis?.takeIf {
    it >= 0 && enqueuedBootCount != null && enqueuedBootCount == currentBootCount && workerStartElapsedRealtimeMillis >= it
}?.let { workerStartElapsedRealtimeMillis - it }

/** Metadata only: never stores search queries, page text, model output or credentials. */
internal data class BottleEnrichmentJob(
    val id: String,
    val sessionId: String,
    val draftId: String,
    val operation: BottleEnrichmentOperation,
    val status: BottleEnrichmentStatus,
    val sourceUrl: String?,
    val model: String?,
    val resultCode: String?,
    val labelCaptureRequired: Boolean,
    val updatedAtMillis: Long,
    val enqueuedElapsedRealtimeMillis: Long? = null,
    val enqueuedBootCount: Int? = null,
)

internal interface BottleEnrichmentQueueStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

internal class SharedPreferencesBottleEnrichmentQueueStorage(context: Context) : BottleEnrichmentQueueStorage {
    private val preferences = context.getSharedPreferences("cocktails_local_enrichment_queue", Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(QUEUE_JSON_KEY, null)

    override fun write(value: String): Boolean = preferences.edit().putString(QUEUE_JSON_KEY, value).commit()

    private companion object {
        const val QUEUE_JSON_KEY = "jobs_v1"
    }
}

/** Durable, device-only restart queue. Network calls are never restarted automatically. */
internal class BottleEnrichmentQueue(private val storage: BottleEnrichmentQueueStorage) {
    fun jobs(): List<BottleEnrichmentJob> = decode(storage.read())

    fun begin(
        sessionId: String,
        draftId: String,
        operation: BottleEnrichmentOperation,
        sourceUrl: String? = null,
        model: String? = null,
        nowMillis: Long,
    ): BottleEnrichmentJob? = synchronized(PROCESS_QUEUE_LOCK) {
        if (!isSafeJobMetadata(sessionId, draftId, operation, sourceUrl, model)) return null
        val current = jobs()
        val existing = current.firstOrNull { it.draftId == draftId }
        val withoutDraft = current.filterNot { it.draftId == draftId }
        if (existing == null && withoutDraft.size >= MAX_JOBS) return null
        val job = BottleEnrichmentJob(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            draftId = draftId,
            operation = operation,
            status = BottleEnrichmentStatus.RUNNING,
            sourceUrl = sourceUrl,
            model = model,
            resultCode = null,
            labelCaptureRequired = existing?.labelCaptureRequired ?: false,
            updatedAtMillis = nowMillis,
            enqueuedElapsedRealtimeMillis = existing?.enqueuedElapsedRealtimeMillis,
            enqueuedBootCount = existing?.enqueuedBootCount,
        )
        job.takeIf { write(withoutDraft + it) }
    }

    /** Worker entry is a one-way QUEUED -> RUNNING transition, serialized with cancel/remove. */
    fun beginQueued(
        sessionId: String,
        draftId: String,
        operation: BottleEnrichmentOperation,
        nowMillis: Long,
    ): BottleEnrichmentJob? = synchronized(PROCESS_QUEUE_LOCK) {
        val current = status(draftId) ?: return@synchronized null
        if (current.sessionId != sessionId || current.status != BottleEnrichmentStatus.QUEUED) return@synchronized null
        begin(sessionId, draftId, operation, nowMillis = nowMillis)
    }

    /** A worker may advance stages only while it still owns the running queue row. */
    fun updateRunning(
        sessionId: String,
        draftId: String,
        operation: BottleEnrichmentOperation,
        sourceUrl: String? = null,
        model: String? = null,
        nowMillis: Long,
    ): BottleEnrichmentJob? = synchronized(PROCESS_QUEUE_LOCK) {
        val current = status(draftId) ?: return@synchronized null
        if (current.sessionId != sessionId || current.status != BottleEnrichmentStatus.RUNNING) return@synchronized null
        begin(sessionId, draftId, operation, sourceUrl, model, nowMillis)
    }

    fun requireLabel(sessionId: String, draftId: String, nowMillis: Long): BottleEnrichmentJob? = putState(
        sessionId = sessionId,
        draftId = draftId,
        operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
        status = BottleEnrichmentStatus.QUEUED,
        labelCaptureRequired = true,
        nowMillis = nowMillis,
    )

    fun enqueue(
        sessionId: String,
        draftId: String,
        nowMillis: Long,
        enqueuedElapsedRealtimeMillis: Long? = null,
        enqueuedBootCount: Int? = null,
    ): BottleEnrichmentJob? = putState(
        sessionId = sessionId,
        draftId = draftId,
        operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
        status = BottleEnrichmentStatus.QUEUED,
        enqueuedElapsedRealtimeMillis = enqueuedElapsedRealtimeMillis,
        enqueuedBootCount = enqueuedBootCount,
        nowMillis = nowMillis,
    )

    /** Completes the independent photo step without resetting an already-running lookup. */
    fun completeLabelCapture(draftId: String, nowMillis: Long): Boolean = synchronized(PROCESS_QUEUE_LOCK) {
        val current = jobs()
        if (current.none { it.draftId == draftId }) return@synchronized false
        write(current.map { job ->
            if (job.draftId == draftId) job.copy(
                status = if (job.status == BottleEnrichmentStatus.CAPTURE_REQUIRED) BottleEnrichmentStatus.QUEUED else job.status,
                labelCaptureRequired = false,
                updatedAtMillis = nowMillis,
            ) else job
        })
    }

    fun status(draftId: String): BottleEnrichmentJob? = jobs().firstOrNull { it.draftId == draftId }

    fun finish(draftId: String, status: BottleEnrichmentStatus, resultCode: String, nowMillis: Long): Boolean {
        require(status in setOf(BottleEnrichmentStatus.READY_FOR_REVIEW, BottleEnrichmentStatus.NEEDS_ATTENTION, BottleEnrichmentStatus.REVIEWED, BottleEnrichmentStatus.CANCELLED))
        if (!resultCode.matches(Regex("[A-Z0-9_]{1,80}"))) return false
        return synchronized(PROCESS_QUEUE_LOCK) {
            val current = jobs()
            val match = current.firstOrNull { it.draftId == draftId } ?: return@synchronized false
            write(current.map { job ->
                if (job.draftId == draftId) job.copy(status = status, resultCode = resultCode, updatedAtMillis = nowMillis) else job
            })
        }
    }

    /** Runs a result commit only while the worker still owns the running row. */
    fun completeIfRunning(
        draftId: String,
        nowMillis: Long,
        finalize: () -> Pair<BottleEnrichmentStatus, String>,
    ): Boolean = synchronized(PROCESS_QUEUE_LOCK) {
        val current = jobs()
        val active = current.firstOrNull { it.draftId == draftId }
            ?.takeIf { it.status == BottleEnrichmentStatus.RUNNING }
            ?: return@synchronized false
        val (status, resultCode) = finalize()
        if (status !in setOf(BottleEnrichmentStatus.READY_FOR_REVIEW, BottleEnrichmentStatus.NEEDS_ATTENTION)) return@synchronized false
        if (!resultCode.matches(Regex("[A-Z0-9_]{1,80}"))) return@synchronized false
        write(current.map { job ->
            if (job.id == active.id) job.copy(status = status, resultCode = resultCode, updatedAtMillis = nowMillis) else job
        })
    }

    private fun putState(
        sessionId: String,
        draftId: String,
        operation: BottleEnrichmentOperation,
        status: BottleEnrichmentStatus,
        labelCaptureRequired: Boolean? = null,
        enqueuedElapsedRealtimeMillis: Long? = null,
        enqueuedBootCount: Int? = null,
        nowMillis: Long,
    ): BottleEnrichmentJob? = synchronized(PROCESS_QUEUE_LOCK) {
        if (!isSafeJobMetadata(sessionId, draftId, operation, null, null)) return null
        val current = jobs()
        val existing = current.firstOrNull { it.draftId == draftId }
        val withoutDraft = current.filterNot { it.draftId == draftId }
        if (existing == null && withoutDraft.size >= MAX_JOBS) return null
        val job = BottleEnrichmentJob(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            draftId = draftId,
            operation = operation,
            status = status,
            sourceUrl = null,
            model = null,
            resultCode = null,
            labelCaptureRequired = labelCaptureRequired ?: existing?.labelCaptureRequired ?: false,
            updatedAtMillis = nowMillis,
            enqueuedElapsedRealtimeMillis = enqueuedElapsedRealtimeMillis ?: existing?.enqueuedElapsedRealtimeMillis,
            enqueuedBootCount = enqueuedBootCount ?: existing?.enqueuedBootCount,
        )
        job.takeIf { write(withoutDraft + it) }
    }

    /** Pauses RUNNING rows without an active WorkManager request; queued work remains schedulable. */
    fun recoverInterrupted(activeWorkDraftIds: Set<String> = emptySet()): List<BottleEnrichmentJob> = synchronized(PROCESS_QUEUE_LOCK) {
        val current = jobs()
        val recovered = current.map { job ->
            if (job.status == BottleEnrichmentStatus.RUNNING && job.draftId !in activeWorkDraftIds) {
                job.copy(status = BottleEnrichmentStatus.PAUSED)
            } else job
        }
        if (recovered != current) write(recovered)
        recovered
    }

    fun pause(jobId: String, nowMillis: Long): Boolean = synchronized(PROCESS_QUEUE_LOCK) {
        val current = jobs().firstOrNull { it.id == jobId } ?: return@synchronized false
        if (current.status !in setOf(BottleEnrichmentStatus.RUNNING, BottleEnrichmentStatus.QUEUED)) return@synchronized false
        update(jobId) { it.copy(status = BottleEnrichmentStatus.PAUSED, updatedAtMillis = nowMillis) }
    }

    fun remove(jobId: String): Boolean = synchronized(PROCESS_QUEUE_LOCK) {
        val current = jobs()
        val next = current.filterNot { it.id == jobId }
        next.size == current.size || write(next)
    }

    fun removeDraft(draftId: String): Boolean = synchronized(PROCESS_QUEUE_LOCK) { removeWhere { it.draftId == draftId } }

    fun removeSession(sessionId: String): Boolean = synchronized(PROCESS_QUEUE_LOCK) { removeWhere { it.sessionId == sessionId } }

    private fun update(jobId: String, transform: (BottleEnrichmentJob) -> BottleEnrichmentJob): Boolean {
        val current = jobs()
        val match = current.firstOrNull { it.id == jobId } ?: return false
        return write(current.map { if (it.id == jobId) transform(match) else it })
    }

    private fun removeWhere(predicate: (BottleEnrichmentJob) -> Boolean): Boolean {
        val current = jobs()
        val next = current.filterNot(predicate)
        return next.size == current.size || write(next)
    }

    private fun write(jobs: List<BottleEnrichmentJob>): Boolean {
        if (jobs.size > MAX_JOBS) return false
        return storage.write(buildJsonArray {
            jobs.forEach { job ->
                add(buildJsonObject {
                    put("id", job.id)
                    put("sessionId", job.sessionId)
                    put("draftId", job.draftId)
                    put("operation", job.operation.name)
                    put("status", job.status.name)
                    job.sourceUrl?.let { put("sourceUrl", it) }
                    job.model?.let { put("model", it) }
                    job.resultCode?.let { put("resultCode", it) }
                    put("labelCaptureRequired", job.labelCaptureRequired)
                    put("updatedAtMillis", job.updatedAtMillis)
                    job.enqueuedElapsedRealtimeMillis?.let { put("enqueuedElapsedRealtimeMillis", it) }
                    job.enqueuedBootCount?.let { put("enqueuedBootCount", it) }
                })
            }
        }.toString())
    }

    private fun decode(raw: String?): List<BottleEnrichmentJob> {
        if (raw.isNullOrBlank() || raw.length > MAX_QUEUE_JSON_CHARS) return emptyList()
        return runCatching {
            (Json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val sessionId = obj.string("sessionId") ?: return@mapNotNull null
                val draftId = obj.string("draftId") ?: return@mapNotNull null
                val operation = obj.string("operation")?.let { runCatching { BottleEnrichmentOperation.valueOf(it) }.getOrNull() }
                    ?: return@mapNotNull null
                val status = obj.string("status")?.let { runCatching { BottleEnrichmentStatus.valueOf(it) }.getOrNull() }
                    ?: return@mapNotNull null
                val url = obj.string("sourceUrl")
                val model = obj.string("model")
                if (!isSafeJobMetadata(sessionId, draftId, operation, url, model)) return@mapNotNull null
                BottleEnrichmentJob(
                    id = obj.string("id")?.takeIf { it.length <= MAX_ID_CHARS } ?: return@mapNotNull null,
                    sessionId = sessionId,
                    draftId = draftId,
                    operation = operation,
                    status = status,
                    sourceUrl = url,
                    model = model,
                    resultCode = obj.string("resultCode")?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) },
                    labelCaptureRequired = obj["labelCaptureRequired"]?.jsonPrimitive?.booleanOrNull
                        ?: (status == BottleEnrichmentStatus.CAPTURE_REQUIRED),
                    updatedAtMillis = obj["updatedAtMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null,
                    enqueuedElapsedRealtimeMillis = obj["enqueuedElapsedRealtimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it >= 0 },
                    enqueuedBootCount = obj["enqueuedBootCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 },
                )
            }.take(MAX_JOBS)
        }.getOrDefault(emptyList())
    }

    private fun isSafeJobMetadata(
        sessionId: String,
        draftId: String,
        operation: BottleEnrichmentOperation,
        sourceUrl: String?,
        model: String?,
    ): Boolean {
        if (sessionId.length !in 1..MAX_ID_CHARS || draftId.length !in 1..MAX_ID_CHARS) return false
        return when (operation) {
            BottleEnrichmentOperation.FIRECRAWL_SEARCH -> sourceUrl == null && model == null
            BottleEnrichmentOperation.FIRECRAWL_FETCH -> normalizePublicHttpsUrl(sourceUrl.orEmpty()) == sourceUrl && model == null
            BottleEnrichmentOperation.OLLAMA_EXTRACTION -> normalizePublicHttpsUrl(sourceUrl.orEmpty()) == sourceUrl && isValidOllamaModelIdentifier(model.orEmpty())
            BottleEnrichmentOperation.LOCAL_EXTRACTION -> normalizePublicHttpsUrl(sourceUrl.orEmpty()) == sourceUrl && model == LOCAL_MODEL_LABEL
        }
    }

    private companion object {
        val PROCESS_QUEUE_LOCK = Any()
        const val MAX_JOBS = 500
        const val MAX_ID_CHARS = 100
        const val MAX_QUEUE_JSON_CHARS = 512 * 1024
    }
}

private fun JsonObject.string(field: String): String? = this[field]?.jsonPrimitive?.contentOrNull

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList().orEmpty()

/** Serializes page/model processing across bottle drafts while allowing scanning to continue. */
internal class BottleEnrichmentSerialGate {
    private val mutex = Mutex()

    suspend fun <T> withSlot(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        /** Shared by the open-screen pipeline and WorkManager workers in this app process. */
        val processWide = BottleEnrichmentSerialGate()
    }
}
