package com.davidhayesbc.cocktails.android

import android.os.Process
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.lang.Thread.UncaughtExceptionHandler
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugReport

/** Local, allowlisted diagnostics. No free-form user or exception text is persisted. */
internal class DiagnosticStore(
    private val file: File,
    private val metadata: Map<String, String> = emptyMap(),
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    private val lock = Any()

    init {
        synchronized(lock) {
            val root = read()
            write(JsonObject(root.toMutableMap().apply { put("metadata", metadataJson()) }))
        }
    }

    fun recordEvent(category: String, atMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (category !in EVENT_CATEGORIES) return@synchronized
        val root = read()
        val events = (root["events"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        events += buildJsonObject { put("at", atMillis); put("category", category) }
        write(root.with("events", JsonArray(events.takeLast(MAX_EVENTS))))
    }

    fun recordScannerFailure(category: String, failure: Throwable? = null, atMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (category !in SCANNER_FAILURE_CATEGORIES) return@synchronized
        val root = read()
        val errors = (root["scannerErrors"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        errors += buildJsonObject {
            put("at", atMillis)
            put("category", category)
            // Type only. Exception message and arbitrary throwable fields are intentionally excluded.
            put("type", safeType(failure))
            put("build", metadataJson())
        }
        write(root.with("scannerErrors", JsonArray(errors.takeLast(MAX_SCANNER_ERRORS))))
    }

    fun recordCrash(thread: Thread, failure: Throwable, atMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        val root = read()
        val crashes = (root["crashes"] as? JsonArray ?: JsonArray(emptyList())).toMutableList()
        crashes += buildJsonObject {
            put("at", atMillis)
            put("thread", if (thread.name == "main") "main" else "background")
            put("type", safeType(failure))
            // Snapshot the build at crash time so later launches can refresh top-level metadata safely.
            put("build", metadataJson())
            put("frames", buildJsonArray {
                firstPartyFrames(failure).take(MAX_FRAMES).forEach { frame ->
                    add(buildJsonObject {
                        put("class", safeLabel(frame.className))
                        put("method", safeLabel(frame.methodName))
                        put("line", frame.lineNumber.coerceAtLeast(0))
                    })
                }
            })
        }
        write(root.with("crashes", JsonArray(crashes.takeLast(MAX_CRASHES))))
    }

    fun recordBottleLookup(report: BottleLookupDebugReport, draftId: String? = null) = synchronized(lock) {
        val safeReport = runCatching { Json.parseToJsonElement(report.toJson()) }.getOrNull() ?: return@synchronized
        val safeJson = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), safeReport)
        if (safeJson.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) return@synchronized
        write(JsonObject(read().toMutableMap().apply { put("latestBottleLookup", safeReport) }))
        draftId?.takeIf { it.matches(DRAFT_ID_PATTERN) }?.let { id ->
            writeBottleLookupForDraft(id, safeJson)
        }
    }

    fun latestBottleLookupPreview(): String? = synchronized(lock) {
        read()["latestBottleLookup"]?.let { Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it) }
    }

    fun latestBottleLookupPreviewForDraft(draftId: String): String? = synchronized(lock) {
        if (!draftId.matches(DRAFT_ID_PATTERN)) return@synchronized null
        val report = draftLookupFile(draftId)
        report.takeIf { it.isFile && it.length() in 1..MAX_BYTES.toLong() }
            ?.let { runCatching { it.readText(StandardCharsets.UTF_8) }.getOrNull() }
    }

    fun clearBottleLookupForDraft(draftId: String): Boolean = synchronized(lock) {
        if (!draftId.matches(DRAFT_ID_PATTERN)) return@synchronized false
        val report = draftLookupFile(draftId)
        val temporary = File(report.parentFile, "${report.name}.tmp")
        val reportDeleted = !report.exists() || (runCatching { deleteFile(report) }.getOrDefault(false) && !report.exists())
        val temporaryDeleted = !temporary.exists() || (runCatching { deleteFile(temporary) }.getOrDefault(false) && !temporary.exists())
        reportDeleted && temporaryDeleted
    }

    fun preview(): String = export()

    fun export(): String = synchronized(lock) {
        val current = read()
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), current)
    }

    fun clear(): Boolean = synchronized(lock) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        val dataDeleted = !file.exists() || runCatching { deleteFile(file) }.getOrDefault(false)
        val tempDeleted = !temp.exists() || runCatching { deleteFile(temp) }.getOrDefault(false)
        val reports = File(file.parentFile, DRAFT_LOOKUP_DIR)
        val reportFiles = reports.listFiles().orEmpty()
        val reportsDeleted = reportFiles.all { !it.exists() || runCatching { deleteFile(it) }.getOrDefault(false) } &&
            reportFiles.none(File::exists)
        val directoryDeleted = !reports.exists() || runCatching { deleteFile(reports) }.getOrDefault(false)
        dataDeleted && tempDeleted && reportsDeleted && directoryDeleted && !file.exists() && !temp.exists() && !reports.exists()
    }

    private fun writeBottleLookupForDraft(draftId: String, json: String) {
        val directory = File(file.parentFile, DRAFT_LOOKUP_DIR)
        if (!directory.isDirectory && !directory.mkdirs()) return
        val destination = draftLookupFile(draftId)
        val temporary = File(directory, "${destination.name}.tmp")
        runCatching {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            if (destination.exists() && !deleteFile(destination)) return
            if (!temporary.renameTo(destination)) return
        }
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("lookup-") && it.name.endsWith(".json") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_DRAFT_LOOKUP_REPORTS)
            ?.forEach { runCatching { deleteFile(it) } }
    }

    private fun draftLookupFile(draftId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(draftId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(File(file.parentFile, DRAFT_LOOKUP_DIR), "lookup-$digest.json")
    }

    private fun read(): JsonObject = runCatching {
        if (!file.exists() || file.length() > MAX_BYTES) emptyJson()
        else Json.parseToJsonElement(file.readText()).let { it as? JsonObject ?: emptyJson() }
    }.getOrDefault(emptyJson())

    private fun write(root: JsonObject) {
        val bounded = root
        val bytes = Json.encodeToString(JsonObject.serializer(), bounded).encodeToByteArray()
        if (bytes.size > MAX_BYTES) {
            // Records are individually capped; dropping oldest events is the safe recovery path.
            val reduced = bounded.with("events", JsonArray(emptyList()))
            val safeBytes = Json.encodeToString(JsonObject.serializer(), reduced).encodeToByteArray()
            if (safeBytes.size > MAX_BYTES) return
            writeAtomically(safeBytes)
        } else {
            writeAtomically(bytes)
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temp.renameTo(file)) {
                file.outputStream().use { it.write(bytes); it.fd.sync() }
                temp.delete()
            }
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    private fun emptyJson() = buildJsonObject {
        put("formatVersion", 1)
        put("metadata", metadataJson())
        put("events", JsonArray(emptyList()))
        put("scannerErrors", JsonArray(emptyList()))
        put("crashes", JsonArray(emptyList()))
    }

    private fun metadataJson() = buildJsonObject {
        metadata.forEach { (k, v) ->
            if (k in METADATA_FIELDS) put(k, safeLabel(v))
        }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        const val MAX_EVENTS = 80
        private const val DRAFT_LOOKUP_DIR = "bottle-lookup-reports"
        private const val MAX_DRAFT_LOOKUP_REPORTS = 12
        private val DRAFT_ID_PATTERN = Regex("[A-Za-z0-9-]{1,100}")
        const val MAX_SCANNER_ERRORS = 20
        const val MAX_CRASHES = 5
        const val MAX_FRAMES = 30
        val EVENT_CATEGORIES = setOf(
            "APP_STARTED", "CAMERA_PERMISSION_GRANTED", "CAMERA_PERMISSION_DENIED",
            "CAMERA_SCAN_OPENED", "CAMERA_PREVIEW_STREAMING", "BARCODE_DECODED",
            "LABEL_CAMERA_READY", "LABEL_CAMERA_STORAGE_FAILED", "LABEL_CAMERA_CAPTURE_STARTED",
            "LABEL_CAMERA_CAPTURE_SAVED", "LABEL_CAMERA_CAPTURE_FAILED",
            "LABEL_PHOTO_PROCESSING_STARTED", "LABEL_PHOTO_PROCESSING_FAILED",
            "LABEL_PHOTO_READY_FOR_REVIEW", "LABEL_PHOTO_APPLIED", "LABEL_PHOTO_SKIPPED",
        )
        val SCANNER_FAILURE_CATEGORIES = setOf(
            "CAMERA_PROVIDER_FAILED", "CAMERA_START_FAILED", "CAMERA_FRAME_FAILED", "BARCODE_DECODE_FAILED",
        )
        private val METADATA_FIELDS = setOf("versionName", "versionCode", "androidApi", "device")

        private fun safeType(t: Throwable?) = t?.javaClass?.simpleName?.take(64)?.filter { it.isLetterOrDigit() || it == '_' } ?: "Unknown"
        private fun safeLabel(value: String) = value.take(100).filter { it.isLetterOrDigit() || it in "._-$" }
        private fun firstPartyFrames(t: Throwable): List<StackTraceElement> = t.stackTrace.filter {
            it.className.startsWith("com.davidhayesbc.cocktails")
        }
    }
}

internal object LocalDiagnostics {
    @Volatile private var store: DiagnosticStore? = null
    private val installed = AtomicBoolean(false)

    fun initialize(directory: File, versionName: String, versionCode: Long) {
        store = DiagnosticStore(
            File(directory, "local-diagnostics.json"),
            mapOf("versionName" to versionName, "versionCode" to versionCode.toString(), "androidApi" to android.os.Build.VERSION.SDK_INT.toString(), "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"),
        )
        if (installed.compareAndSet(false, true)) installHandler()
        recordEvent("APP_STARTED")
    }

    fun initializeIfNeeded(directory: File, versionName: String, versionCode: Long) {
        if (store != null) return
        synchronized(this) {
            if (store == null) initialize(directory, versionName, versionCode)
        }
    }

    internal fun createUncaughtExceptionHandler(
        prior: UncaughtExceptionHandler?,
        recorder: (Thread, Throwable) -> Unit,
        noPriorHandler: (Thread, Throwable) -> Unit,
    ): UncaughtExceptionHandler = UncaughtExceptionHandler { thread, error ->
        runCatching { recorder(thread, error) }
        if (prior != null) prior.uncaughtException(thread, error) else noPriorHandler(thread, error)
    }

    private fun installHandler() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(createUncaughtExceptionHandler(
            prior = prior,
            recorder = { thread, error -> store?.recordCrash(thread, error) },
            noPriorHandler = { _, _ ->
                Process.killProcess(Process.myPid())
                exitProcess(10)
            },
        ))
    }

    fun recordEvent(category: String) { runCatching { store?.recordEvent(category) } }
    fun recordScannerFailure(category: String, error: Throwable? = null) { runCatching { store?.recordScannerFailure(category, error) } }
    fun recordBottleLookup(report: BottleLookupDebugReport, draftId: String? = null) {
        runCatching { store?.recordBottleLookup(report, draftId) }
    }
    fun latestBottleLookupPreview(): String? = runCatching { store?.latestBottleLookupPreview() }.getOrNull()
    fun latestBottleLookupPreviewForDraft(draftId: String): String? =
        runCatching { store?.latestBottleLookupPreviewForDraft(draftId) }.getOrNull()
    fun clearBottleLookupForDraft(draftId: String): Boolean =
        runCatching { store?.clearBottleLookupForDraft(draftId) ?: false }.getOrDefault(false)
    fun preview(): String = runCatching { store?.preview().orEmpty() }.getOrDefault("Diagnostics are unavailable.")
    fun export(): String = runCatching { store?.export().orEmpty() }.getOrDefault("{}")
    fun clear(): Boolean = runCatching { store?.clear() == true }.getOrDefault(false)
}

private fun JsonObject.with(key: String, value: JsonArray): JsonObject = JsonObject(toMutableMap().apply { put(key, value) })
