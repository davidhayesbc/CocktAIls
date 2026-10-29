package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A short, explicit local debug window which can retain several independent lookup attempts. */
internal class DetailedBottleLookupDebugSessionStore(
    private val directory: File,
    private val shareCacheDirectory: File? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val processSessionId: String = PROCESS_SESSION_ID,
) {
    private val stores = mutableMapOf<String, DetailedBottleLookupCaptureStore>()
    private val legacy = DetailedBottleLookupCaptureStore(
        File(directory, LEGACY_FILE), nowMillis, shareCacheDirectory = shareCacheDirectory,
        processSessionId = processSessionId,
    )

    init {
        // Do not silently carry the older one-attempt consent into a multi-attempt session.
        legacy.cancelNextLookup()
    }

    fun startSession(appVersion: String, now: Long = nowMillis()): DetailedCaptureArmResult = synchronized(LOCK) {
        cleanupExpired(now)
        val existing = readSession()
        if (existing != null && existing.get("active")?.jsonPrimitive?.content != "false" && (existing.number("expiresAtMillis") ?: 0) > now) return@synchronized DetailedCaptureArmResult.ALREADY_ARMED
        // An old one-shot arm must not unexpectedly capture outside the new bounded session.
        legacy.cancelNextLookup()
        val root = buildJsonObject {
            put("formatVersion", 1)
            put("sessionId", UUID.randomUUID().toString())
            put("active", true)
            put("startedAtMillis", now.coerceAtLeast(0))
            put("expiresAtMillis", (now + SESSION_MILLIS).coerceAtLeast(now))
            put("claimedCount", 0)
            put("appVersion", appVersion.filter { it.isLetterOrDigit() || it in ".-_+" }.take(80))
        }
        if (writeSession(root)) DetailedCaptureArmResult.ARMED else DetailedCaptureArmResult.STORAGE_ERROR
    }

    /** Off stops new claims. Attempts already claimed retain their own recorder and may finish. */
    fun stopSession(now: Long = nowMillis()): Boolean = synchronized(LOCK) {
        val session = readSession() ?: return@synchronized false
        val expiry = session.number("expiresAtMillis") ?: return@synchronized false
        if (expiry <= now) {
            deleteIfPresent(sessionFile) && deleteIfPresent(sessionTemp)
        } else {
            // Persist an explicit off state; do not remove captures or invalidate in-flight recorders.
            writeSession(buildJsonObject {
                session.forEach { (key, value) -> put(key, value) }
                put("active", false)
            })
        }
    }

    fun sessionStatus(now: Long = nowMillis()): DetailedCaptureStatus {
        synchronized(LOCK) {
            cleanupExpired(now)
            val session = readSession()
            val active = session?.get("active")?.jsonPrimitive?.content != "false" &&
                (session?.number("expiresAtMillis") ?: 0) > now
            val statuses = captureStatuses(now)
            val sessionId = session?.get("sessionId")?.jsonPrimitive?.content
            val count = session?.numberInt("claimedCount")
                ?: statuses.count { it.debugSessionId == sessionId && sessionId != null }
            return DetailedCaptureStatus(
                state = when {
                    active && count >= MAX_SESSION_CAPTURES -> "LIMIT_REACHED"
                    active && statuses.size >= MAX_RETAINED_CAPTURES -> "STORAGE_LIMIT_REACHED"
                    active -> "ACTIVE"
                    else -> "OFF"
                },
                draftId = null,
                attemptToken = null,
                expiresAtMillis = session?.number("expiresAtMillis") ?: 0,
                startedAtMillis = session?.number("startedAtMillis"),
                model = null,
                nextLookupArmed = active && count < MAX_SESSION_CAPTURES,
                sessionActive = active,
                sessionExpiresAtMillis = session?.number("expiresAtMillis"),
                captureCount = count,
                captureLimit = MAX_SESSION_CAPTURES,
            )
        }
    }

    /** Capture only local-model attempts that claim while the session is active. */
    fun claim(
        draftId: String,
        attemptToken: String,
        barcode: String,
        appVersion: String,
        appVersionCode: Long,
        model: String?,
        now: Long = nowMillis(),
        allowNextLookup: Boolean = false,
    ): DetailedBottleLookupRecorder? = synchronized(LOCK) {
        if (!allowNextLookup) return@synchronized null
        cleanupExpired(now)
        val session = readSession() ?: return@synchronized null
        if (session.get("active")?.jsonPrimitive?.content == "false" || (session.number("expiresAtMillis") ?: 0) <= now) return@synchronized null
        if (!safeId(draftId) || !safeId(attemptToken)) return@synchronized null
        val statuses = captureStatuses(now)
        if (statuses.any { it.draftId == draftId && it.attemptToken == attemptToken }) return@synchronized null
        val sessionId = session["sessionId"]?.jsonPrimitive?.content ?: return@synchronized null
        val claimedCount = session.numberInt("claimedCount") ?: statuses.count { it.debugSessionId == sessionId }
        if (claimedCount >= MAX_SESSION_CAPTURES || statuses.size >= MAX_RETAINED_CAPTURES) return@synchronized null
        // Persist the budget before the report file so deletion cannot reset the session cap.
        // A crash in between may conservatively consume a slot, but can never capture too many.
        val updatedSession = buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("claimedCount", claimedCount + 1)
        }
        if (!writeSession(updatedSession)) return@synchronized null
        val captureId = UUID.randomUUID().toString()
        val store = childStore(captureId)
        if (store.arm(draftId, attemptToken, appVersion, now, captureIdOverride = captureId, debugSessionId = sessionId) != DetailedCaptureArmResult.ARMED) return@synchronized null
        store.claim(draftId, attemptToken, barcode, appVersion, appVersionCode, model, now)
    }

    fun pendingAttemptWasMissed(draftId: String, attemptToken: String, now: Long = nowMillis()) = synchronized(LOCK) {
        val status = captureStatuses(now).firstOrNull { it.draftId == draftId && it.attemptToken == attemptToken } ?: return@synchronized
        status.captureId?.let { id -> storeForCapture(id)?.pendingAttemptWasMissed(draftId, attemptToken, now) }
    }

    fun statusForDraft(draftId: String, now: Long = nowMillis()): DetailedCaptureStatus? =
        captureStatuses(now).firstOrNull { it.draftId == draftId }
            ?: legacy.statusForDraft(draftId, now)

    fun currentStatus(now: Long = nowMillis()): DetailedCaptureStatus? =
        captureStatuses(now).firstOrNull() ?: legacy.currentStatus(now) ?: sessionStatus(now)

    fun captureStatuses(now: Long = nowMillis()): List<DetailedCaptureStatus> = synchronized(LOCK) {
        cleanupExpiredChildren(now)
        val statuses = recordsDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.matches(RECORD_NAME) }
            .mapNotNull { file ->
                val id = file.name.removePrefix("capture-").removeSuffix(".json")
                childStore(id).currentStatus(now)?.takeIf { it.captureId == id }
            }
            .toList()
        val old = legacy.currentStatus(now)?.takeIf { it.draftId != null }
        (statuses + listOfNotNull(old)).sortedByDescending { it.startedAtMillis ?: 0 }
    }

    fun previewForCapture(captureId: String, now: Long = nowMillis()): String? =
        storeForCapture(captureId)?.let { store -> draftForCapture(captureId)?.let { store.previewForDraft(it, now) } }

    fun previewForDraft(draftId: String, now: Long = nowMillis()): String? {
        val status = statusForDraft(draftId, now) ?: return null
        return status.captureId?.let { previewForCapture(it, now) } ?: legacy.previewForDraft(draftId, now)
    }

    fun isReviewedPreviewCurrent(draftId: String, preview: String, now: Long = nowMillis()): Boolean {
        val id = previewCaptureId(preview) ?: return legacy.isReviewedPreviewCurrent(draftId, preview, now)
        val status = captureStatuses(now).firstOrNull { it.captureId == id && it.draftId == draftId } ?: return false
        return storeForCapture(id)?.isReviewedPreviewCurrent(draftId, preview, now) == true
    }

    fun copyReviewedPreview(preview: String, copy: (String) -> Unit): Boolean {
        val id = previewCaptureId(preview) ?: return false
        val status = captureStatuses().firstOrNull { it.captureId == id } ?: return false
        return storeForCapture(id)?.copyReviewedPreviewForDraft(status.draftId ?: return false, preview, copy = copy) == true
    }

    fun exportReviewedPreview(preview: String, cacheDirectory: File): File? {
        val id = previewCaptureId(preview) ?: return null
        val status = captureStatuses().firstOrNull { it.captureId == id } ?: return null
        return storeForCapture(id)?.exportReviewedCopyForDraft(status.draftId ?: return null, preview, cacheDirectory)
    }

    fun captureIdInPreview(preview: String): String? = previewCaptureId(preview)

    fun deleteCapture(captureId: String): Boolean = synchronized(LOCK) {
        if (!captureId.matches(UUID_PATTERN)) return@synchronized false
        val status = captureStatuses().firstOrNull { it.captureId == captureId } ?: return false
        val draftId = status.draftId ?: return false
        val file = recordFile(captureId)
        val deleted = if (file.exists()) childStore(captureId).deleteForDraft(draftId) && deleteIfPresent(file) && deleteIfPresent(File(file.parentFile, "${file.name}.tmp"))
        else legacy.deleteForDraft(draftId)
        if (deleted) stores.remove(captureId)
        deleted
    }

    fun deleteForDraft(draftId: String): Boolean = synchronized(LOCK) {
        val matching = captureStatuses().filter { it.draftId == draftId }
        var success = true
        matching.forEach { status -> status.captureId?.let { success = deleteCapture(it) && success } }
        legacy.deleteForDraft(draftId) && success
    }

    fun deleteAll(): Boolean = synchronized(LOCK) {
        var success = legacy.deleteAll()
        recordsDirectory.listFiles().orEmpty().filter { it.isFile && it.name.matches(RECORD_NAME) }.forEach { file ->
            val captureId = file.name.removePrefix("capture-").removeSuffix(".json")
            val draftId = childStore(captureId).currentStatus()?.draftId
            if (draftId != null) success = childStore(captureId).deleteForDraft(draftId) && success
            success = deleteIfPresent(file) && success
        }
        recordsDirectory.listFiles().orEmpty().filterNot { it.name.matches(RECORD_NAME) }.forEach { success = deleteIfPresent(it) && success }
        stores.clear()
        success = deleteIfPresent(sessionFile) && success
        success = deleteIfPresent(sessionTemp) && success
        val shareDir = shareCacheDirectory?.let { File(it, "detailed-lookup-share") }
        shareDir?.listFiles().orEmpty().forEach { success = deleteIfPresent(it) && success }
        success
    }

    fun cleanupExpired(now: Long = nowMillis()): Boolean = synchronized(LOCK) {
        var success = legacy.cleanupExpired(now)
        val session = readSession()
        if (session != null && (session.number("expiresAtMillis") ?: 0) <= now) success = deleteIfPresent(sessionFile) && success
        success = deleteIfPresent(sessionTemp) && success
        success = cleanupExpiredChildren(now) && success
        success
    }

    private fun cleanupExpiredChildren(now: Long): Boolean {
        var success = true
        recordsDirectory.listFiles().orEmpty().filter { it.isFile && it.name.matches(RECORD_NAME) }.forEach { file ->
            val id = file.name.removePrefix("capture-").removeSuffix(".json")
            val status = childStore(id).currentStatus(now)
            if (status == null) success = deleteIfPresent(file) && deleteIfPresent(File(file.parentFile, "${file.name}.tmp")) && success
        }
        // A process can stop after writing a temporary record but before the first
        // atomic rename. Such a file has no captureId/status to enumerate above.
        recordsDirectory.listFiles().orEmpty().filter { it.isFile && it.name.matches(RECORD_TEMP_NAME) }.forEach { temp ->
            val record = File(recordsDirectory, temp.name.removeSuffix(".tmp"))
            if (!record.exists()) success = deleteIfPresent(temp) && success
        }
        if (recordsDirectory.exists() && recordsDirectory.listFiles() == null) success = false
        return success
    }

    private fun storeForCapture(id: String): DetailedBottleLookupCaptureStore? =
        if (id.matches(UUID_PATTERN) && recordFile(id).isFile) childStore(id)
        else if (legacy.currentStatus()?.captureId == id) legacy else null

    private fun draftForCapture(id: String): String? = captureStatuses().firstOrNull { it.captureId == id }?.draftId
    private fun childStore(id: String) = synchronized(LOCK) {
        stores.getOrPut(id) {
            DetailedBottleLookupCaptureStore(recordFile(id), nowMillis, shareCacheDirectory = shareCacheDirectory, processSessionId = processSessionId)
        }
    }
    private fun recordFile(id: String) = File(recordsDirectory, "capture-$id.json")
    private val recordsDirectory get() = File(directory, RECORDS_DIR)
    private val sessionFile get() = File(directory, SESSION_FILE)
    private val sessionTemp get() = File(directory, "$SESSION_FILE.tmp")

    private fun readSession(): JsonObject? = runCatching {
        if (!sessionFile.isFile || sessionFile.length() !in 1..4096) null
        else Json.parseToJsonElement(sessionFile.readText()).jsonObject
    }.getOrNull()

    private fun writeSession(value: JsonObject): Boolean = runCatching {
        if (!directory.isDirectory && !directory.mkdirs()) return false
        FileOutputStream(sessionTemp).use { out -> out.write(value.toString().toByteArray()); out.fd.sync() }
        if (!sessionTemp.renameTo(sessionFile)) return false
        true
    }.getOrDefault(false)

    private fun deleteIfPresent(file: File) = !file.exists() || runCatching { file.delete() && !file.exists() }.getOrDefault(false)

    private fun previewCaptureId(preview: String): String? = runCatching {
        Json.parseToJsonElement(preview).jsonObject["captureId"]?.jsonPrimitive?.content
            ?.takeIf { it.matches(UUID_PATTERN) }
    }.getOrNull()

    companion object {
        const val SESSION_MILLIS = 2 * 60 * 60 * 1000L
        const val MAX_SESSION_CAPTURES = 5
        const val MAX_RETAINED_CAPTURES = 10
        private const val SESSION_FILE = "detailed-lookup-debug-session.json"
        private const val LEGACY_FILE = "detailed-lookup-capture.json"
        private const val RECORDS_DIR = "detailed-lookup-captures"
        private val PROCESS_SESSION_ID = UUID.randomUUID().toString()
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val RECORD_NAME = Regex("capture-$UUID_PATTERN\\.json")
        private val RECORD_TEMP_NAME = Regex("capture-$UUID_PATTERN\\.json\\.tmp")
        private val LOCK = Any()
        private fun safeId(value: String) = value.length in 1..100 && value.matches(Regex("[A-Za-z0-9_-]{1,100}"))
        private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.longOrNull
        private fun JsonObject.numberInt(key: String) = this[key]?.jsonPrimitive?.intOrNull
    }
}
