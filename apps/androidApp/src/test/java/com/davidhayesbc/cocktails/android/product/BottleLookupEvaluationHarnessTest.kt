package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline replay harness. Every replay is sent through the production coordinator and extractor;
 * synthetic fixtures validate scoring mechanics only and are excluded from real-product aggregates.
 */
class BottleLookupEvaluationHarnessTest {
    @Test
    fun offlineReplayUsesProductionLookupAndExtractionAndWritesCoverageGatedReport() = runBlocking {
        val casesRoot = readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json")
        val runsRoot = readInput("bottle.eval.runs", "/bottle-evaluation/v1/replay-runs.json")
        val dataset = parseDataset(casesRoot)
        val variants = parseVariants(runsRoot, dataset.version)
        val report = evaluate(dataset, variants)
        writeReport(report)

        val coverage = report.getValue("coverage").jsonObject
        assertEquals(18, coverage.getValue("expectedReviewedProductCount").jsonPrimitive.intOrNull)
        assertEquals(0, coverage.getValue("reviewedProductCasesLoaded").jsonPrimitive.intOrNull)
        assertEquals(0, coverage.getValue("reviewedProductCasesExecutable").jsonPrimitive.intOrNull)
        assertEquals(18, coverage.getValue("reviewedProductCasesBlocked").jsonPrimitive.intOrNull)
        assertEquals(4, dataset.cases.size)
        assertEquals(2, variants.size)
        assertFalse(report.getValue("qualityAggregateEligible").jsonPrimitive.booleanOrNull == true)

        val variantReports = report.getValue("variants").jsonArray
        assertEquals(2, variantReports.size)
        val first = variantReports.first().jsonObject
        val firstCase = first.getValue("syntheticCaseResults").jsonArray.first().jsonObject
        assertEquals(false, firstCase.getValue("inferenceExecutedByHarness").jsonPrimitive.booleanOrNull)
        assertEquals(true, firstCase.getValue("replayResponseConsumed").jsonPrimitive.booleanOrNull)
        assertEquals(4, first.getValue("syntheticCaseResults").jsonArray.size)
        assertEquals(0, first.getValue("wrongProductAcceptanceCount").jsonPrimitive.intOrNull)
        assertEquals(1, first.getValue("tokenLimitResultCount").jsonPrimitive.intOrNull)
        assertEquals(6, first.getValue("syntheticContractMetrics").jsonObject.getValue("supportedFactTruePositives").jsonPrimitive.intOrNull)
        val second = variantReports.last().jsonObject
        assertEquals(5, second.getValue("syntheticContractMetrics").jsonObject.getValue("supportedFactTruePositives").jsonPrimitive.intOrNull)
        assertEquals(1, second.getValue("syntheticContractMetrics").jsonObject.getValue("supportedFactFalseNegatives").jsonPrimitive.intOrNull)
        assertEquals("not_measured", first.getValue("timingSource").jsonPrimitive.content)
        assertEquals("unavailable_requires_development_and_heldout_reviewed_products", report.getValue("qualityAggregate").jsonPrimitive.content)
        val syntheticResults = first.getValue("syntheticCaseResults").jsonArray.map { it.jsonObject }.associateBy {
            it.getValue("caseId").jsonPrimitive.content
        }
        assertEquals("harbor-light-product-page", syntheticResults.getValue("synthetic-partial-table-fallback-fetch")
            .getValue("selectedSourceId").jsonPrimitive.content)
        assertEquals("not_run", syntheticResults.getValue("synthetic-conflicting-barcode-variants")
            .getValue("finishReason").jsonPrimitive.content)
        assertEquals(listOf("product_name"), syntheticResults.getValue("synthetic-related-product-injection")
            .getValue("predictedFields").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("Prompt and output text must not be copied into the report", report.toString().length < 25_000)
    }
}

private enum class SourceLabelStatus { SUPPORTED, ABSENT, CONFLICTING, UNKNOWN }
private enum class InventoryLabelStatus { CONFIRMED, UNRESOLVED, UNREVIEWED }
private enum class IdentityExpectation { TARGET, AMBIGUOUS, NON_TARGET, UNRESOLVED }

private data class LabeledValue(val status: String, val value: String? = null, val evidence: String? = null)
private data class EvalPage(
    val sourceId: String,
    val hitUrl: String,
    val page: WebPageText,
    val inputSha256: String?,
    val contentLicense: String?,
)
private data class EvalCase(
    val id: String,
    val kind: String,
    val split: String,
    val groupId: String,
    val sourceCoverage: String,
    val barcode: String,
    val identityExpectation: IdentityExpectation,
    val expectedSourceId: String?,
    val inventoryTruth: Map<BottleFactField, LabeledValue>,
    val sourceFacts: Map<BottleFactField, LabeledValue>,
    val searchHits: List<Pair<String, WebSearchHit>>,
    val pages: List<EvalPage>,
)
private data class EvalDataset(val version: String, val expectedReviewedProductCount: Int, val cases: List<EvalCase>)
private data class EvalResponse(
    val response: String,
    val finishReason: String,
    val generatedTokens: Int?,
    val generationMillis: Long?,
)
private data class EvalVariant(
    val id: String,
    val modelId: String,
    val quantization: String,
    val runtime: String,
    val promptVersion: String,
    val temperature: Double?,
    val maxOutputTokens: Int,
    val outputSource: String,
    val timingSource: String,
    val systemPrompt: String?,
    val additionalInstructions: String?,
    val responses: Map<String, EvalResponse>,
)
private data class EvaluatedCase(
    val case: EvalCase,
    val outcome: ScannedBottleOutcome,
    val extractionEvents: List<BottleLookupDebugEvent>,
    val invokedModel: Boolean,
    val promptText: String?,
    val response: EvalResponse?,
)

private fun readInput(property: String, resource: String): JsonObject {
    val path = System.getProperty(property)?.takeIf(String::isNotBlank)
    val content = if (path != null) File(path).readText(Charsets.UTF_8) else {
        BottleLookupEvaluationHarnessTest::class.java.getResourceAsStream(resource)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Evaluation resource $resource was not found")
    }
    return Json.parseToJsonElement(content).jsonObject
}

private fun parseDataset(root: JsonObject): EvalDataset {
    require(root.string("format") == "com.davidhayesbc.cocktails.bottle-evaluation-cases")
    require(root.int("schemaVersion") == 1)
    val cases = root.requiredArray("cases").map(::parseCase)
    require(cases.map(EvalCase::id).distinct().size == cases.size) { "Evaluation case IDs must be unique" }
    val groupSplits = cases.filter { it.kind == "reviewed_product" }.groupBy(EvalCase::groupId)
    require(groupSplits.values.all { group -> group.map(EvalCase::split).distinct().size == 1 }) {
        "All variants of one product family must stay in one evaluation split"
    }
    require(cases.all { it.kind in setOf("reviewed_product", "synthetic", "captured_regression") })
    require(cases.all { it.split in setOf("development", "holdout", "contract") })
    require(cases.all { it.sourceCoverage in setOf("complete", "excerpt", "blocked") })
    require(cases.filter { it.kind == "reviewed_product" }.all { it.split != "contract" })
    require(cases.filter { it.kind == "reviewed_product" }.all { it.sourceCoverage in setOf("complete", "blocked", "excerpt") })
    cases.filter { it.kind == "reviewed_product" && it.sourceCoverage == "complete" }.forEach(::validateFrozenSource)
    return EvalDataset(
        version = root.string("datasetVersion") ?: error("datasetVersion is required"),
        expectedReviewedProductCount = root.int("expectedReviewedProductCount") ?: 18,
        cases = cases,
    )
}

private fun parseCase(value: JsonElement): EvalCase {
    val root = value.jsonObject
    val sourceFacts = parseLabels(root["sourceFacts"] as? JsonObject ?: JsonObject(emptyMap()))
    val inventoryTruth = parseLabels(root["inventoryTruth"] as? JsonObject ?: JsonObject(emptyMap()))
    val hits = root.requiredArray("searchHits").map { hitValue ->
        val hit = hitValue.jsonObject
        hit.requiredString("sourceId") to WebSearchHit(hit.requiredString("title"), hit.requiredString("url"), hit.string("snippet"))
    }
    val pages = root.requiredArray("pages").map { pageValue ->
        val item = pageValue.jsonObject
        val identities = item.requiredArray("structuredIdentities").map { identityValue ->
            val identity = identityValue.jsonObject
            StructuredBottleIdentity(identity.requiredString("barcode"), identity.requiredString("productName"), identity.requiredString("evidence"))
        }
        val page = WebPageText(
            title = item.string("title"),
            sourceUrl = item.requiredString("sourceUrl"),
            markdown = item.requiredString("markdown"),
        provider = item.string("provider") ?: "Fixture replay",
            structuredBottleIdentities = identities,
        )
    EvalPage(item.requiredString("sourceId"), item.requiredString("hitUrl"), page, item.string("inputSha256"), item.string("contentLicense"))
    }
    val barcode = root.requiredString("barcode")
    require(com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup(barcode) != null) { "${root.string("id")} has invalid GTIN" }
    sourceFacts.forEach { (field, label) ->
        require(label.status in SourceLabelStatus.entries.map(Enum<*>::name)) { "Unknown source-label status for $field" }
        if (label.status == SourceLabelStatus.SUPPORTED.name) {
            require(!label.value.isNullOrBlank() && !label.evidence.isNullOrBlank()) { "Supported source facts need a value and exact evidence span" }
            require(pages.any { page -> page.page.markdown.contains(label.evidence!!, ignoreCase = true) || page.page.structuredBottleIdentities.any { it.evidence.contains(label.evidence!!, true) } }) {
                "${root.string("id")} $field evidence does not appear in its supplied source"
            }
        }
        if (label.status == SourceLabelStatus.ABSENT.name) {
            require(root.string("sourceCoverage") == "complete") { "An excerpt cannot label an absent field" }
        }
    }
    inventoryTruth.forEach { (field, label) ->
        require(label.status in InventoryLabelStatus.entries.map(Enum<*>::name)) { "Unknown inventory-label status for $field" }
    }
    return EvalCase(
        id = root.requiredString("id"), kind = root.requiredString("kind"), split = root.requiredString("split"),
        groupId = root.requiredString("groupId"), sourceCoverage = root.requiredString("sourceCoverage"), barcode = barcode,
        identityExpectation = IdentityExpectation.valueOf(root.requiredString("identityExpectation").uppercase(Locale.ROOT)),
        expectedSourceId = root.string("expectedSourceId"), inventoryTruth = inventoryTruth, sourceFacts = sourceFacts,
        searchHits = hits, pages = pages,
    )
}

private fun validateFrozenSource(case: EvalCase) {
    require(case.pages.isNotEmpty()) { "Executable reviewed products require frozen source bytes" }
    case.pages.forEach { page ->
        require(!page.contentLicense.isNullOrBlank()) { "Reviewed source ${page.sourceId} needs a rights/provenance note" }
        require(normalizePublicHttpsUrl(page.page.sourceUrl) != null) { "Frozen source URL must be public HTTPS" }
        val hash = frozenSourceInputSha256(page.page)
        require(page.inputSha256 == hash) { "Frozen extraction-input hash mismatch for ${page.sourceId}" }
    }
}

private fun parseLabels(root: JsonObject): Map<BottleFactField, LabeledValue> = root.mapNotNull { (key, value) ->
    val field = BottleFactField.entries.firstOrNull { it.wireValue == key } ?: error("Unknown evaluation fact field: $key")
    val item = value.jsonObject
    field to LabeledValue(item.requiredString("status").uppercase(Locale.ROOT), item.string("value"), item.string("evidence"))
}.toMap()

private fun parseVariants(root: JsonObject, expectedDatasetVersion: String): List<EvalVariant> {
    require(root.string("format") == "com.davidhayesbc.cocktails.bottle-evaluation-runs")
    require(root.int("schemaVersion") == 1)
    require(root.string("datasetVersion") == expectedDatasetVersion) { "Run manifest and case dataset versions differ" }
    val variants = root.requiredArray("variants").map { value ->
        val variant = value.jsonObject
        val responses = variant.requiredObject("responses").mapValues { (_, responseValue) ->
            val response = responseValue.jsonObject
            EvalResponse(response.requiredString("response"), response.string("finishReason") ?: "UNKNOWN", response.int("generatedTokens"), response.long("generationMillis"))
        }
        EvalVariant(
            id = variant.requiredString("id"), modelId = variant.requiredString("modelId"),
            quantization = variant.requiredString("quantization"), runtime = variant.requiredString("runtime"),
            promptVersion = variant.requiredString("promptVersion"), temperature = variant.double("temperature"),
            maxOutputTokens = variant.int("maxOutputTokens") ?: DEFAULT_LOCAL_MAX_OUTPUT_TOKENS,
            outputSource = variant.requiredString("outputSource"), timingSource = variant.requiredString("timingSource"),
            systemPrompt = variant.string("systemPrompt"), additionalInstructions = variant.string("additionalInstructions"), responses = responses,
        )
    }
    require(variants.map(EvalVariant::id).distinct().size == variants.size) { "Variant IDs must be unique" }
    require(variants.all { it.maxOutputTokens in 1..512 })
    require(variants.all { it.outputSource in setOf("synthetic_fixture", "sanitized_capture_replay") })
    require(variants.all { it.timingSource in setOf("not_measured", "device_observed") })
    require(variants.all { variant ->
        variant.outputSource != "synthetic_fixture" ||
            (variant.timingSource == "not_measured" && variant.responses.values.all { it.generationMillis == null })
    }) { "Synthetic replay fixtures cannot claim measured model timing" }
    require(variants.all { variant ->
        variant.timingSource != "device_observed" ||
            (variant.outputSource == "sanitized_capture_replay" && variant.responses.values.all { it.generationMillis != null && it.generationMillis >= 0 })
    }) { "Device timings require captured model responses and nonnegative observed durations" }
    return variants
}

private suspend fun evaluate(dataset: EvalDataset, variants: List<EvalVariant>): JsonObject {
    val variantReports = variants.map { variant ->
        val evaluated = dataset.cases.map { case -> evaluateCase(case, variant) }
        reportVariant(variant, evaluated)
    }
    val realCases = dataset.cases.filter { it.kind == "reviewed_product" }
    val executableReal = realCases.count { it.sourceCoverage == "complete" && it.pages.isNotEmpty() }
    val executableBySplit = realCases.filter { it.sourceCoverage == "complete" && it.pages.isNotEmpty() }.groupingBy(EvalCase::split).eachCount()
    val hasDevAndHoldout = executableBySplit.getOrDefault("development", 0) > 0 && executableBySplit.getOrDefault("holdout", 0) > 0
    return buildJsonObject {
        put("format", "com.davidhayesbc.cocktails.bottle-evaluation-report")
        put("schemaVersion", 1)
        put("datasetVersion", dataset.version)
        put("coverage", buildJsonObject {
            put("expectedReviewedProductCount", dataset.expectedReviewedProductCount)
            put("reviewedProductCasesLoaded", realCases.size)
            put("reviewedProductCasesExecutable", executableReal)
            put("reviewedProductCasesBlocked", (dataset.expectedReviewedProductCount - executableReal).coerceAtLeast(0))
            put("syntheticContractCases", dataset.cases.count { it.kind == "synthetic" })
        })
        put("qualityAggregateEligible", hasDevAndHoldout)
        put("qualityAggregate", if (hasDevAndHoldout) JsonPrimitive("computed_per_split") else JsonPrimitive("unavailable_requires_development_and_heldout_reviewed_products"))
        put("reviewedProductMetricsBySplit", buildJsonObject {
            realCases.map(EvalCase::split).distinct().sorted().forEach { split ->
                put(split, buildJsonObject {
                    put("loadedCases", realCases.count { it.split == split })
                    put("executableCases", executableBySplit.getOrDefault(split, 0))
                    put("status", if (executableBySplit.getOrDefault(split, 0) > 0) "ready" else "blocked_incomplete_source")
                })
            }
        })
        put("variants", buildJsonArray { variantReports.forEach(::add) })
        put("limitations", buildJsonArray {
            add(JsonPrimitive("Synthetic fixture results validate harness behavior and are excluded from product-quality aggregates."))
            if (executableReal == 0) add(JsonPrimitive("The 18-product reviewed dataset was not materialized; no real-product accuracy is computed."))
            if (variants.all { it.outputSource == "synthetic_fixture" }) add(JsonPrimitive("No model or live provider ran; fixture replay timings are not performance measurements."))
        })
    }
}

private suspend fun evaluateCase(case: EvalCase, variant: EvalVariant): EvaluatedCase {
    val response = variant.responses[case.id]
    val promptConfiguration = LocalBottleExtractionPromptConfiguration(
        version = variant.promptVersion,
        systemPrompt = variant.systemPrompt ?: LocalBottleExtractionPromptConfiguration.DEFAULT.systemPrompt,
        additionalInstructions = variant.additionalInstructions,
        maxOutputTokens = variant.maxOutputTokens,
    )
    var invokedModel = false
    var observedPrompt: String? = null
    val events = mutableListOf<BottleLookupDebugEvent>()
    val localExtractor = LocalBottleDetailExtractor(
        modelPath = { "/evaluation/not-a-real-model.gguf" },
        runner = LocalInferenceRunner { _, _, userPrompt, _ ->
            invokedModel = true
            observedPrompt = userPrompt
            response?.response ?: ""
        },
        promptConfiguration = promptConfiguration,
    )
    val pageByHit = case.pages.associateBy(EvalPage::hitUrl)
    val coordinator = ScannedBottleEnrichmentCoordinator(
        lookup = { BottleProviderCall(OpenFoodFactsLookupResult.NotFound, 404) },
        search = { BottleProviderCall(WebSearchResult.Found(case.searchHits.map(Pair<String, WebSearchHit>::second)), 200) },
        fetch = { url ->
            pageByHit[url]?.let { BottleProviderCall(WebPageResult.Found(it.page), 200) }
                ?: BottleProviderCall(WebPageResult.Unavailable, 404)
        },
        extract = { page, _, barcode ->
            val result = localExtractor.extractWithDiagnostics(page, variant.modelIdForApp(), barcode, events::add)
            BottleProviderCall(result, 200)
        },
    )
    val outcome = coordinator.processScannedBarcode(
        barcode = case.barcode,
        plan = ScannedBottlePlan(false, true, AutomaticBottleExtractor.ON_DEVICE),
        onDebugEvent = events::add,
    )
    // If a fixture has a response, it must either be consumed or be deliberately unreachable because retrieval abstained.
    require(!invokedModel || response != null) { "Unexpected model call without a replay response for ${case.id}/${variant.id}" }
    if (response == null) require(!invokedModel) { "Model ran without a recorded response for ${case.id}/${variant.id}" }
    return EvaluatedCase(case, outcome, events, invokedModel, observedPrompt, response.takeIf { invokedModel })
}

private fun EvalVariant.modelIdForApp(): String = when {
    modelId == "Qwen3-0.6B-Q8_0" -> LOCAL_MODEL_LABEL
    else -> LOCAL_MODEL_LABEL
}

private fun reportVariant(variant: EvalVariant, evaluated: List<EvaluatedCase>): JsonObject {
    val realEligible = evaluated.filter { it.case.kind == "reviewed_product" && it.case.sourceCoverage == "complete" }
    val synthetic = evaluated.filter { it.case.kind == "synthetic" }
    val realMetrics = scoreFacts(realEligible)
    val syntheticMetrics = scoreFacts(synthetic)
    val retrieval = retrievalMetrics(evaluated)
    val tokenLimitCount = evaluated.count { it.response?.finishReason.equals("TOKEN_LIMIT", true) }
    val malformedCount = evaluated.count { result -> result.extractionEvents.any { event ->
        event.stage == BottleLookupDebugStage.LOCAL_RESPONSE_PARSING && event.result == "REJECTED" &&
            "MALFORMED_MODEL_RESPONSE" in event.reasonCodes
    } }
    val wrongProductAcceptance = evaluated.count { item ->
        item.case.identityExpectation in setOf(IdentityExpectation.AMBIGUOUS, IdentityExpectation.NON_TARGET) &&
            (item.outcome.extraction as? BottleDetailExtractionResult.Found)?.suggestions?.facts?.isNotEmpty() == true
    }
    val wrongProductAcceptanceEligible = evaluated.count {
        it.case.identityExpectation in setOf(IdentityExpectation.AMBIGUOUS, IdentityExpectation.NON_TARGET)
    }
    val truthStats = scoreInventoryTruth(evaluated)
    return buildJsonObject {
        put("id", variant.id)
        put("model", variant.modelId)
        put("quantization", variant.quantization)
        put("runtime", variant.runtime)
        put("promptVersion", variant.promptVersion)
        val systemPrompt = variant.systemPrompt ?: LocalBottleExtractionPromptConfiguration.DEFAULT.systemPrompt
        put("systemPromptSha256", sha256(systemPrompt))
        put("additionalInstructionsSha256", variant.additionalInstructions?.let(::sha256) ?: "none")
        put("promptConfigurationSha256", promptConfigurationFingerprint(variant, systemPrompt))
        put("temperature", variant.temperature?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("maxOutputTokens", variant.maxOutputTokens)
        put("outputSource", variant.outputSource)
        put("timingSource", variant.timingSource)
        put("recordedSourceRunGenerationMillisMedian", measuredGenerationMedian(variant, evaluated)?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("sourceSupportedFactMetrics", realMetrics)
        put("sourceSupportedFactMetricsBySplit", buildJsonObject {
            realEligible.map { it.case.split }.distinct().sorted().forEach { split ->
                put(split, scoreFacts(realEligible.filter { it.case.split == split }))
            }
        })
        put("syntheticContractMetrics", syntheticMetrics)
        put("retrievalMetrics", retrieval)
        put("inventoryTruthMetrics", truthStats)
        put("wrongProductAcceptanceCount", wrongProductAcceptance)
        put("wrongProductAcceptanceEligibleCases", wrongProductAcceptanceEligible)
        put("wrongProductAcceptanceRate", if (wrongProductAcceptanceEligible == 0) JsonPrimitive("unavailable")
        else JsonPrimitive(wrongProductAcceptance.toDouble() / wrongProductAcceptanceEligible))
        put("malformedResponseCount", malformedCount)
        put("tokenLimitResultCount", tokenLimitCount)
        put("syntheticCaseResults", buildJsonArray { synthetic.forEach { add(reportCase(it)) } })
        put("reviewedProductCaseResults", buildJsonArray { realEligible.forEach { add(reportCase(it)) } })
    }
}

private fun scoreFacts(cases: List<EvaluatedCase>): JsonObject {
    var truePositive = 0
    var falsePositive = 0
    var falseNegative = 0
    var expectedSupported = 0
    var correctAbstentions = 0
    var abstentionOpportunities = 0
    cases.forEach { item ->
        val facts = (item.outcome.extraction as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty().associateBy(BottleFactEvidence::field)
        item.case.sourceFacts.forEach { (field, label) ->
            val prediction = facts[field]
            when (label.status) {
                SourceLabelStatus.SUPPORTED.name -> {
                    expectedSupported++
                    val correct = prediction != null && normalizeEvalFact(field, prediction.value) == normalizeEvalFact(field, label.value.orEmpty())
                    if (correct) truePositive++ else falseNegative++
                    if (prediction != null && !correct) falsePositive++
                }
                SourceLabelStatus.ABSENT.name, SourceLabelStatus.CONFLICTING.name -> {
                    abstentionOpportunities++
                    if (prediction == null) correctAbstentions++ else falsePositive++
                }
                else -> Unit
            }
        }
    }
    val precisionDenominator = truePositive + falsePositive
    return buildJsonObject {
        put("eligibleCases", cases.size)
        put("supportedFactsExpected", expectedSupported)
        put("supportedFactTruePositives", truePositive)
        put("supportedFactFalsePositives", falsePositive)
        put("supportedFactFalseNegatives", falseNegative)
        put("supportedFieldPrecision", if (precisionDenominator == 0) JsonPrimitive("unavailable") else JsonPrimitive(truePositive.toDouble() / precisionDenominator))
        put("supportedFieldRecall", if (expectedSupported == 0) JsonPrimitive("unavailable") else JsonPrimitive(truePositive.toDouble() / expectedSupported))
        put("correctAbstentions", correctAbstentions)
        put("abstentionOpportunities", abstentionOpportunities)
    }
}

private fun retrievalMetrics(cases: List<EvaluatedCase>) = buildJsonObject {
    val eligible = cases.filter { it.case.expectedSourceId != null }
    val retrieved = eligible.count { item ->
        val selectedUrl = item.outcome.selectedHit?.url
        item.case.pages.singleOrNull { it.sourceId == item.case.expectedSourceId }?.let { it.hitUrl == selectedUrl } == true &&
            (item.outcome.barcodeVerifiedInPage || item.outcome.barcodeVerifiedByStructuredProduct)
    }
    val topK = eligible.count { item -> item.case.searchHits.any { it.first == item.case.expectedSourceId } }
    put("eligibleCases", eligible.size)
    put("expectedSourcePresentInSearchHits", topK)
    put("expectedSourceVerifiedAndSelected", retrieved)
    put("verifiedSelectionRate", if (eligible.isEmpty()) JsonPrimitive("unavailable") else JsonPrimitive(retrieved.toDouble() / eligible.size))
}

private fun scoreInventoryTruth(cases: List<EvaluatedCase>) = buildJsonObject {
    var confirmed = 0
    var matched = 0
    cases.forEach { item ->
        val output = (item.outcome.extraction as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty().associateBy(BottleFactEvidence::field)
        item.case.inventoryTruth.forEach { (field, label) ->
            if (label.status == InventoryLabelStatus.CONFIRMED.name) {
                confirmed++
                if (output[field]?.let { normalizeEvalFact(field, it.value) == normalizeEvalFact(field, label.value.orEmpty()) } == true) matched++
            }
        }
    }
    put("confirmedInventoryFields", confirmed)
    put("exactMatches", matched)
    put("matchRate", if (confirmed == 0) JsonPrimitive("unavailable") else JsonPrimitive(matched.toDouble() / confirmed))
    put("note", "Inventory truth is scored separately from facts supported by each supplied source.")
}

private fun measuredGenerationMedian(variant: EvalVariant, evaluated: List<EvaluatedCase>): Long? {
    if (variant.timingSource != "device_observed") return null
    val values = evaluated.mapNotNull { item -> variant.responses[item.case.id]?.generationMillis }.sorted()
    return values.takeIf(List<Long>::isNotEmpty)?.let { it[it.size / 2] }
}

private fun reportCase(item: EvaluatedCase) = buildJsonObject {
    val extraction = item.outcome.extraction
    put("caseId", item.case.id)
    put("kind", item.case.kind)
    put("split", item.case.split)
    put("retrieval", when {
        item.outcome.search is WebSearchResult.Found && item.outcome.barcodeVerifiedInPage -> "verified_in_page"
        item.outcome.barcodeVerifiedByStructuredProduct -> "verified_structured_product"
        item.outcome.search is WebSearchResult.Found -> "searched_but_no_verified_source"
        else -> "no_search_result"
    })
    put("selectedSourceId", item.case.pages.firstOrNull { it.hitUrl == item.outcome.selectedHit?.url }?.sourceId ?: "unavailable")
    put("extractionResult", when (extraction) {
        is BottleDetailExtractionResult.Found -> "found"
        is BottleDetailExtractionResult.Rejected -> extraction.reason.debugCode
        is BottleDetailExtractionResult.LocalFailure -> extraction.code.wireValue
        BottleDetailExtractionResult.SetupRequired -> "setup_required"
        BottleDetailExtractionResult.InvalidModel -> "invalid_model"
        BottleDetailExtractionResult.AuthenticationRequired -> "authentication_required"
        BottleDetailExtractionResult.QuotaOrRateLimited -> "quota_or_rate_limited"
        BottleDetailExtractionResult.Unavailable -> "unavailable"
        BottleDetailExtractionResult.InvalidResponse -> "invalid_response"
        null -> "not_run"
    })
    put("predictedFields", buildJsonArray {
        (extraction as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty().forEach { add(JsonPrimitive(it.field.wireValue)) }
    })
    put("unresolvedFields", buildJsonArray {
        (extraction as? BottleDetailExtractionResult.Found)?.suggestions?.unresolvedFields.orEmpty().forEach { add(JsonPrimitive(it.wireValue)) }
    })
    put("inferenceExecutedByHarness", false)
    put("replayResponseConsumed", item.invokedModel)
    put("finishReason", item.response?.finishReason ?: "not_run")
    put("recordedSourceRunGenerationMillis", item.response?.generationMillis?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
    put("replayedUserPromptCharacters", item.promptText?.length?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
}

private fun writeReport(report: JsonObject) {
    val path = System.getProperty("bottle.eval.report")
    val output = if (path.isNullOrBlank()) File("build/reports/bottle-evaluation/report.json") else File(path)
    output.parentFile?.mkdirs()
    output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report), Charsets.UTF_8)
}

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** Length-prefix fields so separators inside source text cannot produce ambiguous hashes. */
private fun frozenSourceInputSha256(page: WebPageText): String {
    val fields = buildList {
        add(page.title.orEmpty())
        add(page.sourceUrl)
        add(page.provider)
        add(page.markdown)
        page.structuredBottleIdentities.forEach {
            add(it.barcode)
            add(it.productName)
            add(it.evidence)
        }
    }
    val canonical = buildString {
        fields.forEach { field -> append(field.length).append(':').append(field).append('\n') }
    }
    return sha256(canonical)
}

private fun promptConfigurationFingerprint(variant: EvalVariant, systemPrompt: String): String {
    val fields = listOf(
        variant.promptVersion,
        systemPrompt,
        variant.additionalInstructions.orEmpty(),
        variant.maxOutputTokens.toString(),
        variant.temperature?.toString().orEmpty(),
    )
    return sha256(fields.joinToString("") { "${it.length}:$it\n" })
}

private fun normalizeEvalFact(field: BottleFactField, raw: String): String = when (field) {
    BottleFactField.PRODUCT_NAME, BottleFactField.BRAND -> Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    BottleFactField.QUANTITY -> {
        val match = Regex("(?i)^\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*(ml|l)\\s*$").matchEntire(raw)
        match?.let {
            val amount = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return raw.trim().lowercase(Locale.ROOT)
            val ml = if (it.groupValues[2].equals("l", true)) amount * 1_000 else amount
            "${"%.2f".format(Locale.ROOT, ml).trimEnd('0').trimEnd('.')} ml"
        } ?: raw.trim().lowercase(Locale.ROOT)
    }
    BottleFactField.ABV_PERCENT -> {
        val value = Regex("^\\s*(\\d+(?:[.,]\\d{1,2})?)\\s*%?\\s*$").matchEntire(raw)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        value?.let { "${"%.2f".format(Locale.ROOT, it).trimEnd('0').trimEnd('.')}%" } ?: raw.trim().lowercase(Locale.ROOT)
    }
}

private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key).jsonArray
private fun JsonObject.requiredObject(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.requiredString(key: String): String = string(key) ?: error("Required field '$key' is missing")
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
