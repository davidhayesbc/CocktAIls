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
internal suspend fun recognizeBottleLabel(jpeg: ByteArray): String = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ""
    var sample = 1
    while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 ||
        (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 8_000_000
    ) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@withContext ""
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspendCancellableCoroutine { continuation ->
        // ML Kit owns the bitmap until its Task completes, including coroutine cancellation.
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                val text = if (task.isSuccessful) task.result.text.take(8_000) else ""
                bitmap.recycle()
                recognizer.close()
                if (continuation.isActive) continuation.resume(text)
            }
        } catch (_: Exception) {
            bitmap.recycle()
            recognizer.close()
            if (continuation.isActive) continuation.resume("")
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
    fun enqueue(context: Context, draft: BottleIntakeDraft, attemptToken: String?) {
        runCatching { enqueueChecked(context, draft, attemptToken) }
    }
    private fun enqueueChecked(context: Context, draft: BottleIntakeDraft, attemptToken: String?) {
        if (draft.photoData == null || draft.photoSha256 == null) return
        val token = UUID.randomUUID().toString()
        val store = BottleLabelEvidenceStore.forContext(context)
        if (!store.request(draft.id, token)) return
        val request = OneTimeWorkRequestBuilder<BottleLabelOcrWorker>().setInputData(workDataOf(
            "session" to draft.sessionId, "draft" to draft.id, "photo" to draft.photoSha256,
            "request" to token, "attempt" to attemptToken,
        )).addTag("cocktails-bottle-session-${draft.sessionId}").build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(draft.id), ExistingWorkPolicy.REPLACE, request)
    }
    fun cancel(context: Context, draftId: String) {
        BottleLabelEvidenceStore.forContext(context).cancel(draftId)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(name(draftId)) }
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
        runtime.openRepository().use { repositories ->
            fun currentDraft(): BottleIntakeDraft? {
                if (isStopped || !store.current(id, request)) return null
                val job = queue.status(id)
                if (job?.attemptToken != attempt || job?.status in setOf(BottleEnrichmentStatus.CANCELLED, BottleEnrichmentStatus.PAUSED, BottleEnrichmentStatus.REVIEWED)) return null
                return repositories.repository.openBottleIntakeSession()?.takeIf { it.id == session }?.drafts
                    ?.firstOrNull { it.id == id && it.photoSha256 == photo && it.photoData != null }
            }
            val draft = currentDraft() ?: return@withContext Result.success()
            val text = withTimeoutOrNull(30_000) { recognizeBottleLabel(checkNotNull(draft.photoData)) }.orEmpty()
            currentCoroutineContext().ensureActive()
            val extracted = BottleEnrichmentSerialGate.processWide.withSlot {
                val current = currentDraft() ?: return@withSlot null
                val preferences = applicationContext.getSharedPreferences("cocktails_privacy_settings", Context.MODE_PRIVATE)
                val model = if (preferences.getBoolean("local_ai_enabled", false))
                    LocalModelFileStore(File(applicationContext.filesDir, "local-models")).installedModel() else null
                val readyModel = if (model != null) {
                    try { setForeground(createBottleProcessingForegroundInfo(applicationContext)); model }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null } // Explicit OCR facts survive foreground-start restrictions.
                } else null
                val web = PendingBottleEvidenceStore(SharedPreferencesPendingBottleEvidenceStorage(applicationContext)).read(id)
                LocalBottleDetailExtractor({ readyModel?.absolutePath }, LazyLlamaCppLocalInferenceRunner(applicationContext))
                    .extractLabelText(text, verifiedWebFacts(web, current))
            } ?: return@withContext Result.success()
            currentCoroutineContext().ensureActive()
            val current = currentDraft() ?: return@withContext Result.success()
            val evidence = BottleLabelEvidence(session, id, photo, draft.barcode, extracted.facts, extracted.unresolved, extracted.conflicts)
            if (evidence.belongsTo(current)) store.save(request, evidence)
        }
        Result.success()
    }
}
