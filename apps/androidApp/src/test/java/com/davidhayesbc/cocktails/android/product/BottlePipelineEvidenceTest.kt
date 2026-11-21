package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import com.davidhayesbc.cocktails.core.data.ReferenceProductCodec
import com.davidhayesbc.cocktails.core.data.ReferenceProductMatch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BottlePipelineEvidenceTest {
    private val code = "3035542002004"
    private fun reference(): BottleDetailSuggestions {
        val path = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "catalog-packs/bc-products/products.json") }.first { it.exists() }
        val pack = ReferenceProductCodec.decode(path.readText())
        val row = pack.records.single { it.rawUpc == code }
        assertTrue(row.lookupEligible)
        return referenceBottleEvidence(ReferenceProductMatch(row, pack), code)
    }

    @Test fun cointreauReferenceThenVerifiedWebRetainsSizeConflictAndNeverGuessesWithAi() = runBlocking {
        val order = mutableListOf<String>()
        val hits = listOf(
            WebSearchHit("COINTREAU 750 ml", "https://woolworths.example/product", code),
            WebSearchHit("LICOR COINTREAU 700 ML", "https://judge.me/reviews/product", "sku:$code"),
            WebSearchHit("COINTREAU 750ML", "https://barcode-list.example/item", code),
            WebSearchHit("COINTREAU 750ml", "https://marquis.example/item", "UPC $code"),
        )
        val result = ScannedBottleEnrichmentCoordinator(
            referenceLookup = { order += "reference"; reference() },
            lookup = { order += "off"; BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { order += "search"; BottleProviderCall(WebSearchResult.Found(hits)) },
            fetch = { url -> order += "fetch"; BottleProviderCall(WebPageResult.Found(WebPageText(null, url,
                if (url.contains("judge.me")) "Currencies USD EUR\n# LICOR COINTREAU 700 ML\nSKU: $code\n## Recommended products\nOther bottle 750 ml\nOther can 175 ml\nOther drink 330 ml"
                else "# COINTREAU 750 ml\nUPC: $code", "Firecrawl"))) },
            extract = { _, _, _ -> error("AI cannot resolve a verified package-size conflict") },
        ).processScannedBarcode(code, ScannedBottlePlan(true, true, AutomaticBottleExtractor.ON_DEVICE))
        assertEquals(listOf("reference", "off", "search"), order.take(3))
        assertEquals(3, order.count { it == "fetch" })
        val evidence = (result.extraction as BottleDetailExtractionResult.Found).suggestions
        assertEquals("COINTREAU", evidence.facts.single { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertEquals("40%", evidence.facts.single { it.field == BottleFactField.ABV_PERCENT }.value)
        assertFalse(evidence.facts.any { it.field == BottleFactField.QUANTITY })
        assertEquals("PARTIAL", bottleLookupEvidenceOutcome(evidence))
        assertTrue(evidence.fieldRejections.any { it.field == BottleFactField.QUANTITY && it.reason == BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES })
        val store = PendingBottleEvidenceStore(memoryStorage())
        assertTrue(store.markEdited("draft", BottleDraftField.QUANTITY))
        assertTrue(store.save("draft", evidence))
        val restored = store.read("draft")!!
        assertEquals(evidence.facts.map { it.origin }, restored.facts.map { it.origin })
        assertTrue(restored.facts.all { it.origin?.retrievalProvider == "BC Data Catalogue" })
        assertEquals(evidence.fieldRejections, restored.fieldRejections)
        assertTrue(BottleDraftField.QUANTITY in store.editedFields("draft"))
    }

    @Test fun recommendationsDoNotCreateConflictsButCompetingTargetSizesDo() {
        val page = WebPageText(null, "https://shop.example/item",
            "Currency list\n# COINTREAU 700 ML\nSKU: $code\n## Recommended\nOther 750 ml\nOther 330 ml", "Firecrawl")
        val evidence = headingBarcodeEvidence(page, code)!!
        assertEquals("700 mL", evidence.facts.single { it.field == BottleFactField.QUANTITY }.value)
        assertTrue(evidence.fieldRejections.isEmpty())
        val conflicting = page.copy(markdown = "# COINTREAU\nSKU: $code\nSizes: 700 mL and 750 mL")
        assertTrue(headingBarcodeEvidence(conflicting, code)!!.fieldRejections.any { it.field == BottleFactField.QUANTITY })
        val multiple = page.copy(markdown = "# COINTREAU\nSKU: $code\n## Other bottle\nUPC: 086785900001")
        assertEquals(multiple, scopedBarcodeProductPage(multiple, code))
        assertNull(headingBarcodeEvidence(multiple, code))
        for (ancestor in listOf("Recommended products", "You may also like", "Customers also bought", "Reviews")) {
            assertNull(headingBarcodeEvidence(page.copy(markdown = "# $ancestor\n## COINTREAU\nSKU: $code\n750 ml"), code))
        }
        val generic = headingBarcodeEvidence(page.copy(markdown = "# Specifications\nSKU: $code\nVolume: 750 mL\nABV: 40%"), code)!!
        assertEquals(setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT), generic.facts.map { it.field }.toSet())
        assertFalse(pipelineHasCoreFacts(generic))
        assertTrue(generic.facts.all { it.barcodeBoundProductName == null })
    }

    @Test fun referenceEvidenceSurvivesUnavailableWebAndNoModelIsInvoked() = runBlocking {
        val events = mutableListOf<BottleLookupDebugEvent>()
        val result = ScannedBottleEnrichmentCoordinator(referenceLookup = { reference() },
            lookup = { error("disabled") }, search = { BottleProviderCall(WebSearchResult.Unavailable) },
            fetch = { error("no results") }, extract = { _, _, _ -> error("no source") })
            .processScannedBarcode(code, ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE), onDebugEvent = events::add)
        assertEquals("SUCCESS", bottleLookupEvidenceOutcome((result.extraction as BottleDetailExtractionResult.Found).suggestions))
        assertEquals(BottleLookupDebugStage.REFERENCE_LOOKUP, events.first().stage)
        assertEquals(3, events.first().factFields.size)
    }

    @Test fun priorReferenceAndOffEvidenceReachAiWithoutBeingReattributedToWeb() = runBlocking {
        val source = reference().copy(facts = reference().facts.filter { it.field == BottleFactField.PRODUCT_NAME })
        var called = false
        val page = WebPageText(null, "https://shop.example/item", "UPC: $code\nDetails unavailable", "Firecrawl")
        val result = ScannedBottleEnrichmentCoordinator(referenceLookup = { source },
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.Found(OpenFoodFactsSuggestion(code, code, code,
                "COINTREAU", null, "750 mL", null, null, 1, emptyList()), false)) },
            search = { BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("COINTREAU", page.sourceUrl, code)))) },
            fetch = { BottleProviderCall(WebPageResult.Found(page)) },
            extract = { supplied, _, _ ->
                called = true
                assertEquals(setOf(BottleFactField.PRODUCT_NAME, BottleFactField.QUANTITY), supplied.priorBottleEvidence!!.facts.map { it.field }.toSet())
                assertTrue(pipelinePromptContext(supplied.priorBottleEvidence).contains("context only, not page evidence"))
                BottleProviderCall(BottleDetailExtractionResult.Rejected(BottleExtractionRejectionReason.NO_SUPPORTED_FIELDS))
            }).processScannedBarcode(code, ScannedBottlePlan(true, true, AutomaticBottleExtractor.ON_DEVICE))
        assertTrue(called)
        val evidence = (result.extraction as BottleDetailExtractionResult.Found).suggestions
        assertEquals("BC Data Catalogue", evidence.facts.single { it.field == BottleFactField.PRODUCT_NAME }.origin!!.retrievalProvider)
        assertEquals("Open Food Facts", evidence.facts.single { it.field == BottleFactField.QUANTITY }.origin!!.retrievalProvider)
        assertEquals("PARTIAL", bottleLookupEvidenceOutcome(evidence))
        val store = PendingBottleEvidenceStore(memoryStorage())
        assertTrue(store.save("draft", evidence))
        val draft = BottleIntakeDraft(id = "draft", sessionId = "session", position = 0, barcode = code,
            productId = null, ingredientName = "COINTREAU", category = "Spirit", brand = null, productName = "COINTREAU",
            quantity = "750 mL", abvPercent = null, photoData = null, photoSha256 = null, locationId = "home", level = InventoryLevel.FULL, createdAt = 1)
        val accepted = acceptedProductSourcesForBottle(draft, store.read("draft"))
        assertEquals("BC Data Catalogue", accepted.single { it.fieldName == "product_name" }.retrievalProvider)
        assertEquals("Open Food Facts", accepted.single { it.fieldName == "quantity" }.retrievalProvider)
        assertTrue(acceptedProductSourcesForBottle(draft.copy(quantity = null, quantityEdited = true), store.read("draft"))
            .none { it.fieldName == "quantity" })
    }

    @Test fun zeroFieldPersistableEvidenceIsNotSuccessfulExtraction() {
        val empty = reference().copy(facts = emptyList(), unresolvedFields = BottleFactField.entries.toSet())
        val store = PendingBottleEvidenceStore(memoryStorage())
        assertTrue(store.save("draft", empty))
        assertEquals("NOT_FOUND", bottleLookupEvidenceOutcome(store.read("draft")))
    }

    @Test fun aiOuterSpanSurvivesDeterministicFallbackWithoutDoubleCountingNestedSpans() {
        val report = BottleLookupDebugReport(1, "test", 1, code, code, true, true, true, true,
            "On-device Qwen3", LOCAL_MODEL_LABEL, code)
        report.record(BottleLookupDebugEvent(BottleLookupDebugStage.LOCAL_MODEL_LOADING, "On-device Qwen3", "COMPLETE", 2877))
        report.record(BottleLookupDebugEvent(BottleLookupDebugStage.LOCAL_PROMPT_PREFILL, "On-device Qwen3", "COMPLETE", 16229))
        report.record(BottleLookupDebugEvent(BottleLookupDebugStage.LOCAL_GENERATION, "On-device Qwen3", "COMPLETE", 25205))
        report.record(BottleLookupDebugEvent(BottleLookupDebugStage.DETAIL_EXTRACTION, "Deterministic source parser", "DETERMINISTIC_PARTIAL", 30000))
        val timing = Json.parseToJsonElement(report.toJson()).jsonObject.getValue("timing").jsonObject
        assertEquals(30000L, timing.getValue("aiProviderMillis").jsonObject.getValue("durationMillis").jsonPrimitive.long)
    }

    @Test fun challengeTitleAndBannerCannotBeErasedByProductScoping() = runBlocking {
        for (titleChallenge in listOf(true, false)) {
            val page = WebPageText(if (titleChallenge) "Bot check... One moment..." else "COINTREAU",
                "https://shop.example/item", (if (titleChallenge) "" else "# Bot check... One moment...\n") +
                    "# COINTREAU 750 mL\nSKU: $code\nABV: 40%", "Firecrawl")
            val result = ScannedBottleEnrichmentCoordinator(lookup = { error("disabled") },
                search = { BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("COINTREAU", page.sourceUrl, code)))) },
                fetch = { BottleProviderCall(WebPageResult.Found(page)) }, extract = { _, _, _ -> error("challenge must not reach AI") })
                .processScannedBarcode(code, ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))
            assertFalse(result.barcodeVerifiedInPage)
            assertNull(result.extraction)
        }
    }

    @Test fun conflictsClearOnlyUntouchedPrefillsAndCannotDisappearOnRestore() {
        val current = BottleDraftSuggestionFields("Fruit Liqueurs", "COINTREAU", "", "750 mL", "40%")
        val conflicts = setOf(BottleFactField.QUANTITY)
        assertEquals("", mergeBottleSuggestionFields(current, "COINTREAU", "", emptySet(), conflictingFields = conflicts).quantity)
        assertEquals("750 mL", mergeBottleSuggestionFields(current, "COINTREAU", "", setOf(BottleDraftField.QUANTITY), conflictingFields = conflicts).quantity)
        assertEquals("", mergeBottleSuggestionFields(current.copy(quantity = ""), "COINTREAU", "", setOf(BottleDraftField.QUANTITY), suggestedQuantity = "700 mL", conflictingFields = conflicts).quantity)
        val job = BottleEnrichmentJob("job", "session", "draft", BottleEnrichmentOperation.FIRECRAWL_SEARCH,
            BottleEnrichmentStatus.NEEDS_ATTENTION, null, null, "NO_SUPPORTED_FIELDS", false, 1)
        assertTrue(mayRestoreBottleEvidence(job))
        assertFalse(mayRestoreBottleEvidence(job.copy(resultCode = "LOCAL_STORAGE_ERROR")))
        assertFalse(mayRestoreBottleEvidence(job.copy(status = BottleEnrichmentStatus.RUNNING)))
    }

    private fun memoryStorage() = object : PendingBottleEvidenceStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(draftId: String) = values[draftId]
        override fun write(draftId: String, value: String): Boolean { values[draftId] = value; return true }
        override fun remove(draftId: String): Boolean { values.remove(draftId); return true }
    }
}
