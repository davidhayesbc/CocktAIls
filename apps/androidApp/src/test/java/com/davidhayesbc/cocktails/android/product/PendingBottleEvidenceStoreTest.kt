package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingBottleEvidenceStoreTest {
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
