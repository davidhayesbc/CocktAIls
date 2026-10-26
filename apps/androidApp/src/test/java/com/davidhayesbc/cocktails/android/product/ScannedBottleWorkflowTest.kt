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
        assertEquals("barcode UPC EAN product code 5011333150725", scannedBottleSearchQuery("5011333150725"))
        assertEquals("barcode UPC EAN product code 088352109084", scannedBottleSearchQuery("088352109084"))
        assertEquals("barcode UPC EAN product code 628011812230", scannedBottleSearchQuery("628011812230"))
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
            lookup = { code -> calls += "off:$code"; OpenFoodFactsLookupResult.NotFound },
            search = { query ->
                calls += "search:$query"
                WebSearchResult.Found(listOf(WebSearchHit("Three Wood", "https://shop.example/three-wood", null)))
            },
            fetch = { url -> calls += "fetch:$url"; WebPageResult.Found(page) },
            extract = { fetched, provider, requiredBarcode ->
                calls += "extract:${provider.name}"
                assertEquals("5011333150725", requiredBarcode)
                assertEquals(page, fetched)
                BottleDetailExtractionResult.InvalidResponse
            },
        )

        val result = coordinator.processScannedBarcode(
            "5011333150725",
            ScannedBottlePlan(true, true, AutomaticBottleExtractor.ON_DEVICE),
        )

        assertEquals(listOf(
            "off:5011333150725",
            "search:barcode UPC EAN product code 5011333150725",
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
            lookup = { calls += "off"; OpenFoodFactsLookupResult.NotFound },
            search = { calls += "search"; WebSearchResult.Found(listOf(WebSearchHit("Whisky", "https://shop.example/wrong", null))) },
            fetch = { calls += "fetch"; WebPageResult.Found(WebPageText("Different whisky", "https://shop.example/wrong", "Barcode 5011333150733", "Firecrawl")) },
            extract = { _, provider, _ -> calls += "extract:${provider.name}"; BottleDetailExtractionResult.InvalidResponse },
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
            lookup = { OpenFoodFactsLookupResult.NotFound },
            search = {
                WebSearchResult.Found(listOf(
                    WebSearchHit("Similar whisky", "https://shop.example/similar", "A related bottle"),
                    WebSearchHit("Product code 5011333150725", "https://shop.example/exact", "Three Wood"),
                ))
            },
            fetch = { url ->
                fetchedUrl = url
                WebPageResult.Found(WebPageText("Three Wood", url, "Barcode 5011333150725", "Firecrawl"))
            },
            extract = { _, _, _ -> BottleDetailExtractionResult.InvalidResponse },
        )

        val result = coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, null))

        assertEquals("https://shop.example/exact", fetchedUrl)
        assertEquals(fetchedUrl, result.selectedHit?.url)
        assertTrue(result.barcodeVerifiedInPage)
        assertNull(result.extraction)
    }

    @Test
    fun disabledOrUnconfiguredProvidersAreNotCalledAndManualFlowRemainsAvailable() = runBlocking {
        var calls = 0
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { calls++; OpenFoodFactsLookupResult.Unavailable },
            search = { calls++; WebSearchResult.SetupRequired },
            fetch = { calls++; WebPageResult.Unavailable },
            extract = { _, _, _ -> calls++; BottleDetailExtractionResult.Unavailable },
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
                lookup = { OpenFoodFactsLookupResult.NotFound },
                search = { searchResult },
                fetch = { fetches++; WebPageResult.Unavailable },
                extract = { _, _, _ -> extractions++; BottleDetailExtractionResult.Unavailable },
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
            lookup = { OpenFoodFactsLookupResult.NotFound },
            search = { throw CancellationException("user canceled") },
            fetch = { fetches++; WebPageResult.Unavailable },
            extract = { _, _, _ -> extractions++; BottleDetailExtractionResult.Unavailable },
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
            lookup = { OpenFoodFactsLookupResult.NotFound },
            search = { WebSearchResult.Found(listOf(WebSearchHit("Three Wood", page.sourceUrl, null))) },
            fetch = { WebPageResult.Found(page) },
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
            lookup = { OpenFoodFactsLookupResult.NotFound },
            search = { WebSearchResult.Found(listOf(WebSearchHit("Three Wood", page.sourceUrl, null))) },
            fetch = { WebPageResult.Found(page) },
            extract = { fetched, _, _ ->
                assertTrue(fetched.markdown.contains("700 mL and 750 mL"))
                BottleDetailExtractionResult.InvalidResponse
            },
        ).processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))

        assertTrue(result.barcodeVerifiedInPage)
        assertTrue(result.extraction is BottleDetailExtractionResult.InvalidResponse)
    }
}
