package com.davidhayesbc.cocktails.android.product

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.*
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Bundled Latin recognition; no cloud call and no full-text persistence or logging. */
internal suspend fun recognizeBottleLabel(jpeg: ByteArray): String = recognizeBottleLabelResult(jpeg).text
internal data class LabelRecognition(val text: String, val reason: OcrReason = OcrReason.NONE)
internal suspend fun recognizeBottleLabelResult(jpeg: ByteArray): LabelRecognition = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext LabelRecognition("", OcrReason.INVALID_IMAGE)
    var sample = 1
    while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 ||
        (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 8_000_000
    ) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@withContext LabelRecognition("", OcrReason.INVALID_IMAGE)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspendCancellableCoroutine { continuation ->
        // ML Kit owns the bitmap until its Task completes, including coroutine cancellation.
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                val text = if (task.isSuccessful) task.result.text.take(8_000) else ""
                bitmap.recycle()
                recognizer.close()
                if (continuation.isActive) continuation.resume(LabelRecognition(text, if (task.isSuccessful) OcrReason.NONE else OcrReason.RECOGNITION_FAILED))
            }
        } catch (_: Exception) {
            bitmap.recycle()
            recognizer.close()
            if (continuation.isActive) continuation.resume(LabelRecognition("", OcrReason.RECOGNITION_FAILED))
        }
    }
}

internal class BottleLabelEvidenceStore(private val storage: PendingBottleEvidenceStorage) {
    fun request(draftId: String, token: String): Boolean = synchronized(LOCK) { storage.write("request-$draftId", token) }
    fun cancel(draftId: String) = synchronized(LOCK) { storage.remove("request-$draftId") }
    fun remove(draftId: String) = synchronized(LOCK) { cancel(draftId); storage.remove("label-$draftId") }
    fun current(draftId: String, token: String): Boolean = synchronized(LOCK) { storage.read("request-$draftId") == token }
    fun save(token: String, evidence: BottleLabelEvidence): Boolean = synchronized(LOCK) {
        if (!current(evidence.draftId, token)) return false
        val json = buildJsonObject {
            put("session", evidence.sessionId); put("draft", evidence.draftId); put("photo", evidence.photoSha256)
            evidence.barcode?.let { put("barcode", it) }
            put("facts", buildJsonArray { evidence.facts.take(4).forEach { fact ->
                add(buildJsonObject { put("field", fact.field.wireValue); put("value", fact.value.take(120)); put("quote", fact.quote.take(320)) })
            } })
            put("conflicts", buildJsonArray { evidence.conflicts.forEach { add(JsonPrimitive(it.wireValue)) } })
            put("unresolved", buildJsonArray { evidence.unresolved.forEach { add(JsonPrimitive(it.wireValue)) } })
        }.toString()
        json.length <= 8_000 && storage.write("label-${evidence.draftId}", json)
    }
    fun read(draft: BottleIntakeDraft): BottleLabelEvidence? = runCatching {
        val raw = storage.read("label-${draft.id}")?.takeIf { it.length <= 8_000 } ?: return null
        val root = Json.parseToJsonElement(raw).jsonObject
        fun value(key: String) = root[key]?.jsonPrimitive?.contentOrNull
        val facts = root["facts"]!!.jsonArray.mapNotNull { item ->
            val fact = item.jsonObject
            val field = BottleFactField.entries.firstOrNull { it.wireValue == fact["field"]?.jsonPrimitive?.contentOrNull } ?: return@mapNotNull null
            val text = fact["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..120 } ?: return@mapNotNull null
            val quote = fact["quote"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..320 } ?: return@mapNotNull null
            BottleFactEvidence(field, text, quote, fromLabelPhoto = true)
        }
        if (facts.size > 4) return null
        BottleLabelEvidence(value("session")!!, value("draft")!!, value("photo")!!, value("barcode"), facts,
            root["unresolved"]!!.jsonArray.mapNotNull { item -> BottleFactField.entries.firstOrNull { it.wireValue == item.jsonPrimitive.contentOrNull } }.toSet(),
            root["conflicts"]?.jsonArray.orEmpty().mapNotNull { item -> BottleFactField.entries.firstOrNull { it.wireValue == item.jsonPrimitive.contentOrNull } }.toSet())
            .takeIf { it.belongsTo(draft) }
    }.getOrNull()
    companion object {
        private val LOCK = Any()
        fun forContext(context: Context): BottleLabelEvidenceStore {
            val prefs = context.applicationContext.getSharedPreferences("cocktails_private_label_evidence", Context.MODE_PRIVATE)
            return BottleLabelEvidenceStore(object : PendingBottleEvidenceStorage {
                override fun read(draftId: String) = prefs.getString(draftId, null)
                override fun write(draftId: String, value: String) = prefs.edit().putString(draftId, value).commit()
                override fun remove(draftId: String) = prefs.edit().remove(draftId).commit()
            })
        }
    }
}

internal object BottleLabelOcrScheduler {
    private fun name(id: String) = "cocktails-label-$id"
    fun enqueue(context: Context, draft: BottleIntakeDraft, attemptToken: String?, trigger: OcrTrigger = OcrTrigger.UNKNOWN) {
        runCatching { enqueueChecked(context, draft, attemptToken, trigger) }
    }
    private fun enqueueChecked(context: Context, draft: BottleIntakeDraft, attemptToken: String?, trigger: OcrTrigger) {
        val runtime = BottleScanEnrichmentRuntime(context)
        val journal = BottleOcrJournal.forContext(context)
        BottleLabelSchedulingGate.processWide.serialized {
            val (current, job) = runtime.openRepository().use { repositories ->
                repositories.repository.openBottleIntakeSession()?.takeIf { it.id == draft.sessionId }
                    ?.drafts?.firstOrNull { it.id == draft.id } to
                    BottleEnrichmentQueue(SharedPreferencesBottleEnrichmentQueueStorage(context)).status(draft.id)
            }
            // An obsolete scheduling callback must not replace either current work or its diagnostics.
            if (current == null || current.photoSha256 != draft.photoSha256 || current.barcode != draft.barcode ||
                job?.attemptToken != attemptToken || job != null && (job.sessionId != draft.sessionId || job.draftId != draft.id)) return@serialized
            val correlation = OcrCorrelation.of(current, attemptToken)
            val token = UUID.randomUUID().toString()
            if (job?.status in setOf(BottleEnrichmentStatus.CANCELLED, BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.REVIEWED)) {
                runCatching { journal.begin(correlation, token, OcrState.SKIPPED, OcrReason.QUEUE_INACTIVE, trigger) }
                return@serialized
            }
            if (current.photoData == null || current.photoSha256 == null) {
                runCatching { journal.begin(correlation, token, OcrState.SKIPPED, OcrReason.MISSING_PHOTO, trigger) }
                return@serialized
            }
            BottleLabelSchedulingGate.processWide.scheduleIfCurrent(draft, attemptToken, { current to job }) {
                runCatching { journal.begin(correlation, token, trigger = trigger) }
                fun failed(reason: OcrReason) { runCatching { journal.update(correlation, token) { it.copy(state = OcrState.FAILED, reason = reason) } } }
                val store = BottleLabelEvidenceStore.forContext(context)
                if (!store.request(draft.id, token)) { failed(OcrReason.REQUEST_STORAGE_FAILED); return@scheduleIfCurrent false }
                try {
                    val request = OneTimeWorkRequestBuilder<BottleLabelOcrWorker>().setInputData(workDataOf(
                        "session" to draft.sessionId, "draft" to draft.id, "photo" to draft.photoSha256,
                        "request" to token, "attempt" to attemptToken,
                        "ocr_context" to correlation.toJson().toString(),
                    )).addTag("cocktails-bottle-session-${draft.sessionId}").build()
                    val operation = WorkManager.getInstance(context).enqueueUniqueWork(name(draft.id), ExistingWorkPolicy.REPLACE, request)
                    operation.result.addListener({ runCatching { operation.result.get() }.onFailure { failed(OcrReason.ENQUEUE_FAILED) } }, java.util.concurrent.Executor { it.run() })
                    true
                } catch (_: Exception) { failed(OcrReason.ENQUEUE_FAILED); false }
            }
        }
    }
    fun cancel(context: Context, draftId: String) {
        BottleLabelSchedulingGate.processWide.serialized {
            runCatching { BottleOcrJournal.forContext(context).cancelDraft(draftId) }
            BottleLabelEvidenceStore.forContext(context).cancel(draftId)
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(name(draftId)) }
        }
    }
}

internal class BottleLabelOcrWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("draft") ?: return@withContext Result.failure()
        val session = inputData.getString("session") ?: return@withContext Result.failure()
        val photo = inputData.getString("photo") ?: return@withContext Result.failure()
        val request = inputData.getString("request") ?: return@withContext Result.failure()
        val attempt = inputData.getString("attempt")
        val store = BottleLabelEvidenceStore.forContext(applicationContext)
        val queue = BottleEnrichmentQueue(SharedPreferencesBottleEnrichmentQueueStorage(applicationContext))
        val runtime = BottleScanEnrichmentRuntime(applicationContext)
        val journal = BottleOcrJournal.forContext(applicationContext)
        // Old queued work may predate diagnostics; create its correlation only from an authoritative draft.
        var correlation = runCatching { inputData.getString("ocr_context")?.let { ocrCorrelationFromJson(Json.parseToJsonElement(it).jsonObject) } }.getOrNull()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000
        fun update(change: (OcrDiagnosticRecord) -> OcrDiagnosticRecord) { correlation?.let { c -> runCatching { journal.update(c, request, change) } } }
        fun finish(state: OcrState, reason: OcrReason) = update { it.stopped(state, reason).copy(workerElapsedMs = elapsed()) }
        try {
            runtime.openRepository().use { repositories ->
                fun currentDraft(): BottleIntakeDraft? {
                    if (isStopped) { finish(OcrState.INTERRUPTED, OcrReason.WORK_STOPPED); return null }
                    if (!store.current(id, request)) { finish(OcrState.STALE, OcrReason.SUPERSEDED_REQUEST); return null }
                    val job = queue.status(id)
                    if (job?.attemptToken != attempt) { finish(OcrState.STALE, OcrReason.STALE_ATTEMPT); return null }
                    if (job?.status in setOf(BottleEnrichmentStatus.CANCELLED, BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.REVIEWED)) {
                        finish(OcrState.CANCELLED, OcrReason.QUEUE_INACTIVE); return null
                    }
                    val current = repositories.repository.openBottleIntakeSession()?.takeIf { it.id == session }?.drafts
                        ?.firstOrNull { it.id == id && it.photoSha256 == photo && it.photoData != null }
                    if (current == null || correlation?.let { it != OcrCorrelation.of(current, attempt) } == true) {
                        finish(OcrState.STALE, OcrReason.STALE_PHOTO_OR_DRAFT); return null
                    }
                    return current
                }
                val draft = currentDraft() ?: return@withContext Result.success()
                if (correlation == null) {
                    val registered = BottleLabelSchedulingGate.processWide.serialized {
                        val latest = currentDraft() ?: return@serialized false
                        correlation = OcrCorrelation.of(latest, attempt)
                        runCatching { journal.begin(correlation!!, request) }
                        true
                    }
                    if (!registered) return@withContext Result.success()
                }
                runCatching { journal.start(correlation!!, request, runAttemptCount) }
                val recognitionStarted = System.nanoTime()
                val recognition = withTimeoutOrNull(30_000) { recognizeBottleLabelResult(checkNotNull(draft.photoData)) }
                    ?: LabelRecognition("", OcrReason.RECOGNITION_TIMEOUT)
                update { it.copy(recognitionMs = (System.nanoTime() - recognitionStarted).coerceAtLeast(0) / 1_000_000, textFound = recognition.text.isNotBlank(), textCharacterCount = recognition.text.take(8000).length, recognitionState = if (recognition.reason == OcrReason.NONE) OcrState.COMPLETED else OcrState.FAILED, recognitionReason = recognition.reason) }
                currentCoroutineContext().ensureActive()
                if (recognition.reason != OcrReason.NONE) { finish(OcrState.FAILED, recognition.reason); return@withContext Result.success() }
                if (currentDraft() == null) return@withContext Result.success()
                update { it.copy(phase = OcrPhase.WAITING_FOR_LABEL_AI) }
                val waitingStarted = System.nanoTime()
                val extracted = BottleEnrichmentSerialGate.processWide.withSlot {
                    update { it.copy(labelAiWaitMs = (System.nanoTime() - waitingStarted).coerceAtLeast(0) / 1_000_000) }
                    val current = currentDraft() ?: return@withSlot null
                    val preferences = applicationContext.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
                    val enabled = preferences.getBoolean("local_ai_enabled", false)
                    val model = if (enabled) LocalModelFileStore(File(applicationContext.filesDir, "local-models")).installedModel() else null
                    var skip = if (!enabled) OcrReason.LOCAL_AI_DISABLED else OcrReason.MODEL_MISSING
                    val readyModel = if (model != null && recognition.text.isNotBlank()) {
                        try { setForeground(createBottleProcessingForegroundInfo(applicationContext)); model }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { skip = OcrReason.FOREGROUND_UNAVAILABLE; null }
                    } else null
                    val web = PendingBottleEvidenceStore(SharedPreferencesPendingBottleEvidenceStorage(applicationContext)).read(id)
                    LocalBottleDetailExtractor({ readyModel?.absolutePath }, LazyLlamaCppLocalInferenceRunner(applicationContext))
                        .extractLabelText(recognition.text, verifiedWebFacts(web, current)) { state, reason, duration ->
                            update { it.copy(phase = if (state == OcrState.RUNNING) OcrPhase.LABEL_AI else OcrPhase.VALIDATION,
                                labelAiState = state, labelAiReason = if (state == OcrState.SKIPPED && reason != OcrReason.NO_TEXT) skip else reason, labelAiMs = duration) }
                        }
                } ?: return@withContext Result.success()
                currentCoroutineContext().ensureActive()
                val current = currentDraft() ?: return@withContext Result.success()
                val evidence = BottleLabelEvidence(session, id, photo, draft.barcode, extracted.facts, extracted.unresolved, extracted.conflicts)
                val saved = evidence.belongsTo(current) && store.save(request, evidence)
                update { it.copy(state = if (saved) OcrState.COMPLETED else OcrState.FAILED, phase = OcrPhase.DONE,
                    reason = if (!saved) OcrReason.EVIDENCE_STORAGE_FAILED else if (recognition.text.isBlank()) OcrReason.NO_TEXT
                        else if (extracted.explicitCandidateCount + extracted.modelCandidateCount == 0) OcrReason.NO_CANDIDATES
                        else if (extracted.facts.isEmpty()) OcrReason.NO_ACCEPTED_FIELDS else OcrReason.NONE,
                    explicitCandidateCount = extracted.explicitCandidateCount, modelCandidateCount = extracted.modelCandidateCount,
                    workerElapsedMs = elapsed(), supportedFields = extracted.facts.map { fact -> fact.field.wireValue }.toSet(),
                    unresolvedFields = extracted.unresolved.map { field -> field.wireValue }.toSet(), conflictingFields = extracted.conflicts.map { field -> field.wireValue }.toSet()) }
            }
            Result.success()
        } catch (cancelled: CancellationException) { finish(OcrState.INTERRUPTED, OcrReason.WORK_STOPPED); throw cancelled }
        catch (_: Exception) { finish(OcrState.FAILED, OcrReason.WORKER_FAILED); Result.failure() }
        catch (_: LinkageError) { finish(OcrState.FAILED, OcrReason.WORKER_FAILED); Result.failure() }
    }
}
