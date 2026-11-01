package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BottleOcrDiagnosticsTest {
    private class Memory : OcrDiagnosticStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String): Boolean { this.value = value; return true }
        override fun clear(): Boolean { value = null; return true }
    }
    private val draft = BottleIntakeDraft("private-draft", "private-session", 0, "0086785900001", null,
        "Secret product", "Spirit", null, null, byteArrayOf(1), "private-photo-hash", locationId = "home", level = InventoryLevel.FULL, createdAt = 1)
    private val c = OcrCorrelation.of(draft, "private-attempt")
    private fun records(journal: BottleOcrJournal, current: List<OcrCorrelation> = listOf(c)) = journal.snapshot(current)["attempts"]!!.jsonArray.map { it.jsonObject }

    @Test fun queuedRunningCompletedPersistAndCombinedExportDoesNotAlterWebTimingOrExposeContent() {
        val memory = Memory(); var now = 100L
        val journal = BottleOcrJournal(memory, "process", { now++ })
        assertTrue(journal.begin(c, "request")); assertEquals("QUEUED", records(journal).single()["state"]!!.jsonPrimitive.content)
        journal.start(c, "request", 0)
        journal.update(c, "request") { it.copy(state = OcrState.COMPLETED, phase = OcrPhase.DONE, workerElapsedMs = 1000, recognitionMs = 100,
            textFound = true, labelAiState = OcrState.COMPLETED, labelAiMs = 800,
            supportedFields = setOf("quantity", "PRIVATE label text"), unresolvedFields = setOf("abv_percent"), conflictingFields = setOf("abv_percent")) }
        val reopened = BottleOcrJournal(memory, "another-process")
        val report = reopened.snapshot(listOf(c))
        val combined = combineBottleDiagnostics(attachBottleOcrDiagnostics("""{"latestBottleLookup":{"elapsedMs":5000}}""", report), emptyList())
        val root = Json.parseToJsonElement(combined).jsonObject["standardDiagnostics"]!!.jsonObject
        assertEquals(5000, root["latestBottleLookup"]!!.jsonObject["elapsedMs"]!!.jsonPrimitive.int)
        val result = root["labelOcr"]!!.jsonObject["attempts"]!!.jsonArray.single().jsonObject
        assertEquals("COMPLETED", result["state"]!!.jsonPrimitive.content)
        assertEquals(1, result["supportedFieldCount"]!!.jsonPrimitive.int)
        assertEquals("CONFLICTING_FIELD_VALUES", result["rejections"]!!.jsonObject["abv_percent"]!!.jsonPrimitive.content)
        for (secret in listOf("Secret product", "0086785900001", "private-draft", "private-session", "private-photo-hash", "private-attempt", "PRIVATE label text", "another-process")) assertFalse(secret, combined.contains(secret))
        assertFalse(result.containsKey("owner"))
    }
    @Test fun supersededPhotoAndAttemptCannotPublishOverNewWork() {
        val journal = BottleOcrJournal(Memory(), "process")
        journal.begin(c, "old"); journal.start(c, "old", 0)
        val next = OcrCorrelation.of(draft.copy(photoSha256 = "new-photo"), "new-attempt")
        journal.begin(next, "new"); journal.start(next, "new", 0)
        assertFalse(journal.update(c, "old") { it.copy(state = OcrState.COMPLETED) })
        val result = records(journal, listOf(next))
        assertEquals("STALE", result.first()["state"]!!.jsonPrimitive.content)
        assertFalse(result.first()["isCurrent"]!!.jsonPrimitive.boolean)
        assertTrue(result.last()["isCurrent"]!!.jsonPrimitive.boolean)
        assertEquals("RUNNING", result.last()["state"]!!.jsonPrimitive.content)
        // A changed barcode with the same photo also invalidates the old context.
        val changed = OcrCorrelation.of(draft.copy(barcode = "080480007577"), "private-attempt")
        assertFalse(records(journal, listOf(changed)).any { it["isCurrent"]!!.jsonPrimitive.boolean })
    }
    @Test fun cancellationPreventsLateCompletionAndSessionCancellationIsScoped() {
        val journal = BottleOcrJournal(Memory(), "process")
        val other = OcrCorrelation.of(draft.copy(id = "other", sessionId = "other-session"), null)
        journal.begin(c, "one"); journal.start(c, "one", 0); journal.begin(other, "two")
        journal.cancelSession(draft.sessionId)
        assertFalse(journal.update(c, "one") { it.copy(state = OcrState.COMPLETED) })
        val states = records(journal, listOf(c, other)).map { it["state"]!!.jsonPrimitive.content }
        assertEquals(listOf("CANCELLED", "QUEUED"), states)
        journal.cancelDraft("other")
        assertEquals("CANCELLED", records(journal, listOf(other)).last()["state"]!!.jsonPrimitive.content)
    }
    @Test fun restartShowsInterruptedNotCompletedAndWorkerRetryResetsStageTimings() {
        val memory = Memory(); val first = BottleOcrJournal(memory, "first")
        first.begin(c, "one"); first.start(c, "one", 0)
        first.update(c, "one") { it.copy(recognitionMs = 42, textFound = true) }
        val restarted = BottleOcrJournal(memory, "second")
        assertEquals("INTERRUPTED", records(restarted).single()["state"]!!.jsonPrimitive.content)
        restarted.start(c, "one", 1)
        val record = records(restarted).single()
        assertEquals("RUNNING", record["state"]!!.jsonPrimitive.content)
        assertFalse(record.containsKey("recognitionMs"))
        assertEquals(1, record["retryCount"]!!.jsonPrimitive.int)
    }
    @Test fun systemStoppedRequestCanRestartButCancellationAndSupersessionCannot() {
        for (sameProcess in listOf(true, false)) {
            val memory = Memory(); val first = BottleOcrJournal(memory, "first")
            first.begin(c, "one"); first.start(c, "one", 0)
            first.update(c, "one") { it.stopped(OcrState.INTERRUPTED, OcrReason.WORK_STOPPED) }
            assertEquals("INTERRUPTED", records(first).single()["recognitionState"]!!.jsonPrimitive.content)
            assertEquals("WORK_STOPPED", records(first).single()["recognitionReason"]!!.jsonPrimitive.content)
            assertFalse(first.update(c, "one") { it.copy(state = OcrState.COMPLETED) })
            val retry = if (sameProcess) first else BottleOcrJournal(memory, "second")
            assertTrue(retry.start(c, "one", 1))
            assertTrue(retry.update(c, "one") { it.copy(state = OcrState.COMPLETED, recognitionState = OcrState.COMPLETED) })
            assertEquals("COMPLETED", records(retry).single()["state"]!!.jsonPrimitive.content)
            assertFalse(retry.start(c, "one", 2))
        }
        for (cancelSession in listOf(true, false)) {
            val journal = BottleOcrJournal(Memory(), "process")
            journal.begin(c, "one"); journal.start(c, "one", 0)
            journal.update(c, "one") { it.stopped(OcrState.INTERRUPTED, OcrReason.WORK_STOPPED) }
            if (cancelSession) journal.cancelSession(draft.sessionId) else journal.cancelDraft(draft.id)
            assertFalse(journal.start(c, "one", 1))
            assertEquals("USER_CANCELLED", records(journal).single()["reason"]!!.jsonPrimitive.content)
            journal.begin(c, "two")
            assertFalse(journal.start(c, "one", 1))
        }
    }
    @Test fun terminalStatesFinishActiveChildrenAndPreserveCompletedStages() {
        for (action in listOf("supersede", "stale", "cancel", "restart")) {
            val memory = Memory(); val journal = BottleOcrJournal(memory, "first")
            journal.begin(c, "one"); journal.start(c, "one", 0)
            journal.update(c, "one") { it.copy(recognitionState = OcrState.COMPLETED, recognitionReason = OcrReason.NONE,
                recognitionMs = 42, labelAiState = OcrState.RUNNING, labelAiReason = OcrReason.NONE) }
            val expected = when (action) {
                "supersede" -> { journal.begin(c, "two"); "STALE" to "SUPERSEDED_REQUEST" }
                "cancel" -> { journal.cancelDraft(draft.id); "CANCELLED" to "USER_CANCELLED" }
                "restart" -> "INTERRUPTED" to "PROCESS_RESTARTED"
                else -> "STALE" to "STALE_PHOTO_OR_DRAFT"
            }
            val result = when (action) {
                "restart" -> records(BottleOcrJournal(memory, "second")).first()
                "stale" -> records(journal, emptyList()).first()
                else -> records(journal).first()
            }
            assertEquals(expected.first, result["state"]!!.jsonPrimitive.content)
            assertEquals(expected.first, result["labelAiState"]!!.jsonPrimitive.content)
            assertEquals(expected.second, result["labelAiReason"]!!.jsonPrimitive.content)
            assertEquals("COMPLETED", result["recognitionState"]!!.jsonPrimitive.content)
            assertEquals("NONE", result["recognitionReason"]!!.jsonPrimitive.content)
            assertEquals(42, result["recognitionMs"]!!.jsonPrimitive.int)
        }
    }
    @Test fun missingPhotoSkippedReportsAreBoundedAndDraftScopedAndClearable() {
        val journal = BottleOcrJournal(Memory(), "process")
        val missing = OcrCorrelation.of(draft.copy(photoData = null, photoSha256 = null), null)
        repeat(20) { journal.begin(missing, "request-$it", OcrState.SKIPPED, OcrReason.MISSING_PHOTO) }
        assertEquals(12, records(journal, listOf(missing)).size)
        val scoped = journal.snapshot(listOf(missing), "unrelated")["attempts"]!!.jsonArray
        assertTrue(scoped.isEmpty())
        assertEquals("MISSING_PHOTO", records(journal, listOf(missing)).last()["reason"]!!.jsonPrimitive.content)
        assertTrue(journal.clear()); assertTrue(records(journal).isEmpty())
    }
    @Test fun unavailableCurrentContextIsUnknownRatherThanFalselyStale() {
        val journal = BottleOcrJournal(Memory(), "process")
        journal.begin(c, "one"); journal.start(c, "one", 0)
        val snapshot = journal.snapshot(null)
        assertFalse(snapshot["currentContextAvailable"]!!.jsonPrimitive.boolean)
        val record = snapshot["attempts"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, record["isCurrent"])
        assertEquals("RUNNING", record["state"]!!.jsonPrimitive.content)
    }
    @Test fun unknownOrMalformedPersistedFieldsNeverPassThrough() {
        val memory = Memory(); val journal = BottleOcrJournal(memory, "process")
        journal.begin(c, "one")
        val injected = Json.parseToJsonElement(memory.value!!).jsonArray.single().jsonObject.toMutableMap()
        injected["rawOcr"] = JsonPrimitive("secret text")
        injected["supportedFields"] = JsonArray(listOf(JsonPrimitive("api_key=secret")))
        memory.value = JsonArray(listOf(JsonObject(injected))).toString()
        assertFalse(journal.snapshot(listOf(c)).toString().contains("secret"))
        injected["state"] = JsonPrimitive("secret-state")
        memory.value = JsonArray(listOf(JsonObject(injected))).toString()
        assertTrue(records(journal).isEmpty())
    }
    @Test fun labelAiReportsSkippedFailedCompletedAndCancelledWithoutPayloads() = runBlocking {
        val events = mutableListOf<Pair<OcrState, OcrReason>>()
        suspend fun extract(path: String?, output: suspend () -> String) = LocalBottleDetailExtractor({ path }, LocalInferenceRunner { _, _, _, _ -> output() })
            .extractLabelText("750 mL", emptyList()) { state, reason, _ -> events += state to reason }
        extract(null) { error("must not run") }
        assertEquals(listOf(OcrState.SKIPPED to OcrReason.MODEL_MISSING), events); events.clear()
        val facts = extract("private/model") { throw IllegalStateException("secret prompt") }
        assertEquals(OcrState.FAILED to OcrReason.MODEL_RUNTIME_FAILED, events.last())
        assertEquals(BottleFactField.QUANTITY, facts.facts.single().field); events.clear()
        extract("private/model") { "not JSON secret" }
        assertEquals(OcrState.FAILED to OcrReason.MODEL_RESPONSE_INVALID, events.last()); events.clear()
        extract("private/model") { "{\"facts\":[]}" }
        assertEquals(OcrState.COMPLETED to OcrReason.NONE, events.last()); events.clear()
        try { extract("private/model") { throw CancellationException("secret") }; fail("expected cancellation") } catch (_: CancellationException) { }
        assertEquals(OcrState.CANCELLED to OcrReason.WORK_STOPPED, events.last())
    }
}
