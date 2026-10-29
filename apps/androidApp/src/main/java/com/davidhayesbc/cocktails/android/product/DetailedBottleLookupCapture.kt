package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Extra lookup material is opt-in, local-only, bounded, and expires after one day. */
internal interface DetailedBottleLookupRecorder {
    fun recordSearchSelection(hits: List<WebSearchHit>, selectedIndex: Int?, decision: String)
    fun recordFetchedPage(
        index: Int,
        url: String,
        title: String?,
        barcodeDecision: String,
        structuredIdentities: List<StructuredBottleIdentity>,
        structuredIdentityStatus: StructuredIdentityStatus?,
    )
    fun recordValidatorInput(
        provider: String,
        barcode: String?,
        sourceText: String,
        pageTitle: String?,
        structuredIdentities: List<StructuredBottleIdentity>,
        structuredIdentityStatus: StructuredIdentityStatus?,
        selectedStructuredIdentity: StructuredBottleIdentity? = null,
    )
    fun recordModelResponse(
        provider: String,
        model: String,
        response: String,
        envelopeStatus: Int? = null,
        generationFinishReason: String? = null,
        outputTokenLimitReached: Boolean = false,
    )
    fun finish(result: String)
}

internal enum class DetailedCaptureArmResult { ARMED, ALREADY_ARMED, CAPTURE_EXISTS, INVALID_DRAFT, STORAGE_ERROR }

internal data class DetailedCaptureStatus(
    val state: String,
    val draftId: String?,
    val attemptToken: String?,
    val expiresAtMillis: Long,
    val startedAtMillis: Long?,
    val model: String?,
    val nextLookupArmed: Boolean = false,
    val captureId: String? = null,
    val sessionActive: Boolean = false,
    val sessionExpiresAtMillis: Long? = null,
    val captureCount: Int = 0,
    val captureLimit: Int = 0,
    val debugSessionId: String? = null,
)

/**
 * Stores one explicitly armed, single-attempt capture in app-private no-backup storage.
 * Every write is attempt-fenced; content is never added to ordinary diagnostics or backups.
 */
internal class DetailedBottleLookupCaptureStore(
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deleteFile: (File) -> Boolean = { it.delete() },
    private val shareCacheDirectory: File? = null,
    private val processSessionId: String = PROCESS_SESSION_ID,
) {
    private val reviewedPreviews = mutableMapOf<String, String>()

    init {
        synchronized(LOCK) { cleanupExpired(nowMillis()) }
    }

    fun arm(
        draftId: String,
        expectedAttemptToken: String?,
        appVersion: String,
        now: Long = nowMillis(),
        captureIdOverride: String? = null,
        debugSessionId: String? = null,
    ): DetailedCaptureArmResult = synchronized(LOCK) {
        if (!safeDraftId(draftId)) return@synchronized DetailedCaptureArmResult.INVALID_DRAFT
        if (expectedAttemptToken != null && !safeAttemptToken(expectedAttemptToken)) return@synchronized DetailedCaptureArmResult.INVALID_DRAFT
        if (captureIdOverride != null && !UUID_CAPTURE_ID.matches(captureIdOverride)) return@synchronized DetailedCaptureArmResult.INVALID_DRAFT
        val existing = readLive(now)
        if (existing != null) {
            val state = existing.string("state")
            return@synchronized if (state == ARMED && existing.string("draftId") == draftId) {
                DetailedCaptureArmResult.ALREADY_ARMED
            } else {
                DetailedCaptureArmResult.CAPTURE_EXISTS
            }
        }
        val saved = write(buildJsonObject {
            put("formatVersion", 1)
            put("captureId", captureIdOverride ?: UUID.randomUUID().toString())
            put("draftId", draftId)
            expectedAttemptToken?.takeIf(::safeAttemptToken)?.let { put("attemptToken", it) }
            put("state", ARMED)
            put("armedAtMillis", now.coerceAtLeast(0))
            put("expiresAtMillis", (now + RETENTION_MILLIS).coerceAtLeast(now))
            put("appVersion", safeIdentifier(appVersion, 80))
            debugSessionId?.takeIf { UUID_CAPTURE_ID.matches(it) }?.let { put("debugSessionId", it) }
        })
        if (saved) DetailedCaptureArmResult.ARMED else DetailedCaptureArmResult.STORAGE_ERROR
    }

    /** Arms exactly the next eligible on-device lookup attempt; the first claimant wins atomically. */
    fun armNextLookup(appVersion: String, now: Long = nowMillis()): DetailedCaptureArmResult = synchronized(LOCK) {
        val existing = readLive(now)
        if (existing != null) {
            return@synchronized if (existing.string("state") == ARMED && existing.string("scope") == NEXT_LOOKUP) {
                DetailedCaptureArmResult.ALREADY_ARMED
            } else {
                DetailedCaptureArmResult.CAPTURE_EXISTS
            }
        }
        val saved = write(buildJsonObject {
            put("formatVersion", 1)
            put("captureId", UUID.randomUUID().toString())
            put("scope", NEXT_LOOKUP)
            put("state", ARMED)
            put("armedAtMillis", now.coerceAtLeast(0))
            put("expiresAtMillis", (now + RETENTION_MILLIS).coerceAtLeast(now))
            put("appVersion", safeIdentifier(appVersion, 80))
        })
        if (saved) DetailedCaptureArmResult.ARMED else DetailedCaptureArmResult.STORAGE_ERROR
    }

    /** Turning debug mode off cancels only an unclaimed next-lookup arm. */
    fun cancelNextLookup(): Boolean = synchronized(LOCK) {
        val current = readStoredObject(file)
        if (current?.string("scope") != NEXT_LOOKUP || current.string("state") != ARMED) return@synchronized false
        deleteIfPresent(file) && deleteIfPresent(temporaryCaptureFile())
    }

    /** Bind a pending capture immediately before WorkManager schedules that exact attempt. */
    fun bindArmedAttempt(draftId: String, attemptToken: String, now: Long = nowMillis()): Boolean = synchronized(LOCK) {
        if (!safeDraftId(draftId) || !safeAttemptToken(attemptToken)) return@synchronized false
        val current = readLive(now) ?: return@synchronized false
        if (current.string("state") != ARMED || current.string("draftId") != draftId) return@synchronized false
        val expected = current.string("attemptToken")
        if (expected != null && expected != attemptToken) {
            write(current.withString("state", "MISSED").withNumber("finishedAtMillis", now.coerceAtLeast(0)))
            return@synchronized false
        }
        write(current.withString("attemptToken", attemptToken))
    }

    /** Atomically consumes the opt-in for one exact worker attempt. */
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
        val current = readLive(now) ?: return@synchronized null
        val isNextLookup = current.string("scope") == NEXT_LOOKUP && current.string("state") == ARMED && current.string("draftId") == null
        val isSpecificAttempt = current.string("state") == ARMED && current.string("draftId") == draftId && current.string("attemptToken") == attemptToken
        if ((!isSpecificAttempt && !(allowNextLookup && isNextLookup)) || !safeDraftId(draftId) || !safeAttemptToken(attemptToken)) return@synchronized null
        val captureId = current.string("captureId") ?: return@synchronized null
        val started = current.withString("state", CAPTURING)
            .withString("scope", CAPTURED_ATTEMPT)
            .withString("draftId", draftId)
            .withString("attemptToken", attemptToken)
            .withString("appVersion", safeIdentifier(appVersion, 80))
            .withString("model", safeIdentifier(model.orEmpty(), 128))
            .withString("processSessionId", processSessionId)
            .withString("barcode", barcode.filter(Char::isDigit).take(18))
            .withNumber("appVersionCode", appVersionCode.coerceAtLeast(0))
            .withNumber("startedAtMillis", now.coerceAtLeast(0))
        if (!write(started)) return@synchronized null
        Recorder(this, captureId, draftId, attemptToken)
    }

    fun pendingAttemptWasMissed(draftId: String, attemptToken: String, now: Long = nowMillis()) = synchronized(LOCK) {
        val current = readLive(now) ?: return@synchronized
        if (current.string("state") == ARMED && current.string("draftId") == draftId && current.string("attemptToken") == attemptToken) {
            write(current.withString("state", "MISSED").withNumber("finishedAtMillis", now.coerceAtLeast(0)))
        }
    }

    fun statusForDraft(draftId: String, now: Long = nowMillis()): DetailedCaptureStatus? = synchronized(LOCK) {
        if (!safeDraftId(draftId)) return@synchronized null
        var current = readLive(now)?.takeIf { it.string("draftId") == draftId } ?: return@synchronized null
        current = markInterruptedAfterProcessRestart(current, now)
        status(current)
    }

    fun currentStatus(now: Long = nowMillis()): DetailedCaptureStatus? = synchronized(LOCK) {
        var current = readLive(now) ?: return@synchronized null
        current = markInterruptedAfterProcessRestart(current, now)
        status(current)
    }

    private fun status(current: JsonObject) =
        DetailedCaptureStatus(
            state = current.string("state") ?: "UNKNOWN",
            draftId = current.string("draftId"),
            attemptToken = current.string("attemptToken"),
            expiresAtMillis = current.number("expiresAtMillis") ?: 0,
            startedAtMillis = current.number("startedAtMillis"),
            model = current.string("model"),
            nextLookupArmed = current.string("scope") == NEXT_LOOKUP && current.string("state") == ARMED,
            captureId = current.string("captureId"),
            debugSessionId = current.string("debugSessionId"),
        )

    /** Call only after an explicit preview action. */
    fun previewForDraft(draftId: String, now: Long = nowMillis()): String? = synchronized(LOCK) {
        if (!safeDraftId(draftId)) return@synchronized null
        var root = readLive(now)?.takeIf { it.string("draftId") == draftId } ?: return@synchronized null
        if (root.string("state") == ARMED) return@synchronized null
        root = markInterruptedAfterProcessRestart(root, now)
        val preview = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
        root.string("captureId")?.let { reviewedPreviews[it] = digest(preview) }
        preview
    }

    /** Rejects a preview as soon as its capture expires, is deleted, or is replaced. */
    fun isReviewedPreviewCurrent(draftId: String, reviewedPreview: String, now: Long = nowMillis()): Boolean = synchronized(LOCK) {
        val current = readLive(now)?.takeIf { it.string("draftId") == draftId } ?: return@synchronized false
        reviewedSnapshotMatches(current, draftId, reviewedPreview)
    }

    /** Performs an explicit copy only while the preview still belongs to the live capture. */
    fun copyReviewedPreviewForDraft(
        draftId: String,
        reviewedPreview: String,
        now: Long = nowMillis(),
        copy: (String) -> Unit,
    ): Boolean = synchronized(LOCK) {
        val current = readLive(now)?.takeIf { it.string("draftId") == draftId } ?: return@synchronized false
        if (!reviewedSnapshotMatches(current, draftId, reviewedPreview)) return@synchronized false
        copy(reviewedPreview)
        true
    }

    fun exportCopyForDraft(draftId: String, cacheDirectory: File, now: Long = nowMillis()): File? = synchronized(LOCK) {
        val preview = previewForDraft(draftId, now) ?: return@synchronized null
        exportReviewedCopyForDraft(draftId, preview, cacheDirectory, now)
    }

    /** Writes exactly the content the user saw, so sharing cannot include newer unreviewed data. */
    fun exportReviewedCopyForDraft(draftId: String, reviewedPreview: String, cacheDirectory: File, now: Long = nowMillis()): File? = synchronized(LOCK) {
        val current = readLive(now)?.takeIf { it.string("draftId") == draftId } ?: return@synchronized null
        if (!reviewedSnapshotMatches(current, draftId, reviewedPreview) || reviewedPreview.isBlank()) return@synchronized null
        val reviewedBytes = reviewedPreview.toByteArray(Charsets.UTF_8)
        if (reviewedBytes.size > MAX_CAPTURE_BYTES) return@synchronized null
        val directory = File(cacheDirectory, SHARE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) return@synchronized null
        cleanupExpiredShareCopies(now)
        val shareExpiry = minOf(now + SHARE_RETENTION_MILLIS, current.number("expiresAtMillis") ?: now)
        val output = File(directory, "lookup-${draftDigest(draftId).take(12)}-$shareExpiry-${UUID.randomUUID()}.json")
        val temporary = File(directory, "${output.name}.tmp")
        runCatching {
            FileOutputStream(temporary).use { stream ->
                stream.write(reviewedBytes)
                stream.fd.sync()
            }
            if (!temporary.renameTo(output)) return@synchronized null
            // The capture's own expiry may be sooner than the one-hour share-cache limit.
            val captureExpiry = current.number("expiresAtMillis") ?: now
            output.setLastModified(minOf(System.currentTimeMillis(), captureExpiry))
            output
        }.getOrNull()
    }

    fun deleteForDraft(draftId: String): Boolean = synchronized(LOCK) {
        if (!safeDraftId(draftId)) return@synchronized false
        val current = readStoredObject(file)
        val temporary = temporaryCaptureFile()
        val temporaryObject = readStoredObject(temporary)
        val currentOwner = current?.string("draftId")
        val temporaryOwner = temporaryObject?.string("draftId")
        // Keep the primary record as a retryable owner marker until every exported
        // copy is removed. Otherwise a partial share deletion can orphan a copy.
        val sharesDeleted = removeShareCopies(draftId)
        val primaryDeleted = when {
            currentOwner == draftId -> sharesDeleted && deleteIfPresent(file)
            file.exists() && current == null -> false
            else -> true
        }
        val canDeleteTemporary = sharesDeleted && (temporaryOwner == draftId || currentOwner == draftId || !file.exists() && temporaryObject == null)
        val temporaryDeleted = if (canDeleteTemporary) deleteIfPresent(temporary) else true
        current?.string("captureId")?.let(reviewedPreviews::remove)
        temporaryObject?.string("captureId")?.let(reviewedPreviews::remove)
        primaryDeleted && temporaryDeleted && sharesDeleted
    }

    fun deleteAll(): Boolean = synchronized(LOCK) {
        val captureDeleted = deleteIfPresent(file)
        val temporaryFile = temporaryCaptureFile()
        val temporaryDeleted = deleteIfPresent(temporaryFile)
        val shares = shareCacheDirectory?.let { File(it, SHARE_DIRECTORY) }
        val sharesDeleted = deleteDirectoryContents(shares)
        reviewedPreviews.clear()
        captureDeleted && temporaryDeleted && sharesDeleted
    }

    /** Best-effort scheduled/startup cleanup. Android may defer it while this app is stopped. */
    fun cleanupExpired(now: Long = nowMillis()): Boolean = synchronized(LOCK) {
        val current = readStoredObject(file)
        val expiry = current?.number("expiresAtMillis")
        var success = true
        if (file.exists() && (current == null || expiry == null || expiry <= now)) {
            success = deleteIfPresent(file) && success
            current?.string("draftId")?.let { success = removeShareCopies(it) && success }
            current?.string("captureId")?.let(reviewedPreviews::remove)
        }
        // A .tmp file can only remain after a crashed or failed atomic write: every
        // active write is protected by LOCK, so removing it here cannot race a writer.
        success = deleteIfPresent(temporaryCaptureFile()) && success
        success = cleanupExpiredShareCopies(now) && success
        success
    }

    private fun append(captureId: String, draftId: String, attemptToken: String, key: String, value: JsonElementBuilder): Boolean = synchronized(LOCK) {
        val current = readLive(nowMillis()) ?: return@synchronized false
        if (!owns(current, captureId, draftId, attemptToken) || current.string("state") != CAPTURING) return@synchronized false
        write(current.with(key, value()))
    }

    private fun recordSelection(captureId: String, draftId: String, attemptToken: String, hits: List<WebSearchHit>, selectedIndex: Int?, decision: String) = append(
        captureId, draftId, attemptToken, "sourceSelection", {
            buildJsonObject {
                put("candidateCount", hits.size.coerceIn(0, 100))
                put("candidatesTruncated", hits.size > 5)
                selectedIndex?.takeIf { it in 0..4 }?.let { put("selectedIndex", it) }
                put("decision", decision.takeIf { it in SAFE_DECISIONS } ?: "OTHER")
                put("candidates", buildJsonArray {
                    val expectedBarcode = readLive(nowMillis())?.string("barcode").orEmpty()
                    hits.take(5).forEach { hit ->
                        val safeTitle = redactCaptureText(hit.title.take(MAX_TITLE_CHARS))
                        val safeSnippet = redactCaptureText(hit.snippet.orEmpty().take(MAX_SNIPPET_CHARS))
                        add(buildJsonObject {
                            safeCaptureUrl(hit.url)?.let { put("source", it) }
                            put("title", safeTitle.first)
                            put("snippet", safeSnippet.first)
                            put("redacted", safeTitle.second || safeSnippet.second)
                            put("titleTruncated", hit.title.length > MAX_TITLE_CHARS)
                            put("snippetTruncated", (hit.snippet?.length ?: 0) > MAX_SNIPPET_CHARS)
                            put("containsTargetBarcode", sourceMentionsScannedBarcode(expectedBarcode, "${hit.title} ${hit.snippet.orEmpty()}"))
                        })
                    }
                })
            }
        },
    )

    private fun recordFetchedPage(
        captureId: String,
        draftId: String,
        attemptToken: String,
        index: Int,
        url: String,
        title: String?,
        decision: String,
        identities: List<StructuredBottleIdentity>,
        identityStatus: StructuredIdentityStatus?,
    ) = synchronized(LOCK) {
        val current = readLive(nowMillis()) ?: return@synchronized false
        if (!owns(current, captureId, draftId, attemptToken) || current.string("state") != CAPTURING) return@synchronized false
        val pages = (current["fetchedPages"] as? JsonArray)?.toMutableList().orEmpty()
        if (pages.size >= MAX_FETCHED_PAGES) return@synchronized false
        val safeTitle = title?.let(::redactCaptureText)
        val page = buildJsonObject {
            put("candidateIndex", index.coerceIn(0, 4))
            safeCaptureUrl(url)?.let { put("source", it) }
            safeTitle?.first?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)?.let { put("pageTitle", it) }
            put("pageTitleRedacted", safeTitle?.second ?: false)
            put("pageTitleTruncated", (title?.length ?: 0) > MAX_TITLE_CHARS)
            put("barcodeDecision", decision.takeIf { it in SAFE_BARCODE_DECISIONS } ?: "OTHER")
            put("structuredIdentityStatus", identityStatus?.debugCode?.takeIf { it in SAFE_IDENTITY_STATUSES } ?: "NOT_CHECKED")
            put("structuredIdentities", buildJsonArray {
                identities.take(MAX_STRUCTURED_IDENTITIES).forEach { identity ->
                    val name = redactCaptureText(identity.productName.take(MAX_IDENTITY_TEXT_CHARS))
                    val evidence = redactCaptureText(identity.evidence.take(MAX_IDENTITY_TEXT_CHARS))
                    add(buildJsonObject {
                        put("barcode", identity.barcode.filter(Char::isDigit).take(18))
                        put("productName", name.first)
                        put("evidence", evidence.first)
                        put("redacted", name.second || evidence.second)
                        put("productNameTruncated", identity.productName.length > MAX_IDENTITY_TEXT_CHARS)
                        put("evidenceTruncated", identity.evidence.length > MAX_IDENTITY_TEXT_CHARS)
                    })
                }
            })
            put("structuredIdentitiesTruncated", identities.size > MAX_STRUCTURED_IDENTITIES)
        }
        write(current.with("fetchedPages", JsonArray(pages + page)))
    }

    private fun recordValidatorInput(
        captureId: String,
        draftId: String,
        attemptToken: String,
        provider: String,
        barcode: String?,
        sourceText: String,
        pageTitle: String?,
        structuredIdentities: List<StructuredBottleIdentity>,
        status: StructuredIdentityStatus?,
        selectedStructuredIdentity: StructuredBottleIdentity?,
    ): Boolean {
        val source = redactCaptureText(sourceText.take(MAX_SOURCE_CHARS))
        val title = pageTitle?.let(::redactCaptureText)
        val identities = structuredIdentities.take(MAX_STRUCTURED_IDENTITIES).map { identity ->
            val name = redactCaptureText(identity.productName.take(MAX_IDENTITY_TEXT_CHARS))
            val evidence = redactCaptureText(identity.evidence.take(MAX_IDENTITY_TEXT_CHARS))
            buildJsonObject {
                put("barcode", identity.barcode.filter(Char::isDigit).take(18))
                put("productName", name.first)
                put("evidence", evidence.first)
                put("redacted", name.second || evidence.second)
                put("productNameTruncated", identity.productName.length > MAX_IDENTITY_TEXT_CHARS)
                put("evidenceTruncated", identity.evidence.length > MAX_IDENTITY_TEXT_CHARS)
            }
        }
        val selectedIdentity = selectedStructuredIdentity?.let { identity ->
            val name = redactCaptureText(identity.productName.take(MAX_IDENTITY_TEXT_CHARS))
            val evidence = redactCaptureText(identity.evidence.take(MAX_IDENTITY_TEXT_CHARS))
            buildJsonObject {
                put("barcode", identity.barcode.filter(Char::isDigit).take(18))
                put("productName", name.first)
                put("evidence", evidence.first)
                put("redacted", name.second || evidence.second)
                put("productNameTruncated", identity.productName.length > MAX_IDENTITY_TEXT_CHARS)
                put("evidenceTruncated", identity.evidence.length > MAX_IDENTITY_TEXT_CHARS)
            }
        }
        return append(captureId, draftId, attemptToken, "validatorInput") {
            buildJsonObject {
                put("provider", provider.takeIf { it in setOf("On-device Qwen3", "Ollama Cloud") } ?: "unknown")
                barcode?.let { put("targetBarcode", it.filter(Char::isDigit).take(18)) }
                put("sourceText", source.first)
                put("sourceTextRedacted", source.second)
                put("pageTitle", title?.first.orEmpty())
                put("pageTitleRedacted", title?.second ?: false)
                put("pageTitleTruncated", (pageTitle?.length ?: 0) > MAX_TITLE_CHARS)
                put("structuredIdentityStatus", status?.debugCode?.takeIf { it in SAFE_IDENTITY_STATUSES } ?: "NOT_CHECKED")
                put("structuredIdentities", JsonArray(identities))
                selectedIdentity?.let { put("selectedStructuredIdentity", it) }
                put("structuredIdentitiesTruncated", structuredIdentities.size > MAX_STRUCTURED_IDENTITIES)
                put("sourceTextTruncated", sourceText.length > MAX_SOURCE_CHARS)
            }
        }
    }

    private fun recordModelResponse(
        captureId: String,
        draftId: String,
        attemptToken: String,
        provider: String,
        model: String,
        response: String,
        httpStatus: Int?,
        generationFinishReason: String?,
        outputTokenLimitReached: Boolean,
    ): Boolean {
        val bounded = response.take(MAX_MODEL_RESPONSE_CHARS)
        val safe = redactCaptureText(bounded)
        return append(captureId, draftId, attemptToken, "modelResponse") {
            buildJsonObject {
                put("provider", provider.takeIf { it in setOf("On-device Qwen3", "Ollama Cloud") } ?: "unknown")
                put("model", safeIdentifier(model, 128))
                put("response", safe.first)
                put("responseRedacted", safe.second)
                put("responseTruncated", response.length > MAX_MODEL_RESPONSE_CHARS)
                httpStatus?.takeIf { it in 100..599 }?.let { put("httpStatus", it) }
                generationFinishReason?.takeIf { it in SAFE_GENERATION_FINISH_REASONS }?.let { put("generationFinishReason", it) }
                if (outputTokenLimitReached) put("outputTokenLimitReached", true)
            }
        }
    }

    private fun finish(captureId: String, draftId: String, attemptToken: String, result: String): Boolean = synchronized(LOCK) {
        val current = readLive(nowMillis()) ?: return@synchronized false
        if (!owns(current, captureId, draftId, attemptToken) || current.string("state") != CAPTURING) return@synchronized false
        val safeResult = result.takeIf { it in SAFE_FINISH_STATES } ?: "COMPLETE"
        val tokenLimited = current["modelResponse"]?.jsonObject?.get("outputTokenLimitReached")?.jsonPrimitive?.contentOrNull == "true"
        val finishedState = if (safeResult == "COMPLETE" && tokenLimited) "PARTIAL" else safeResult
        write(current.withString("state", finishedState)
            .withNumber("finishedAtMillis", nowMillis().coerceAtLeast(0)))
    }

    private fun readLive(now: Long): JsonObject? {
        val current = readStoredObject(file) ?: run {
            if (file.exists()) deleteIfPresent(file)
            deleteIfPresent(temporaryCaptureFile())
            return null
        }
        val expiry = current.number("expiresAtMillis") ?: run {
            deleteIfPresent(file)
            deleteIfPresent(temporaryCaptureFile())
            current.string("draftId")?.let(::removeShareCopies)
            return null
        }
        val validDraft = current.string("draftId")?.let(::safeDraftId) == true
        val validNextLookup = current.string("scope") == NEXT_LOOKUP && current.string("state") == ARMED && current.string("draftId") == null
        if (expiry <= now || (!validDraft && !validNextLookup)) {
            deleteIfPresent(file)
            deleteIfPresent(temporaryCaptureFile())
            current.string("draftId")?.let(::removeShareCopies)
            current.string("captureId")?.let(reviewedPreviews::remove)
            return null
        }
        // Remove remnants from a prior interrupted atomic write before exposing data.
        deleteIfPresent(temporaryCaptureFile())
        return current
    }

    private fun reviewedSnapshotMatches(current: JsonObject, draftId: String, preview: String): Boolean {
        if (!safeDraftId(draftId) || preview.isBlank() || preview.toByteArray(Charsets.UTF_8).size > MAX_CAPTURE_BYTES) return false
        val snapshot = readStoredObject(preview) ?: return false
        val captureId = current.string("captureId") ?: return false
        return snapshot.string("draftId") == draftId &&
            snapshot.string("captureId") == captureId &&
            snapshot.string("attemptToken") == current.string("attemptToken") &&
            reviewedPreviews[captureId] == digest(preview)
    }

    private fun readStoredObject(source: File): JsonObject? = runCatching {
        if (!source.isFile || source.length() !in 1..MAX_CAPTURE_BYTES.toLong()) null
        else Json.parseToJsonElement(source.readText(Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    private fun readStoredObject(serialized: String): JsonObject? = runCatching {
        Json.parseToJsonElement(serialized) as? JsonObject
    }.getOrNull()

    private fun temporaryCaptureFile() = File(file.parentFile, "${file.name}.tmp")

    private fun deleteIfPresent(target: File): Boolean =
        !target.exists() || runCatching { deleteFile(target) }.getOrDefault(false) && !target.exists()

    private fun deleteDirectoryContents(directory: File?): Boolean {
        if (directory == null || !directory.exists()) return true
        val files = directory.listFiles() ?: return false
        var success = true
        files.forEach { if (!deleteIfPresent(it)) success = false }
        return success
    }

    private fun write(value: JsonObject): Boolean {
        val bytes = Json.encodeToString(JsonObject.serializer(), value).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CAPTURE_BYTES) return false
        return runCatching {
            file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (!temporary.renameTo(file)) return false
            true
        }.getOrDefault(false)
    }

    private fun owns(value: JsonObject, captureId: String, draftId: String, attemptToken: String) =
        value.string("captureId") == captureId && value.string("draftId") == draftId && value.string("attemptToken") == attemptToken

    private fun markInterruptedAfterProcessRestart(value: JsonObject, now: Long): JsonObject {
        if (value.string("state") != CAPTURING || value.string("processSessionId") == processSessionId) return value
        val interrupted = value.withString("state", "INTERRUPTED").withNumber("finishedAtMillis", now.coerceAtLeast(0))
        write(interrupted)
        return interrupted
    }

    private fun removeShareCopies(draftId: String): Boolean {
        val directory = shareCacheDirectory?.let { File(it, SHARE_DIRECTORY) } ?: return true
        if (!directory.exists()) return true
        val files = directory.listFiles() ?: return false
        val prefix = "lookup-${draftDigest(draftId).take(12)}-"
        var success = true
        files.filter { it.name.startsWith(prefix) }.forEach { if (!deleteIfPresent(it)) success = false }
        return success
    }

    private fun cleanupExpiredShareCopies(now: Long): Boolean {
        val directory = shareCacheDirectory?.let { File(it, SHARE_DIRECTORY) } ?: return true
        if (!directory.exists()) return true
        val files = directory.listFiles() ?: return false
        val expired = files.filter { old ->
            old.name.endsWith(".tmp") || (shareExpiryFromName(old.name)?.let { it <= now } ?: (now - old.lastModified() > SHARE_RETENTION_MILLIS))
        }
        var success = true
        expired.forEach { if (!deleteIfPresent(it)) success = false }
        return success
    }

    private fun shareExpiryFromName(name: String): Long? = Regex("^lookup-[0-9a-f]{12}-(\\d+)-[0-9a-fA-F-]{36}\\.json$")
        .matchEntire(name)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private class Recorder(
        private val store: DetailedBottleLookupCaptureStore,
        private val captureId: String,
        private val draftId: String,
        private val attemptToken: String,
    ) : DetailedBottleLookupRecorder {
        override fun recordSearchSelection(hits: List<WebSearchHit>, selectedIndex: Int?, decision: String) {
            store.recordSelection(captureId, draftId, attemptToken, hits, selectedIndex, decision)
        }

        override fun recordFetchedPage(
            index: Int,
            url: String,
            title: String?,
            barcodeDecision: String,
            structuredIdentities: List<StructuredBottleIdentity>,
            structuredIdentityStatus: StructuredIdentityStatus?,
        ) {
            store.recordFetchedPage(captureId, draftId, attemptToken, index, url, title, barcodeDecision, structuredIdentities, structuredIdentityStatus)
        }

        override fun recordValidatorInput(
            provider: String,
            barcode: String?,
            sourceText: String,
            pageTitle: String?,
            structuredIdentities: List<StructuredBottleIdentity>,
            structuredIdentityStatus: StructuredIdentityStatus?,
            selectedStructuredIdentity: StructuredBottleIdentity?,
        ) {
            store.recordValidatorInput(
                captureId, draftId, attemptToken, provider, barcode, sourceText, pageTitle, structuredIdentities,
                structuredIdentityStatus, selectedStructuredIdentity,
            )
        }

        override fun recordModelResponse(
            provider: String,
            model: String,
            response: String,
            envelopeStatus: Int?,
            generationFinishReason: String?,
            outputTokenLimitReached: Boolean,
        ) {
            store.recordModelResponse(
                captureId, draftId, attemptToken, provider, model, response, envelopeStatus,
                generationFinishReason, outputTokenLimitReached,
            )
        }

        override fun finish(result: String) { store.finish(captureId, draftId, attemptToken, result) }
    }

    companion object {
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        const val MAX_CAPTURE_BYTES = 192 * 1024
        const val MAX_SOURCE_CHARS = 24_000
        const val MAX_MODEL_RESPONSE_CHARS = 16_000
        const val MAX_STRUCTURED_IDENTITIES = 12
        const val MAX_IDENTITY_TEXT_CHARS = 400
        const val MAX_TITLE_CHARS = 240
        const val MAX_SNIPPET_CHARS = 600
        const val RETENTION_DESCRIPTION = "24 hours"
        private const val MAX_FETCHED_PAGES = 3
        private const val SHARE_DIRECTORY = "detailed-lookup-share"
        private const val SHARE_RETENTION_MILLIS = 60 * 60 * 1000L
        private const val ARMED = "ARMED"
        private const val CAPTURING = "CAPTURING"
        private const val NEXT_LOOKUP = "NEXT_LOOKUP"
        private const val CAPTURED_ATTEMPT = "CAPTURED_ATTEMPT"
        private val SAFE_GENERATION_FINISH_REASONS = setOf("END_OF_SEQUENCE", "TOKEN_LIMIT", "CANCELLED", "DECODE_ERROR", "UNKNOWN")
        private val LOCK = Any()
        private val PROCESS_SESSION_ID = UUID.randomUUID().toString()
        private val SAFE_DECISIONS = setOf("NO_USABLE_SOURCE", "SELECTED_EXACT_MATCH", "SELECTED_FALLBACK", "NO_SEARCH_RESULTS")
        private val SAFE_BARCODE_DECISIONS = setOf("VERIFIED", "STRUCTURED_PRODUCT_MATCH", "MISSING", "MISMATCH", "AMBIGUOUS", "INVALID_TARGET")
        private val SAFE_IDENTITY_STATUSES = StructuredIdentityStatus.entries.mapTo(mutableSetOf()) { it.debugCode }
        private val SAFE_FINISH_STATES = setOf("COMPLETE", "PARTIAL", "INTERRUPTED", "FAILED", "CANCELLED")
        private val SAFE_DRAFT = Regex("[A-Za-z0-9-]{1,100}")
        private val SAFE_ATTEMPT = Regex("[0-9a-fA-F-]{36}")
        private val UUID_CAPTURE_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        private fun safeDraftId(value: String) = SAFE_DRAFT.matches(value)
        private fun safeAttemptToken(value: String) = SAFE_ATTEMPT.matches(value)
        private fun safeIdentifier(value: String, max: Int) = value.filter { it.isLetterOrDigit() || it in "._:+-" }.take(max)

        private fun safeCaptureUrl(value: String): String? = BottleLookupDebugReport.safeDiagnosticUrl(value)

        private fun redactCaptureText(value: String): Pair<String, Boolean> {
            var result = value
            val patterns = listOf(
                Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}"),
                Regex("(?i)\\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"']{8,}"),
                Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
                Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
                Regex("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
                Regex("(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"),
            )
            var changed = false
            patterns.forEach { pattern ->
                val next = pattern.replace(result, "[REDACTED_SECRET_LIKE_TEXT]")
                if (next != result) changed = true
                result = next
            }
            return result to changed
        }

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.number(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
        private fun draftDigest(draftId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(draftId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        private fun JsonObject.withString(key: String, value: String): JsonObject = JsonObject(toMutableMap().apply { put(key, JsonPrimitive(value)) })
        private fun JsonObject.withNumber(key: String, value: Long): JsonObject = JsonObject(toMutableMap().apply { put(key, JsonPrimitive(value)) })
        private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject = JsonObject(toMutableMap().apply { put(key, value) })
        private fun interface JsonElementBuilder { operator fun invoke(): kotlinx.serialization.json.JsonElement }
    }
}
