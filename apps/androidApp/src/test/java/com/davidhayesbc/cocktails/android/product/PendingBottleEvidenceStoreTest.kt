package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingBottleEvidenceStoreTest {
    @Test
    fun concurrentResultSaveCannotEraseExplicitScalarClearsAfterRecreation() {
        val snapshotRead = java.util.concurrent.CountDownLatch(1)
        val resumeSave = java.util.concurrent.CountDownLatch(1)
        val editsFinished = java.util.concurrent.CountDownLatch(1)
        val raw = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val storage = object : PendingBottleEvidenceStorage {
            override fun read(draftId: String): String? {
                val snapshot = raw.get()
                if (Thread.currentThread().name == "result-save") {
                    snapshotRead.countDown()
                    check(resumeSave.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return snapshot
            }
            override fun write(draftId: String, value: String): Boolean { raw.set(value); return true }
            override fun remove(draftId: String): Boolean { raw.set(null); return true }
        }
        val suggestion = BottleDetailSuggestions("Fixture", "Fixture", "https://shop.example/item",
            listOf(BottleFactEvidence(BottleFactField.QUANTITY, "700 mL", "Size: 700 mL")))
        val saved = java.util.concurrent.atomic.AtomicBoolean(false)
        val save = Thread({ saved.set(PendingBottleEvidenceStore(storage).save("draft", suggestion)) }, "result-save")
        save.start()
        assertTrue(snapshotRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val edit = Thread {
            val uiStore = PendingBottleEvidenceStore(storage)
            uiStore.markEdited("draft", BottleDraftField.QUANTITY)
            uiStore.markEdited("draft", BottleDraftField.ABV_PERCENT)
            editsFinished.countDown()
        }
        edit.start()
        // Without the cross-instance lock the edits finish here, then the stale save overwrites them.
        editsFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        resumeSave.countDown()
        save.join(2_000)
        edit.join(2_000)
        assertFalse(save.isAlive)
        assertFalse(edit.isAlive)
        assertTrue(saved.get())
        val recreated = PendingBottleEvidenceStore(storage)
        val fields = recreated.editedFields("draft")
        assertEquals(setOf(BottleDraftField.QUANTITY, BottleDraftField.ABV_PERCENT), fields)
        assertEquals(suggestion, recreated.read("draft"))
        val manual = BottleDraftSuggestionFields("Manual name", "", "", "", "")
        assertEquals(manual, mergeBottleSuggestionFields(manual, "", "", fields, "700 mL", "40"))
    }

    @Test
    fun verifiedSuggestionsAndFieldEvidenceSurviveStoreRecreationUntilExplicitAcceptance() {
        val storage = MemoryStorage()
        val firstStore = PendingBottleEvidenceStore(storage)
        val suggestion = BottleDetailSuggestions(
            provider = "On-device Qwen3",
            model = "Qwen3-0.6B-Q8_0",
            sourceUrl = "https://shop.example/item",
            facts = listOf(BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Example Whisky", "UPC 5011333150725 · Example Whisky")),
            unresolvedFields = setOf(BottleFactField.QUANTITY),
            targetBarcode = "5011333150725",
            identityEvidence = "UPC 5011333150725 · Example Whisky",
        )

        assertTrue(firstStore.markEdited("draft-1", BottleDraftField.BRAND))
        assertTrue(firstStore.save("draft-1", suggestion))
        val reopenedStore = PendingBottleEvidenceStore(storage)
        assertEquals(suggestion, reopenedStore.read("draft-1"))
        assertEquals(setOf(BottleDraftField.BRAND), reopenedStore.editedFields("draft-1"))
        assertTrue(reopenedStore.remove("draft-1"))
        assertNull(reopenedStore.read("draft-1"))
    }

    @Test
    fun rejectsUnsafeUrlsAndMalformedStoredEvidence() {
        val storage = MemoryStorage()
        val store = PendingBottleEvidenceStore(storage)
        val unsafe = BottleDetailSuggestions(
            provider = "Test", model = "Test", sourceUrl = "http://localhost/private",
            facts = listOf(BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Example", "Example")),
        )
        assertFalse(store.save("draft-1", unsafe))
        storage.values["draft-1"] = "not-json"
        assertNull(store.read("draft-1"))
    }

    private class MemoryStorage : PendingBottleEvidenceStorage {
        val values = mutableMapOf<String, String>()
        override fun read(draftId: String): String? = values[draftId]
        override fun write(draftId: String, value: String): Boolean { values[draftId] = value; return true }
        override fun remove(draftId: String): Boolean = values.remove(draftId) != null
    }
}
