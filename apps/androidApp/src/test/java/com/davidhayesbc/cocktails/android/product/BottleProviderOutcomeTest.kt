package com.davidhayesbc.cocktails.android.product

import org.junit.Assert.assertEquals
import org.junit.Test

class BottleProviderOutcomeTest {
    @Test
    fun deterministicPageFactsAreNotMisreportedAsOnDeviceAiCompletion() {
        val extraction = BottleDetailExtractionResult.Found(
            BottleDetailSuggestions(
                provider = "Verified source table",
                model = "Deterministic extraction",
                sourceUrl = "https://example.com/bottle",
                facts = listOf(BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Three Wood", "### Three Wood")),
                targetBarcode = "5011333150725",
                identityEvidence = "UPC 5011333150725 · Three Wood",
                extractionMethod = BottleExtractionMethod.DETERMINISTIC_SOURCE,
            ),
        )
        val statuses = bottleProviderOutcomes(
            plan = ScannedBottlePlan(openFoodFactsEnabled = false, firecrawlEnabled = true, extractor = AutomaticBottleExtractor.ON_DEVICE),
            outcome = ScannedBottleOutcome(barcode = "5011333150725", search = WebSearchResult.Found(emptyList()), extraction = extraction),
        )

        assertEquals(BottleProviderOutcome.SKIPPED_DETERMINISTIC, statuses.ai)
    }

    @Test
    fun noSearchResultsAndSkippedModelRemainSeparateOutcomes() {
        val outcome = ScannedBottleOutcome(
            barcode = "5011733110725",
            search = WebSearchResult.Found(emptyList()),
        )

        val statuses = bottleProviderOutcomes(
            plan = ScannedBottlePlan(
                openFoodFactsEnabled = true,
                firecrawlEnabled = true,
                extractor = AutomaticBottleExtractor.ON_DEVICE,
            ),
            outcome = outcome,
        )

        assertEquals(BottleProviderOutcome.NO_RESULTS, statuses.web)
        assertEquals(BottleProviderOutcome.SKIPPED_NO_SOURCE, statuses.ai)
    }

    @Test
    fun configuredSourceWithDisabledExtractorIsNotMisreportedAsAiFailure() {
        val statuses = bottleProviderOutcomes(
            plan = ScannedBottlePlan(openFoodFactsEnabled = false, firecrawlEnabled = true, extractor = null, aiRequested = true),
            outcome = ScannedBottleOutcome(
                barcode = "5011333150725",
                search = WebSearchResult.Found(listOf(WebSearchHit("Bottle", "https://example.com/bottle", null))),
                selectedHit = WebSearchHit("Bottle", "https://example.com/bottle", null),
                page = WebPageResult.Found(WebPageText("Bottle", "https://example.com/bottle", "UPC 5011333150725", "Firecrawl")),
                barcodeVerifiedInPage = true,
            ),
        )

        assertEquals(BottleProviderOutcome.COMPLETE, statuses.web)
        assertEquals(BottleProviderOutcome.NOT_CONFIGURED, statuses.ai)
    }

    @Test
    fun explicitlyDisabledProvidersAreShownAsOffRatherThanFailedOrMissing() {
        val statuses = bottleProviderOutcomes(
            plan = ScannedBottlePlan(
                openFoodFactsEnabled = false,
                firecrawlEnabled = false,
                extractor = null,
                firecrawlRequested = false,
                aiRequested = false,
            ),
            outcome = ScannedBottleOutcome(barcode = "5011333150725"),
        )

        assertEquals(BottleProviderOutcome.DISABLED, statuses.web)
        assertEquals(BottleProviderOutcome.DISABLED, statuses.ai)
    }

    @Test
    fun enabledButUnconfiguredProvidersAreNotMisreportedAsDisabled() {
        val statuses = bottleProviderOutcomes(
            plan = ScannedBottlePlan(
                openFoodFactsEnabled = false,
                firecrawlEnabled = false,
                extractor = null,
                firecrawlRequested = true,
                aiRequested = true,
            ),
            outcome = ScannedBottleOutcome(barcode = "5011333150725"),
        )

        assertEquals(BottleProviderOutcome.NOT_CONFIGURED, statuses.web)
        assertEquals(BottleProviderOutcome.SKIPPED_NO_SOURCE, statuses.ai)
    }
}
