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
    ): BottleEnrichmentJob? {
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
        )
        return job.takeIf { write(withoutDraft + it) }
    }

    fun requireLabel(sessionId: String, draftId: String, nowMillis: Long): BottleEnrichmentJob? = putState(
        sessionId = sessionId,
        draftId = draftId,
        operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
        status = BottleEnrichmentStatus.QUEUED,
        labelCaptureRequired = true,
        nowMillis = nowMillis,
    )

    fun enqueue(sessionId: String, draftId: String, nowMillis: Long): BottleEnrichmentJob? = putState(
        sessionId = sessionId,
        draftId = draftId,
        operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
        status = BottleEnrichmentStatus.QUEUED,
        nowMillis = nowMillis,
    )

    /** Completes the independent photo step without resetting an already-running lookup. */
    fun completeLabelCapture(draftId: String, nowMillis: Long): Boolean {
        val current = jobs()
        if (current.none { it.draftId == draftId }) return false
        return write(current.map { job ->
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
        val current = jobs()
        val match = current.firstOrNull { it.draftId == draftId } ?: return false
        return write(current.map { job ->
            if (job.draftId == draftId) job.copy(status = status, resultCode = resultCode, updatedAtMillis = nowMillis) else job
        })
    }

    private fun putState(
        sessionId: String,
        draftId: String,
        operation: BottleEnrichmentOperation,
        status: BottleEnrichmentStatus,
        labelCaptureRequired: Boolean? = null,
        nowMillis: Long,
    ): BottleEnrichmentJob? {
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
        )
        return job.takeIf { write(withoutDraft + it) }
    }

    /** Converts process-death RUNNING rows to PAUSED; caller must ask the user before retrying. */
    fun recoverInterrupted(): List<BottleEnrichmentJob> {
        val current = jobs()
        val recovered = current.map { job ->
            if (job.status == BottleEnrichmentStatus.RUNNING || job.status == BottleEnrichmentStatus.QUEUED) {
                job.copy(status = BottleEnrichmentStatus.PAUSED)
            } else job
        }
        if (recovered != current) write(recovered)
        return recovered
    }

    fun pause(jobId: String, nowMillis: Long): Boolean = update(jobId) { it.copy(status = BottleEnrichmentStatus.PAUSED, updatedAtMillis = nowMillis) }

    fun remove(jobId: String): Boolean {
        val current = jobs()
        val next = current.filterNot { it.id == jobId }
        return next.size == current.size || write(next)
    }

    fun removeDraft(draftId: String): Boolean = removeWhere { it.draftId == draftId }

    fun removeSession(sessionId: String): Boolean = removeWhere { it.sessionId == sessionId }

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
}
