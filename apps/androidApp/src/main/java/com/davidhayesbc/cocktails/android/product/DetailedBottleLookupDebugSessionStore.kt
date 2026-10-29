package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Persistent local debug toggle with a bounded, expiring ring of independent lookup attempts. */
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
        // An old one-time arm must not silently enable persistent debug capture.
        legacy.cancelNextLookup()
    }

    fun enableDebugMode(appVersion: String, now: Long = nowMillis()): DetailedCaptureArmResult = synchronized(LOCK) {
        cleanupExpired(now)
        val existing = readSession()
        if (existing?.get("active")?.jsonPrimitive?.content == "true") return@synchronized DetailedCaptureArmResult.ALREADY_ARMED
        legacy.cancelNextLookup()
        val root = buildJsonObject {
            put("formatVersion", 1)
            put("sessionId", UUID.randomUUID().toString())
            put("active", true)
            put("startedAtMillis", now.coerceAtLeast(0))
            put("appVersion", appVersion.filter { it.isLetterOrDigit() || it in ".-_+" }.take(80))
        }
        if (writeSession(root)) DetailedCaptureArmResult.ARMED else DetailedCaptureArmResult.STORAGE_ERROR
    }

    /** Off stops new claims. Attempts already claimed retain their own recorder and may finish. */
    fun disableDebugMode(): Boolean = synchronized(LOCK) {
        val session = readSession() ?: return@synchronized true
        if (session["active"]?.jsonPrimitive?.content != "true") return@synchronized true
        writeSession(buildJsonObject {
            session.forEach { (key, value) -> put(key, value) }
            put("active", false)
        })
    }

    fun sessionStatus(now: Long = nowMillis()): DetailedCaptureStatus {
        synchronized(LOCK) {
            cleanupExpired(now)
            val session = readSession()
            val active = session?.get("active")?.jsonPrimitive?.content == "true"
            val statuses = captureStatuses(now)
            val count = statuses.size
            return DetailedCaptureStatus(
                state = if (active) "ACTIVE" else "OFF",
                draftId = null,
                attemptToken = null,
                expiresAtMillis = 0,
                startedAtMillis = session?.number("startedAtMillis"),
                model = null,
                nextLookupArmed = false,
                sessionActive = active,
                sessionExpiresAtMillis = null,
                captureCount = count,
                captureLimit = MAX_RETAINED_CAPTURES,
            )
        }
    }

    /** Capture only local-model attempts that claim while the persistent setting is active. */
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
        if (session.get("active")?.jsonPrimitive?.content != "true") return@synchronized null
        if (!safeId(draftId) || !safeId(attemptToken)) return@synchronized null
        val statuses = captureStatuses(now)
        if (statuses.any { it.draftId == draftId && it.attemptToken == attemptToken }) return@synchronized null
        val sessionId = session["sessionId"]?.jsonPrimitive?.content ?: return@synchronized null
        if (statuses.size >= MAX_RETAINED_CAPTURES) {
            val evictable = statuses
                .filter { status -> status.state != "CAPTURING" && status.captureId != null }
                .minByOrNull { it.startedAtMillis ?: Long.MAX_VALUE }
                ?: return@synchronized null
            if (!deleteCapture(evictable.captureId!!)) return@synchronized null
        }
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

    fun reportContents(draftId: String? = null, now: Long = nowMillis()): List<String> = synchronized(LOCK) {
        captureStatuses(now)
            .filter { draftId == null || it.draftId == draftId }
            .mapNotNull { status ->
                val owner = status.draftId ?: return@mapNotNull null
                val id = status.captureId
                if (id == null) legacy.reportContentForDraft(owner, now)
                else storeForCapture(id)?.reportContentForDraft(owner, now)
            }
    }

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
        val active = readSession()?.get("active")?.jsonPrimitive?.content == "true"
        if (!active) success = deleteIfPresent(sessionFile) && success
        success = deleteIfPresent(sessionTemp) && success
        val shareDir = shareCacheDirectory?.let { File(it, "detailed-lookup-share") }
        shareDir?.listFiles().orEmpty().forEach { success = deleteIfPresent(it) && success }
        success
    }

    fun cleanupExpired(now: Long = nowMillis()): Boolean = synchronized(LOCK) {
        var success = legacy.cleanupExpired(now)
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

    companion object {
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
    }
}
