package com.davidhayesbc.cocktails.android.product

import android.content.Context
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class OcrState { QUEUED, RUNNING, COMPLETED, SKIPPED, FAILED, CANCELLED, STALE, INTERRUPTED }
internal enum class OcrPhase { NONE, RECOGNITION, WAITING_FOR_LABEL_AI, LABEL_AI, VALIDATION, DONE }
internal enum class OcrReason {
    NONE, MISSING_PHOTO, STALE_PHOTO_OR_DRAFT, STALE_ATTEMPT, SUPERSEDED_REQUEST, QUEUE_INACTIVE,
    REQUEST_STORAGE_FAILED, ENQUEUE_FAILED, WORK_STOPPED, PROCESS_RESTARTED, USER_CANCELLED,
    RECOGNITION_TIMEOUT, RECOGNITION_FAILED, INVALID_IMAGE, NO_TEXT, LOCAL_AI_DISABLED,
    MODEL_MISSING, FOREGROUND_UNAVAILABLE, MODEL_RUNTIME_FAILED, MODEL_RESPONSE_INVALID,
    EVIDENCE_STORAGE_FAILED, WORKER_FAILED,
}
internal data class OcrCorrelation(val draftRef: String, val sessionRef: String, val photoRef: String?, val attemptRef: String?, val contextRef: String) {
    companion object {
        fun of(draft: BottleIntakeDraft, attempt: String?) = OcrCorrelation(
            opaque("draft", draft.id), opaque("session", draft.sessionId), draft.photoSha256?.let { opaque("photo", it) },
            attempt?.let { opaque("attempt", it) }, opaque("context", listOf(draft.id, draft.sessionId, draft.photoSha256, attempt, draft.barcode).joinToString("|")),
        )
    }
}
internal fun opaque(kind: String, value: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$kind:$value".toByteArray()).joinToString("") { "%02x".format(it) }

internal data class OcrDiagnosticRecord(
    val correlation: OcrCorrelation,
    val requestRef: String,
    val state: OcrState,
    val phase: OcrPhase = OcrPhase.NONE,
    val reason: OcrReason = OcrReason.NONE,
    val queuedAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val workerElapsedMs: Long? = null,
    val recognitionMs: Long? = null,
    val textFound: Boolean? = null,
    val labelAiState: OcrState? = null,
    val labelAiReason: OcrReason? = null,
    val labelAiMs: Long? = null,
    val supportedFields: Set<String> = emptySet(),
    val unresolvedFields: Set<String> = emptySet(),
    val conflictingFields: Set<String> = emptySet(),
    val retryCount: Int = 0,
    val owner: String? = null,
    val recognitionState: OcrState? = null,
    val recognitionReason: OcrReason? = null,
)
internal interface OcrDiagnosticStorage { fun read(): String?; fun write(value: String): Boolean; fun clear(): Boolean }

/** Separate bounded journal: no photo, path, barcode, values, quotes, prompts or throwable messages. */
internal class BottleOcrJournal(private val storage: OcrDiagnosticStorage, private val process: String = PROCESS, private val now: () -> Long = System::currentTimeMillis) {
    fun begin(correlation: OcrCorrelation, request: String, state: OcrState = OcrState.QUEUED, reason: OcrReason = OcrReason.NONE): Boolean = synchronized(LOCK) {
        val records = read().map { old ->
            if (old.correlation.draftRef == correlation.draftRef && old.state in ACTIVE)
                old.copy(state = OcrState.STALE, reason = OcrReason.SUPERSEDED_REQUEST, updatedAt = now()) else old
        }
        save(records + OcrDiagnosticRecord(correlation, opaque("request", request), state, reason = reason, queuedAt = now(), updatedAt = now()))
    }
    fun update(correlation: OcrCorrelation, request: String, transform: (OcrDiagnosticRecord) -> OcrDiagnosticRecord): Boolean = synchronized(LOCK) {
        val records = read()
        val latest = records.lastOrNull { it.correlation.draftRef == correlation.draftRef } ?: return false
        if (latest.correlation != correlation || latest.requestRef != opaque("request", request) || latest.state !in ACTIVE) return false
        val changed = transform(latest).copy(correlation = latest.correlation, requestRef = latest.requestRef, queuedAt = latest.queuedAt, updatedAt = now())
        save(records.map { if (it.requestRef == latest.requestRef) changed else it })
    }
    fun start(c: OcrCorrelation, request: String, retry: Int) = update(c, request) {
        it.copy(state = OcrState.RUNNING, phase = OcrPhase.RECOGNITION, reason = OcrReason.NONE, startedAt = now(), owner = process,
            recognitionMs = null, workerElapsedMs = null, textFound = null, labelAiState = null, labelAiReason = null, labelAiMs = null,
            supportedFields = emptySet(), unresolvedFields = emptySet(), conflictingFields = emptySet(), retryCount = retry.coerceIn(0, 100),
            recognitionState = OcrState.RUNNING, recognitionReason = OcrReason.NONE)
    }
    fun cancelDraft(draftId: String) = synchronized(LOCK) {
        val ref = opaque("draft", draftId)
        save(read().map { if (it.correlation.draftRef == ref && it.state in ACTIVE) it.copy(state = OcrState.CANCELLED, reason = OcrReason.USER_CANCELLED, updatedAt = now(), recognitionState = if (it.recognitionState == OcrState.RUNNING) OcrState.CANCELLED else it.recognitionState, labelAiState = if (it.labelAiState == OcrState.RUNNING) OcrState.CANCELLED else it.labelAiState) else it })
    }
    fun cancelSession(sessionId: String) = synchronized(LOCK) {
        val ref = opaque("session", sessionId)
        save(read().map { if (it.correlation.sessionRef == ref && it.state in ACTIVE) it.copy(state = OcrState.CANCELLED, reason = OcrReason.USER_CANCELLED, updatedAt = now(), recognitionState = if (it.recognitionState == OcrState.RUNNING) OcrState.CANCELLED else it.recognitionState, labelAiState = if (it.labelAiState == OcrState.RUNNING) OcrState.CANCELLED else it.labelAiState) else it })
    }
    fun clear() = synchronized(LOCK) { storage.clear() }
    fun snapshot(current: List<OcrCorrelation>?, draftId: String? = null): JsonObject = synchronized(LOCK) {
        val all = read()
        val latest = all.groupBy { it.correlation.draftRef }.mapValues { it.value.last().requestRef }
        val records = all.filter { draftId == null || it.correlation.draftRef == opaque("draft", draftId) }
        buildJsonObject {
            put("formatVersion", 1)
            put("recognitionEngine", "ML_KIT_BUNDLED_LATIN")
            put("labelAiEngine", "LOCAL_TEXT_EXTRACTOR")
            put("timingScope", "OCR worker only; labelAiMs is nested in workerElapsedMs; do not add to web lookup timings")
            put("rawTextIncluded", false); put("photosIncluded", false)
            put("currentContextAvailable", current != null)
            put("missingCurrentRecordMeaning", "No OCR diagnostics recorded for this photo and attempt; absence does not mean OCR completed")
            put("attempts", buildJsonArray { records.forEach { original ->
                val isCurrent = current?.contains(original.correlation) == true && latest[original.correlation.draftRef] == original.requestRef
                val record = when {
                    current != null && !isCurrent && original.state in ACTIVE -> original.copy(state = OcrState.STALE, reason = OcrReason.STALE_PHOTO_OR_DRAFT)
                    original.state == OcrState.RUNNING && original.owner != process -> original.copy(state = OcrState.INTERRUPTED, reason = OcrReason.PROCESS_RESTARTED, recognitionState = if (original.recognitionState == OcrState.RUNNING) OcrState.INTERRUPTED else original.recognitionState, labelAiState = if (original.labelAiState == OcrState.RUNNING) OcrState.INTERRUPTED else original.labelAiState)
                    else -> original
                }
                val json = record.toJson().toMutableMap()
                json.remove("owner")
                json["isCurrent"] = if (current == null) JsonNull else JsonPrimitive(isCurrent)
                json["supportedFieldCount"] = JsonPrimitive(record.supportedFields.size)
                json["unresolvedFieldCount"] = JsonPrimitive(record.unresolvedFields.size)
                json["rejections"] = buildJsonObject {
                    record.unresolvedFields.forEach { put(it, if (it in record.conflictingFields) "CONFLICTING_FIELD_VALUES" else "NO_SUPPORTED_EVIDENCE") }
                }
                add(JsonObject(json))
            } })
            put("currentAttemptRecorded", records.any { current?.contains(it.correlation) == true && latest[it.correlation.draftRef] == it.requestRef })
        }
    }
    private fun read(): List<OcrDiagnosticRecord> = runCatching {
        val raw = storage.read()?.takeIf { it.length <= 64 * 1024 } ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.takeLast(12).mapNotNull { item -> runCatching { recordFromJson(item.jsonObject) }.getOrNull() }.mapNotNull(::safe)
    }.getOrDefault(emptyList())
    private fun safe(r: OcrDiagnosticRecord): OcrDiagnosticRecord? {
        val c = r.correlation
        if (listOfNotNull(c.draftRef, c.sessionRef, c.photoRef, c.attemptRef, c.contextRef, r.requestRef).any { !it.matches(Regex("[a-f0-9]{64}")) }) return null
        val fields = BottleFactField.entries.map { it.wireValue }.toSet()
        fun duration(value: Long?) = value?.coerceIn(0, 86_400_000)
        return r.copy(supportedFields = r.supportedFields.intersect(fields), unresolvedFields = r.unresolvedFields.intersect(fields), conflictingFields = r.conflictingFields.intersect(fields),
            workerElapsedMs = duration(r.workerElapsedMs), recognitionMs = duration(r.recognitionMs), labelAiMs = duration(r.labelAiMs), retryCount = r.retryCount.coerceIn(0, 100))
    }
    private fun save(records: List<OcrDiagnosticRecord>): Boolean = storage.write(JsonArray(records.takeLast(12).mapNotNull(::safe).map { it.toJson() }).toString())
    companion object {
        private val LOCK = Any()
        private val PROCESS = UUID.randomUUID().toString()
        private val ACTIVE = setOf(OcrState.QUEUED, OcrState.RUNNING)
        fun forContext(context: Context) = forDirectory(context.applicationContext.filesDir)
        fun forDirectory(directory: File) = BottleOcrJournal(object : OcrDiagnosticStorage {
            private val file = File(directory, "bottle-ocr-diagnostics.json")
            override fun read() = file.takeIf { it.isFile && it.length() <= 64 * 1024 }?.readText()
            override fun write(value: String): Boolean = runCatching {
                val temp = File(directory, "bottle-ocr-diagnostics.json.tmp")
                temp.outputStream().use { it.write(value.toByteArray()); it.fd.sync() }
                temp.renameTo(file)
            }.getOrDefault(false)
            override fun clear(): Boolean {
                val temp = File(directory, "bottle-ocr-diagnostics.json.tmp")
                return (!file.exists() || file.delete()) && (!temp.exists() || temp.delete())
            }
        })
    }
}

internal fun OcrCorrelation.toJson() = buildJsonObject {
    put("draftRef", draftRef); put("sessionRef", sessionRef); photoRef?.let { put("photoRef", it) }
    attemptRef?.let { put("attemptRef", it) }; put("contextRef", contextRef)
}
internal fun ocrCorrelationFromJson(root: JsonObject) = OcrCorrelation(
    root.getValue("draftRef").jsonPrimitive.content, root.getValue("sessionRef").jsonPrimitive.content,
    root["photoRef"]?.jsonPrimitive?.contentOrNull, root["attemptRef"]?.jsonPrimitive?.contentOrNull,
    root.getValue("contextRef").jsonPrimitive.content,
)
private fun OcrDiagnosticRecord.toJson() = buildJsonObject {
    put("correlation", correlation.toJson()); put("requestRef", requestRef); put("state", state.name); put("phase", phase.name); put("reason", reason.name)
    put("queuedAt", queuedAt); put("updatedAt", updatedAt); startedAt?.let { put("startedAt", it) }
    workerElapsedMs?.let { put("workerElapsedMs", it) }; recognitionMs?.let { put("recognitionMs", it) }; textFound?.let { put("textFound", it) }
    labelAiState?.let { put("labelAiState", it.name) }; labelAiReason?.let { put("labelAiReason", it.name) }; labelAiMs?.let { put("labelAiMs", it) }
    put("supportedFields", JsonArray(supportedFields.sorted().map(::JsonPrimitive)))
    put("unresolvedFields", JsonArray(unresolvedFields.sorted().map(::JsonPrimitive)))
    put("conflictingFields", JsonArray(conflictingFields.sorted().map(::JsonPrimitive)))
    put("retryCount", retryCount); owner?.let { put("owner", it) }
    recognitionState?.let { put("recognitionState", it.name) }; recognitionReason?.let { put("recognitionReason", it.name) }
}
private fun recordFromJson(r: JsonObject): OcrDiagnosticRecord {
    fun text(key: String) = r[key]?.jsonPrimitive?.contentOrNull
    fun number(key: String) = r[key]?.jsonPrimitive?.longOrNull
    fun fields(key: String) = r[key]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    return OcrDiagnosticRecord(ocrCorrelationFromJson(r.getValue("correlation").jsonObject), text("requestRef")!!,
        OcrState.valueOf(text("state")!!), OcrPhase.valueOf(text("phase")!!), OcrReason.valueOf(text("reason")!!),
        number("queuedAt")!!, number("updatedAt")!!, number("startedAt"), number("workerElapsedMs"), number("recognitionMs"),
        r["textFound"]?.jsonPrimitive?.booleanOrNull, text("labelAiState")?.let(OcrState::valueOf), text("labelAiReason")?.let(OcrReason::valueOf),
        number("labelAiMs"), fields("supportedFields"), fields("unresolvedFields"), fields("conflictingFields"),
        r["retryCount"]?.jsonPrimitive?.intOrNull ?: 0, text("owner"),
        text("recognitionState")?.let(OcrState::valueOf), text("recognitionReason")?.let(OcrReason::valueOf))
}
