package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.InventoryLevel

class ScannedBottleWorkflowTest {
    @Test
    fun barcodeSearchQueryIncludesExactScannedCodeAndDoesNotNeedUnverifiedProductText() {
        assertEquals("\"5011333150725\"", scannedBottleSearchQuery("5011333150725"))
        assertEquals("\"088352109084\"", scannedBottleSearchQuery("088352109084"))
        assertEquals("\"628011812230\"", scannedBottleSearchQuery("628011812230"))
        assertNull(scannedBottleSearchQuery("not-a-barcode"))
    }

    @Test
    fun sourceMustContainSameNormalizedBarcodeBeforeItCanBeExtracted() {
        val barcode = "5011333150725"
        assertTrue(sourceMentionsScannedBarcode(barcode, "UPC: 5011333150725 · Three Wood"))
        assertTrue(sourceMentionsScannedBarcode(barcode, "Product code 5 011 3331 5072 5"))
        assertFalse(sourceMentionsScannedBarcode(barcode, "Auchentoshan Three Wood 700 ml"))
        assertFalse(sourceMentionsScannedBarcode(barcode, "5011333150733"))
        assertFalse(sourceMentionsScannedBarcode("invalid", "5011333150725"))
        assertEquals(ScannedBarcodePageDecision.MISSING, scannedBarcodePageDecision(barcode, "Auchentoshan Three Wood 700 ml"))
        assertEquals(ScannedBarcodePageDecision.MISMATCH, scannedBarcodePageDecision(barcode, "EAN 5011333150732"))
        assertEquals(ScannedBarcodePageDecision.AMBIGUOUS, scannedBarcodePageDecision(barcode, "UPC 5011333150725, alternate UPC 5011333150732"))
    }

    @Test
    fun onlyStillAcceptedFactValuesBecomeDurableProductSources() {
        val barcode = "5011333150725"
        val suggestions = BottleDetailSuggestions(
            provider = "Ollama Cloud",
            model = "test-model",
            retrievalProvider = "Firecrawl",
            sourceUrl = "https://shop.example/three-wood",
            facts = listOf(
                BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Three Wood", "UPC 5011333150725 · Three Wood"),
                BottleFactEvidence(BottleFactField.BRAND, "Auchentoshan", "Brand: Auchentoshan"),
                BottleFactEvidence(BottleFactField.ABV_PERCENT, "43%", "43% ABV"),
            ),
            targetBarcode = barcode,
            identityEvidence = "UPC 5011333150725 · Three Wood",
        )
        val accepted = BottleIntakeDraft(
            id = "draft-1", sessionId = "session-1", position = 0, barcode = barcode,
            productId = null, ingredientName = "Three Wood", category = "Whisky", brand = "Auchentoshan",
            productName = "Three Wood", photoData = null, photoSha256 = null,
            locationId = "home-1", level = InventoryLevel.FULL, createdAt = 1,
        )

        val sources = acceptedProductSourcesForBottle(accepted, suggestions)
        assertEquals(setOf("product_name" to "Three Wood", "brand" to "Auchentoshan"), sources.map { it.fieldName to it.fieldValue }.toSet())
        assertTrue(sources.all { it.retrievalProvider == "Firecrawl" && it.extractionProvider == "Ollama Cloud" })
        assertTrue(acceptedProductSourcesForBottle(accepted.copy(ingredientName = "My own name", productName = "My own name"), suggestions)
            .none { it.fieldName == "product_name" })
        assertTrue(acceptedProductSourcesForBottle(accepted.copy(barcode = "5011333150733"), suggestions).isEmpty())
    }

    @Test
    fun extractorSelectionNeverFallsBackFromEnabledLocalToCloud() {
        assertEquals(AutomaticBottleExtractor.ON_DEVICE, automaticBottleExtractor(true, true))
        assertEquals(AutomaticBottleExtractor.ON_DEVICE, automaticBottleExtractor(true, false))
        assertEquals(AutomaticBottleExtractor.OLLAMA_CLOUD, automaticBottleExtractor(false, true))
        assertNull(automaticBottleExtractor(false, false))
    }

    @Test
    fun enabledProvidersRunInOrderAndOnlyAnExactBarcodePageReachesTheExtractor() = runBlocking {
        val calls = mutableListOf<String>()
        val page = WebPageText(
            title = "Auchentoshan Three Wood",
            sourceUrl = "https://shop.example/three-wood",
            markdown = "Product code 5011333150725 · 750 mL · 43% ABV",
            provider = "Firecrawl",
        )
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { code -> calls += "off:$code"; BottleProviderCall(OpenFoodFactsLookupResult.NotFound, 404) },
            search = { query ->
                calls += "search:$query"
                BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("Three Wood", "https://shop.example/three-wood", null))), 200)
            },
            fetch = { url -> calls += "fetch:$url"; BottleProviderCall(WebPageResult.Found(page), 200) },
            extract = { fetched, provider, requiredBarcode ->
                calls += "extract:${provider.name}"
                assertEquals("5011333150725", requiredBarcode)
                assertEquals(page, fetched)
                BottleProviderCall(BottleDetailExtractionResult.InvalidResponse)
            },
        )

        val result = coordinator.processScannedBarcode(
            "5011333150725",
            ScannedBottlePlan(true, true, AutomaticBottleExtractor.ON_DEVICE),
        )

        assertEquals(listOf(
            "off:5011333150725",
            "search:\"5011333150725\"",
            "fetch:https://shop.example/three-wood",
            "extract:ON_DEVICE",
        ), calls)
        assertTrue(result.barcodeVerifiedInPage)
        assertTrue(result.extraction is BottleDetailExtractionResult.InvalidResponse)
    }

    @Test
    fun wrongVariantPageIsShownButNeverSentToModelAndNoCloudFallbackOccurs() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { calls += "off"; BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { calls += "search"; BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("Whisky", "https://shop.example/wrong", null)))) },
            fetch = { calls += "fetch"; BottleProviderCall(WebPageResult.Found(WebPageText("Different whisky", "https://shop.example/wrong", "Barcode 5011333150733", "Firecrawl"))) },
            extract = { _, provider, _ -> calls += "extract:${provider.name}"; BottleProviderCall(BottleDetailExtractionResult.InvalidResponse) },
        )

        val result = coordinator.processScannedBarcode(
            "5011333150725",
            ScannedBottlePlan(openFoodFactsEnabled = false, firecrawlEnabled = true, extractor = AutomaticBottleExtractor.ON_DEVICE),
        )

        assertEquals(listOf("search", "fetch"), calls)
        assertFalse(result.barcodeVerifiedInPage)
        assertNull(result.extraction)
    }

    @Test
    fun exactBarcodeSearchHitIsPreferredOverAnUnverifiedResult() = runBlocking {
        var fetchedUrl: String? = null
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = {
                BottleProviderCall(WebSearchResult.Found(listOf(
                    WebSearchHit("Similar whisky", "https://shop.example/similar", "A related bottle"),
                    WebSearchHit("Product code 5011333150725", "https://shop.example/exact", "Three Wood"),
                )), 200)
            },
            fetch = { url ->
                fetchedUrl = url
                BottleProviderCall(WebPageResult.Found(WebPageText("Three Wood", url, "Barcode 5011333150725", "Firecrawl")), 200)
            },
            extract = { _, _, _ -> BottleProviderCall(BottleDetailExtractionResult.InvalidResponse) },
        )

        val result = coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, null))

        assertEquals("https://shop.example/exact", fetchedUrl)
        assertEquals(fetchedUrl, result.selectedHit?.url)
        assertTrue(result.barcodeVerifiedInPage)
        assertNull(result.extraction)
    }

    @Test
    fun genericBarcodeLookupPagesAreReportedButNotFetched() = runBlocking {
        var actualQuery: String? = null
        var fetchCount = 0
        val events = mutableListOf<BottleLookupDebugEvent>()
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound, 404) },
            search = { query ->
                actualQuery = query
                BottleProviderCall(WebSearchResult.Found(listOf(
                    WebSearchHit("Barcode Lookup | UPC, EAN & ISBN Search", "https://www.barcodelookup.com/", "Search by UPC"),
                    WebSearchHit("UPC Lookup", "https://go-upc.com/barcode-lookup", "Look up product details"),
                    WebSearchHit("EAN Search", "https://www.ean-search.org/", "EAN database"),
                )), 200)
            },
            fetch = { fetchCount++; BottleProviderCall(WebPageResult.Unavailable) },
            extract = { _, _, _ -> error("Generic search pages must not reach the model") },
        )

        val outcome = coordinator.processScannedBarcode(
            "5011333150725",
            ScannedBottlePlan(openFoodFactsEnabled = true, firecrawlEnabled = true, extractor = AutomaticBottleExtractor.ON_DEVICE),
            onDebugEvent = events::add,
        )

        assertEquals("\"5011333150725\"", actualQuery)
        assertEquals(0, fetchCount)
        assertNull(outcome.selectedHit)
        val selection = events.single { it.stage == BottleLookupDebugStage.SOURCE_SELECTION }
        assertEquals("NO_USABLE_SOURCE", selection.result)
        assertEquals(listOf("GENERIC_BARCODE_LOOKUP", "GENERIC_BARCODE_LOOKUP", "GENERIC_BARCODE_LOOKUP"), selection.reasonCodes)
        assertEquals(3, events.last { it.stage == BottleLookupDebugStage.FIRECRAWL_SEARCH }.resultCount)
    }

    @Test
    fun debugTraceRecordsProviderStatusTimingsAndBarcodeRejectionWithoutPageText() = runBlocking {
        val events = mutableListOf<BottleLookupDebugEvent>()
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound, 404) },
            search = { BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("Whisky", "https://shop.example/item?token=secret", null))), 200) },
            fetch = { BottleProviderCall(WebPageResult.Found(WebPageText("Whisky", it, "No matching code was present", "Firecrawl")), 200) },
            extract = { _, _, _ -> error("No model extraction is allowed for an unverified page") },
        )

        coordinator.processScannedBarcode(
            "5011333150725",
            ScannedBottlePlan(openFoodFactsEnabled = true, firecrawlEnabled = true, extractor = AutomaticBottleExtractor.OLLAMA_CLOUD),
            onDebugEvent = events::add,
        )

        assertEquals(404, events.last { it.stage == BottleLookupDebugStage.OPEN_FOOD_FACTS }.httpStatus)
        assertEquals(200, events.last { it.stage == BottleLookupDebugStage.FIRECRAWL_SEARCH }.httpStatus)
        assertTrue(events.last { it.stage == BottleLookupDebugStage.FIRECRAWL_SEARCH }.durationMillis >= 0)
        assertEquals("BARCODE_MISSING", events.last { it.stage == BottleLookupDebugStage.BARCODE_MATCH }.result)
        assertEquals(listOf("NO_EXACT_CODE_IN_PAGE"), events.last { it.stage == BottleLookupDebugStage.BARCODE_MATCH }.reasonCodes)
        assertTrue(events.none { it.stage == BottleLookupDebugStage.DETAIL_EXTRACTION && it.result == "CANDIDATES_READY" })
        val report = BottleLookupDebugReport(
            createdAtMillis = 1,
            versionName = "1.0",
            versionCode = 4,
            originalBarcode = "5011333150725",
            normalizedBarcode = "5011333150725",
            openFoodFactsEnabled = true,
            openFoodFactsConfigured = true,
            firecrawlEnabled = true,
            firecrawlConfigured = true,
            extractorProvider = "Ollama Cloud",
            modelIdentifier = "fixture-model",
            query = "\"5011333150725\"",
        )
        events.forEach(report::record)
        report.finish("PARTIAL")
        val json = report.toJson()
        assertFalse(json.contains("token="))
        assertTrue(json.contains("shop.example/item"))
        assertTrue(json.contains("5011333150725"))
    }

    @Test
    fun disabledOrUnconfiguredProvidersAreNotCalledAndManualFlowRemainsAvailable() = runBlocking {
        var calls = 0
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { calls++; BottleProviderCall(OpenFoodFactsLookupResult.Unavailable) },
            search = { calls++; BottleProviderCall(WebSearchResult.SetupRequired) },
            fetch = { calls++; BottleProviderCall(WebPageResult.Unavailable) },
            extract = { _, _, _ -> calls++; BottleProviderCall(BottleDetailExtractionResult.Unavailable) },
        )

        val result = coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(false, false, null))

        assertEquals(0, calls)
        assertNull(result.openFoodFacts)
        assertNull(result.search)
        assertNull(result.page)
        assertNull(result.extraction)
    }

    @Test
    fun searchFailureAndNoResultsDoNotFetchOrRunInference() = runBlocking {
        listOf(WebSearchResult.QuotaExceeded, WebSearchResult.RateLimited, WebSearchResult.Unavailable, WebSearchResult.Found(emptyList())).forEach { searchResult ->
            var fetches = 0
            var extractions = 0
            val coordinator = ScannedBottleEnrichmentCoordinator(
                lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
                search = { BottleProviderCall(searchResult, if (searchResult is WebSearchResult.RateLimited) 429 else 200) },
                fetch = { fetches++; BottleProviderCall(WebPageResult.Unavailable) },
                extract = { _, _, _ -> extractions++; BottleProviderCall(BottleDetailExtractionResult.Unavailable) },
            )
            val result = coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(true, true, AutomaticBottleExtractor.OLLAMA_CLOUD))
            assertEquals(searchResult, result.search)
            assertEquals(0, fetches)
            assertEquals(0, extractions)
        }
    }

    @Test
    fun cancellationStopsThePipelineWithoutFetchingOrRunningAModel() = runBlocking {
        var fetches = 0
        var extractions = 0
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { throw CancellationException("user canceled") },
            fetch = { fetches++; BottleProviderCall(WebPageResult.Unavailable) },
            extract = { _, _, _ -> extractions++; BottleProviderCall(BottleDetailExtractionResult.Unavailable) },
        )

        val canceled = runCatching {
            coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))
        }

        assertTrue(canceled.exceptionOrNull() is CancellationException)
        assertEquals(0, fetches)
        assertEquals(0, extractions)
    }

    @Test
    fun cancellationAfterFetchNeverReturnsLateModelSuggestions() = runBlocking {
        var extractions = 0
        val page = WebPageText("Three Wood", "https://shop.example/item", "UPC 5011333150725", "Firecrawl")
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("Three Wood", page.sourceUrl, null)))) },
            fetch = { BottleProviderCall(WebPageResult.Found(page)) },
            extract = { _, _, _ -> extractions++; throw CancellationException("user canceled") },
        )

        val canceled = runCatching {
            coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))
        }

        assertTrue(canceled.exceptionOrNull() is CancellationException)
        assertEquals(1, extractions)
    }

    @Test
    fun sourceWithCompetingBottleSizesKeepsFactsForReviewInsteadOfInferringOne() = runBlocking {
        val page = WebPageText(
            "Three Wood",
            "https://shop.example/three-wood",
            "UPC 5011333150725. Available in 700 mL and 750 mL bottles.",
            "Firecrawl",
        )
        val result = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("Three Wood", page.sourceUrl, null)))) },
            fetch = { BottleProviderCall(WebPageResult.Found(page)) },
            extract = { fetched, _, _ ->
                assertTrue(fetched.markdown.contains("700 mL and 750 mL"))
                BottleProviderCall(BottleDetailExtractionResult.InvalidResponse)
            },
        ).processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))

        assertTrue(result.barcodeVerifiedInPage)
        assertTrue(result.extraction is BottleDetailExtractionResult.InvalidResponse)
    }
}
