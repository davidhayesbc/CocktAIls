package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BottleLabelEvidenceTest {
    private val code = "0086785900001"
    private val draft = BottleIntakeDraft(id = "draft", sessionId = "session", position = 0,
        productId = null, brand = null, productName = null, ingredientName = "Unidentified bottle", category = "Spirit", locationId = "home",
        level = InventoryLevel.FULL, createdAt = 1, barcode = code,
        photoData = byteArrayOf(1), photoSha256 = "photo-one")
    private fun label(text: String): BottleLabelEvidence {
        val parsed = independentBottleFacts(text, explicitLabelCandidates(text))
        return BottleLabelEvidence(draft.sessionId, draft.id, draft.photoSha256!!, code,
            parsed.facts, parsed.unresolved, parsed.conflicts)
    }
    @Test fun missingNameAndModelAbstentionKeepIndependentLabelFacts() = runBlocking {
        val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, _, _ -> "{\"facts\":[]}" })
            .extractLabelText("40% ALC/VOL\n750 mL", emptyList())
        assertEquals(setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT), result.facts.map { it.field }.toSet())
        assertTrue(BottleFactField.PRODUCT_NAME in result.unresolved)
    }
    @Test fun cloudContextCannotBecomeLabelEvidenceAndMalformedNameDoesNotDiscardSize() = runBlocking {
        val source = "750 mL"
        val web = listOf(BottleFactEvidence(BottleFactField.BRAND, "Different Brand", "Different Brand"))
        val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, prompt, _ ->
            assertTrue(prompt.contains("VERIFIED WEB CONTEXT (separate source, context only):"))
            assertTrue(prompt.contains("LABEL OCR (may contain recognition errors; omit uncertain fields):\n750 mL"))
            """{"facts":[{"field":"brand","value":"Different Brand","evidence":"Brand: Different Brand"},{"field":"product_name","value":"Invented","evidence":"Invented"}]}"""
        }).extractLabelText(source, web)
        assertEquals(listOf(BottleFactField.QUANTITY), result.facts.map { it.field })
    }
    @Test fun conflictingLabelValuesStayUnresolvedEvenWhenWebAgreesWithOne() {
        val label = label("40% ALC/VOL\n45% ALC/VOL\n750 mL")
        assertTrue(BottleFactField.ABV_PERCENT in label.conflicts)
        val web = BottleDetailSuggestions("Web", "Fixture", "https://shop.example/item",
            listOf(BottleFactEvidence(BottleFactField.ABV_PERCENT, "40%", "UPC $code 40% ALC/VOL")),
            targetBarcode = code, identityEvidence = "UPC $code")
        val merged = mergeLabelAndWebEvidence(web, label, draft)!!
        assertFalse(merged.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(merged.facts.single().fromLabelPhoto)
    }
    @Test fun webIdentityAndPhotoIdentityRemainIndependent() {
        val web = BottleDetailSuggestions("Web", "Fixture", "https://shop.example/item",
            listOf(BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Unrelated", "Unrelated")))
        assertEquals(listOf(BottleFactField.QUANTITY), mergeLabelAndWebEvidence(web, label("750 mL"), draft)!!.facts.map { it.field })
        val photo = label("750 mL")
        assertFalse(photo.belongsTo(draft.copy(photoSha256 = "photo-two")))
        assertFalse(photo.belongsTo(draft.copy(barcode = "080480007577")))
        assertFalse(photo.belongsTo(draft.copy(sessionId = "another")))
        assertFalse(photo.belongsTo(draft.copy(photoData = null)))
    }
    @Test fun crossSourceConflictDoesNotChooseEitherValueOrReplaceManualClears() {
        val web = BottleDetailSuggestions("Web", "Fixture", "https://shop.example/item",
            listOf(BottleFactEvidence(BottleFactField.QUANTITY, "700 mL", "UPC $code 700 mL")),
            targetBarcode = code, identityEvidence = "UPC $code")
        val merged = mergeLabelAndWebEvidence(web, label("750 mL\n40% ALC/VOL"), draft)!!
        assertTrue(BottleFactField.QUANTITY in merged.unresolvedFields)
        assertFalse(merged.facts.any { it.field == BottleFactField.QUANTITY })
        val manual = BottleDraftSuggestionFields("My name", "", "", "", "")
        assertEquals(manual, mergeBottleSuggestionFields(manual, "Other", "Other", BottleDraftField.entries.toSet(), "750 mL", "40%"))
    }
    @Test fun partialWebFactsNeedSamePassageBarcodeAndRejectConflictingSources() {
        fun page(text: String) = WebPageText("Fixture", "https://shop.example/item", text, "Fixture")
        val result = barcodeBoundPartialSpecifications(page("UPC $code · 750 mL · 40% ALC/VOL"), code)!!
        assertEquals(setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT), result.suggestions.facts.map { it.field }.toSet())
        assertNull(barcodeBoundPartialSpecifications(page("UPC $code; try another bottle: 750 mL · 40% ALC/VOL"), code))
        assertNull(barcodeBoundPartialSpecifications(page("UPC $code\nOther bottle: 750 mL · 40% ALC/VOL"), code))
        assertNull(barcodeBoundPartialSpecifications(page("UPC 080480007577 · 750 mL · 40% ALC/VOL"), code))
        val conflictingSize = barcodeBoundPartialSpecifications(page("UPC $code · 750 mL\nSize 700 mL"), code)!!.suggestions
        assertTrue(conflictingSize.facts.isEmpty())
        assertTrue(BottleFactRejection(BottleFactField.QUANTITY, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES) in conflictingSize.fieldRejections)
    }
    @Test fun localPipelinePreservesBarcodeBoundPartialFactsAfterModelAbstains() = runBlocking {
        val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, _, _ -> "{\"facts\":[]}" })
            .extract(WebPageText("Fixture", "https://shop.example/item", "UPC $code · 750 mL · 40% ALC/VOL", "Fixture"), LOCAL_MODEL_LABEL, code)
        assertTrue(result is BottleDetailExtractionResult.Found)
        assertEquals(setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT),
            (result as BottleDetailExtractionResult.Found).suggestions.facts.map { it.field }.toSet())
    }
    @Test fun webConflictSurvivesExtractionPersistenceAndLabelMerge() = runBlocking {
        for (size in listOf("750 mL · ", "")) {
            val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, _, _ -> "{\"facts\":[]}" })
                .extract(WebPageText("Fixture", "https://shop.example/item",
                    "UPC $code · ${size}40% ALC/VOL · 45% ALC/VOL", "Fixture"), LOCAL_MODEL_LABEL, code)
                as BottleDetailExtractionResult.Found
            val conflict = BottleFactRejection(BottleFactField.ABV_PERCENT, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES)
            assertTrue(conflict in result.suggestions.fieldRejections)
            val memory = object : PendingBottleEvidenceStorage {
                var value: String? = null
                override fun read(draftId: String) = value
                override fun write(draftId: String, value: String): Boolean { this.value = value; return true }
                override fun remove(draftId: String): Boolean { value = null; return true }
            }
            assertTrue(PendingBottleEvidenceStore(memory).save(draft.id, result.suggestions))
            assertTrue(PendingBottleEvidenceStore(memory).markEdited(draft.id, BottleDraftField.QUANTITY))
            val restored = PendingBottleEvidenceStore(memory).read(draft.id)!!
            assertTrue(conflict in restored.fieldRejections)
            val merged = mergeLabelAndWebEvidence(restored, label("750 mL\n40% ALC/VOL"), draft)!!
            assertEquals(listOf(BottleFactField.QUANTITY), merged.facts.map { it.field })
            assertTrue(BottleFactField.ABV_PERCENT in merged.unresolvedFields)
            // Review UI uses this same marker to display its always-visible conflict warning.
            assertTrue(conflict in merged.fieldRejections)
        }
    }
    @Test fun namedProductModelSourceConflictSurvivesPersistenceAndLabelMerge() = runBlocking {
        val quote = "Acme Gin 750 mL 40% ABV"
        val source = "Acme Gin\nUPC $code · $quote\nAcme Gin 45% ABV"
        val modelJson = """{"facts":[{"field":"product_name","value":"Acme Gin","evidence":"Acme Gin"},{"field":"quantity","value":"750 mL","evidence":"$quote"},{"field":"abv_percent","value":"40%","evidence":"$quote"}]}"""
        val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, _, _ -> modelJson })
            .extract(WebPageText("Acme Gin", "https://shop.example/item", source, "Fixture"), LOCAL_MODEL_LABEL, code)
            as BottleDetailExtractionResult.Found
        assertEquals("On-device Qwen3", result.suggestions.provider)
        assertEquals(BottleExtractionMethod.MODEL, result.suggestions.extractionMethod)
        assertEquals(setOf(BottleFactField.PRODUCT_NAME, BottleFactField.QUANTITY), result.suggestions.facts.map { it.field }.toSet())
        val memory = object : PendingBottleEvidenceStorage {
            var value: String? = null
            override fun read(draftId: String) = value
            override fun write(draftId: String, value: String): Boolean { this.value = value; return true }
            override fun remove(draftId: String): Boolean { value = null; return true }
        }
        assertTrue(PendingBottleEvidenceStore(memory).save(draft.id, result.suggestions))
        val restored = PendingBottleEvidenceStore(memory).read(draft.id)!!
        val merged = mergeLabelAndWebEvidence(restored, label("750 mL\n40% ALC/VOL"), draft)!!
        assertFalse(merged.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(BottleFactField.ABV_PERCENT in merged.unresolvedFields)
        val conflict = BottleFactRejection(BottleFactField.ABV_PERCENT, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES)
        assertTrue(conflict in result.suggestions.fieldRejections)
        assertTrue(conflict in restored.fieldRejections)
        assertTrue(conflict in merged.fieldRejections)
    }
    @Test fun structuredNameFallbackPreservesSourceConflictsWithoutModelParsing() = runBlocking {
        val page = WebPageText("Acme Gin", "https://shop.example/item", "Acme Gin\n40% ABV\n45% ABV", "Fixture",
            listOf(StructuredBottleIdentity(code, "Acme Gin", "UPC $code · Acme Gin")))
        val result = LocalBottleDetailExtractor({ null }, LocalInferenceRunner { _, _, _, _ -> error("No model configured") })
            .extract(page, LOCAL_MODEL_LABEL, code) as BottleDetailExtractionResult.Found
        assertEquals("Structured product data", result.suggestions.provider)
        val memory = object : PendingBottleEvidenceStorage {
            var value: String? = null
            override fun read(draftId: String) = value
            override fun write(draftId: String, value: String): Boolean { this.value = value; return true }
            override fun remove(draftId: String): Boolean { value = null; return true }
        }
        assertTrue(PendingBottleEvidenceStore(memory).save(draft.id, result.suggestions))
        val restored = PendingBottleEvidenceStore(memory).read(draft.id)!!
        val merged = mergeLabelAndWebEvidence(restored, label("750 mL\n40% ALC/VOL"), draft)!!
        assertFalse(merged.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(BottleFactField.ABV_PERCENT in merged.unresolvedFields)
        assertTrue(BottleFactRejection(BottleFactField.ABV_PERCENT, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES) in merged.fieldRejections)
    }
    @Test fun independentFieldsDoNotInferBrandFromProductTitleAfterAnotherLine() = runBlocking {
        val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Acme Gin","evidence":"Acme Gin"},{"field":"brand","value":"Acme","evidence":"Acme Gin"}]}"""
        }).extractLabelText("Distilled in England\nAcme Gin\n750 mL", emptyList())
        assertEquals(setOf(BottleFactField.PRODUCT_NAME, BottleFactField.QUANTITY), result.facts.map { it.field }.toSet())
        assertTrue(BottleFactField.BRAND in result.unresolved)
    }
    @Test fun explicitlyLabeledBrandRemainsValidAlongsideProductNameAndSize() = runBlocking {
        val result = LocalBottleDetailExtractor({ "fixture.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Acme Gin","evidence":"Acme Gin"},{"field":"brand","value":"Acme","evidence":"Brand: Acme"}]}"""
        }).extractLabelText("Distilled in England\nAcme Gin\nBrand: Acme\n750 mL", emptyList())
        assertEquals(setOf(BottleFactField.PRODUCT_NAME, BottleFactField.BRAND, BottleFactField.QUANTITY), result.facts.map { it.field }.toSet())
    }
    @Test fun persistedEvidenceRejectsStaleRequestsAndChangedPhotos() {
        val memory = object : PendingBottleEvidenceStorage {
            val values = mutableMapOf<String,String>()
            override fun read(draftId: String) = values[draftId]
            override fun write(draftId: String, value: String): Boolean { values[draftId] = value; return true }
            override fun remove(draftId: String): Boolean { values.remove(draftId); return true }
        }
        val store = BottleLabelEvidenceStore(memory)
        assertTrue(store.request(draft.id, "first"))
        assertTrue(store.request(draft.id, "second"))
        assertFalse(store.save("first", label("750 mL")))
        assertTrue(store.save("second", label("750 mL\nPRIVATE FULL TRANSCRIPT NOT RETAINED")))
        assertFalse(memory.values.values.any { it.contains("PRIVATE FULL TRANSCRIPT") })
        assertEquals("750 mL", BottleLabelEvidenceStore(memory).read(draft)!!.facts.single().value)
        assertNull(store.read(draft.copy(photoSha256 = "new-photo")))
        store.cancel(draft.id)
        assertFalse(store.save("second", label("700 mL")))
    }
}
