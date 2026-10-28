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
    fun correctingBarcodeKeepsEveryExplicitlyEditedManualFieldAndPhoto() {
        val photo = byteArrayOf(1, 2, 3)
        val draft = BottleIntakeDraft(
            id = "draft-manual-correction",
            sessionId = "session-manual-correction",
            position = 0,
            barcode = "5011733110725",
            productId = "old-product",
            ingredientName = "My hand-entered bottle",
            category = "Whisky",
            brand = "My hand-entered brand",
            productName = "My hand-entered product",
            photoData = photo,
            photoSha256 = "local-hash",
            quantity = "700 mL",
            abvPercent = "40%",
            quantityEdited = true,
            abvPercentEdited = true,
            locationId = "home-1",
            level = InventoryLevel.FULL,
            createdAt = 1,
        )

        val corrected = draft.correctedForBarcode(
            "5011333150725",
            setOf(
                BottleDraftField.INGREDIENT_NAME,
                BottleDraftField.BRAND,
                BottleDraftField.PRODUCT_NAME,
                BottleDraftField.QUANTITY,
                BottleDraftField.ABV_PERCENT,
            ),
        )

        assertEquals("5011333150725", corrected.barcode)
        assertNull(corrected.productId)
        assertEquals(draft.ingredientName, corrected.ingredientName)
        assertEquals(draft.brand, corrected.brand)
        assertEquals(draft.productName, corrected.productName)
        assertEquals(draft.quantity, corrected.quantity)
        assertEquals(draft.abvPercent, corrected.abvPercent)
        assertTrue(corrected.quantityEdited)
        assertTrue(corrected.abvPercentEdited)
        assertTrue(corrected.photoData === photo)
    }

    @Test
    fun manualBottleCorrectionInfersFieldsFromExistingDraftWhenOlderEditMarkersAreAbsent() {
        val draft = BottleIntakeDraft(
            id = "draft-older-manual",
            sessionId = "session-older-manual",
            position = 0,
            barcode = "5011733110725",
            productId = null,
            ingredientName = "Manual bottle name",
            category = "Whisky",
            brand = "Manual brand",
            productName = "Manual expression",
            photoData = null,
            photoSha256 = null,
            quantity = "700 mL",
            abvPercent = "40%",
            locationId = "home-1",
            level = InventoryLevel.FULL,
            createdAt = 1,
        )

        val edited = bottleFieldsToPreserveOnBarcodeCorrection(draft, emptySet(), isAutomatedScan = false)
        val corrected = draft.correctedForBarcode("5011333150725", edited)

        assertEquals(setOf(
            BottleDraftField.INGREDIENT_NAME,
            BottleDraftField.BRAND,
            BottleDraftField.PRODUCT_NAME,
            BottleDraftField.QUANTITY,
            BottleDraftField.ABV_PERCENT,
        ), edited)
        assertEquals("Manual bottle name", corrected.ingredientName)
        assertEquals("Manual brand", corrected.brand)
        assertEquals("Manual expression", corrected.productName)
        assertEquals("700 mL", corrected.quantity)
        assertEquals("40%", corrected.abvPercent)
    }

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
        assertEquals(listOf(
            "CANDIDATE_1_GENERIC_BARCODE_LOOKUP",
            "CANDIDATE_2_GENERIC_BARCODE_LOOKUP",
            "CANDIDATE_3_GENERIC_BARCODE_LOOKUP",
        ), selection.reasonCodes)
        assertEquals(3, events.last { it.stage == BottleLookupDebugStage.FIRECRAWL_SEARCH }.resultCount)
    }

    @Test
    fun productSpecificHitOnBarcodeLookupDomainIsNotBlacklisted() = runBlocking {
        var fetchedUrl: String? = null
        val exact = WebSearchHit(
            "Auchentoshan Three Wood Single Malt Scotch Whisky",
            "https://www.barcodelookup.com/5011333150725/auchentoshan-three-wood",
            "UPC 5011333150725",
        )
        val selection = selectScannedBottleSearchHit("5011333150725", listOf(exact))
        assertEquals(exact, selection.selected)
        assertEquals("SELECTED_EXACT_MATCH", selection.resultCode)

        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(listOf(exact))) },
            fetch = { url ->
                fetchedUrl = url
                BottleProviderCall(WebPageResult.Found(WebPageText(
                    "Auchentoshan Three Wood",
                    url,
                    "UPC 5011333150725 is Auchentoshan Three Wood.",
                    "Firecrawl",
                )))
            },
            extract = { _, _, _ -> BottleProviderCall(BottleDetailExtractionResult.Unavailable) },
        )
        val outcome = coordinator.processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, null))
        assertEquals(exact.url, fetchedUrl)
        assertTrue(outcome.barcodeVerifiedInPage)
    }

    @Test
    fun automaticScanContinuesAfterFirstPageMismatchAndExtractsOnlyVerifiedCandidate() = runBlocking {
        val hits = listOf(
            WebSearchHit("Product 5011333150725", "https://shop.example/first", "UPC 5011333150725"),
            WebSearchHit("Product 5011333150725", "https://shop.example/second", "UPC 5011333150725"),
            WebSearchHit("Product 5011333150725", "https://shop.example/third", "UPC 5011333150725"),
        )
        val fetched = mutableListOf<String>()
        val extracted = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val events = mutableListOf<BottleLookupDebugEvent>()
        val coordinator = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(hits)) },
            fetch = { url ->
                fetched += url
                val markdown = if (url == hits.first().url) {
                    "Auchentoshan Three Wood 750 mL and 43% ABV, but no code was rendered."
                } else {
                    "UPC 5011333150725 belongs to Auchentoshan Three Wood. 750 mL and 43% ABV."
                }
                BottleProviderCall(WebPageResult.Found(WebPageText("Auchentoshan Three Wood", url, markdown, "Firecrawl")), 200)
            },
            extract = { page, provider, requiredBarcode ->
                extracted += page.sourceUrl
                assertEquals("5011333150725", requiredBarcode)
                assertEquals(hits[1].url, page.sourceUrl)
                BottleProviderCall(BottleDetailExtractionResult.InvalidResponse)
            },
        )

        val result = coordinator.processScannedBarcode(
            "5011333150725",
            ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE),
            onStage = { stage, _, progress -> if (stage == ScannedBottleStage.SOURCE_FETCH) stages += progress.orEmpty() },
            onDebugEvent = events::add,
        )

        assertEquals(listOf(hits[0].url, hits[1].url), fetched)
        assertEquals(listOf(hits[1].url), extracted)
        assertEquals(2, stages.size)
        assertTrue(stages.first().contains("1 of 3"))
        assertTrue(stages.last().contains("2 of 3"))
        assertEquals(hits[1], result.selectedHit)
        assertTrue(result.barcodeVerifiedInPage)
        assertEquals("BARCODE_MISSING", events.first { it.stage == BottleLookupDebugStage.BARCODE_MATCH }.result)
        assertEquals("BARCODE_VERIFIED", events.last { it.stage == BottleLookupDebugStage.BARCODE_MATCH }.result)
    }

    @Test
    fun automaticScanStopsAfterThreePageFetchesAndStopsImmediatelyOnQuota() = runBlocking {
        val hits = (1..5).map { index ->
            WebSearchHit("Product 5011333150725 result $index", "https://shop.example/$index", "UPC 5011333150725")
        }
        var fetchCount = 0
        var extractCount = 0
        val allMismatches = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(hits)) },
            fetch = { url ->
                fetchCount++
                BottleProviderCall(WebPageResult.Found(WebPageText("Wrong product", url, "UPC 5011333150733", "Firecrawl")))
            },
            extract = { _, _, _ -> extractCount++; BottleProviderCall(BottleDetailExtractionResult.InvalidResponse) },
        ).processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))
        assertEquals(MAX_AUTOMATIC_SOURCE_FETCHES, fetchCount)
        assertEquals(0, extractCount)
        assertFalse(allMismatches.barcodeVerifiedInPage)

        fetchCount = 0
        val quota = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(hits)) },
            fetch = { fetchCount++; BottleProviderCall(WebPageResult.QuotaExceeded, 402) },
            extract = { _, _, _ -> extractCount++; BottleProviderCall(BottleDetailExtractionResult.InvalidResponse) },
        ).processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))
        assertEquals(1, fetchCount)
        assertEquals(WebPageResult.QuotaExceeded, quota.page)
        assertEquals(0, extractCount)
    }

    @Test
    fun exactStructuredVariantBarcodeIsAcceptedOnlyWhenUniqueAndTitleMatches() = runBlocking {
        val identity = StructuredBottleIdentity(
            "5011333150725", "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            "Auchentoshan Three Wood Single Malt Scotch Whisky | Canadian Liquor Store",
            "https://shop.example/auchentoshan-three-wood",
            "750 mL bottle. 43% ABV.",
            "Firecrawl",
            listOf(identity),
        )
        assertEquals(identity, structuredBottleIdentityForPage("5011333150725", page))
        assertEquals(StructuredIdentityStatus.VERIFIED, resolveStructuredBottleIdentityForPage("5011333150725", page).status)
        assertNull(structuredBottleIdentityForPage("5011333150725", page.copy(title = "Other Reserve")))
        assertEquals(
            StructuredIdentityStatus.TITLE_MISMATCH,
            resolveStructuredBottleIdentityForPage("5011333150725", page.copy(title = "Other Reserve")).status,
        )
        assertEquals(
            StructuredIdentityStatus.STRUCTURED_DATA_MISSING,
            resolveStructuredBottleIdentityForPage("5011333150725", page.copy(structuredBottleIdentities = emptyList())).status,
        )
        assertEquals(
            StructuredIdentityStatus.BARCODE_NOT_PRESENT,
            resolveStructuredBottleIdentityForPage("5011333150725", page.copy(structuredBottleIdentities = listOf(identity.copy(barcode = "5011333150733")))).status,
        )
        assertNull(
            structuredBottleIdentityForPage(
                "5011333150725",
                page.copy(structuredBottleIdentities = listOf(
                    identity,
                    identity.copy(productName = "Other Reserve", evidence = "Structured product fields: barcode 5011333150725; product Other Reserve"),
                )),
            ),
        )
        assertEquals(
            StructuredIdentityStatus.AMBIGUOUS_MATCH,
            resolveStructuredBottleIdentityForPage(
                "5011333150725",
                page.copy(structuredBottleIdentities = listOf(
                    identity,
                    identity.copy(productName = "Other Reserve", evidence = "Structured product fields: barcode 5011333150725; product Other Reserve"),
                )),
            ).status,
        )

        var extractorSawProof = false
        val result = ScannedBottleEnrichmentCoordinator(
            lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound) },
            search = { BottleProviderCall(WebSearchResult.Found(listOf(WebSearchHit("Auchentoshan Three Wood 5011333150725", page.sourceUrl, "UPC 5011333150725")))) },
            fetch = { BottleProviderCall(WebPageResult.Found(page)) },
            extract = { fetchedPage, _, requiredBarcode ->
                extractorSawProof = requiredBarcode == "5011333150725" && structuredBottleIdentityForPage(requiredBarcode, fetchedPage) == identity
                BottleProviderCall(BottleDetailExtractionResult.InvalidResponse)
            },
        ).processScannedBarcode("5011333150725", ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE))

        assertTrue(result.barcodeVerifiedInPage)
        assertTrue(result.barcodeVerifiedByStructuredProduct)
        assertTrue(extractorSawProof)
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
        assertTrue((events.last { it.stage == BottleLookupDebugStage.FIRECRAWL_SEARCH }.durationMillis ?: -1L) >= 0)
        assertEquals("BARCODE_MISSING", events.last { it.stage == BottleLookupDebugStage.BARCODE_MATCH }.result)
        assertEquals(listOf("NO_EXACT_CODE_IN_PAGE", "CANDIDATE_1_REJECTED"), events.last { it.stage == BottleLookupDebugStage.BARCODE_MATCH }.reasonCodes)
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
        assertTrue(json.contains("https://shop.example/"))
        assertFalse(json.contains("/item"))
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
