package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottleEnrichmentQueueTest {
    @Test
    fun interruptedWorkPersistsOnlyBoundedMetadataAndRequiresExplicitResume() {
        val storage = MemoryStorage()
        val queue = BottleEnrichmentQueue(storage)
        val search = queue.begin("session-1", "draft-1", BottleEnrichmentOperation.FIRECRAWL_SEARCH, nowMillis = 10)!!
        val fetch = queue.begin(
            "session-1", "draft-1", BottleEnrichmentOperation.FIRECRAWL_FETCH,
            sourceUrl = "https://example.com/product", nowMillis = 20,
        )!!
        assertEquals(1, queue.jobs().size)
        assertFalse(search.id == fetch.id)
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

    private class MemoryStorage(var raw: String? = null) : BottleEnrichmentQueueStorage {
        override fun read(): String? = raw
        override fun write(value: String): Boolean {
            raw = value
            return true
        }
    }
}
