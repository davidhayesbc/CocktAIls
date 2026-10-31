package com.davidhayesbc.cocktails.android.product

import com.arm.aichat.InferenceEngine
import com.arm.aichat.ModelLoadException
import com.arm.aichat.NativeCpuBackendUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.nio.file.Files

class LocalBottleDetailExtractorTest {
    @Test
    fun labeledFallbackNeverAttachesAnotherVariantScalarsToStructuredIdentity() = runBlocking {
        for (matched in listOf(false, true)) for (combined in listOf(false, true)) {
            val name = "Polar River London Dry Gin"
            val title = if (matched) name else "Polar River London Sweet Gin"
            val barcode = "080686032069"
            val record = if (combined) "UPC $barcode | 750 mL" else "UPC: $barcode\nSize: 750 mL"
            val page = WebPageText(title, "https://example.org/product",
                "$title\n$record\nABV: 40%", "Fixture",
                structuredBottleIdentities = listOf(StructuredBottleIdentity(barcode, name, "UPC $barcode; $name")))
            assertTrue(resolveStructuredBottleIdentityForPage(barcode, page).identity != null)
            assertEquals(matched, structuredProductNamesAgree(title, name))
            for (mode in listOf("missing", "invalid", "empty", "timeout", "truncated")) {
                val runner = LocalInferenceRunner { _, _, _, _ ->
                    when (mode) {
                        "timeout" -> throw LocalInferenceFailureException(LocalInferenceFailureCode.GENERATION_TIMEOUT)
                        "truncated" -> throw LocalInferenceFailureException(LocalInferenceFailureCode.MODEL_OUTPUT_TRUNCATED)
                        else -> """{"facts":[]}"""
                    }
                }
                val result = LocalBottleDetailExtractor({ if (mode == "missing") null else "/fixture.gguf" }, runner)
                    .extract(page, if (mode == "invalid") "invalid" else LOCAL_MODEL_LABEL, barcode)
                    as BottleDetailExtractionResult.Found
                val fields = result.suggestions.facts.map { it.field }.toSet()
                assertEquals("$matched / $mode", matched, BottleFactField.QUANTITY in fields)
                assertEquals("$matched / $mode", matched, BottleFactField.ABV_PERCENT in fields)
                assertEquals(name, result.suggestions.facts.single { it.field == BottleFactField.PRODUCT_NAME }.value)
                if (!matched) assertTrue(result.suggestions.unresolvedFields.containsAll(
                    setOf(BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT)))
            }
        }
    }

    @Test
    fun barcodeRedactionKeepsFollowingSourceLineBreak() {
        assertEquals(
            "Product Code: [barcode omitted]\nAlcohol content: 40.0%",
            redactBarcodeIdentifiers("Product Code: 080686032069\nAlcohol content: 40.0%"),
        )
        assertEquals(
            "UPC [barcode omitted] | 750 ml",
            redactBarcodeIdentifiers("UPC 3163933575001 | 750 ml"),
        )
    }

    private companion object {
        val COOK_STREET_AUCHENTOSHAN = """
            Free in-store pickup

            Free local delivery on orders over 80

            Free in-store pickup

            Free local delivery on orders over 80

            Free in-store pickup

            Free local delivery on orders over 80

            Free in-store pickup

            Free local delivery on orders over 80

            Free in-store pickup

            ### AUCHENTOSHAN - THREE WOOD

            | Price: | 89.99 ~~89.99~~ |
            | Deposit: | 0.10 |
            | Category: | Single Malt |
            | Country: | |
            | Color: | |
            | Varietal: | |
            | Region: | |
            | Size / Unit: | 750ML |
            | SKU: | 138495 |
            | UPC: | 5011333150725 |
            | Alcohol: | 43% |
            | Availability: | 2 |

            Add to Cart
            Continue Shopping
            Product tasting notes and store hours.
        """.trimIndent()
    }

    private fun cookStreetAuchentoshanPage(markdown: String = COOK_STREET_AUCHENTOSHAN): WebPageText = WebPageText(
        title = "Cook St. Liquor",
        sourceUrl = "https://shop.cookstliquor.com/products/auchentoshan-three-wood",
        markdown = markdown,
        provider = "Firecrawl",
    )

    @Test
    fun exactCookStreetProductTableProducesEvidenceBackedFactsWithoutWastingModelTokens() = runBlocking {
        var modelCalled = false
        val events = mutableListOf<BottleLookupDebugEvent>()
        val result = LocalBottleDetailExtractor({ null }, LocalInferenceRunner { _, _, _, _ ->
            modelCalled = true
            error("The explicit UPC-bound product table already contains all supported core facts")
        }).extractWithDiagnostics(
            cookStreetAuchentoshanPage(),
            LOCAL_MODEL_LABEL,
            "5011333150725",
            events::add,
        )

        assertFalse("Deterministic facts should avoid an unnecessary slow or truncated generation", modelCalled)
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals(BottleExtractionMethod.DETERMINISTIC_SOURCE, suggestions.extractionMethod)
        assertEquals("Verified source table", suggestions.provider)
        assertEquals("Deterministic extraction", suggestions.model)
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertEquals("### AUCHENTOSHAN - THREE WOOD", suggestions.identityEvidence?.lineSequence()?.first())
        assertEquals(
            mapOf(
                BottleFactField.PRODUCT_NAME to "AUCHENTOSHAN - THREE WOOD",
                BottleFactField.QUANTITY to "750 mL",
                BottleFactField.ABV_PERCENT to "43%",
            ),
            suggestions.facts.associate { it.field to it.value },
        )
        assertEquals("No unlabeled brand split or stock-count inference is allowed", setOf(BottleFactField.BRAND), suggestions.unresolvedFields)
        assertFalse(suggestions.facts.any { it.field == BottleFactField.QUANTITY && it.value == "1" })
        assertTrue(suggestions.facts.all { fact -> fact.quote in COOK_STREET_AUCHENTOSHAN })
        assertTrue(events.any {
            it.stage == BottleLookupDebugStage.LOCAL_GENERATION && it.result == "SKIPPED" &&
                "DETERMINISTIC_CORE_FACTS_COMPLETE" in it.reasonCodes
        })
        assertTrue(events.any {
            it.stage == BottleLookupDebugStage.LOCAL_DETERMINISTIC_FACTS && it.result == "FOUND" &&
                "BARCODE_BOUND_MARKDOWN_TABLE" in it.reasonCodes
        })
    }

    @Test
    fun incompleteBarcodeTableScopesPromptAndPreservesRulesWhenModelRepeatsBoilerplateClaims() = runBlocking {
        val page = cookStreetAuchentoshanPage(COOK_STREET_AUCHENTOSHAN.replace("| Alcohol: | 43% |", ""))
        var prompt = ""
        var outputLimit = 0
        val modelReply = """{"facts":[{"field":"product_name","value":"AUCHENTOSHAN - THREE WOOD","evidence":"Free in-store pickup"},{"field":"quantity","value":"1","evidence":"Free local delivery on orders over 80"},{"field":"abv_percent","value":"43%","evidence":"Alcohol: 43%"},{"field":"product_name","value":"AUCHENTOSHAN - THREE WOOD","evidence":"Free in-store pickup"}]}"""
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, maxTokens ->
            prompt = userPrompt
            outputLimit = maxTokens
            modelReply
        }).extract(page, LOCAL_MODEL_LABEL, "5011333150725") as BottleDetailExtractionResult.Found

        assertEquals(160, outputLimit)
        assertFalse("Repeated delivery and pickup boilerplate stays outside the model input", prompt.contains("Free in-store pickup"))
        assertFalse(prompt.contains("Free local delivery"))
        assertTrue(prompt.contains("| Size / Unit: | 750ML |"))
        assertTrue(prompt.contains("| UPC: |") && prompt.contains("[barcode omitted]"))
        assertTrue(prompt.contains("Requested fields: brand, abv_percent"))
        assertEquals("AUCHENTOSHAN - THREE WOOD", result.suggestions.facts.single { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertEquals("750 mL", result.suggestions.facts.single { it.field == BottleFactField.QUANTITY }.value)
        assertTrue(result.suggestions.facts.none { it.value == "1" || it.field == BottleFactField.ABV_PERCENT })
        assertFalse("A rejected AI quantity must not mark the labeled size unresolved", BottleFactField.QUANTITY in result.suggestions.unresolvedFields)
        assertTrue(BottleFactField.ABV_PERCENT in result.suggestions.unresolvedFields)
        assertEquals(BottleExtractionMethod.DETERMINISTIC_FALLBACK_AFTER_MODEL, result.suggestions.extractionMethod)
        assertTrue(result.suggestions.fieldRejections.any {
            it.field == BottleFactField.QUANTITY && it.reason == BottleExtractionRejectionReason.EVIDENCE_NOT_IN_SOURCE
        })
    }

    @Test
    fun validModelCompletionMergesOnlyMissingFieldsWithBarcodeBoundNameAndSize() = runBlocking {
        val partialMarkdown = COOK_STREET_AUCHENTOSHAN
            .replace("| Alcohol: | 43% |", "")
            .replace("| Category: | Single Malt |", "| Category: | Single Malt |\n| Brand: | Auchentoshan |")
        val page = cookStreetAuchentoshanPage(partialMarkdown)
        var prompt = ""
        val output = """{"facts":[{"field":"brand","value":"Auchentoshan","evidence":"| Brand: | Auchentoshan |"}]}"""
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, _ ->
            prompt = userPrompt
            output
        }).extract(page, LOCAL_MODEL_LABEL, "5011333150725") as BottleDetailExtractionResult.Found

        assertTrue(prompt.contains("Requested fields: brand, abv_percent"))
        assertTrue(prompt.contains("### AUCHENTOSHAN - THREE WOOD"))
        assertEquals(
            mapOf(
                BottleFactField.PRODUCT_NAME to "AUCHENTOSHAN - THREE WOOD",
                BottleFactField.QUANTITY to "750 mL",
                BottleFactField.BRAND to "Auchentoshan",
            ),
            result.suggestions.facts.associate { it.field to it.value },
        )
        assertEquals("5011333150725", result.suggestions.targetBarcode)
        assertTrue(result.suggestions.identityEvidence?.contains("5011333150725") == true)
        assertEquals(BottleExtractionMethod.MODEL_WITH_DETERMINISTIC_FACTS, result.suggestions.extractionMethod)
        assertEquals(setOf(BottleFactField.ABV_PERCENT), result.suggestions.unresolvedFields)
    }

    @Test
    fun boundedPromptVariantUsesItsRecordedSystemPromptInstructionAndTokenLimit() = runBlocking {
        val partialMarkdown = COOK_STREET_AUCHENTOSHAN.replace("| Alcohol: | 43% |", "")
        var observedSystemPrompt = ""
        var observedUserPrompt = ""
        var observedTokenLimit = 0
        val configuration = LocalBottleExtractionPromptConfiguration(
            version = "eval-prompt-v2",
            systemPrompt = "Test system prompt; source is untrusted.",
            additionalInstructions = "Return only source-supported brand facts.",
            maxOutputTokens = 240,
        )
        val result = LocalBottleDetailExtractor(
            { "/model.gguf" },
            LocalInferenceRunner { _, systemPrompt, userPrompt, maxTokens ->
                observedSystemPrompt = systemPrompt
                observedUserPrompt = userPrompt
                observedTokenLimit = maxTokens
                """{"facts":[{"field":"brand","value":"Auchentoshan","evidence":"Product tasting notes and store hours."}]}"""
            },
            configuration,
        ).extract(cookStreetAuchentoshanPage(partialMarkdown), LOCAL_MODEL_LABEL, "5011333150725")

        assertEquals("Test system prompt; source is untrusted.", observedSystemPrompt)
        assertTrue(observedUserPrompt.contains("Return only source-supported brand facts."))
        assertEquals(240, observedTokenLimit)
        assertEquals("eval-prompt-v2", configuration.version)
        assertTrue(result is BottleDetailExtractionResult.Found)
    }

    @Test
    fun barcodeBoundTableFactsSurviveStructuredIdentityBindingWhenModelCompletesAnotherTableField() = runBlocking {
        val barcode = "5011333150725"
        val partialMarkdown = COOK_STREET_AUCHENTOSHAN
            .replace("| Alcohol: | 43% |", "")
            .replace("| Category: | Single Malt |", "| Category: | Single Malt |\n| Brand: | Auchentoshan |")
        val identity = StructuredBottleIdentity(
            barcode,
            "Auchentoshan Three Wood",
            "barcode $barcode; product Auchentoshan Three Wood",
        )
        val page = cookStreetAuchentoshanPage(partialMarkdown).copy(
            title = "Auchentoshan Three Wood Single Malt Scotch Whisky",
            structuredBottleIdentities = listOf(identity),
        )
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"brand","value":"Auchentoshan","evidence":"| Brand: | Auchentoshan |"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL, barcode) as BottleDetailExtractionResult.Found

        assertEquals(
            mapOf(
                BottleFactField.PRODUCT_NAME to identity.productName,
                BottleFactField.QUANTITY to "750 mL",
                BottleFactField.BRAND to "Auchentoshan",
            ),
            result.suggestions.facts.associate { it.field to it.value },
        )
        assertEquals(barcode, result.suggestions.targetBarcode)
        assertEquals(identity.evidence, result.suggestions.identityEvidence)
        assertFalse(BottleFactField.PRODUCT_NAME in result.suggestions.unresolvedFields)
        assertFalse(BottleFactField.QUANTITY in result.suggestions.unresolvedFields)
        assertFalse(BottleFactField.BRAND in result.suggestions.unresolvedFields)
        assertTrue(BottleFactField.ABV_PERCENT in result.suggestions.unresolvedFields)
        assertEquals(BottleExtractionMethod.MODEL_WITH_DETERMINISTIC_FACTS, result.suggestions.extractionMethod)
    }

    @Test
    fun markdownFactsAreBoundOnlyToOneUniqueTargetBarcodeProductTable() {
        assertEquals(null, barcodeBoundMarkdownProduct("5011333150725", COOK_STREET_AUCHENTOSHAN.replace("5011333150725", "5011333150733")))

        val repeatedTarget = COOK_STREET_AUCHENTOSHAN + "\n\n### Another listing\n\n| UPC: | 5011333150725 |\n|---|---|\n| Size / Unit: | 700 mL |\n| Alcohol: | 40% |"
        assertTrue("Two records with one code are ambiguous", barcodeBoundMarkdownProduct("5011333150725", repeatedTarget) == null)

        val secondProductBarcode = "\n\n### Related Reserve\n\n| UPC: | 5011333150732 |\n|---|---|\n| Size / Unit: | 700 mL |\n| Alcohol: | 40% |"
        assertTrue("A mixed-code page cannot lend facts across products", barcodeBoundMarkdownProduct("5011333150725", COOK_STREET_AUCHENTOSHAN + secondProductBarcode) == null)
    }

    @Test
    fun markdownProductHeadingCanBindItsOwnTableDespiteGenericRetailerPageTitle() {
        val barcode = "5011333150725"
        val parsed = parseBottleFactCandidate(
            """{"facts":[{"field":"product_name","value":"AUCHENTOSHAN - THREE WOOD","evidence":"### AUCHENTOSHAN - THREE WOOD"},{"field":"abv_percent","value":"43%","evidence":"| Alcohol: | 43% |"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://shop.cookstliquor.com/products/auchentoshan-three-wood",
            COOK_STREET_AUCHENTOSHAN, requiredBarcode = null,
        )

        val bound = bindPageBarcodeIdentity(parsed, barcode, cookStreetAuchentoshanPage()) as BottleDetailExtractionResult.Found
        assertEquals(barcode, bound.suggestions.targetBarcode)
        assertEquals("AUCHENTOSHAN - THREE WOOD", bound.suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertTrue(bound.suggestions.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(bound.suggestions.identityEvidence?.contains(barcode) == true)

        val unrelated = parseBottleFactCandidate(
            """{"facts":[{"field":"product_name","value":"AUCHENTOSHAN - THREE WOOD","evidence":"### AUCHENTOSHAN - THREE WOOD"},{"field":"abv_percent","value":"40%","evidence":"Other Gin 40% ABV"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://shop.cookstliquor.com/products/auchentoshan-three-wood",
            COOK_STREET_AUCHENTOSHAN + "\nOther Gin 40% ABV",
        ) as BottleDetailExtractionResult.Found
        val unrelatedBound = bindPageBarcodeIdentity(unrelated, barcode, cookStreetAuchentoshanPage()) as BottleDetailExtractionResult.Found
        assertFalse(unrelatedBound.suggestions.facts.any { it.field == BottleFactField.ABV_PERCENT })
        assertTrue(BottleFactField.ABV_PERCENT in unrelatedBound.suggestions.unresolvedFields)
    }

    @Test
    fun conflictingLabeledRowsStayUnresolvedAndAvailabilityNeverBecomesBottleQuantity() {
        val conflict = COOK_STREET_AUCHENTOSHAN
            .replace("| Size / Unit: | 750ML |", "| Size / Unit: | 750ML |\n| Size / Unit: | 700ML |")
            .replace("| Alcohol: | 43% |", "| Alcohol: | 43% |\n| Alcohol: | 40% |")
        val parsed = barcodeBoundMarkdownProduct("5011333150725", conflict)!!
        assertFalse(parsed.hasCoreFacts)
        assertEquals(listOf("AUCHENTOSHAN - THREE WOOD"), parsed.facts.filter { it.field == BottleFactField.PRODUCT_NAME }.map { it.value })
        assertTrue(BottleFactField.QUANTITY in parsed.unresolvedFields)
        assertTrue(BottleFactField.ABV_PERCENT in parsed.unresolvedFields)
        assertTrue(parsed.fieldRejections.all { it.reason == BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES })
        assertFalse(parsed.facts.any { it.value == "2" || it.value == "1" })
    }

    @Test
    fun repeatedModelFieldsAreNotSilentlyDeduplicated() {
        val source = "North Star Gin 40% ABV."
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin"},{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin"},{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin", source,
        ) as BottleDetailExtractionResult.Found

        assertTrue(result.suggestions.facts.none { it.field == BottleFactField.PRODUCT_NAME })
        assertTrue(BottleFactField.PRODUCT_NAME in result.suggestions.unresolvedFields)
        assertTrue(BottleFactRejection(BottleFactField.PRODUCT_NAME, BottleExtractionRejectionReason.INVALID_FACT_SHAPE) in result.suggestions.fieldRejections)
        assertEquals(listOf(BottleFactField.ABV_PERCENT), result.suggestions.facts.map(BottleFactEvidence::field))
    }

    @Test
    fun optedInCaptureReceivesExactValidatorInputAndModelReplyAtTheirPipelineStages() = runBlocking {
        val root = Files.createTempDirectory("extractor-capture").toFile()
        val store = DetailedBottleLookupCaptureStore(File(root, "capture.json"), { 100L }, processSessionId = "test")
        val token = "00000000-0000-0000-0000-000000000099"
        assertEquals(DetailedCaptureArmResult.ARMED, store.arm("draft-a", token, "preview", 100L))
        val capture = store.claim("draft-a", token, "5011333150725", "preview.20", 20, "Qwen", 100L)!!
        val rawModelReply = """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin"}]}"""
        val page = WebPageText(
            "North Star Gin",
            "https://shop.example/north-star?session=private",
            "UPC 5011333150725 · North Star Gin 40% ABV.",
            "Firecrawl",
        )
        assertTrue(scannedProductIdentityEvidence("5011333150725", "North Star Gin", page.markdown) != null)
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ -> rawModelReply })
            .extractWithDetailedCapture(page, LOCAL_MODEL_LABEL, "5011333150725", capture)
        capture.finish("COMPLETE")

        assertTrue("Capture fixture must produce a valid identity-bound result: $result", result is BottleDetailExtractionResult.Found)
        val preview = store.previewForDraft("draft-a", 100L)!!
        assertTrue(preview.contains("UPC [barcode omitted]"))
        assertTrue(preview.contains("North Star Gin 40% ABV."))
        assertTrue(preview.contains(rawModelReply.replace("\"", "\\\"")))
        assertTrue(preview.contains("STRUCTURED_IDENTITY_DATA_MISSING"))
        assertFalse(preview.contains("session=private"))
        root.deleteRecursively()
        Unit
    }

    @Test
    fun usesLocalRunnerAndAcceptsOnlyQuotedFieldsFromSelectedPage() = runBlocking {
        val page = WebPageText("Example Gin", "https://example.org/gin", "<p>North Star Gin 40% ABV</p><a href=\"https://tracker.example/?user=secret\">Buy</a>", "Firecrawl")
        var receivedPrompt = ""
        val extractor = LocalBottleDetailExtractor({ "/private/model.gguf" }, LocalInferenceRunner { _, system, prompt, tokens ->
            assertTrue(system.contains("untrusted data"))
            assertEquals(160, tokens)
            receivedPrompt = prompt
            """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin 40% ABV"},{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV"}]}"""
        })

        val result = extractor.extract(page, LOCAL_MODEL_LABEL)

        assertTrue(result is BottleDetailExtractionResult.Found)
        result as BottleDetailExtractionResult.Found
        assertEquals("On-device Qwen3", result.suggestions.provider)
        assertEquals(2, result.suggestions.facts.size)
        assertFalse(receivedPrompt.contains("tracker.example"))
        assertFalse(receivedPrompt.contains("user=secret"))
    }

    @Test
    fun localInferencePromptAndOutputBudgetStayBoundedForLongPages() = runBlocking {
        val page = WebPageText(
            "North Star Gin",
            "https://example.org/gin",
            "North Star Gin 40% ABV. " + "Unrelated page text. ".repeat(1_000),
            "Firecrawl",
        )
        var promptLength = 0
        var outputBudget = 0
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, prompt, maxTokens ->
            promptLength = prompt.length
            outputBudget = maxTokens
            """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin 40% ABV"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL)

        assertTrue(result is BottleDetailExtractionResult.Found)
        assertTrue("Prompt should not forward an entire long page", promptLength <= 3_200)
        assertEquals(160, outputBudget)
    }

    @Test
    fun fullBoundedSourceStillDetectsConflictsBeyondShortInferencePrompt() = runBlocking {
        val lateConflict = "North Star Gin is 43% ABV."
        val source = "North Star Gin is 40% ABV. " + "Ordinary retailer text. ".repeat(150) + lateConflict
        var prompt = ""
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, _ ->
            prompt = userPrompt
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"North Star Gin is 40% ABV"}]}"""
        }).extract(
            WebPageText("North Star Gin", "https://example.org/gin", source, "Firecrawl"),
            LOCAL_MODEL_LABEL,
        )

        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertTrue(BottleFactField.ABV_PERCENT in suggestions.unresolvedFields)
        assertTrue(suggestions.facts.none { it.field == BottleFactField.ABV_PERCENT })
        assertTrue("Prompt remains short enough for the local model", prompt.length <= 3_200)
        assertFalse("The late conflict is outside the model prompt", prompt.contains(lateConflict))
    }

    @Test
    fun doesNotRunWhenModelMissingAndRejectsUnsupportedModel() = runBlocking {
        var invoked = false
        val runner = LocalInferenceRunner { _, _, _, _ -> invoked = true; "{}" }
        val page = WebPageText("Gin", "https://example.org/gin", "Gin 40% ABV", "Firecrawl")

        assertEquals(BottleDetailExtractionResult.SetupRequired, LocalBottleDetailExtractor({ null }, runner).extract(page, LOCAL_MODEL_LABEL))
        assertEquals(BottleDetailExtractionResult.InvalidModel, LocalBottleDetailExtractor({ "/model" }, runner).extract(page, "other-model"))
        assertFalse(invoked)
    }

    @Test
    fun rejectsLocalCandidateWithoutExactSourceEvidence() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"brand","value":"Invented Brand","evidence":"Invented Brand"}]}""",
            provider = "On-device Qwen3",
            model = LOCAL_MODEL_LABEL,
            sourceUrl = "https://example.org/gin",
            sourceText = "North Star Gin 40% ABV",
        )

        assertTrue(result is BottleDetailExtractionResult.Rejected)
    }

    @Test
    fun doesNotTreatTitleOnlyBrandAsIndependentEvidence() {
        val fixtures = listOf(
            Triple("Banff Ice", "Banff Ice", "Banff Ice 750ml"),
            Triple("DUBONNET ROUGE", "DUBONNET ROUGE", "DUBONNET ROUGE"),
            Triple("Wild Turkey 101", "Wild Turkey", "Wild Turkey 101 750ml"),
        )
        fixtures.forEach { (productName, brand, titleEvidence) ->
            val result = parseBottleFactCandidate(
                """{"facts":[{"field":"product_name","value":"$productName","evidence":"$titleEvidence"},{"field":"brand","value":"$brand","evidence":"$titleEvidence"}]}""",
                provider = "On-device Qwen3",
                model = LOCAL_MODEL_LABEL,
                sourceUrl = "https://example.org/bottle",
                sourceText = titleEvidence,
            ) as BottleDetailExtractionResult.Found

            assertEquals(setOf(BottleFactField.PRODUCT_NAME), result.suggestions.facts.map { it.field }.toSet())
            assertTrue(BottleFactField.BRAND in result.suggestions.unresolvedFields)
            assertTrue(result.suggestions.fieldRejections.any {
                it.field == BottleFactField.BRAND &&
                    it.reason == BottleExtractionRejectionReason.BRAND_NOT_DISTINCT_FROM_PRODUCT_NAME
            })
        }
    }

    @Test
    fun defaultPromptRequiresUnitsAnchoredAbvEvidenceAndBrandDistinction() = runBlocking {
        var observedSystemPrompt = ""
        val extractor = LocalBottleDetailExtractor(
            { "/model.gguf" },
            LocalInferenceRunner { _, systemPrompt, _, _ ->
                observedSystemPrompt = systemPrompt
                """{"facts":[]}"""
            },
        )
        extractor.extract(
            WebPageText(
                title = "Sample Gin",
                sourceUrl = "https://example.org/gin",
                markdown = "Sample Gin\nContainer Size: 750 mL\nAlcohol content: 40%",
                provider = "fixture",
            ),
            LOCAL_MODEL_LABEL,
        )

        assertEquals("bottle-facts-v2-dev-candidate", DEFAULT_LOCAL_PROMPT_VERSION)
        assertTrue(observedSystemPrompt.contains("include the explicit unit exactly as shown"))
        assertTrue(observedSystemPrompt.contains("Teneur en alcool : 40%"))
        assertTrue(observedSystemPrompt.contains("Do not copy a full product name into brand"))
        assertTrue(observedSystemPrompt.contains("otherwise omit brand"))
    }

    @Test
    fun acceptsOnlyEquivalentAbvNotationAndWhitespaceWithoutConvertingFacts() {
        val source = "Auchentoshan Three Wood: Alcohol by volume 43.0%. Bottle size 750 mL."
        val formattedAbv = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"43 % ABV","evidence":"Alcohol by volume 43.0%"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/whisky", source,
        ) as BottleDetailExtractionResult.Found
        assertEquals("43%", formattedAbv.suggestions.facts.single().value)

        val englishLabeledAbv = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"40.0%","evidence":"Alcohol content: 40.0%"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/vodka", "Banff Ice\nAlcohol content: 40.0%",
        ) as BottleDetailExtractionResult.Found
        assertEquals("40%", englishLabeledAbv.suggestions.facts.single().value)

        val frenchLabeledAbv = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"50.5%","evidence":"Teneur en alcool : 50.5%"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/bourbon", "Wild Turkey 101\nTeneur en alcool : 50.5%",
        ) as BottleDetailExtractionResult.Found
        assertEquals("50.5%", frenchLabeledAbv.suggestions.facts.single().value)

        val unrelatedPercentage = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"Sale 40%; the alcohol content is not stated"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin", "Sale 40%; the alcohol content is not stated",
        ) as BottleDetailExtractionResult.Rejected
        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, unrelatedPercentage.reason)

        val unrelatedAvailabilityNumber = parseBottleFactCandidate(
            """{"facts":[{"field":"quantity","value":"2","evidence":"Availability: 2"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin", "Availability: 2",
        ) as BottleDetailExtractionResult.Rejected
        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, unrelatedAvailabilityNumber.reason)

        val quantityWithoutUnit = parseBottleFactCandidate(
            """{"facts":[{"field":"quantity","value":"750","evidence":"Container Size: 750 mL"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin", "Container Size: 750 mL",
        ) as BottleDetailExtractionResult.Rejected
        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, quantityWithoutUnit.reason)

        val formattedQuantity = parseBottleFactCandidate(
            """{"facts":[{"field":"quantity","value":"750ml","evidence":"Bottle size 750 mL"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/whisky", source,
        ) as BottleDetailExtractionResult.Found
        assertEquals("750 mL", formattedQuantity.suggestions.facts.single().value)

        val partialQuantity = parseBottleFactCandidate(
            """{"facts":[{"field":"quantity","value":"75 mL","evidence":"Bottle size 750 mL"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/whisky", source,
        )
        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, (partialQuantity as BottleDetailExtractionResult.Rejected).reason)

        val convertedQuantity = parseBottleFactCandidate(
            """{"facts":[{"field":"quantity","value":"0.75 L","evidence":"Bottle size 750 mL"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/whisky", source,
        )
        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, (convertedQuantity as BottleDetailExtractionResult.Rejected).reason)

        val wrongAbv = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"46%","evidence":"Alcohol by volume 43.0%"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/whisky", source,
        )
        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, (wrongAbv as BottleDetailExtractionResult.Rejected).reason)

        val outOfRangeConflict = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV and 140% ABV"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin",
            "North Star Gin 40% ABV and 140% ABV",
        )
        assertTrue(outOfRangeConflict is BottleDetailExtractionResult.Rejected)
        assertEquals(
            BottleExtractionRejectionReason.INVALID_ABV_EVIDENCE,
            (outOfRangeConflict as BottleDetailExtractionResult.Rejected).reason,
        )
        assertTrue(BottleFactField.ABV_PERCENT in unresolvedBottleSourceFields("North Star Gin 40% ABV and 140% ABV"))
    }

    @Test
    fun unsupportedOptionalFactDoesNotDiscardAnIndependentlySupportedFact() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"quantity","value":"750 mL","evidence":"Bottle size 750 mL"},{"field":"abv_percent","value":"46%","evidence":"North Star Gin 40% ABV"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin", "North Star Gin 40% ABV. Bottle size 750 mL.",
        ) as BottleDetailExtractionResult.Found

        assertEquals(listOf(BottleFactField.QUANTITY), result.suggestions.facts.map(BottleFactEvidence::field))
        assertTrue(BottleFactField.ABV_PERCENT in result.suggestions.unresolvedFields)
        assertTrue(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE in result.rejectedCandidates)
    }

    @Test
    fun conflictingModelCandidatesIdentifyOnlyTheRejectedField() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV"},{"field":"abv_percent","value":"43%","evidence":"North Star Gin 43% ABV"},{"field":"quantity","value":"750 mL","evidence":"Bottle size 750 mL"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin",
            "North Star Gin 40% ABV. North Star Gin 43% ABV. Bottle size 750 mL.",
        ) as BottleDetailExtractionResult.Rejected

        assertEquals(BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES, result.reason)
        assertEquals(setOf(BottleFactField.ABV_PERCENT), result.rejectedFields)
    }

    @Test
    fun conflictDiagnosticsRetainOtherFieldRejectionReasons() {
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV"},{"field":"abv_percent","value":"43%","evidence":"North Star Gin 43% ABV"},{"field":"quantity","value":"700 mL","evidence":"Bottle size 750 mL"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin",
            "North Star Gin 40% ABV. North Star Gin 43% ABV. Bottle size 750 mL.",
        ) as BottleDetailExtractionResult.Rejected

        assertEquals(
            setOf(
                BottleFactRejection(BottleFactField.ABV_PERCENT, BottleExtractionRejectionReason.CONFLICTING_FIELD_VALUES),
                BottleFactRejection(BottleFactField.QUANTITY, BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE),
            ),
            result.fieldRejections.toSet(),
        )
    }

    @Test
    fun fieldRejectionReportMapsEachFieldToItsActualReasonWithoutValues() {
        val source = "North Star Gin 40% ABV and North Star Gin 43% ABV. Bottle size 750 mL."
        val result = parseBottleFactCandidate(
            """{"facts":[{"field":"product_name","value":"Secret Bottle","evidence":"quote not in page"},{"field":"quantity","value":"700 mL","evidence":"Bottle size 750 mL"},{"field":"abv_percent","value":"40%","evidence":"North Star Gin 40% ABV and North Star Gin 43% ABV"}]}""",
            "On-device Qwen3", LOCAL_MODEL_LABEL, "https://example.org/gin", source,
        ) as BottleDetailExtractionResult.Rejected

        assertEquals(
            setOf(
                BottleFactRejection(BottleFactField.PRODUCT_NAME, BottleExtractionRejectionReason.EVIDENCE_NOT_IN_SOURCE),
                BottleFactRejection(BottleFactField.QUANTITY, BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE),
                BottleFactRejection(BottleFactField.ABV_PERCENT, BottleExtractionRejectionReason.INVALID_ABV_EVIDENCE),
            ),
            result.fieldRejections.toSet(),
        )
        val report = newDebugReport().also {
            it.record(BottleLookupDebugEvent(
                stage = BottleLookupDebugStage.LOCAL_RESPONSE_PARSING,
                provider = "On-device Qwen3",
                result = "REJECTED",
                rejectedFacts = result.fieldRejections,
            ))
        }.toJson()
        assertTrue(report.contains("\"field\": \"PRODUCT_NAME\""))
        assertTrue(report.contains("\"reason\": \"EVIDENCE_NOT_IN_SOURCE\""))
        assertTrue(report.contains("\"field\": \"QUANTITY\""))
        assertTrue(report.contains("\"reason\": \"VALUE_NOT_IN_EVIDENCE\""))
        assertTrue(report.contains("\"field\": \"ABV_PERCENT\""))
        assertTrue(report.contains("\"reason\": \"INVALID_ABV_EVIDENCE\""))
        assertFalse(report.contains("Secret Bottle"))
        assertFalse(report.contains("40% ABV"))
        assertFalse(report.contains("quote not in page"))
    }

    @Test
    fun verifiedStructuredNameSurvivesWhenEveryModelFactIsUnsupported() = runBlocking {
        val barcode = "5011333150725"
        val identity = StructuredBottleIdentity(barcode, "Auchentoshan Three Wood", "barcode $barcode; product Auchentoshan Three Wood")
        val page = WebPageText(
            "Auchentoshan Three Wood", "https://shop.example/three-wood", "Auchentoshan Three Wood 43% ABV", "Firecrawl", listOf(identity),
        )
        val events = mutableListOf<BottleLookupDebugEvent>()
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Auchentoshan Three Wood 43% ABV"},{"field":"abv_percent","value":"46%","evidence":"Auchentoshan Three Wood 43% ABV"}]}"""
        }).extractWithDiagnostics(page, LOCAL_MODEL_LABEL, barcode, events::add)

        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("Structured product data + On-device Qwen3", suggestions.provider)
        assertEquals("Auchentoshan Three Wood", suggestions.facts.single().value)
        assertFalse(BottleFactField.PRODUCT_NAME in suggestions.unresolvedFields)
        assertTrue(BottleFactField.ABV_PERCENT in suggestions.unresolvedFields)
        val parseEvent = events.single { it.stage == BottleLookupDebugStage.LOCAL_RESPONSE_PARSING && it.result == "REJECTED" }
        assertEquals(listOf("VALUE_NOT_IN_EVIDENCE"), parseEvent.reasonCodes)
        assertEquals(listOf("product_name", "abv_percent"), parseEvent.factFields)
        assertTrue(events.any { "STRUCTURED_FACTS_FALLBACK_AFTER_MODEL_REJECTION" in it.reasonCodes })

        val debugReport = newDebugReport().also { events.forEach(it::record); it.finish("PARTIAL") }.toJson()
        assertTrue(debugReport.contains("VALUE_NOT_IN_EVIDENCE"))
        assertTrue(debugReport.contains("ABV_PERCENT"))
        assertFalse(debugReport.contains("46%"))
        assertFalse(debugReport.contains("Auchentoshan Three Wood 43% ABV"))
    }

    @Test
    fun reportsMissingStructuredFallbackAndDoesNotMislabelParserRejectionAsIdentityFailure() = runBlocking {
        val barcode = "5011333150725"
        val page = WebPageText(
            "Auchentoshan Three Wood Single Malt Scotch Whisky",
            "https://cook.example/auchentoshan-three-wood",
            "UPC $barcode. Auchentoshan Three Wood Single Malt Scotch Whisky. 750 mL. 43% ABV.",
            "Firecrawl",
        )
        val events = mutableListOf<BottleLookupDebugEvent>()
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Auchentoshan Three Wood Single Malt Scotch Whisky"},{"field":"quantity","value":"700 mL","evidence":"750 mL"},{"field":"abv_percent","value":"46%","evidence":"43% ABV"}]}"""
        }).extractWithDiagnostics(page, LOCAL_MODEL_LABEL, barcode, events::add)

        assertEquals(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE, (result as BottleDetailExtractionResult.Rejected).reason)
        assertEquals(
            listOf("product_name", "quantity", "abv_percent"),
            events.single { it.stage == BottleLookupDebugStage.LOCAL_RESPONSE_PARSING && it.result == "REJECTED" }.factFields,
        )
        val identityAvailability = events.single { it.stage == BottleLookupDebugStage.LOCAL_STRUCTURED_IDENTITY }
        assertEquals("NO_MATCH", identityAvailability.result)
        assertEquals(listOf("STRUCTURED_IDENTITY_DATA_MISSING"), identityAvailability.reasonCodes)
        val identityStage = events.single { it.stage == BottleLookupDebugStage.LOCAL_IDENTITY_BINDING }
        assertEquals("SKIPPED", identityStage.result)
        assertEquals(listOf("MODEL_CANDIDATE_REJECTED_BEFORE_IDENTITY_BINDING"), identityStage.reasonCodes)
    }

    @Test
    fun mismatchedModelNameDoesNotEraseBarcodeBoundFacts() {
        val result = BottleDetailExtractionResult.Found(
            BottleDetailSuggestions(
                provider = "On-device Qwen3", model = LOCAL_MODEL_LABEL, sourceUrl = "https://shop.example/item",
                facts = listOf(
                    BottleFactEvidence(BottleFactField.PRODUCT_NAME, "Other Reserve", "Other Reserve 700 mL"),
                    BottleFactEvidence(BottleFactField.QUANTITY, "750ml", "Auchentoshan Three Wood bottle size 750 mL"),
                ),
            ),
        )
        val bound = bindStructuredProductIdentity(
            result, "5011333150725",
            StructuredBottleIdentity("5011333150725", "Auchentoshan Three Wood", "barcode 5011333150725; product Auchentoshan Three Wood"),
        ) as BottleDetailExtractionResult.Found

        assertEquals("Auchentoshan Three Wood", bound.suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertEquals("750ml", bound.suggestions.facts.first { it.field == BottleFactField.QUANTITY }.value)
        assertFalse(BottleFactField.PRODUCT_NAME in bound.suggestions.unresolvedFields)
        assertTrue(BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE in bound.rejectedCandidates)
    }

    @Test
    fun rejectedModelProductNameDoesNotMarkVerifiedStructuredNameUnresolved() = runBlocking {
        val barcode = "5011333150725"
        val identity = StructuredBottleIdentity(barcode, "Auchentoshan Three Wood", "barcode $barcode; product Auchentoshan Three Wood")
        val page = WebPageText(
            "Auchentoshan Three Wood", "https://shop.example/three-wood",
            "Auchentoshan Three Wood bottle size 750 mL.", "Firecrawl", listOf(identity),
        )
        val result = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Auchentoshan Three Wood bottle size 750 mL"},{"field":"quantity","value":"750 mL","evidence":"Auchentoshan Three Wood bottle size 750 mL"}]}"""
        }).extract(page, LOCAL_MODEL_LABEL, barcode) as BottleDetailExtractionResult.Found

        assertEquals("Auchentoshan Three Wood", result.suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertEquals("750 mL", result.suggestions.facts.first { it.field == BottleFactField.QUANTITY }.value)
        assertFalse(BottleFactField.PRODUCT_NAME in result.suggestions.unresolvedFields)
        assertTrue(BottleExtractionRejectionReason.VALUE_NOT_IN_EVIDENCE in result.rejectedCandidates)
    }

    @Test
    fun acceptsExactStructuredVariantIdentityAndRejectsModelNameFromAnotherProduct() = runBlocking {
        val identity = StructuredBottleIdentity(
            "5011333150725",
            "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            "Auchentoshan Three Wood Single Malt Scotch Whisky | Canadian Liquor Store",
            "https://shop.example/auchentoshan-three-wood",
            "Auchentoshan Three Wood bottle size: 750 mL. Auchentoshan Three Wood is 43% ABV. Alternative: Other Reserve 700 mL.",
            "Firecrawl",
            listOf(identity),
        )
        val output = """{"facts":[{"field":"quantity","value":"750 mL","evidence":"Auchentoshan Three Wood bottle size: 750 mL"},{"field":"abv_percent","value":"43%","evidence":"Auchentoshan Three Wood is 43% ABV"}]}"""
        var prompt = ""
        val extractor = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, _ ->
            prompt = userPrompt
            output
        })

        val result = extractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725")
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertEquals(identity.evidence, suggestions.identityEvidence)
        assertEquals("Auchentoshan Three Wood", suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertTrue(prompt.contains(identity.productName))
        assertFalse(prompt.contains(identity.barcode))

        val wrongNameExtractor = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, _, _ ->
            """{"facts":[{"field":"product_name","value":"Other Reserve","evidence":"Other Reserve 700 mL"}]}"""
        })
        val wrongNameResult = wrongNameExtractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725")
        assertTrue(wrongNameResult is BottleDetailExtractionResult.Found)
        wrongNameResult as BottleDetailExtractionResult.Found
        assertEquals("Auchentoshan Three Wood", wrongNameResult.suggestions.facts.first { it.field == BottleFactField.PRODUCT_NAME }.value)
        assertTrue(BottleExtractionRejectionReason.PRODUCT_NAME_NOT_TIED_TO_BARCODE in wrongNameResult.rejectedCandidates)
    }

    @Test
    fun scannedBarcodeIsBoundLocallyAndRedactedFromInferencePrompt() = runBlocking {
        val page = WebPageText(
            "North Star Gin",
            "https://shop.example/north-star",
            "UPC 5011333150725 belongs to North Star Gin, 40% ABV.",
            "Firecrawl",
        )
        var prompt = ""
        val extractor = LocalBottleDetailExtractor({ "/model.gguf" }, LocalInferenceRunner { _, _, userPrompt, _ ->
            prompt = userPrompt
            """{"facts":[{"field":"product_name","value":"North Star Gin","evidence":"North Star Gin, 40% ABV"}]}"""
        })

        val result = extractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725")
        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("5011333150725", suggestions.targetBarcode)
        assertTrue(prompt.contains("[barcode omitted]"))
        assertFalse(prompt.contains("5011333150725"))
    }

    @Test
    fun malformedLocalOutputKeepsOnlyTheIndependentlyVerifiedStructuredProductName() = runBlocking {
        val barcode = "5011333150725"
        val identity = StructuredBottleIdentity(
            barcode,
            "Auchentoshan Three Wood",
            "Structured product fields: barcode $barcode; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            "Auchentoshan Three Wood",
            "https://shop.example/three-wood",
            "Auchentoshan Three Wood 750 mL, 43% ABV.",
            "Firecrawl",
            listOf(identity),
        )
        val events = mutableListOf<BottleLookupDebugEvent>()
        val result = LocalBottleDetailExtractor(
            { "/model.gguf" },
            LocalInferenceRunner { _, _, _, _ -> "I found this bottle: Auchentoshan Three Wood" },
        ).extractWithDiagnostics(page, LOCAL_MODEL_LABEL, barcode, events::add)

        assertTrue(result is BottleDetailExtractionResult.Found)
        val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
        assertEquals("Structured product data + On-device Qwen3", suggestions.provider)
        assertEquals("Auchentoshan Three Wood", suggestions.facts.single().value)
        assertEquals(setOf(BottleFactField.BRAND, BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT), suggestions.unresolvedFields)
        assertTrue(events.any {
            it.stage == BottleLookupDebugStage.LOCAL_RESPONSE_PARSING &&
                it.reasonCodes == listOf("MALFORMED_MODEL_RESPONSE")
        })
        assertTrue(events.any {
            it.stage == BottleLookupDebugStage.LOCAL_IDENTITY_BINDING &&
                it.reasonCodes == listOf("STRUCTURED_FACTS_FALLBACK_AFTER_MALFORMED_RESPONSE")
        })
    }

    @Test
    fun tokenLimitIsReportedAndFallsBackToVerifiedStructuredNameOnly() = runBlocking {
        val barcode = "5011333150725"
        val identity = StructuredBottleIdentity(
            barcode,
            "Auchentoshan Three Wood",
            "Structured product fields: barcode $barcode; product Auchentoshan Three Wood",
        )
        val modelFile = validHeaderModelFile()
        val engine = FakeInferenceEngine(
            output = "{\"facts\":[",
            generationStats = InferenceEngine.GenerationStats(
                160,
                InferenceEngine.GenerationFinishReason.TOKEN_LIMIT,
            ),
        )
        val captureRoot = Files.createTempDirectory("token-limit-capture").toFile()
        val captureStore = DetailedBottleLookupCaptureStore(File(captureRoot, "capture.json"), { 1_000L }, processSessionId = "test")
        assertEquals(DetailedCaptureArmResult.ARMED, captureStore.armNextLookup("preview.20", 1_000L))
        val detailedCapture = captureStore.claim(
            "draft-token-limit", "00000000-0000-0000-0000-000000000098", barcode, "preview.20", 20,
            LOCAL_MODEL_LABEL, 1_000L, allowNextLookup = true,
        )!!
        val events = mutableListOf<BottleLookupDebugEvent>()
        try {
            val result = LocalBottleDetailExtractor({ modelFile.absolutePath }, LlamaCppLocalInferenceRunner(engine))
                .extractWithDetailedCapture(
                    WebPageText("Auchentoshan Three Wood", "https://shop.example/three-wood", "Auchentoshan Three Wood", "Firecrawl", listOf(identity)),
                    LOCAL_MODEL_LABEL,
                    barcode,
                    detailedCapture,
                    events::add,
                )
            detailedCapture.finish("COMPLETE")

            assertTrue(result is BottleDetailExtractionResult.Found)
            val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
            assertEquals("Structured product data + On-device Qwen3", suggestions.provider)
            assertEquals("Auchentoshan Three Wood", suggestions.facts.single().value)
            val generation = events.single { it.stage == BottleLookupDebugStage.LOCAL_GENERATION && it.result == "FAILED" }
            assertEquals(160, generation.generatedTokens)
            assertEquals("TOKEN_LIMIT", generation.finishReason)
            assertTrue(generation.reasonCodes.contains("LOCAL_GENERATION_TOKEN_LIMIT"))
            assertTrue(events.any { "STRUCTURED_FACTS_FALLBACK_AFTER_TRUNCATION" in it.reasonCodes })
            val preview = captureStore.previewForDraft("draft-token-limit", 1_000L)!!
            assertTrue(preview.contains("\\\"facts\\\":["))
            assertTrue(preview.contains("\"generationFinishReason\": \"TOKEN_LIMIT\""))
            assertTrue(preview.contains("\"outputTokenLimitReached\": true"))
            assertTrue(preview.contains("\"state\": \"PARTIAL\""))
            assertEquals("PARTIAL", captureStore.statusForDraft("draft-token-limit", 1_000L)?.state)
        } finally {
            captureRoot.deleteRecursively()
            modelFile.delete()
        }
    }

    @Test
    fun persistentDebugModeCapturesWorkerClaimThroughTruncatedLocalOutputAndCombinedReport() = runBlocking {
        val root = Files.createTempDirectory("debug-mode-worker-token-limit").toFile()
        val modelFile = validHeaderModelFile()
        val store = DetailedBottleLookupDebugSessionStore(root, processSessionId = "worker-test")
        assertEquals(DetailedCaptureArmResult.ARMED, store.enableDebugMode("preview.17"))
        val plan = ScannedBottlePlan(openFoodFactsEnabled = false, firecrawlEnabled = true, extractor = AutomaticBottleExtractor.ON_DEVICE)
        val attemptToken = "00000000-0000-0000-0000-000000000777"
        val capture = claimDetailedLookupCaptureForWorker(
            store = store,
            plan = plan,
            draftId = "draft-token-limit-worker",
            attemptToken = attemptToken,
            barcode = "5011333150725",
            appVersion = "preview.17",
            appVersionCode = 17,
            model = LOCAL_MODEL_LABEL,
        ) ?: error("The eligible on-device worker attempt should claim a capture")
        val engine = FakeInferenceEngine(
            output = "{\"facts\":[",
            generationStats = InferenceEngine.GenerationStats(160, InferenceEngine.GenerationFinishReason.TOKEN_LIMIT),
        )
        try {
            val result = LocalBottleDetailExtractor({ modelFile.absolutePath }, LlamaCppLocalInferenceRunner(engine))
                .extractWithDetailedCapture(
                    WebPageText("Product", "https://example.org/product", "Product size 750 mL", "Fixture provider"),
                    LOCAL_MODEL_LABEL,
                    "5011333150725",
                    capture,
                )
            capture.finish(if (result is BottleDetailExtractionResult.Found) "COMPLETE" else "FAILED")

            val saved = store.reportContents("draft-token-limit-worker").single()
            assertTrue(saved.contains("draft-token-limit-worker"))
            assertTrue(saved.contains(attemptToken))
            assertTrue(saved.contains("{\\\"facts\\\":["))
            assertTrue(saved.contains("TOKEN_LIMIT"))
            assertTrue(saved.contains("outputTokenLimitReached"))
            val combined = combineBottleDiagnostics("{}", listOf(saved))
            assertTrue(combined.contains("detailedCapturesIncluded"))
            assertTrue(combined.contains("{\\\"facts\\\":["))
            assertTrue(store.reportContents("another-draft").isEmpty())
        } finally {
            root.deleteRecursively()
            modelFile.delete()
        }
    }

    @Test
    fun localRuntimeFailureIsNotReportedAsNetworkFailureAndLeaksNoExceptionMessage() = runBlocking {
        val source = WebPageText("Test Gin", "https://shop.example/gin", "Test Gin 40% ABV", "Firecrawl")
        val modelFile = validHeaderModelFile()
        val engine = FakeInferenceEngine(
            initialState = InferenceEngine.State.Error(
                exception = IllegalStateException("secret /private/model.gguf token=do-not-copy"),
                diagnosticType = "UnsatisfiedLinkError",
                diagnosticFrames = listOf(StackTraceElement("com.arm.aichat.internal.InferenceEngineImpl", "init", "InferenceEngineImpl.kt", 91)),
            ),
        )
        val events = mutableListOf<BottleLookupDebugEvent>()

        val result = try {
            LocalBottleDetailExtractor({ modelFile.absolutePath }, LlamaCppLocalInferenceRunner(engine))
                .extractWithDiagnostics(source, LOCAL_MODEL_LABEL, null, events::add)
        } finally {
            modelFile.delete()
        }

        assertEquals(BottleDetailExtractionResult.LocalFailure(LocalInferenceFailureCode.NATIVE_RUNTIME_UNAVAILABLE), result)
        val report = newDebugReport().also { events.forEach(it::record) }.toJson()
        assertTrue(report.contains("LOCAL_RUNTIME_INITIALIZATION"))
        assertTrue(report.contains("LOCAL_NATIVE_RUNTIME_UNAVAILABLE"))
        assertTrue(report.contains("UnsatisfiedLinkError"))
        assertTrue(report.contains("InferenceEngineImpl.init:91"))
        assertFalse(report.contains("NETWORK_ERROR"))
        assertFalse(report.contains("do-not-copy"))
        assertFalse(report.contains("/private/model.gguf"))
        assertFalse(report.contains("secret"))
    }

    @Test
    fun reportsLocalValidationLoadGenerationParseAndIdentityStages() = runBlocking {
        val barcode = "5011333150725"
        val identity = StructuredBottleIdentity(
            barcode,
            "Test Whisky",
            "Structured product fields: barcode $barcode; product Test Whisky",
        )
        val source = WebPageText(
            "Test Whisky",
            "https://shop.example/whisky",
            "Test Whisky 40% ABV",
            "Firecrawl",
            listOf(identity),
        )
        val modelFile = validHeaderModelFile()
        val engine = FakeInferenceEngine(
            output = """{"facts":[{"field":"product_name","value":"Test Whisky","evidence":"Test Whisky 40% ABV"}]}""",
            generationStats = InferenceEngine.GenerationStats(
                49,
                InferenceEngine.GenerationFinishReason.END_OF_SEQUENCE,
            ),
        )
        val events = mutableListOf<BottleLookupDebugEvent>()

        val result = try {
            LocalBottleDetailExtractor({ modelFile.absolutePath }, LlamaCppLocalInferenceRunner(engine))
                .extractWithDiagnostics(source, LOCAL_MODEL_LABEL, barcode, events::add)
        } finally {
            modelFile.delete()
        }

        assertTrue(result is BottleDetailExtractionResult.Found)
        val stages = events.map(BottleLookupDebugEvent::stage)
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_INPUT_VALIDATION))
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_RUNTIME_INITIALIZATION))
        assertTrue(events.any {
            it.stage == BottleLookupDebugStage.LOCAL_RUNTIME_INITIALIZATION &&
                it.result == "READY" && it.reasonCodes == listOf("LOCAL_CPU_BACKEND_REGISTERED")
        })
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_MODEL_LOADING))
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_PROMPT_SETUP))
        val prefill = events.single { it.stage == BottleLookupDebugStage.LOCAL_PROMPT_PREFILL && it.result == "COMPLETE" }
        assertTrue((prefill.durationMillis ?: 0) > 0)
        assertTrue((prefill.promptCharacters ?: 0) > 0)
        val firstToken = events.single { it.stage == BottleLookupDebugStage.LOCAL_FIRST_TOKEN }
        assertTrue((firstToken.durationMillis ?: 0) > 0)
        val generation = events.single { it.stage == BottleLookupDebugStage.LOCAL_GENERATION && it.result == "COMPLETE" }
        assertEquals(1, generation.outputChunks)
        assertEquals(49, generation.generatedTokens)
        assertEquals("END_OF_SEQUENCE", generation.finishReason)
        assertTrue((generation.durationMillis ?: 0) > 0)
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_GENERATION))
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_RESPONSE_PARSING))
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_IDENTITY_BINDING))
        assertTrue(engine.cleanedUp)
    }

    @Test
    fun localModelLoadAndGenerationFailuresKeepTheirActualStage() = runBlocking {
        val page = WebPageText("Test Gin", "https://shop.example/gin", "Test Gin 40% ABV", "Firecrawl")
        val modelFile = validHeaderModelFile()

        val loadEvents = mutableListOf<BottleLookupDebugEvent>()
        try {
            val loadResult = LocalBottleDetailExtractor({ modelFile.absolutePath }, LlamaCppLocalInferenceRunner(FakeInferenceEngine(throwOn = "load")))
                .extractWithDiagnostics(page, LOCAL_MODEL_LABEL, null, loadEvents::add)
            assertEquals(BottleDetailExtractionResult.LocalFailure(LocalInferenceFailureCode.MODEL_LOAD_FAILED), loadResult)
            val loadFailure = loadEvents.single { it.stage == BottleLookupDebugStage.LOCAL_MODEL_LOADING && it.result == "FAILED" }
            assertEquals(listOf("LOCAL_MODEL_LOAD_FAILED"), loadFailure.reasonCodes)
            assertEquals("ModelLoadException", loadFailure.failureType)

            val generationEvents = mutableListOf<BottleLookupDebugEvent>()
            val generationResult = LocalBottleDetailExtractor({ modelFile.absolutePath }, LlamaCppLocalInferenceRunner(FakeInferenceEngine(throwOn = "generation")))
                .extractWithDiagnostics(page, LOCAL_MODEL_LABEL, null, generationEvents::add)
            assertEquals(BottleDetailExtractionResult.LocalFailure(LocalInferenceFailureCode.GENERATION_FAILED), generationResult)
            assertTrue(generationEvents.any { it.stage == BottleLookupDebugStage.LOCAL_GENERATION && it.result == "FAILED" })
            assertFalse(generationEvents.any { "NETWORK_ERROR" == it.result })
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun nativeGenerationTimeoutRequestsInterruptionAndKeepsVerifiedStructuredName() = runBlocking {
        val modelFile = validHeaderModelFile()
        val identity = StructuredBottleIdentity(
            "5011333150725",
            "Auchentoshan Three Wood",
            "Structured product fields: barcode 5011333150725; product Auchentoshan Three Wood",
        )
        val page = WebPageText(
            "Auchentoshan Three Wood Single Malt Scotch Whisky | Canadian Liquor Store",
            "https://shop.example/auchentoshan-three-wood",
            "Auchentoshan Three Wood Single Malt Scotch Whisky",
            "Firecrawl",
            listOf(identity),
        )
        val engine = FakeInferenceEngine(waitForNativeCancel = true)
        val events = mutableListOf<BottleLookupDebugEvent>()
        try {
            val result = LocalBottleDetailExtractor(
                { modelFile.absolutePath },
                LlamaCppLocalInferenceRunner(engine, generationTimeoutMs = 500),
            ).extractWithDiagnostics(page, LOCAL_MODEL_LABEL, "5011333150725", events::add)

            assertTrue(result is BottleDetailExtractionResult.Found)
            val suggestions = (result as BottleDetailExtractionResult.Found).suggestions
            assertEquals("Structured product data + On-device Qwen3", suggestions.provider)
            assertEquals("Auchentoshan Three Wood", suggestions.facts.single().value)
            assertEquals(setOf(BottleFactField.BRAND, BottleFactField.QUANTITY, BottleFactField.ABV_PERCENT), suggestions.unresolvedFields)
            assertEquals("5011333150725", suggestions.targetBarcode)
            assertTrue(engine.nativeCancellationRequested)
            assertTrue(engine.cleanedUp)
            val timeout = events.single {
                it.stage == BottleLookupDebugStage.LOCAL_GENERATION && it.result == "FAILED"
            }
            assertEquals(listOf("LOCAL_GENERATION_TIMEOUT"), timeout.reasonCodes)
            assertTrue((timeout.durationMillis ?: -1L) in 400..750)
            assertTrue((timeout.promptCharacters ?: 0) > 0)
            assertTrue(events.any { it.stage == BottleLookupDebugStage.LOCAL_PROMPT_PREFILL && it.result == "COMPLETE" })
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun nativeModelLoadCategoriesArePreservedInLookupDiagnostics() = runBlocking {
        val modelFile = validHeaderModelFile()
        val page = WebPageText("Test Whisky", "https://shop.example/whisky", "Test Whisky", "Firecrawl")
        try {
            val cases = listOf(
                "architecture" to LocalInferenceFailureCode.MODEL_ARCHITECTURE_UNSUPPORTED,
                "allocation" to LocalInferenceFailureCode.MODEL_ALLOCATION_FAILED,
            )
            for ((failureKind, expectedCode) in cases) {
                val events = mutableListOf<BottleLookupDebugEvent>()
                val result = LocalBottleDetailExtractor(
                    { modelFile.absolutePath },
                    LlamaCppLocalInferenceRunner(FakeInferenceEngine(throwOn = failureKind)),
                ).extractWithDiagnostics(page, LOCAL_MODEL_LABEL, null, events::add)
                assertEquals(BottleDetailExtractionResult.LocalFailure(expectedCode), result)
                assertTrue(events.any {
                    it.stage == BottleLookupDebugStage.LOCAL_MODEL_LOADING &&
                        it.result == "FAILED" && it.reasonCodes == listOf(expectedCode.wireValue)
                })
            }
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun missingNativeCpuBackendHasItsOwnSafeDiagnostic() = runBlocking {
        val modelFile = validHeaderModelFile()
        val failure = NativeCpuBackendUnavailableException()
        val engine = FakeInferenceEngine(
            initialState = InferenceEngine.State.Error(
                exception = failure,
                diagnosticType = failure.javaClass.simpleName,
            ),
        )
        val events = mutableListOf<BottleLookupDebugEvent>()
        try {
            val result = LocalBottleDetailExtractor(
                { modelFile.absolutePath },
                LlamaCppLocalInferenceRunner(engine),
            ).extractWithDiagnostics(
                WebPageText("Test Whisky", "https://shop.example/whisky", "Test Whisky", "Firecrawl"),
                LOCAL_MODEL_LABEL,
                null,
                events::add,
            )

            assertEquals(
                BottleDetailExtractionResult.LocalFailure(LocalInferenceFailureCode.CPU_BACKEND_UNAVAILABLE),
                result,
            )
            val error = events.single {
                it.stage == BottleLookupDebugStage.LOCAL_RUNTIME_INITIALIZATION && it.result == "FAILED"
            }
            assertEquals(listOf("LOCAL_CPU_BACKEND_UNAVAILABLE"), error.reasonCodes)
            assertEquals("NativeCpuBackendUnavailableException", error.failureType)
        } finally {
            modelFile.delete()
        }
    }

    @Test
    fun rejectsInvalidInstalledModelBeforeInitializingNativeRuntime() = runBlocking {
        val invalidModel = java.io.File.createTempFile("invalid-qwen-", ".gguf").apply { writeText("not a GGUF model") }
        val events = mutableListOf<BottleLookupDebugEvent>()
        try {
            val result = LocalBottleDetailExtractor(
                { invalidModel.absolutePath },
                LlamaCppLocalInferenceRunner(FakeInferenceEngine()),
            ).extractWithDiagnostics(
                WebPageText("Test Gin", "https://shop.example/gin", "Test Gin 40% ABV", "Firecrawl"),
                LOCAL_MODEL_LABEL,
                null,
                events::add,
            )

            assertEquals(BottleDetailExtractionResult.LocalFailure(LocalInferenceFailureCode.MODEL_FILE_INVALID), result)
            assertTrue(events.any { it.stage == BottleLookupDebugStage.LOCAL_INPUT_VALIDATION && it.reasonCodes.contains("LOCAL_MODEL_FILE_INVALID") })
            assertFalse(events.any { it.stage == BottleLookupDebugStage.LOCAL_RUNTIME_INITIALIZATION })
        } finally {
            invalidModel.delete()
        }
    }

    @Test
    fun diagnosticCallbackCancellationIsNotSwallowed() = runBlocking {
        val extractor = LocalBottleDetailExtractor(
            { null },
            LocalInferenceRunner { _, _, _, _ -> error("Runner must not be called") },
        )
        try {
            extractor.extractWithDiagnostics(
                WebPageText("Gin", "https://shop.example/gin", "Gin 40% ABV", "Firecrawl"),
                LOCAL_MODEL_LABEL,
                null,
            ) { throw CancellationException("cancel diagnostics") }
            throw AssertionError("Diagnostic cancellation should propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancel diagnostics", cancelled.message)
        }
    }

    @Test
    fun callerTimeoutIsNotReclassifiedAsLocalGenerationTimeout() = runBlocking {
        val extractor = LocalBottleDetailExtractor({ "/model.gguf" }, DelayedDiagnosticRunner())
        try {
            withTimeout(50) {
                extractor.extractWithDiagnostics(
                    WebPageText("Gin", "https://shop.example/gin", "Gin 40% ABV", "Firecrawl"),
                    LOCAL_MODEL_LABEL,
                    null,
                )
            }
            throw AssertionError("The enclosing deadline should propagate")
        } catch (_: TimeoutCancellationException) {
            // Expected: a caller-owned deadline is cancellation, not a model-stage timeout result.
        }
    }

    @Test
    fun explicitCallerCancellationIsNotConvertedToExtractionFailure() = runBlocking {
        val extractor = LocalBottleDetailExtractor({ "/model.gguf" }, DelayedDiagnosticRunner())
        val job = launch {
            extractor.extractWithDiagnostics(
                WebPageText("Gin", "https://shop.example/gin", "Gin 40% ABV", "Firecrawl"),
                LOCAL_MODEL_LABEL,
                null,
            )
        }
        delay(20)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun callerCancellationDuringSystemPromptWaitsForNativeExitBeforeCleanup() = runBlocking {
        val modelFile = validHeaderModelFile()
        val engine = BlockingNativeInferenceEngine(blockSystemPrompt = true)
        val runner = LlamaCppLocalInferenceRunner(engine, generationTimeoutMs = 10_000)
        val job = launch { runner.generateWithDiagnostics(modelFile.absolutePath, "system", "user", 16) {} }
        try {
            assertTrue(withContext(Dispatchers.IO) { engine.systemPromptEntered.await(3, TimeUnit.SECONDS) })
            job.cancel()
            job.join()

            assertTrue(engine.nativeCancellationRequested)
            assertTrue(engine.nativeOperationExited)
            assertTrue(engine.cleanedUp)
            assertTrue("Cleanup must follow exit from the simulated native call", engine.cleanupOrder > engine.nativeExitOrder)
        } finally {
            engine.releaseNativeOperation.countDown()
            modelFile.delete()
        }
    }

    @Test
    fun callerCancellationDuringGenerationWaitsForNativeExitAndNextRequestResetsFlag() = runBlocking {
        val modelFile = validHeaderModelFile()
        val engine = BlockingNativeInferenceEngine(blockSystemPrompt = false)
        val runner = LlamaCppLocalInferenceRunner(engine, generationTimeoutMs = 10_000)
        val job = launch { runner.generateWithDiagnostics(modelFile.absolutePath, "system", "user", 16) {} }
        try {
            assertTrue(withContext(Dispatchers.IO) { engine.generationEntered.await(3, TimeUnit.SECONDS) })
            job.cancel()
            job.join()

            assertTrue(engine.nativeCancellationRequested)
            assertTrue(engine.nativeOperationExited)
            assertTrue(engine.cleanedUp)
            assertTrue("Cleanup must follow exit from the simulated native call", engine.cleanupOrder > engine.nativeExitOrder)

            engine.blockNextGeneration = false
            assertEquals("{}", runner.generateWithDiagnostics(modelFile.absolutePath, "system", "user", 16) {})
            assertFalse("Loading a fresh native request clears its cancellation flag", engine.nativeCancellationRequested)
            assertTrue(engine.loadCount >= 2)
        } finally {
            engine.releaseNativeOperation.countDown()
            modelFile.delete()
        }
    }

    private fun newDebugReport() = BottleLookupDebugReport(
        createdAtMillis = 1,
        versionName = "test",
        versionCode = 1,
        originalBarcode = "5011333150725",
        normalizedBarcode = "5011333150725",
        openFoodFactsEnabled = false,
        openFoodFactsConfigured = true,
        firecrawlEnabled = true,
        firecrawlConfigured = true,
        extractorProvider = "On-device Qwen3",
        modelIdentifier = LOCAL_MODEL_LABEL,
        query = "\"5011333150725\"",
        onDeviceAiEnabled = true,
        onDeviceModelInstalled = true,
        localModelIdentifier = LOCAL_MODEL_LABEL,
    )

    private fun validHeaderModelFile() = java.io.File.createTempFile("qwen-test-", ".gguf").apply {
        writeBytes(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
    }

    private class FakeInferenceEngine(
        initialState: InferenceEngine.State = InferenceEngine.State.Initialized,
        private val output: String = "{}",
        private val throwOn: String? = null,
        private val waitForNativeCancel: Boolean = false,
        private val generationStats: InferenceEngine.GenerationStats? = null,
    ) : InferenceEngine {
        override val state = MutableStateFlow(initialState)
        var cleanedUp = false
        @Volatile var nativeCancellationRequested = false

        override suspend fun loadModel(pathToModel: String) {
            when (throwOn) {
                "load" -> throw ModelLoadException()
                "architecture" -> throw com.arm.aichat.UnsupportedModelArchitectureException()
                "allocation" -> throw com.arm.aichat.NativeModelAllocationException()
            }
        }

        override suspend fun setSystemPrompt(systemPrompt: String) {
            if (throwOn == "prompt") throw testFailure()
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            if (throwOn == "generation") throw testFailure()
            state.value = InferenceEngine.State.ProcessingUserPrompt
            delay(20)
            state.value = InferenceEngine.State.Generating
            delay(20)
            if (waitForNativeCancel) {
                while (!nativeCancellationRequested) delay(5)
            } else {
                emit(output)
            }
            state.value = InferenceEngine.State.ModelReady
        }

        override fun requestGenerationCancellation() { nativeCancellationRequested = true }

        override suspend fun lastGenerationStats(): InferenceEngine.GenerationStats? = generationStats

        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = ""

        override fun cleanUp() { cleanedUp = true }

        override fun destroy() = Unit

        private fun testFailure() = IllegalStateException("secret path=/private/model.gguf api_key=never-copy").apply {
            stackTrace = arrayOf(StackTraceElement("com.arm.aichat.internal.InferenceEngineImpl", "loadModel", "InferenceEngineImpl.kt", 145))
        }
    }

    private class DelayedDiagnosticRunner : DiagnosticLocalInferenceRunner {
        override suspend fun generate(modelPath: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String {
            delay(1_000)
            return "{}"
        }

        override suspend fun generateWithDiagnostics(
            modelPath: String,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            onTokenLimitOutput: (String) -> Unit,
            onEvent: (BottleLookupDebugEvent) -> Unit,
        ): String {
            onEvent(BottleLookupDebugEvent(BottleLookupDebugStage.LOCAL_GENERATION, "On-device Qwen3", "RUNNING"))
            delay(1_000)
            return "{}"
        }
    }

    /** Simulates a JNI call that cannot return until the native cancellation callback fires. */
    private class BlockingNativeInferenceEngine(
        private val blockSystemPrompt: Boolean,
    ) : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Initialized)
        val systemPromptEntered = CountDownLatch(1)
        val generationEntered = CountDownLatch(1)
        val releaseNativeOperation = CountDownLatch(1)
        @Volatile var nativeCancellationRequested = false
            private set
        @Volatile var nativeOperationExited = false
            private set
        @Volatile var cleanedUp = false
            private set
        @Volatile var blockNextGeneration = true
        @Volatile var loadCount = 0
            private set
        @Volatile var nativeExitOrder = 0
            private set
        @Volatile var cleanupOrder = 0
            private set
        private val order = AtomicInteger()

        override suspend fun loadModel(pathToModel: String) {
            loadCount++
            nativeCancellationRequested = false
            nativeOperationExited = false
            cleanedUp = false
            cleanupOrder = 0
            state.value = InferenceEngine.State.ModelReady
        }

        override suspend fun setSystemPrompt(systemPrompt: String) {
            if (blockSystemPrompt) {
                state.value = InferenceEngine.State.ProcessingSystemPrompt
                withContext(NonCancellable + Dispatchers.IO) {
                    systemPromptEntered.countDown()
                    releaseNativeOperation.await()
                    recordNativeExit()
                }
                state.value = InferenceEngine.State.ModelReady
                if (nativeCancellationRequested) throw CancellationException("Simulated native prompt prefill cancelled")
            }
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            state.value = InferenceEngine.State.ProcessingUserPrompt
            if (blockNextGeneration) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        generationEntered.countDown()
                        releaseNativeOperation.await()
                    }
                } finally {
                    recordNativeExit()
                }
                if (nativeCancellationRequested) return@flow
            }
            state.value = InferenceEngine.State.Generating
            emit("{}")
            state.value = InferenceEngine.State.ModelReady
        }

        override fun requestGenerationCancellation() {
            nativeCancellationRequested = true
            releaseNativeOperation.countDown()
        }

        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = ""

        override fun cleanUp() {
            cleanedUp = true
            cleanupOrder = order.incrementAndGet()
            state.value = InferenceEngine.State.Initialized
        }

        override fun destroy() = Unit

        private fun recordNativeExit() {
            nativeOperationExited = true
            nativeExitOrder = order.incrementAndGet()
        }
    }
}
