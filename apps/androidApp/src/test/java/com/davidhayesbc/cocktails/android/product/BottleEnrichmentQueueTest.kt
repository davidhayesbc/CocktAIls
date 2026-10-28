package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class BottleEnrichmentQueueTest {
    @Test
    fun correctingBarcodeInvalidatesRunningAttemptAndSourceWithoutStartingAnotherRequest() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val queued = queue.enqueue("session-correct", "draft-correct", nowMillis = 1)!!
        val running = queue.beginQueued(
            sessionId = queued.sessionId,
            draftId = queued.draftId,
            operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
            attemptToken = queued.attemptToken!!,
            nowMillis = 2,
        )!!
        val fetching = queue.updateRunning(
            sessionId = running.sessionId,
            draftId = running.draftId,
            operation = BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = "https://example.com/old-product",
            attemptToken = running.attemptToken!!,
            nowMillis = 3,
        )!!

        assertTrue(queue.resetForBarcodeChange(fetching.draftId, nowMillis = 4))
        val corrected = queue.status(fetching.draftId)!!
        assertEquals(BottleEnrichmentStatus.NEEDS_ATTENTION, corrected.status)
        assertEquals(BottleEnrichmentOperation.FIRECRAWL_SEARCH, corrected.operation)
        assertEquals("BARCODE_CORRECTED", corrected.resultCode)
        assertNull(corrected.sourceUrl)
        assertNull(corrected.attemptToken)
        assertEquals(BottleProviderOutcome.NOT_RECORDED, corrected.webOutcome)
        assertEquals(BottleProviderOutcome.NOT_RECORDED, corrected.aiOutcome)
        assertFalse(queue.completeIfRunning(fetching.draftId, fetching.attemptToken!!, 5) {
            BottleEnrichmentStatus.READY_FOR_REVIEW to "STALE_RESULT"
        })
        assertEquals(BottleEnrichmentStatus.NEEDS_ATTENTION, queue.status(fetching.draftId)!!.status)
    }

    @Test
    fun webStatusCompletesWhenBackgroundJobMovesToAiExtraction() {
        val queue = BottleEnrichmentQueue(MemoryStorage())
        val queued = queue.enqueue("session-stages", "draft-stages", nowMillis = 1)!!
        val search = queue.beginQueued(
            sessionId = queued.sessionId,
            draftId = queued.draftId,
            operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
            attemptToken = queued.attemptToken!!,
            nowMillis = 2,
        )!!
        assertEquals(BottleProviderOutcome.RUNNING, search.webOutcome)
        assertEquals(BottleProviderOutcome.QUEUED, search.aiOutcome)

        val fetch = queue.updateRunning(
            search.sessionId, search.draftId, BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = "https://example.com/product", attemptToken = search.attemptToken!!, nowMillis = 3,
        )!!
        assertEquals(BottleProviderOutcome.RUNNING, fetch.webOutcome)
        val extraction = queue.updateRunning(
            fetch.sessionId, fetch.draftId, BottleEnrichmentOperation.LOCAL_EXTRACTION,
            sourceUrl = fetch.sourceUrl, model = LOCAL_MODEL_LABEL, attemptToken = fetch.attemptToken!!, nowMillis = 4,
        )!!
        assertEquals(BottleProviderOutcome.COMPLETE, extraction.webOutcome)
        assertEquals(BottleProviderOutcome.RUNNING, extraction.aiOutcome)
    }

    @Test
    fun interruptedWorkPersistsOnlyBoundedMetadataAndRequiresExplicitResume() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val waitingForLabel = queue.requireLabel("session-1", "draft-1", nowMillis = 5)!!
        assertEquals(BottleEnrichmentStatus.QUEUED, waitingForLabel.status)
        assertTrue(waitingForLabel.labelCaptureRequired)
        val queued = queue.enqueue("session-1", "draft-1", nowMillis = 8)!!
        assertEquals(waitingForLabel.id, queued.id)
        assertEquals(BottleEnrichmentStatus.QUEUED, queued.status)
        assertTrue(queued.labelCaptureRequired)
        val search = queue.begin("session-1", "draft-1", BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = 10)!!
        assertTrue(search.labelCaptureRequired)
        assertTrue(queue.completeLabelCapture("draft-1", nowMillis = 15))
        assertFalse(queue.status("draft-1")!!.labelCaptureRequired)
        val fetch = queue.begin(
            "session-1", "draft-1", BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = "https://example.com/product", nowMillis = 20,
        )!!
        assertEquals(1, queue.jobs().size)
        assertEquals(search.id, fetch.id)
        assertTrue(storage.raw!!.contains("https://example.com/product"))
        assertFalse(storage.raw!!.contains("search terms"))
        assertFalse(storage.raw!!.contains("page markdown"))
        assertFalse(storage.raw!!.contains("api-key-canary"))

        val afterProcessRestart = BottleEnrichmentQueue(storage).recoverInterrupted()
        assertEquals(BottleEnrichmentStatus.PAUSED, afterProcessRestart.single().status)
        assertEquals(BottleEnrichmentOperation.FIRECRAWL_FETCH, afterProcessRestart.single().operation)
        assertEquals("https://example.com/product", afterProcessRestart.single().sourceUrl)
        assertEquals(BottleEnrichmentStatus.PAUSED, BottleEnrichmentQueue(storage).jobs().single().status)
    }

    @Test
    fun extractionQueueStoresModelAndSourceButRejectsUnsafeOrUnboundedMetadata() {
        val queue = BottleEnrichmentQueue(MemoryStorage())
        val job = queue.begin(
            sessionId = "session",
            draftId = "draft",
            operation = BottleEnrichmentOperation.OLLAMA_EXTRACTION,
            sourceUrl = "https://example.com/gin",
            model = "qwen3:8b-cloud",
            nowMillis = 1,
        )!!
        assertEquals("qwen3:8b-cloud", job.model)
        assertNull(queue.begin("session", "bad-url", BottleEnrichmentOperation.FIRECRAWL_FETCH, sourceUrl = "http://127.0.0.1/private", nowMillis = 2))
        assertNull(queue.begin("session", "bad-model", BottleEnrichmentOperation.OLLAMA_EXTRACTION, sourceUrl = "https://example.com", model = "invalid model", nowMillis = 3))
        assertTrue(queue.removeDraft("draft"))
        assertTrue(queue.jobs().isEmpty())
    }

    @Test
    fun localExtractionPersistsOnlySourceAndModelMetadataAndPausesOnRestart() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val running = queue.begin(
            "session-local",
            "draft-local",
            BottleEnrichmentOperation.LOCAL_EXTRACTION,
            sourceUrl = "https://example.org/gin",
            model = LOCAL_MODEL_LABEL,
            nowMillis = 10,
        )!!

        val restored = BottleEnrichmentQueue(storage).recoverInterrupted().single()

        assertEquals(running.id, restored.id)
        assertEquals(BottleEnrichmentOperation.LOCAL_EXTRACTION, restored.operation)
        assertEquals(BottleEnrichmentStatus.PAUSED, restored.status)
        assertFalse(storage.raw.orEmpty().contains("source text"))
        assertFalse(storage.raw.orEmpty().contains("generated output"))
        assertFalse(storage.raw.orEmpty().contains("apiKey"))
    }

    @Test
    fun malformedQueueStorageFailsClosedWithoutIssuingRequests() {
        val storage = MemoryStorage("not-json")
        val queue = BottleEnrichmentQueue(storage)
        assertTrue(queue.jobs().isEmpty())
        assertTrue(queue.recoverInterrupted().isEmpty())
    }

    @Test
    fun completedSuggestionsRemainInReviewQueueAndNeverPersistPageOrModelOutput() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val job = queue.enqueue("session-review", "draft-review", nowMillis = 1)!!
        queue.begin(
            sessionId = job.sessionId,
            draftId = job.draftId,
            operation = BottleEnrichmentOperation.LOCAL_EXTRACTION,
            sourceUrl = "https://example.com/product",
            model = LOCAL_MODEL_LABEL,
            nowMillis = 2,
        )

        assertTrue(queue.finish(job.draftId, BottleEnrichmentStatus.READY_FOR_REVIEW, "CANDIDATES_READY", 3))
        val restored = BottleEnrichmentQueue(storage).jobs().single()

        assertEquals(BottleEnrichmentStatus.READY_FOR_REVIEW, restored.status)
        assertEquals("CANDIDATES_READY", restored.resultCode)
        assertEquals(job.id, restored.id)
        assertTrue(storage.raw.orEmpty().contains("https://example.com/product"))
        assertFalse(storage.raw.orEmpty().contains("page markdown"))
        assertFalse(storage.raw.orEmpty().contains("generated output"))
        assertFalse(storage.raw.orEmpty().contains("api-key-canary"))
    }

    @Test
    fun perProviderOutcomesSurviveRestartWithoutSavingResponseText() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val queued = queue.enqueue("session-outcomes", "draft-outcomes", nowMillis = 1)!!
        val running = queue.beginQueued(
            sessionId = queued.sessionId,
            draftId = queued.draftId,
            operation = BottleEnrichmentOperation.FIRECRAWL_SEARCH,
            attemptToken = queued.attemptToken!!,
            nowMillis = 2,
        )!!

        assertTrue(queue.completeIfRunning(
            draftId = running.draftId,
            attemptToken = running.attemptToken!!,
            nowMillis = 3,
            providerOutcomes = BottleEnrichmentProviderOutcomes(
                web = BottleProviderOutcome.NO_RESULTS,
                ai = BottleProviderOutcome.SKIPPED_NO_SOURCE,
            ),
        ) { BottleEnrichmentStatus.NEEDS_ATTENTION to "NO_SOURCE_FOUND" })

        val reopened = BottleEnrichmentQueue(storage).status("draft-outcomes")!!
        assertEquals(BottleProviderOutcome.NO_RESULTS, reopened.webOutcome)
        assertEquals(BottleProviderOutcome.SKIPPED_NO_SOURCE, reopened.aiOutcome)
        assertFalse(storage.raw.orEmpty().contains("page markdown"))
        assertFalse(storage.raw.orEmpty().contains("model output"))
    }

    @Test
    fun untouchedQueuedJobsRemainSchedulableWhileInterruptedWorkPauses() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        queue.enqueue("session", "draft-queued", nowMillis = 1)
        queue.enqueue("session", "draft-running", nowMillis = 2)
        queue.begin("session", "draft-running", BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = 3)
        queue.enqueue("session", "draft-ready", nowMillis = 2)
        queue.finish("draft-ready", BottleEnrichmentStatus.NEEDS_ATTENTION, "NO_MATCH", 4)

        val recovered = BottleEnrichmentQueue(storage).recoverInterrupted().associateBy { it.draftId }

        assertEquals(BottleEnrichmentStatus.QUEUED, recovered.getValue("draft-queued").status)
        assertEquals(BottleEnrichmentStatus.PAUSED, recovered.getValue("draft-running").status)
        assertEquals(BottleEnrichmentStatus.NEEDS_ATTENTION, recovered.getValue("draft-ready").status)
    }

    @Test
    fun activeDurableWorkRemainsRunningDuringUiReconciliation() {
        val queue = BottleEnrichmentQueue(MemoryStorage())
        queue.enqueue("session", "draft-active", nowMillis = 1)
        queue.begin("session", "draft-active", BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = 2)

        val recovered = queue.recoverInterrupted(activeWorkDraftIds = setOf("draft-active"))

        assertEquals(BottleEnrichmentStatus.RUNNING, recovered.single().status)
    }

    @Test
    fun legacyQueuedJobGetsOneStableWorkerAttemptTokenDuringRecovery() {
        val storage = MemoryStorage(
            """[{"id":"job","sessionId":"session","draftId":"draft","operation":"FIRECRAWL_SEARCH","status":"QUEUED","labelCaptureRequired":false,"updatedAtMillis":1}]""",
        )
        val queue = BottleEnrichmentQueue(storage)

        val recovered = queue.recoverInterrupted().single()
        val token = recovered.attemptToken

        assertTrue(token != null)
        assertEquals(token, BottleEnrichmentQueue(storage).jobs().single().attemptToken)
    }

    @Test
    fun cancelledQueuedWorkCannotBeClaimedOrAdvancedByALateWorker() {
        val queue = BottleEnrichmentQueue(MemoryStorage())
        val queued = queue.enqueue("session", "draft", nowMillis = 1)!!
        assertTrue(queue.finish("draft", BottleEnrichmentStatus.CANCELLED, "CANCELLED_BY_USER", 2))
        var finalizeCalled = false

        assertNull(queue.beginQueued("session", "draft", BottleEnrichmentOperation.FIRECRAWL_SEARCH, queued.attemptToken!!, nowMillis = 3))
        assertEquals(BottleEnrichmentStatus.CANCELLED, queue.status("draft")!!.status)
        assertNull(queue.updateRunning(
            "session", "draft", BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = "https://example.com/product", attemptToken = "stale-attempt", nowMillis = 4,
        ))
        assertFalse(queue.completeIfRunning("draft", "stale-attempt", 5) {
            finalizeCalled = true
            BottleEnrichmentStatus.READY_FOR_REVIEW to "CANDIDATES_READY"
        })
        assertFalse(finalizeCalled)
        assertFalse(queue.pause(queued.id, 6))
        assertEquals(queued.id, queue.status("draft")!!.id)
        assertEquals("CANCELLED_BY_USER", queue.status("draft")!!.resultCode)
    }

    @Test
    fun lateAttemptCannotPauseOrFinalizeAnExplicitlyRetriedBottle() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        queue.enqueue("session", "draft", nowMillis = 1)
        val first = queue.beginQueued("session", "draft", BottleEnrichmentOperation.FIRECRAWL_SEARCH, queue.status("draft")!!.attemptToken!!, 2)!!
        val firstAttempt = first.attemptToken!!
        assertTrue(queue.finish("draft", BottleEnrichmentStatus.CANCELLED, "CANCELLED_BY_USER", 3))
        val retried = queue.enqueue("session", "draft", nowMillis = 4)!!
        val second = queue.beginQueued("session", "draft", BottleEnrichmentOperation.FIRECRAWL_SEARCH, retried.attemptToken!!, 5)!!
        val secondAttempt = second.attemptToken!!
        assertFalse(firstAttempt == secondAttempt)

        assertNull(queue.updateRunning(
            "session", "draft", BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = "https://example.com/product", attemptToken = firstAttempt, nowMillis = 6,
        ))
        assertFalse(queue.pauseAttempt("draft", firstAttempt, 7))
        assertFalse(queue.finishAttempt("draft", firstAttempt, BottleEnrichmentStatus.NEEDS_ATTENTION, "OLD_FAILED", 8))
        assertFalse(queue.completeIfRunning("draft", firstAttempt, 9) {
            BottleEnrichmentStatus.READY_FOR_REVIEW to "CANDIDATES_READY"
        })
        assertEquals(BottleEnrichmentStatus.RUNNING, queue.status("draft")!!.status)
        assertEquals(secondAttempt, BottleEnrichmentQueue(storage).status("draft")!!.attemptToken)
    }

    @Test
    fun cancelledOrStoppedWorkerCannotPersistLateSuggestions() {
        assertFalse(mayPersistBottleEnrichmentResult(true, BottleEnrichmentStatus.RUNNING))
        assertFalse(mayPersistBottleEnrichmentResult(false, BottleEnrichmentStatus.CANCELLED))
        assertFalse(mayPersistBottleEnrichmentResult(false, BottleEnrichmentStatus.REVIEWED))
        assertTrue(mayPersistBottleEnrichmentResult(false, BottleEnrichmentStatus.RUNNING))
    }

    @Test
    fun labelCaptureAndLookupCanOverlapAndCaptureStateSurvivesRestart() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val job = queue.requireLabel("session", "draft", nowMillis = 1)!!
        queue.begin("session", "draft", BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = 2)

        val restarted = BottleEnrichmentQueue(storage).recoverInterrupted().single()

        assertEquals(job.id, restarted.id)
        assertEquals(BottleEnrichmentStatus.PAUSED, restarted.status)
        assertTrue(restarted.labelCaptureRequired)
        assertTrue(BottleEnrichmentQueue(storage).completeLabelCapture("draft", nowMillis = 3))
        val completed = BottleEnrichmentQueue(storage).jobs().single()
        assertFalse(completed.labelCaptureRequired)
        assertEquals(BottleEnrichmentStatus.PAUSED, completed.status)
    }

    @Test
    fun lookupCanFinishBeforeLabelCaptureWithoutLosingEitherResult() {
        val queue = BottleEnrichmentQueue(MemoryStorage())
        queue.requireLabel("session", "draft", nowMillis = 1)
        queue.begin("session", "draft", BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = 2)

        assertTrue(queue.finish("draft", BottleEnrichmentStatus.READY_FOR_REVIEW, "CANDIDATES_READY", 3))
        val ready = queue.status("draft")!!
        assertTrue(ready.labelCaptureRequired)
        assertEquals(BottleEnrichmentStatus.READY_FOR_REVIEW, ready.status)

        assertTrue(queue.completeLabelCapture("draft", nowMillis = 4))
        val afterCapture = queue.status("draft")!!
        assertFalse(afterCapture.labelCaptureRequired)
        assertEquals(BottleEnrichmentStatus.READY_FOR_REVIEW, afterCapture.status)
        assertEquals("CANDIDATES_READY", afterCapture.resultCode)
    }

    @Test
    fun cancellationAndRetryKeepTheSameBottleJobWithoutRestartingItAutomatically() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val queued = queue.enqueue("session", "draft", nowMillis = 1)!!
        assertTrue(queue.finish("draft", BottleEnrichmentStatus.CANCELLED, "CANCELLED_BY_USER", 2))

        val cancelled = BottleEnrichmentQueue(storage).jobs().single()
        assertEquals(queued.id, cancelled.id)
        assertEquals(BottleEnrichmentStatus.CANCELLED, cancelled.status)
        assertTrue(queue.jobs().none { it.status == BottleEnrichmentStatus.RUNNING })

        val retried = queue.enqueue("session", "draft", nowMillis = 3)!!
        assertEquals(queued.id, retried.id)
        assertEquals(BottleEnrichmentStatus.QUEUED, retried.status)
        assertNull(retried.resultCode)
    }

    @Test
    fun retryKeepsPersistedExplicitlyEditedFieldsIncludingClearedValues() {
        val edited = setOf(BottleDraftField.BRAND, BottleDraftField.PRODUCT_NAME)

        val retried = preserveEditedBottleFields(emptySet(), edited)

        assertEquals(edited, retried)
        assertTrue(BottleDraftField.BRAND in retried)
    }

    @Test
    fun scanPipelineRunsOneProviderAndLocalModelJobAtATime() = runBlocking {
        val gate = BottleEnrichmentSerialGate()
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        coroutineScope {
            (1..5).map {
                async(Dispatchers.Default) {
                    gate.withSlot {
                        val nowActive = active.incrementAndGet()
                        maximum.updateAndGet { prior -> maxOf(prior, nowActive) }
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }.forEach { it.await() }
        }
        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }

    private class MemoryStorage(var raw: String? = null) : BottleEnrichmentQueueStorage {
        override fun read(): String? = raw
        override fun write(value: String): Boolean {
            raw = value
            return true
        }
    }
}
