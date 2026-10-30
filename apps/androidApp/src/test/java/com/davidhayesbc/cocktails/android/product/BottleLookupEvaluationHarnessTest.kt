package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Offline replay harness. Every replay is sent through the production coordinator and extractor;
 * synthetic fixtures validate scoring mechanics only and are excluded from real-product aggregates.
 */
class BottleLookupEvaluationHarnessTest {
    @Test
    fun portableJobsContainProductionInputsButNoExpectedLabels() {
        val config = readLocalRuntimeConfig()
        val prompt = buildPortablePromptJobs("dataset-v1", "split-v1", config,
            eligibleCaseIds = listOf("product-a"), noInferenceCaseIds = emptyList(), noInferenceReasons = emptyMap(), tasks = listOf(buildJsonObject {
            put("caseId", "product-a")
            put("sourceId", "source-a")
            put("sourceInputSha256", "a".repeat(64))
            put("taskSha256", sha256("system\u0000user"))
            put("systemPrompt", "system")
            put("userPrompt", "user")
        }))
        assertEquals(false, prompt.getValue("answerKeyIncluded").jsonPrimitive.booleanOrNull)
        assertEquals("development", prompt.requiredString("evaluationSplit"))
        assertEquals(listOf("product-a"), prompt.getValue("caseIds").jsonArray.map { it.jsonPrimitive.content })
        val encoded = Json.encodeToString(JsonObject.serializer(), prompt)
        listOf("sourceFacts", "inventoryTruth", "confirmedValue", "householdBottleId").forEach { key ->
            assertFalse("Prompt jobs must not serialize the gold label field $key", encoded.contains("\"$key\""))
        }
        assertTrue(encoded.contains("\"systemPrompt\":\"system\""))
        assertTrue(encoded.contains("\"userPrompt\":\"user\""))
    }

    @Test
    fun exporterStopsWhenReviewedCasesHaveNoFrozenSources() = runBlocking {
        val dataset = parseDataset(readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json"))
        val output = kotlin.io.path.createTempDirectory("bottle-eval-empty-export").toFile()
        try {
            val noSources = dataset.copy(cases = dataset.cases.map { case ->
                if (case.kind == "reviewed_product") case.copy(sourceCoverage = "blocked", sourceFacts = emptyMap(), pages = emptyList()) else case
            })
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { exportProductionPromptJobs(noSources, File(output, "jobs.json"), "development") }
            }
            assertFalse(File(output, "jobs.json").exists())
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun evaluationSplitSelectionKeepsReviewedProductFamiliesGrouped() {
        val dataset = parseDataset(readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json"))
        val development = eligibleProductionPromptCases(dataset, "development")
        val holdout = eligibleProductionPromptCases(dataset, "holdout")
        assertEquals(3, development.size)
        assertEquals(4, holdout.size)
        assertTrue(development.all { it.split == "development" })
        assertTrue(holdout.all { it.split == "holdout" })
        assertEquals(2, holdout.count { it.groupId == "esquimalt-vermouth-family" })
        assertTrue(holdout.filter { it.groupId == "sons-of-vancouver-family" }.isEmpty())
        assertTrue(dataset.cases.filter { it.groupId == "sons-of-vancouver-family" }.all { it.split == "holdout" })
    }

    @Test
    fun developmentTitleOnlyBrandLabelsRemainUnknownAndAreNotForcedIntoSourceGold() {
        val dataset = parseDataset(readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json"))
        val development = dataset.cases.filter {
            it.kind == "reviewed_product" && it.split == "development" && it.sourceFacts.isNotEmpty()
        }
        assertEquals(setOf("banff-ice-vodka", "dubonnet-rouge", "wild-turkey-101"), development.map { it.id }.toSet())
        development.forEach { case ->
            val brand = case.sourceFacts[BottleFactField.BRAND]
            assertEquals("Brand should not be treated as source gold from a title alone for ${case.id}", SourceLabelStatus.UNKNOWN.name, brand?.status)
            assertEquals(null, brand?.value)
            assertEquals(null, brand?.evidence)
        }
    }

    @Test
    fun archivedV1AndCurrentCandidatePromptsAreExplicitlyVersionedForPairedEvaluation() {
        val v1 = promptConfigurationForVersion("bottle-facts-v1", DEFAULT_LOCAL_MAX_OUTPUT_TOKENS)
        val candidate = promptConfigurationForVersion(DEFAULT_LOCAL_PROMPT_VERSION, DEFAULT_LOCAL_MAX_OUTPUT_TOKENS)
        assertEquals("bottle-facts-v1", v1.version)
        assertTrue(v1.systemPrompt.contains("Every evidence quote must be copied exactly"))
        assertEquals(DEFAULT_LOCAL_PROMPT_VERSION, candidate.version)
        assertTrue(candidate.systemPrompt.contains("include the explicit unit exactly as shown"))
        assertEquals(v1.maxOutputTokens, candidate.maxOutputTokens)
    }

    @Test
    fun pairedHoldoutJobsUseSameFrozenCasesAndUserInputsWithOnlyPromptVersionChanged() = runBlocking {
        val dataset = parseDataset(readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json"))
        val holdout = eligibleProductionPromptCases(dataset, "holdout")
        val v1Prompt = promptConfigurationForVersion("bottle-facts-v1", DEFAULT_LOCAL_MAX_OUTPUT_TOKENS)
        val candidatePrompt = LocalBottleExtractionPromptConfiguration.DEFAULT
        val baselineConfig = readLocalRuntimeConfig(v1Prompt.version)
        val candidateConfig = readLocalRuntimeConfig(candidatePrompt.version)
        val baseline = captureProductionPromptBatch(dataset, holdout, baselineConfig, v1Prompt)
        val candidate = captureProductionPromptBatch(dataset, holdout, candidateConfig, candidatePrompt)

        assertEquals(
            setOf("auchentoshan-three-wood", "esquimalt-dry-vermouth", "esquimalt-rosso-sweet-vermouth", "giffard-grenadine-syrup"),
            baseline.eligibleCaseIds.toSet(),
        )
        assertEquals(baseline.eligibleCaseIds, candidate.eligibleCaseIds)
        assertEquals(baseline.noInferenceCaseIds, candidate.noInferenceCaseIds)
        val baselineTasks = baseline.tasks.associateBy { it.requiredString("caseId") }
        val candidateTasks = candidate.tasks.associateBy { it.requiredString("caseId") }
        assertEquals(baselineTasks.keys, candidateTasks.keys)
        baselineTasks.forEach { (caseId, oldTask) ->
            val newTask = candidateTasks.getValue(caseId)
            assertEquals(oldTask.requiredString("sourceId"), newTask.requiredString("sourceId"))
            assertEquals(oldTask.requiredString("sourceInputSha256"), newTask.requiredString("sourceInputSha256"))
            assertEquals(oldTask.requiredString("userPrompt"), newTask.requiredString("userPrompt"))
            assertTrue(oldTask.requiredString("systemPrompt") != newTask.requiredString("systemPrompt"))
        }
        val oldBaseline = baselineConfig.getValue("androidBaseline").jsonObject
        val newBaseline = candidateConfig.getValue("androidBaseline").jsonObject
        assertEquals(JsonObject(oldBaseline - "promptVersion"), JsonObject(newBaseline - "promptVersion"))
    }

    @Test
    fun developmentPromptJobsContainProductHeadingsButNotHeldoutCases() = runBlocking {
        val dataset = parseDataset(readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json"))
        val development = eligibleProductionPromptCases(dataset, "development")
        val batch = captureProductionPromptBatch(dataset, development, readLocalRuntimeConfig())
        assertEquals(listOf("banff-ice-vodka", "dubonnet-rouge", "wild-turkey-101"), batch.eligibleCaseIds)
        assertTrue(batch.tasks.none { it.requiredString("caseId").startsWith("esquimalt") || it.requiredString("caseId") == "auchentoshan-three-wood" })
        batch.tasks.forEach { task ->
            val prompt = task.requiredString("userPrompt")
            val expectedHeading = when (task.requiredString("caseId")) {
                "banff-ice-vodka" -> "Banff Ice 750ml"
                "dubonnet-rouge" -> "DUBONNET ROUGE"
                "wild-turkey-101" -> "Wild Turkey 101 750ml"
                else -> error("Unexpected development case")
            }
            assertTrue("The model prompt must contain the source product heading", prompt.contains(expectedHeading))
        }
    }

    @Test
    fun hostResultImportRequiresExactJobHashAndMarksDesktopInferenceOrigin(): Unit = runBlocking {
        val config = readLocalRuntimeConfig()
        val page = WebPageText(
            title = "Sample Spirit 5011333150725",
            sourceUrl = "https://products.example/items/sample-spirit",
            markdown = "UPC: 5011333150725\n### Sample Spirit\nBrand: Sample House",
            provider = "Frozen test source",
        )
        val sourceHash = frozenSourceInputSha256(page)
        val evalPage = EvalPage("source-a", page.sourceUrl, page, "2026-10-29T00:00:00Z", "test fixture",
            sourceHash, "short factual test excerpt")
        val case = EvalCase(
            id = "sample-product", kind = "reviewed_product", split = "development", groupId = "sample-family",
            sourceCoverage = "complete", sourceCaptureStatus = "fixture", sourceSelectionMode = "independent_product_source",
            barcode = "5011333150725", identityExpectation = IdentityExpectation.TARGET, expectedSourceId = "source-a",
            inventoryTruth = emptyMap(),
            sourceFacts = mapOf(BottleFactField.PRODUCT_NAME to LabeledValue("SUPPORTED", "Sample Spirit", "### Sample Spirit")),
            researchTargets = emptyList(), searchHits = emptyList(), pages = listOf(evalPage),
        )
        val deterministicCase = sampleDeterministicCase("deterministic-case")
        val abstainedCase = sampleInferenceCase("abstained-case")
            .copy(searchHits = emptyList(), sourceSelectionMode = "barcode_search")
        val dataset = EvalDataset("dataset-v1", "split-v1", 3, listOf(case, deterministicCase, abstainedCase))
        val batch = captureProductionPromptBatch(dataset, dataset.cases, config)
        val task = batch.tasks.single()
        val jobs = buildPortablePromptJobs(
            dataset.version, dataset.splitPlan, config,
            eligibleCaseIds = batch.eligibleCaseIds,
            noInferenceCaseIds = batch.noInferenceCaseIds,
            noInferenceReasons = batch.noInferenceReasons,
            tasks = batch.tasks,
        )
        val baseline = config.getValue("androidBaseline").jsonObject
        val configSha = jobs.requiredString("configSha256")
        val resultJson = buildJsonObject {
            put("format", "com.davidhayesbc.cocktails.bottle-evaluation-host-results"); put("schemaVersion", 1)
            put("jobFileSha256", "pending"); put("configSha256", configSha)
            put("modelSha256", config.getValue("model").jsonObject.requiredString("sha256"))
            put("llamaCppCommit", config.getValue("llamaCpp").jsonObject.requiredString("commit"))
            put("runtimeVersion", "llama.cpp d854d98 build fixture"); put("runtimeBackend", "CUDA")
            put("devices", "CUDA0"); put("gpuOffloadedLayers", "20/20"); put("hostRuntime", "fixture only")
            put("inferenceSettings", buildJsonObject {
                put("contextTokens", baseline.getValue("contextTokens")); put("batchTokens", baseline.getValue("batchTokens"))
                put("maxOutputTokens", baseline.getValue("maxOutputTokens")); put("temperature", baseline.getValue("temperature"))
                put("grammarSha256", sha256(baseline.requiredString("grammar"))); put("samplers", baseline.getValue("samplers"))
            })
            put("responses", buildJsonArray { add(buildJsonObject {
                put("caseId", case.id); put("taskSha256", task.requiredString("taskSha256")); put("sourceInputSha256", sourceHash)
                put("modelOutput", "{\"facts\":[]}"); put("finishReason", "END_OF_SEQUENCE")
                put("generatedTokens", 2); put("generationMillis", 10); put("promptEvaluationMillis", 2)
                put("promptTokens", 8); put("requestRoundTripMillis", 12)
            }) })
        }
        val expectedResultKeys = setOf("format", "schemaVersion", "jobFileSha256", "configSha256", "modelSha256", "llamaCppCommit", "runtimeVersion", "runtimeBackend", "devices", "gpuOffloadedLayers", "hostRuntime", "inferenceSettings", "responses")
        assertEquals(expectedResultKeys, resultJson.keys)

        val temp = kotlin.io.path.createTempDirectory("bottle-eval-import").toFile()
        try {
            val jobsFile = File(temp, "jobs.json")
            jobsFile.writeText(Json.encodeToString(JsonObject.serializer(), jobs), Charsets.UTF_8)
            val jobSha = sha256(jobsFile.readBytes())
            val boundResult = buildJsonObject {
                resultJson.forEach { (key, value) -> put(key, if (key == "jobFileSha256") JsonPrimitive(jobSha) else value) }
            }
            val resultsFile = File(temp, "results.json")
            resultsFile.writeText(Json.encodeToString(JsonObject.serializer(), boundResult), Charsets.UTF_8)
            val imported = importHostInferenceResults(dataset, jobsFile, resultsFile)
            assertEquals(listOf("abstained-case", "deterministic-case", case.id), imported.caseIds)
            assertEquals("host_model_execution_result", imported.variant.outputSource)
            assertEquals("host_model_execution", imported.variant.responses.getValue(case.id).origin)

            val alteredUserPrompt = "edited host-side prompt"
            val alteredTaskSha = sha256("${task.requiredString("systemPrompt")}\u0000$alteredUserPrompt")
            val alteredTask = buildJsonObject {
                task.forEach { (key, value) ->
                    when (key) {
                        "userPrompt" -> put(key, alteredUserPrompt)
                        "taskSha256" -> put(key, alteredTaskSha)
                        else -> put(key, value)
                    }
                }
            }
            val alteredJobs = buildJsonObject {
                jobs.forEach { (key, value) ->
                    if (key == "tasks") put(key, buildJsonArray { add(alteredTask) }) else put(key, value)
                }
            }
            jobsFile.writeText(Json.encodeToString(JsonObject.serializer(), alteredJobs), Charsets.UTF_8)
            val alteredJobSha = sha256(jobsFile.readBytes())
            val alteredResult = buildJsonObject {
                boundResult.forEach { (key, value) ->
                    when (key) {
                        "jobFileSha256" -> put(key, alteredJobSha)
                        "responses" -> put(key, buildJsonArray {
                            value.jsonArray.forEach { response ->
                                add(buildJsonObject {
                                    response.jsonObject.forEach { (responseKey, responseValue) ->
                                        put(responseKey, if (responseKey == "taskSha256") JsonPrimitive(alteredTaskSha) else responseValue)
                                    }
                                })
                            }
                        })
                        else -> put(key, value)
                    }
                }
            }
            resultsFile.writeText(Json.encodeToString(JsonObject.serializer(), alteredResult), Charsets.UTF_8)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { importHostInferenceResults(dataset, jobsFile, resultsFile) }
            }

            jobsFile.writeText(Json.encodeToString(JsonObject.serializer(), jobs), Charsets.UTF_8)
            resultsFile.writeText(Json.encodeToString(JsonObject.serializer(), boundResult), Charsets.UTF_8)
            jobsFile.appendText(" ")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { importHostInferenceResults(dataset, jobsFile, resultsFile) }
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun productionPromptBatchSeparatesModelJobsFromDeterministicAndAbstainedCases() = runBlocking {
        val modelCase = sampleInferenceCase("model-case")
        val deterministicCase = sampleDeterministicCase("deterministic-case")
        val abstainedCase = modelCase.copy(id = "abstained-case", searchHits = emptyList(), sourceSelectionMode = "barcode_search")
        val dataset = EvalDataset("dataset-v1", "split-v1", 3, listOf(modelCase, deterministicCase, abstainedCase))
        val batch = captureProductionPromptBatch(dataset, dataset.cases, readLocalRuntimeConfig())

        assertEquals(listOf("abstained-case", "deterministic-case", "model-case"), batch.eligibleCaseIds)
        assertEquals(listOf("model-case"), batch.tasks.map { it.requiredString("caseId") })
        assertEquals(listOf("abstained-case", "deterministic-case"), batch.noInferenceCaseIds)
        assertEquals(
            mapOf("abstained-case" to "RETRIEVAL_ABSTENTION", "deterministic-case" to "DETERMINISTIC_SOURCE"),
            batch.noInferenceReasons,
        )
    }

    @Test
    fun allNoInferenceBatchIsRepresentedWithoutCreatingModelTasks() = runBlocking {
        val deterministic = sampleDeterministicCase("deterministic-case")
        val abstained = sampleInferenceCase("abstained-case").copy(searchHits = emptyList(), sourceSelectionMode = "barcode_search")
        val dataset = EvalDataset("dataset-v1", "split-v1", 2, listOf(deterministic, abstained))
        val batch = captureProductionPromptBatch(dataset, dataset.cases, readLocalRuntimeConfig())
        val jobs = buildPortablePromptJobs(
            dataset.version, dataset.splitPlan, readLocalRuntimeConfig(),
            batch.eligibleCaseIds, batch.noInferenceCaseIds, batch.noInferenceReasons, batch.tasks,
        )

        assertTrue(batch.tasks.isEmpty())
        assertEquals(emptyList<String>(), jobs.requiredArray("caseIds").map { it.jsonPrimitive.content })
        assertEquals(batch.eligibleCaseIds, jobs.requiredArray("eligibleCaseIds").map { it.jsonPrimitive.content })
        assertEquals(batch.eligibleCaseIds, jobs.requiredArray("noInferenceCaseIds").map { it.jsonPrimitive.content })
        assertEquals(
            setOf("DETERMINISTIC_SOURCE", "RETRIEVAL_ABSTENTION"),
            jobs.requiredObject("noInferenceReasons").values.map { it.jsonPrimitive.content }.toSet(),
        )
        assertTrue(jobs.requiredArray("tasks").isEmpty())
    }

    @Test
    fun replayedTokenLimitedValidJsonUsesProductionTruncationFailurePath() = runBlocking {
        val case = sampleInferenceCase("truncated-case")
        val validLookingJson = """{"facts":[{"field":"product_name","value":"Sample Spirit","evidence":"Sample Spirit"}]}"""
        val variant = localQwenVariant().copy(responses = mapOf(case.id to EvalResponse(
            response = validLookingJson,
            finishReason = "TOKEN_LIMIT",
            generatedTokens = DEFAULT_LOCAL_MAX_OUTPUT_TOKENS,
            generationMillis = 1,
        )))

        val evaluated = evaluateCase(case, variant, localServer = null)

        assertTrue(evaluated.outcome.extraction is BottleDetailExtractionResult.LocalFailure)
        assertEquals(
            LocalInferenceFailureCode.MODEL_OUTPUT_TRUNCATED,
            (evaluated.outcome.extraction as BottleDetailExtractionResult.LocalFailure).code,
        )
        assertEquals("TOKEN_LIMIT", evaluated.response?.finishReason)
        assertFalse(evaluated.extractionEvents.any {
            it.stage == BottleLookupDebugStage.LOCAL_RESPONSE_PARSING && it.result == "RUNNING"
        })
    }

    @Test
    fun localLlamaRunnerUsesLoopbackLegacyTemplateAndAndroidSamplerContract() = runBlocking {
        assertPinnedRuntimeMatchesAndroidNativeSource()
        val runtimeConfig = readLocalRuntimeConfig()
        val baseline = runtimeConfig.getValue("androidBaseline").jsonObject
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val result = runLocalLlamaServer(
            "http://127.0.0.1:9931", "system marker", "/no_think\nsource marker", 160,
            baseline.getValue("grammar").jsonPrimitive.content,
        ) { url, body, onRoundTrip ->
            requests += url to body
            onRoundTrip(12L)
            if (url.endsWith("/apply-template")) {
                buildJsonObject { put("prompt", "test-rendered-prompt") }
            } else {
                buildJsonObject {
                    put("content", "{\"facts\":[]}")
                    put("stop_type", "limit")
                    put("timings", buildJsonObject {
                        put("predicted_n", 160); put("predicted_ms", 1234.8); put("prompt_ms", 20.6); put("prompt_n", 44)
                    })
                }
            }
        }.response
        val templateBody = requests[0].second
        val completionBody = requests[1].second
        assertTrue(requests[0].first.endsWith("/apply-template"))
        assertTrue(requests[1].first.endsWith("/completion"))
        val messages = templateBody.getValue("messages").jsonArray
        assertEquals("system marker", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("/no_think\nsource marker", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("test-rendered-prompt", completionBody.getValue("prompt").jsonPrimitive.content)
        assertEquals(160, completionBody.getValue("n_predict").jsonPrimitive.intOrNull)
        assertEquals(-1, completionBody.getValue("seed").jsonPrimitive.intOrNull)
        assertEquals(false, completionBody.getValue("cache_prompt").jsonPrimitive.booleanOrNull)
        assertEquals(0.3, completionBody.getValue("temperature").jsonPrimitive.doubleOrNull!!, 0.0001)
        assertEquals(baseline.getValue("grammar").jsonPrimitive.content, completionBody.getValue("grammar").jsonPrimitive.content)
        assertFalse(completionBody.containsKey("json_schema"))
        assertEquals(
            listOf("penalties", "dry", "top_n_sigma", "top_k", "typ_p", "top_p", "min_p", "xtc", "temperature"),
            completionBody.getValue("samplers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(listOf("\n", ":", "\"", "*"), completionBody.getValue("dry_sequence_breakers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("TOKEN_LIMIT", result.finishReason)
        assertEquals(160, result.generatedTokens)
        assertEquals(1234L, result.generationMillis)
        assertEquals(20L, result.promptEvaluationMillis)
        assertEquals(44, result.promptTokens)
        assertEquals(12L, result.requestRoundTripMillis)
        assertEquals("{\"facts\":[]}", result.response)
    }

    @Test
    fun localModelEndpointRejectsNonLoopbackAndCredentialBearingUrls() {
        listOf("https://127.0.0.1:9931", "http://example.com:9931", "http://user@127.0.0.1:9931", "http://127.0.0.1:9931?token=x")
            .forEach { endpoint ->
                assertThrows(IllegalArgumentException::class.java) { validateLoopbackEndpoint(endpoint) }
            }
        assertEquals("http://127.0.0.1:9931", validateLoopbackEndpoint("http://127.0.0.1:9931/"))
    }

    @Test
    fun offlineReplayUsesProductionLookupAndExtractionAndWritesCoverageGatedReport() = runBlocking {
        val casesRoot = readInput("bottle.eval.cases", "/bottle-evaluation/v1/cases.json")
        val runsRoot = readInput("bottle.eval.runs", "/bottle-evaluation/v1/replay-runs.json")
        val dataset = parseDataset(casesRoot)
        val exportPath = System.getProperty("bottle.eval.export.jobs")?.takeIf(String::isNotBlank)
        if (exportPath != null) {
            exportProductionPromptJobs(dataset, File(exportPath))
            return@runBlocking
        }
        val importJobsPath = System.getProperty("bottle.eval.import.jobs")?.takeIf(String::isNotBlank)
        val importResultsPath = System.getProperty("bottle.eval.import.results")?.takeIf(String::isNotBlank)
        require((importJobsPath == null) == (importResultsPath == null)) {
            "Host result import requires both bottle.eval.import.jobs and bottle.eval.import.results."
        }
        if (importJobsPath != null && importResultsPath != null) {
            val imported = importHostInferenceResults(dataset, File(importJobsPath), File(importResultsPath))
            val selected = dataset.copy(cases = imported.caseIds.map { id -> dataset.cases.single { it.id == id } })
            writeReport(evaluate(selected, listOf(imported.variant)))
            return@runBlocking
        }
        val localServer = System.getProperty("bottle.eval.local.server")?.takeIf(String::isNotBlank)
        if (localServer != null) {
            val selectedIds = System.getProperty("bottle.eval.local.caseIds")
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            val selectedCases = dataset.cases.filter { case ->
                case.kind == "reviewed_product" && case.sourceCoverage == "complete" && case.pages.isNotEmpty() &&
                    case.sourceFacts.values.any { it.status != SourceLabelStatus.UNKNOWN.name } &&
                    (selectedIds == null || case.id in selectedIds)
            }
            require(selectedCases.isNotEmpty()) {
                "Local Qwen evaluation requires at least one reviewed product case with complete frozen source input; no inference was sent."
            }
            require(selectedIds == null || selectedCases.map(EvalCase::id).toSet() == selectedIds) {
                "Every requested local case ID must identify a complete reviewed-product source case."
            }
            require(selectedIds == null || selectedCases.all { it.split == "development" }) {
                "A selected subset may include development cases only; evaluate the full frozen manifest to score holdout."
            }
            val localVariant = localQwenVariant()
            val localReport = evaluate(dataset.copy(cases = selectedCases), listOf(localVariant), localServer)
            writeReport(localReport)
            return@runBlocking
        }
        val variants = parseVariants(runsRoot, dataset.version)
        val report = evaluate(dataset, variants)
        writeReport(report)

        val coverage = report.getValue("coverage").jsonObject
        assertEquals(18, coverage.getValue("expectedReviewedProductCount").jsonPrimitive.intOrNull)
        assertEquals(18, coverage.getValue("reviewedProductCasesLoaded").jsonPrimitive.intOrNull)
        assertEquals(7, coverage.getValue("reviewedProductCasesExecutable").jsonPrimitive.intOrNull)
        assertEquals(11, coverage.getValue("reviewedProductCasesBlocked").jsonPrimitive.intOrNull)
        assertEquals(18, dataset.cases.count { it.kind == "reviewed_product" })
        assertEquals(5, dataset.cases.count { it.kind == "synthetic" })
        assertEquals(23, dataset.cases.size)
        assertEquals("product-family-holdout-2026-10-29-v1", report.getValue("splitPlan").jsonPrimitive.content)
        assertEquals(12, coverage.getValue("reviewedProductResearchTargets").jsonPrimitive.intOrNull)
        val reviewed = dataset.cases.filter { it.kind == "reviewed_product" }.associateBy(EvalCase::id)
        assertEquals(InventoryLabelStatus.UNRESOLVED.name, reviewed.getValue("giffard-grenadine-syrup")
            .inventoryTruth.getValue(BottleFactField.ABV_PERCENT).status)
        assertEquals(InventoryLabelStatus.UNRESOLVED.name, reviewed.getValue("sons-junicopia-gin")
            .inventoryTruth.getValue(BottleFactField.ABV_PERCENT).status)
        assertEquals(7, reviewed.values.count { it.sourceFacts.isNotEmpty() && it.pages.isNotEmpty() })
        assertEquals(11, reviewed.values.count { it.sourceFacts.isEmpty() && it.pages.isEmpty() })
        assertEquals(3, reviewed.values.count { it.pages.isNotEmpty() && it.split == "development" })
        assertEquals(4, reviewed.values.count { it.pages.isNotEmpty() && it.split == "holdout" })
        reviewed.values.filter { it.sourceCaptureStatus == "captured_http_excerpt" }.forEach { case ->
            assertTrue(case.pages.all { it.sourceResponseSha256?.matches(Regex("[a-f0-9]{64}")) == true })
        }
        assertEquals("ABSENT", reviewed.getValue("giffard-grenadine-syrup").sourceFacts
            .getValue(BottleFactField.ABV_PERCENT).status)
        assertEquals("UNRESOLVED", reviewed.getValue("giffard-grenadine-syrup").inventoryTruth
            .getValue(BottleFactField.ABV_PERCENT).status)
        assertEquals("holdout", reviewed.getValue("esquimalt-dry-vermouth").split)
        assertEquals("holdout", reviewed.getValue("esquimalt-rosso-sweet-vermouth").split)
        assertEquals("holdout", reviewed.getValue("sons-junicopia-gin").split)
        assertEquals(setOf("holdout"), reviewed.values.filter { it.groupId == "sons-of-vancouver-family" }.map(EvalCase::split).toSet())
        assertEquals(setOf("holdout"), reviewed.values.filter { it.groupId == "esquimalt-vermouth-family" }.map(EvalCase::split).toSet())
        assertEquals(2, variants.size)
        assertFalse(report.getValue("qualityAggregateEligible").jsonPrimitive.booleanOrNull == true)

        val variantReports = report.getValue("variants").jsonArray
        assertEquals(2, variantReports.size)
        val first = variantReports.first().jsonObject
        assertEquals(0, first.getValue("sourceSupportedFactMetrics").jsonObject
            .getValue("eligibleCases").jsonPrimitive.intOrNull)
        assertEquals(7, first.getValue("reviewedProductCasesSkippedWithoutRecordedModelOutput").jsonArray.size)
        val firstCase = first.getValue("syntheticCaseResults").jsonArray.first().jsonObject
        assertEquals(false, firstCase.getValue("inferenceExecutedByHarness").jsonPrimitive.booleanOrNull)
        assertEquals(true, firstCase.getValue("replayResponseConsumed").jsonPrimitive.booleanOrNull)
        assertEquals(5, first.getValue("syntheticCaseResults").jsonArray.size)
        assertEquals(0, first.getValue("wrongProductAcceptanceCount").jsonPrimitive.intOrNull)
        assertEquals(1, first.getValue("tokenLimitResultCount").jsonPrimitive.intOrNull)
        assertEquals(9, first.getValue("syntheticContractMetrics").jsonObject.getValue("supportedFactTruePositives").jsonPrimitive.intOrNull)
        val second = variantReports.last().jsonObject
        assertEquals(7, second.getValue("syntheticContractMetrics").jsonObject.getValue("supportedFactTruePositives").jsonPrimitive.intOrNull)
        assertEquals(2, second.getValue("syntheticContractMetrics").jsonObject.getValue("supportedFactFalseNegatives").jsonPrimitive.intOrNull)
        assertEquals("not_measured", first.getValue("timingSource").jsonPrimitive.content)
        assertEquals("unavailable_requires_recorded_model_outputs_in_development_and_holdout", report.getValue("qualityAggregate").jsonPrimitive.content)
        val syntheticResults = first.getValue("syntheticCaseResults").jsonArray.map { it.jsonObject }.associateBy {
            it.getValue("caseId").jsonPrimitive.content
        }
        assertEquals("harbor-light-product-page", syntheticResults.getValue("synthetic-partial-table-fallback-fetch")
            .getValue("selectedSourceId").jsonPrimitive.content)
        assertEquals("not_run", syntheticResults.getValue("synthetic-conflicting-barcode-variants")
            .getValue("finishReason").jsonPrimitive.content)
        assertEquals(listOf("product_name"), syntheticResults.getValue("synthetic-related-product-injection")
            .getValue("predictedFields").jsonArray.map { it.jsonPrimitive.content })
        val independent = syntheticResults.getValue("synthetic-independent-product-source-extraction")
        assertEquals("not_evaluated_manual_source_selection", independent.getValue("retrieval").jsonPrimitive.content)
        assertEquals("manually-selected-salal-page", independent.getValue("selectedSourceId").jsonPrimitive.content)
        assertEquals(true, independent.getValue("replayResponseConsumed").jsonPrimitive.booleanOrNull)
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
    val capturedAtUtc: String?,
    val selectionMethod: String?,
    val inputSha256: String?,
    val contentLicense: String?,
    val sourceResponseSha256: String? = null,
)
private data class EvalCase(
    val id: String,
    val kind: String,
    val split: String,
    val groupId: String,
    val sourceCoverage: String,
    val sourceCaptureStatus: String,
    val sourceSelectionMode: String,
    val barcode: String,
    val identityExpectation: IdentityExpectation,
    val expectedSourceId: String?,
    val inventoryTruth: Map<BottleFactField, LabeledValue>,
    val sourceFacts: Map<BottleFactField, LabeledValue>,
    val researchTargets: List<String>,
    val searchHits: List<Pair<String, WebSearchHit>>,
    val pages: List<EvalPage>,
)
private data class EvalDataset(val version: String, val splitPlan: String, val expectedReviewedProductCount: Int, val cases: List<EvalCase>)
private data class EvalResponse(
    val response: String,
    val finishReason: String,
    val generatedTokens: Int?,
    val generationMillis: Long?,
    val requestRoundTripMillis: Long? = null,
    val promptEvaluationMillis: Long? = null,
    val promptTokens: Int? = null,
    val origin: String = "fixture_replay",
)
private data class LocalServerCompletion(val response: EvalResponse)
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
    val modelArtifactSha256: String? = null,
    val llamaCppCommit: String? = null,
    val runtimeBackend: String? = null,
)
private data class EvaluatedCase(
    val case: EvalCase,
    val outcome: ScannedBottleOutcome,
    val extractionEvents: List<BottleLookupDebugEvent>,
    val invokedModel: Boolean,
    val promptText: String?,
    val response: EvalResponse?,
    val inferenceExecuted: Boolean,
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
    val expectedProducts = root.int("expectedReviewedProductCount") ?: 18
    require(cases.count { it.kind == "reviewed_product" } <= expectedProducts) {
        "Loaded reviewed-product cases exceed the dataset's declared product count"
    }
    return EvalDataset(
        version = root.string("datasetVersion") ?: error("datasetVersion is required"),
        splitPlan = root.string("splitPlan") ?: "unspecified",
        expectedReviewedProductCount = expectedProducts,
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
        EvalPage(
            item.requiredString("sourceId"), item.requiredString("hitUrl"), page,
            item.string("capturedAtUtc"), item.string("selectionMethod"),
            item.string("inputSha256"), item.string("contentLicense"), item.string("sourceResponseSha256"),
        )
    }
    val barcode = root.requiredString("barcode")
    val sourceSelectionMode = root.string("sourceSelectionMode") ?: "barcode_search"
    require(sourceSelectionMode in setOf("barcode_search", "independent_product_source"))
    if (sourceSelectionMode == "independent_product_source") {
        val selectedId = root.string("expectedSourceId") ?: error("Independent extraction requires a manually selected source ID")
        require(pages.any { it.sourceId == selectedId }) { "Independent extraction source ID must exist in the supplied pages" }
        require(hits.isEmpty()) { "Independent source extraction must not include retrieval hits" }
    }
    require(com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup(barcode) != null) { "${root.string("id")} has invalid GTIN" }
    sourceFacts.forEach { (field, label) ->
        require(label.status in SourceLabelStatus.entries.map(Enum<*>::name)) { "Unknown source-label status for $field" }
        if (label.status == SourceLabelStatus.SUPPORTED.name) {
            require(!label.value.isNullOrBlank() && !label.evidence.isNullOrBlank()) { "Supported source facts need a value and exact evidence span" }
            require(pages.any { page ->
                page.page.markdown.contains(label.evidence!!, ignoreCase = true) ||
                    page.page.title.orEmpty().contains(label.evidence!!, ignoreCase = true) ||
                    page.page.structuredBottleIdentities.any { it.evidence.contains(label.evidence!!, true) }
            }) {
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
        groupId = root.requiredString("groupId"), sourceCoverage = root.requiredString("sourceCoverage"),
        sourceCaptureStatus = root.string("sourceCaptureStatus") ?: "fixture",
        sourceSelectionMode = sourceSelectionMode,
        barcode = barcode,
        identityExpectation = IdentityExpectation.valueOf(root.requiredString("identityExpectation").uppercase(Locale.ROOT)),
        expectedSourceId = root.string("expectedSourceId"), inventoryTruth = inventoryTruth, sourceFacts = sourceFacts,
        researchTargets = root["researchTargets"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.also { targets ->
            require(targets.all { normalizePublicHttpsUrl(it) != null }) { "Research targets must be public HTTPS URLs" }
        },
        searchHits = hits, pages = pages,
    )
}

private fun validateFrozenSource(case: EvalCase) {
    require(case.pages.isNotEmpty()) { "Executable reviewed products require frozen source bytes" }
    case.pages.forEach { page ->
        require(!page.contentLicense.isNullOrBlank()) { "Reviewed source ${page.sourceId} needs a rights/provenance note" }
        require(!page.capturedAtUtc.isNullOrBlank()) { "Reviewed source ${page.sourceId} needs a capture time" }
        runCatching { java.time.Instant.parse(page.capturedAtUtc) }
            .getOrElse { error("Reviewed source ${page.sourceId} capture time must be UTC ISO-8601") }
        require(!page.selectionMethod.isNullOrBlank()) { "Reviewed source ${page.sourceId} needs a selection method" }
        require(normalizePublicHttpsUrl(page.page.sourceUrl) != null) { "Frozen source URL must be public HTTPS" }
        if (case.sourceCaptureStatus == "captured_http_excerpt") {
            require(page.sourceResponseSha256?.matches(Regex("[a-f0-9]{64}")) == true) {
                "Captured source ${page.sourceId} needs a SHA-256 of the fetched response body"
            }
        }
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

private suspend fun evaluate(dataset: EvalDataset, variants: List<EvalVariant>, localServer: String? = null): JsonObject {
    val variantReports = variants.map { variant ->
        val skippedWithoutOutput = dataset.cases.filter { case ->
            case.kind == "reviewed_product" && case.sourceCoverage == "complete" &&
                localServer == null && case.id !in variant.responses
        }.map(EvalCase::id).sorted()
        val evaluated = dataset.cases.filter { case ->
            case.id !in skippedWithoutOutput
        }.map { case -> evaluateCase(case, variant, localServer) }
        reportVariant(variant, evaluated, skippedWithoutOutput)
    }
    val realCases = dataset.cases.filter { it.kind == "reviewed_product" }
    val executableReal = realCases.count { it.sourceCoverage == "complete" && it.pages.isNotEmpty() }
    val executableBySplit = realCases.filter { it.sourceCoverage == "complete" && it.pages.isNotEmpty() }.groupingBy(EvalCase::split).eachCount()
    val hasModelOutputForBothSplits = variantReports.any { variant ->
        val splits = variant.getValue("sourceSupportedFactMetricsBySplit").jsonObject
        listOf("development", "holdout").all { split ->
            (splits[split] as? JsonObject)?.get("eligibleCases")?.jsonPrimitive?.intOrNull?.let { it > 0 } == true
        }
    }
    return buildJsonObject {
        put("format", "com.davidhayesbc.cocktails.bottle-evaluation-report")
        put("schemaVersion", 1)
        put("datasetVersion", dataset.version)
        put("splitPlan", dataset.splitPlan)
        put("coverage", buildJsonObject {
            put("expectedReviewedProductCount", dataset.expectedReviewedProductCount)
            put("reviewedProductCasesLoaded", realCases.size)
            put("reviewedProductCasesExecutable", executableReal)
            put("reviewedProductCasesBlocked", (dataset.expectedReviewedProductCount - executableReal).coerceAtLeast(0))
            put("syntheticContractCases", dataset.cases.count { it.kind == "synthetic" })
            put("reviewedProductResearchTargets", realCases.sumOf { it.researchTargets.size })
        })
        put("qualityAggregateEligible", hasModelOutputForBothSplits)
        put("qualityAggregate", if (hasModelOutputForBothSplits) JsonPrimitive("computed_per_split") else JsonPrimitive("unavailable_requires_recorded_model_outputs_in_development_and_holdout"))
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
            if (executableReal == 0) add(JsonPrimitive("Product-level reviewed labels are present, but no case has complete frozen source inputs; no real-product accuracy is computed."))
            if (variants.all { it.outputSource == "synthetic_fixture" }) add(JsonPrimitive("No model or live provider ran; fixture replay timings are not performance measurements."))
            if (variants.any { it.outputSource == "host_model_execution_result" }) add(JsonPrimitive("Model inference was performed on the separate desktop host; the cloud JVM only imported and validated outputs. Host CUDA timings are not Android device timings."))
        })
    }
}

private fun localQwenVariant(
    promptConfiguration: LocalBottleExtractionPromptConfiguration = LocalBottleExtractionPromptConfiguration.DEFAULT,
) = EvalVariant(
    id = "qwen3-0.6b-q8_0-local-gpu-baseline",
    modelId = LOCAL_MODEL_LABEL,
    quantization = "Q8_0",
    runtime = System.getProperty("bottle.eval.local.runtime") ?: "llama.cpp host server; build identity unavailable",
    promptVersion = promptConfiguration.version,
    temperature = 0.3,
    maxOutputTokens = DEFAULT_LOCAL_MAX_OUTPUT_TOKENS,
    outputSource = "local_model_execution",
    timingSource = "host_runtime_observed",
    systemPrompt = promptConfiguration.systemPrompt,
    additionalInstructions = null,
    responses = emptyMap(),
)

private fun findRepositoryRoot(): File {
    var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (true) {
        if (File(current, "tools/bottle-eval/qwen3-0.6b-q8-gpu.json").isFile) return current
        current = current.parentFile ?: break
    }
    error("Could not locate the repository root for the bottle evaluation tools")
}

private fun readLocalRuntimeConfig(promptVersion: String? = null): JsonObject {
    val config = Json.parseToJsonElement(
        File(findRepositoryRoot(), "tools/bottle-eval/qwen3-0.6b-q8-gpu.json").readText(Charsets.UTF_8),
    ).jsonObject
    if (promptVersion == null) return config
    val baseline = config.getValue("androidBaseline").jsonObject + ("promptVersion" to JsonPrimitive(promptVersion))
    return JsonObject(config + ("androidBaseline" to JsonObject(baseline)))
}

private fun promptConfigurationForVersion(version: String, maxOutputTokens: Int): LocalBottleExtractionPromptConfiguration {
    val systemPrompt = when (version) {
        DEFAULT_LOCAL_PROMPT_VERSION -> LocalBottleExtractionPromptConfiguration.DEFAULT.systemPrompt
        "bottle-facts-v1" -> BottleLookupEvaluationHarnessTest::class.java
            .getResourceAsStream("/bottle-evaluation/v1/prompts/bottle-facts-v1.txt")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Archived baseline prompt bottle-facts-v1 is missing")
        else -> error("Unsupported bottle evaluation prompt version: $version")
    }
    return LocalBottleExtractionPromptConfiguration(version, systemPrompt, maxOutputTokens = maxOutputTokens)
}

private fun assertPinnedRuntimeMatchesAndroidNativeSource() {
    val root = findRepositoryRoot()
    val nativeSource = File(root, "apps/androidApp/src/main/cpp/ai_chat.cpp").readText(Charsets.UTF_8)
    val commonHeader = File(root, "third_party/llama.cpp/common/common.h").readText(Charsets.UTF_8)
    val config = readLocalRuntimeConfig()
    val baseline = config.getValue("androidBaseline").jsonObject
    fun cppInt(name: String): Int = Regex("constexpr int\\s+$name\\s*=\\s*(\\d+)").find(nativeSource)
        ?.groupValues?.get(1)?.toInt() ?: error("Android JNI constant $name was not found")
    fun samplingNumber(name: String): String = Regex("(?:int32_t|float)\\s+$name\\s*=\\s*([-0-9.]+)")
        .find(commonHeader)?.groupValues?.get(1) ?: error("Pinned llama.cpp sampler default $name was not found")
    fun samplingInt(name: String): Int = Regex("(?:int32_t|float)\\s+$name\\s*=\\s*(\\d+)")
        .find(commonHeader)?.groupValues?.get(1)?.toInt() ?: error("Pinned llama.cpp sampler default $name was not found")

    assertEquals(cppInt("DEFAULT_CONTEXT_SIZE"), baseline.getValue("contextTokens").jsonPrimitive.intOrNull)
    assertEquals(cppInt("BATCH_SIZE"), baseline.getValue("batchTokens").jsonPrimitive.intOrNull)
    assertEquals(DEFAULT_LOCAL_MAX_OUTPUT_TOKENS, baseline.getValue("maxOutputTokens").jsonPrimitive.intOrNull)
    assertEquals(DEFAULT_LOCAL_PROMPT_VERSION, baseline.getValue("promptVersion").jsonPrimitive.content)
    val nativeTemp = Regex("constexpr float\\s+DEFAULT_SAMPLER_TEMP\\s*=\\s*([0-9.]+)f").find(nativeSource)
        ?.groupValues?.get(1)?.toDouble() ?: error("Android JNI sampler temperature was not found")
    assertEquals(nativeTemp, baseline.getValue("temperature").jsonPrimitive.doubleOrNull!!, 0.0001)
    assertTrue(nativeSource.contains("/* use_jinja */ false"))
    val nativeGrammar = Regex("LOCAL_FACTS_GBNF\\[\\]\\s*=\\s*R\\\"GBNF\\(\\n(.*?)\\n\\)GBNF\\\"", RegexOption.DOT_MATCHES_ALL)
        .find(nativeSource)?.groupValues?.get(1) ?: error("Android JNI GBNF grammar was not found")
    assertEquals(nativeGrammar, baseline.getValue("grammar").jsonPrimitive.content)
    assertEquals("GBNF", baseline.getValue("grammarType").jsonPrimitive.content)

    listOf("top_k", "top_p", "min_p", "xtc_probability", "xtc_threshold", "typ_p", "top_n_sigma",
        "penalty_repeat", "penalty_freq", "penalty_present", "dry_multiplier", "dry_base")
        .forEach { name -> assertEquals(samplingNumber(name).toDouble(), baseline.getValue("samplerParameters").jsonObject
            .getValue(samplerConfigName(name)).jsonPrimitive.doubleOrNull!!, 0.0001) }
    listOf("n_prev" to "repeat_last_n", "dry_allowed_length" to "dry_allowed_length", "dry_penalty_last_n" to "dry_penalty_last_n")
        .forEach { (nativeName, configName) -> assertEquals(samplingInt(nativeName), baseline.getValue("samplerParameters").jsonObject
            .getValue(configName).jsonPrimitive.intOrNull) }
    assertEquals(
        listOf("penalties", "dry", "top_n_sigma", "top_k", "typ_p", "top_p", "min_p", "xtc", "temperature"),
        baseline.getValue("samplers").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(listOf("\n", ":", "\"", "*"),
        baseline.getValue("samplerParameters").jsonObject.getValue("dry_sequence_breakers").jsonArray.map { it.jsonPrimitive.content })
    val samplerBlock = commonHeader.substringAfter("std::vector<enum common_sampler_type> samplers = {").substringBefore("};")
    val samplerNames = Regex("COMMON_SAMPLER_TYPE_([A-Z_]+)").findAll(samplerBlock).map { match ->
        when (match.groupValues[1]) {
            "PENALTIES" -> "penalties"
            "DRY" -> "dry"
            "TOP_N_SIGMA" -> "top_n_sigma"
            "TOP_K" -> "top_k"
            "TYPICAL_P" -> "typ_p"
            "TOP_P" -> "top_p"
            "MIN_P" -> "min_p"
            "XTC" -> "xtc"
            "TEMPERATURE" -> "temperature"
            else -> error("Unexpected pinned sampler ${match.groupValues[1]}")
        }
    }.toList()
    assertEquals(samplerNames, baseline.getValue("samplers").jsonArray.map { it.jsonPrimitive.content })
}

private fun samplerConfigName(nativeName: String): String = when (nativeName) {
    "typ_p" -> "typical_p"
    "penalty_repeat" -> "repeat_penalty"
    "penalty_freq" -> "frequency_penalty"
    "penalty_present" -> "presence_penalty"
    else -> nativeName
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun sampleInferenceCase(id: String): EvalCase {
    val page = WebPageText(
        title = "Sample Spirit 5011333150725",
        sourceUrl = "https://products.example/items/sample-spirit",
        markdown = "UPC: 5011333150725\n### Sample Spirit\nBrand: Sample House",
        provider = "Frozen test source",
    )
    val sourceHash = frozenSourceInputSha256(page)
    val evalPage = EvalPage("source-$id", page.sourceUrl, page, "2026-10-29T00:00:00Z", "test fixture",
        sourceHash, "short factual test excerpt")
    return EvalCase(
        id = id, kind = "reviewed_product", split = "development", groupId = "sample-family-$id",
        sourceCoverage = "complete", sourceCaptureStatus = "fixture", sourceSelectionMode = "independent_product_source",
        barcode = "5011333150725", identityExpectation = IdentityExpectation.TARGET, expectedSourceId = evalPage.sourceId,
        inventoryTruth = emptyMap(),
        sourceFacts = mapOf(BottleFactField.PRODUCT_NAME to LabeledValue("SUPPORTED", "Sample Spirit", "### Sample Spirit")),
        researchTargets = emptyList(), searchHits = emptyList(), pages = listOf(evalPage),
    )
}

private fun sampleDeterministicCase(id: String): EvalCase {
    val page = WebPageText(
        title = "Sample House Sample Spirit",
        sourceUrl = "https://products.example/items/sample-spirit",
        markdown = """### Sample Spirit
            | UPC: | 5011333150725 |
            | Size: | 750 mL |
            | Alcohol: | 43% |
        """.trimIndent(),
        provider = "Frozen test source",
    )
    val sourceHash = frozenSourceInputSha256(page)
    val sourceId = "source-$id"
    val evalPage = EvalPage(sourceId, page.sourceUrl, page, "2026-10-29T00:00:00Z", "test fixture",
        sourceHash, "short factual test excerpt")
    return EvalCase(
        id = id, kind = "reviewed_product", split = "development", groupId = "sample-family-$id",
        sourceCoverage = "complete", sourceCaptureStatus = "fixture", sourceSelectionMode = "barcode_search",
        barcode = "5011333150725", identityExpectation = IdentityExpectation.TARGET, expectedSourceId = sourceId,
        inventoryTruth = emptyMap(),
        sourceFacts = mapOf(
            BottleFactField.PRODUCT_NAME to LabeledValue("SUPPORTED", "Sample Spirit", "### Sample Spirit"),
            BottleFactField.QUANTITY to LabeledValue("SUPPORTED", "750 mL", "Size: 750 mL"),
            BottleFactField.ABV_PERCENT to LabeledValue("SUPPORTED", "43%", "Alcohol: 43%"),
        ),
        researchTargets = emptyList(),
        searchHits = listOf(sourceId to WebSearchHit("Sample Spirit", page.sourceUrl, "UPC 5011333150725")),
        pages = listOf(evalPage),
    )
}

private fun eligibleProductionPromptCases(dataset: EvalDataset, evaluationSplit: String = "full"): List<EvalCase> {
    require(evaluationSplit in setOf("development", "holdout", "full")) { "Evaluation split must be development, holdout, or full" }
    return dataset.cases.filter { case ->
        case.kind == "reviewed_product" && case.sourceCoverage == "complete" && case.pages.isNotEmpty() &&
            (evaluationSplit == "full" || case.split == evaluationSplit) &&
            case.sourceFacts.values.any { it.status != SourceLabelStatus.UNKNOWN.name }
    }
}

private data class ProductionPromptBatch(
    val eligibleCaseIds: List<String>,
    val noInferenceCaseIds: List<String>,
    val noInferenceReasons: Map<String, String>,
    val tasks: List<JsonObject>,
)

private suspend fun captureProductionPromptBatch(
    dataset: EvalDataset,
    cases: List<EvalCase>,
    config: JsonObject,
    promptConfiguration: LocalBottleExtractionPromptConfiguration = LocalBottleExtractionPromptConfiguration.DEFAULT,
): ProductionPromptBatch {
    require(cases.isNotEmpty()) { "Prompt capture requires at least one eligible reviewed product case" }
    val variant = localQwenVariant(promptConfiguration)
    val tasks = mutableListOf<JsonObject>()
    val noInferenceCaseIds = mutableListOf<String>()
    val noInferenceReasons = mutableMapOf<String, String>()
    cases.sortedBy(EvalCase::id).forEach { case ->
        var captured: JsonObject? = null
        val evaluated = evaluateCase(case, variant, localServer = null) { systemPrompt, userPrompt, maxTokens, page ->
            check(captured == null) {
                "Production extraction requested multiple source prompts for ${case.id}; this job format supports one prompt per case"
            }
            require(maxTokens == config.getValue("androidBaseline").jsonObject.getValue("maxOutputTokens").jsonPrimitive.intOrNull)
            val pageInput = case.pages.firstOrNull { it.page.sourceUrl == page.sourceUrl }
                ?: error("Production extraction selected a page outside this frozen case")
            val taskSha = sha256("$systemPrompt\u0000$userPrompt")
            captured = buildJsonObject {
                put("caseId", case.id)
                put("sourceId", pageInput.sourceId)
                put("sourceInputSha256", pageInput.inputSha256 ?: frozenSourceInputSha256(page))
                put("taskSha256", taskSha)
                put("systemPrompt", systemPrompt)
                put("userPrompt", userPrompt)
            }
        }
        if (captured != null) {
            tasks += captured!!
        } else {
            check(!evaluated.invokedModel) { "Production inference started without capturing a prompt for ${case.id}" }
            noInferenceCaseIds += case.id
            noInferenceReasons[case.id] = when {
                (evaluated.outcome.extraction as? BottleDetailExtractionResult.Found)
                    ?.suggestions?.extractionMethod == BottleExtractionMethod.DETERMINISTIC_SOURCE -> "DETERMINISTIC_SOURCE"
                evaluated.outcome.selectedHit == null || evaluated.outcome.page !is WebPageResult.Found -> "RETRIEVAL_ABSTENTION"
                else -> "PRE_INFERENCE_REJECTION"
            }
        }
    }
    val eligibleCaseIds = cases.map(EvalCase::id).sorted()
    check((tasks.map { it.requiredString("caseId") } + noInferenceCaseIds).sorted() == eligibleCaseIds) {
        "Production prompt outcomes did not classify every eligible case exactly once"
    }
    return ProductionPromptBatch(eligibleCaseIds, noInferenceCaseIds.sorted(), noInferenceReasons.toSortedMap(), tasks)
}

private suspend fun exportProductionPromptJobs(
    dataset: EvalDataset,
    destination: File,
    evaluationSplit: String = System.getProperty("bottle.eval.export.split")?.takeIf(String::isNotBlank) ?: "development",
) {
    assertPinnedRuntimeMatchesAndroidNativeSource()
    val promptVersion = System.getProperty("bottle.eval.prompt.version")
        ?.takeIf(String::isNotBlank) ?: DEFAULT_LOCAL_PROMPT_VERSION
    val promptConfiguration = promptConfigurationForVersion(promptVersion, DEFAULT_LOCAL_MAX_OUTPUT_TOKENS)
    val eligible = eligibleProductionPromptCases(dataset, evaluationSplit)
    require(eligible.isNotEmpty()) {
        "No reviewed product case has a complete frozen source and independent source labels. No prompts or model jobs were exported."
    }
    val config = readLocalRuntimeConfig(promptVersion)
    val batch = captureProductionPromptBatch(dataset, eligible, config, promptConfiguration)
    val result = buildPortablePromptJobs(
        dataset.version, dataset.splitPlan, config, batch.eligibleCaseIds, batch.noInferenceCaseIds,
        batch.noInferenceReasons, batch.tasks, evaluationSplit,
    )
    destination.absoluteFile.parentFile?.mkdirs()
    destination.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result), Charsets.UTF_8)
}

private fun buildPortablePromptJobs(
    datasetVersion: String,
    splitPlan: String,
    config: JsonObject,
    eligibleCaseIds: List<String>,
    noInferenceCaseIds: List<String>,
    noInferenceReasons: Map<String, String>,
    tasks: List<JsonObject>,
    evaluationSplit: String = "development",
): JsonObject {
    val configText = Json.encodeToString(JsonObject.serializer(), config)
    val ids = tasks.map { it.requiredString("caseId") }
    require(ids.distinct().size == ids.size)
    val eligible = eligibleCaseIds.distinct().sorted()
    val noInference = noInferenceCaseIds.distinct().sorted()
    require(evaluationSplit in setOf("development", "holdout", "full"))
    require(eligible.isNotEmpty() && eligible.size <= 100 && eligible == eligibleCaseIds.sorted())
    require(noInference.size <= 100 && noInference == noInferenceCaseIds.sorted())
    require(noInferenceReasons.keys == noInference.toSet() && noInferenceReasons.values.all {
        it in setOf("DETERMINISTIC_SOURCE", "RETRIEVAL_ABSTENTION", "PRE_INFERENCE_REJECTION")
    }) { "Every no-inference case must have one safe production outcome classification" }
    require((ids + noInference).distinct().sorted() == eligible && ids.none { it in noInference }) {
        "Prompt tasks and no-inference cases must partition all eligible cases"
    }
    return buildJsonObject {
        put("format", "com.davidhayesbc.cocktails.bottle-evaluation-prompt-jobs")
        put("schemaVersion", 1)
        put("datasetVersion", datasetVersion)
        put("splitPlan", splitPlan)
        put("evaluationSplit", evaluationSplit)
        put("config", config)
        put("configCanonicalJson", configText)
        put("configSha256", sha256(configText))
        put("caseIds", buildJsonArray { ids.sorted().forEach { add(JsonPrimitive(it)) } })
        put("eligibleCaseIds", buildJsonArray { eligible.forEach { add(JsonPrimitive(it)) } })
        put("noInferenceCaseIds", buildJsonArray { noInference.forEach { add(JsonPrimitive(it)) } })
        put("noInferenceReasons", buildJsonObject { noInferenceReasons.toSortedMap().forEach { (id, reason) -> put(id, reason) } })
        put("tasks", buildJsonArray { tasks.forEach(::add) })
        put("answerKeyIncluded", false)
    }
}

private data class ImportedHostEvaluation(val variant: EvalVariant, val caseIds: List<String>)

private suspend fun importHostInferenceResults(dataset: EvalDataset, jobsFile: File, resultsFile: File): ImportedHostEvaluation {
    assertPinnedRuntimeMatchesAndroidNativeSource()
    require(jobsFile.length() <= 8L * 1024L * 1024L) { "Prompt-job file exceeds the 8 MiB safety limit" }
    val jobsBytes = jobsFile.readBytes()
    val jobSha = sha256(jobsBytes)
    val jobs = Json.parseToJsonElement(jobsBytes.toString(Charsets.UTF_8)).jsonObject
    require(jobs.requiredString("format") == "com.davidhayesbc.cocktails.bottle-evaluation-prompt-jobs" && jobs.int("schemaVersion") == 1)
    require(jobs.keys == setOf("format", "schemaVersion", "datasetVersion", "splitPlan", "evaluationSplit", "config", "configCanonicalJson", "configSha256", "caseIds", "eligibleCaseIds", "noInferenceCaseIds", "noInferenceReasons", "tasks", "answerKeyIncluded"))
    require(jobs["answerKeyIncluded"]?.jsonPrimitive?.booleanOrNull == false)
    val configText = jobs.requiredString("configCanonicalJson")
    require(sha256(configText) == jobs.requiredString("configSha256")) { "Exported job config hash mismatch" }
    val jobConfig = Json.parseToJsonElement(configText).jsonObject
    val promptVersion = jobConfig.getValue("androidBaseline").jsonObject.requiredString("promptVersion")
    val baselineForPrompt = jobConfig.getValue("androidBaseline").jsonObject
    val promptConfiguration = promptConfigurationForVersion(
        promptVersion,
        baselineForPrompt.getValue("maxOutputTokens").jsonPrimitive.intOrNull ?: DEFAULT_LOCAL_MAX_OUTPUT_TOKENS,
    )
    val config = readLocalRuntimeConfig(promptVersion)
    require(jobConfig == config && jobs["config"]?.jsonObject == config) {
        "Exported jobs do not match the currently pinned Android runtime configuration"
    }
    require(jobs.requiredString("datasetVersion") == dataset.version && jobs.requiredString("splitPlan") == dataset.splitPlan)
    val tasks = jobs.requiredArray("tasks").map { it.jsonObject }
    require(tasks.size <= 100)
    val tasksById = tasks.associateBy { it.requiredString("caseId") }
    require(tasksById.size == tasks.size) { "Prompt jobs contain duplicate cases" }
    require(jobs.requiredArray("caseIds").map { it.jsonPrimitive.content } == tasksById.keys.sorted()) {
        "Prompt job case index does not match task list"
    }
    val eligibleCaseIds = jobs.requiredArray("eligibleCaseIds").map { it.jsonPrimitive.content }
    val noInferenceCaseIds = jobs.requiredArray("noInferenceCaseIds").map { it.jsonPrimitive.content }
    val noInferenceReasons = jobs.requiredObject("noInferenceReasons").mapValues { (_, value) -> value.jsonPrimitive.content }
    require(eligibleCaseIds.size <= 100 && noInferenceCaseIds.size <= 100 &&
        eligibleCaseIds == eligibleCaseIds.distinct().sorted() && noInferenceCaseIds == noInferenceCaseIds.distinct().sorted()) {
        "Eligible and no-inference case indexes must be sorted and unique"
    }
    require((tasksById.keys + noInferenceCaseIds).distinct().sorted() == eligibleCaseIds &&
        tasksById.keys.none { it in noInferenceCaseIds } && noInferenceReasons.keys == noInferenceCaseIds.toSet()) {
        "Prompt tasks and no-inference cases must partition the eligible case set"
    }
    val evaluationSplit = jobs.requiredString("evaluationSplit")
    require(evaluationSplit in setOf("development", "holdout", "full"))
    val eligibleCases = eligibleProductionPromptCases(dataset, evaluationSplit)
    val expectedEligibleIds = eligibleCases.map(EvalCase::id).sorted()
    require(eligibleCaseIds == expectedEligibleIds) { "Prompt jobs do not cover the current frozen executable case set" }
    val expectedBatch = captureProductionPromptBatch(dataset, eligibleCases, config, promptConfiguration)
    require(noInferenceCaseIds == expectedBatch.noInferenceCaseIds) {
        "Prompt jobs misclassify cases where the production extractor does not request inference"
    }
    require(noInferenceReasons == expectedBatch.noInferenceReasons) {
        "Prompt jobs misclassify production outcomes for cases without model inference"
    }
    require(tasksById.keys == expectedBatch.tasks.map { it.requiredString("caseId") }.toSet()) {
        "Host results must include every case that the production extractor sends to inference, including holdout"
    }
    require(tasksById.isNotEmpty()) {
        "No host inference results are expected because every eligible case was handled without model inference"
    }
    val expectedTasks = expectedBatch.tasks.associateBy { it.requiredString("caseId") }
    tasks.forEach { task ->
        require(task.keys == setOf("caseId", "sourceId", "sourceInputSha256", "taskSha256", "systemPrompt", "userPrompt"))
        val system = task.requiredString("systemPrompt")
        val user = task.requiredString("userPrompt")
        require(system.length <= 16_000 && user.length <= 24_000)
        require(sha256("$system\u0000$user") == task.requiredString("taskSha256")) { "Prompt task hash mismatch" }
        val productionTask = expectedTasks[task.requiredString("caseId")]
            ?: error("Prompt task is not in the current production extraction job set")
        require(task == productionTask) { "Exported prompt task differs from the current production extractor input" }
        val case = dataset.cases.singleOrNull { it.id == task.requiredString("caseId") }
            ?: error("Prompt result refers to a case outside the loaded dataset")
        require(case.kind == "reviewed_product" && case.sourceCoverage == "complete") {
            "Only complete reviewed product cases can be imported as product evaluation results"
        }
        require(case.pages.any { it.sourceId == task.requiredString("sourceId") && it.inputSha256 == task.requiredString("sourceInputSha256") }) {
            "Prompt task source does not match the frozen dataset"
        }
    }
    require(resultsFile.length() <= 12L * 1024L * 1024L) { "Host result file exceeds the 12 MiB safety limit" }
    val results = Json.parseToJsonElement(resultsFile.readText(Charsets.UTF_8)).jsonObject
    require(results.requiredString("format") == "com.davidhayesbc.cocktails.bottle-evaluation-host-results" && results.int("schemaVersion") == 1)
    require(results.keys == setOf("format", "schemaVersion", "jobFileSha256", "configSha256", "modelSha256", "llamaCppCommit", "runtimeVersion", "runtimeBackend", "devices", "gpuOffloadedLayers", "hostRuntime", "inferenceSettings", "responses"))
    require(results.requiredString("jobFileSha256") == jobSha) { "Host result belongs to a different exact prompt-job file" }
    require(results.requiredString("configSha256") == jobs.requiredString("configSha256"))
    require(results.requiredString("modelSha256") == config.getValue("model").jsonObject.requiredString("sha256"))
    val llamaCommit = config.getValue("llamaCpp").jsonObject.requiredString("commit")
    require(results.requiredString("llamaCppCommit") == llamaCommit)
    require(results.requiredString("runtimeVersion").contains(llamaCommit.take(7))) { "Host runtime version does not identify the pinned llama.cpp commit" }
    require(results.requiredString("runtimeBackend") == "CUDA")
    require(results.requiredString("runtimeVersion").length <= 300)
    require(results.requiredString("devices").length <= 500)
    require(results.requiredString("gpuOffloadedLayers").matches(Regex("\\d{1,4}/\\d{1,4}")))
    val baseline = config.getValue("androidBaseline").jsonObject
    val hostSettings = results.requiredObject("inferenceSettings")
    require(hostSettings.getValue("contextTokens").jsonPrimitive.intOrNull == baseline.getValue("contextTokens").jsonPrimitive.intOrNull)
    require(hostSettings.getValue("batchTokens").jsonPrimitive.intOrNull == baseline.getValue("batchTokens").jsonPrimitive.intOrNull)
    require(hostSettings.getValue("maxOutputTokens").jsonPrimitive.intOrNull == baseline.getValue("maxOutputTokens").jsonPrimitive.intOrNull)
    require(hostSettings.getValue("temperature").jsonPrimitive.doubleOrNull == baseline.getValue("temperature").jsonPrimitive.doubleOrNull)
    require(hostSettings.getValue("samplers") == baseline.getValue("samplers"))
    require(hostSettings.requiredString("grammarSha256") == sha256(baseline.requiredString("grammar")))
    val responses = results.requiredArray("responses").map { it.jsonObject }
    require(responses.size <= 100)
    val responseById = responses.associateBy { it.requiredString("caseId") }
    require(responseById.size == responses.size && responseById.keys == tasksById.keys) {
        "Host results must include exactly one response for every exported task"
    }
    val maxTokens = baseline.getValue("maxOutputTokens").jsonPrimitive.intOrNull!!
    val parsedResponses = responseById.mapValues { (id, response) ->
        require(response.keys == setOf("caseId", "taskSha256", "sourceInputSha256", "modelOutput", "finishReason", "generatedTokens", "generationMillis", "promptEvaluationMillis", "promptTokens", "requestRoundTripMillis"))
        val task = tasksById.getValue(id)
        require(response.requiredString("taskSha256") == task.requiredString("taskSha256"))
        require(response.requiredString("sourceInputSha256") == task.requiredString("sourceInputSha256"))
        val tokens = response.int("generatedTokens")
        require(tokens == null || tokens in 0..maxTokens)
        val promptTokens = response.int("promptTokens")
        require(promptTokens == null || promptTokens in 0..(baseline.getValue("contextTokens").jsonPrimitive.intOrNull ?: 4096))
        val generation = response.long("generationMillis")
        require(generation == null || generation in 0..600_000)
        val promptEval = response.long("promptEvaluationMillis")
        val requestRtt = response.long("requestRoundTripMillis")
        require(promptEval == null || promptEval in 0..600_000)
        require(requestRtt == null || requestRtt in 0..600_000)
        require(response.requiredString("finishReason") in setOf("END_OF_SEQUENCE", "TOKEN_LIMIT", "STOP_WORD", "UNKNOWN"))
        EvalResponse(
            response = response.requiredString("modelOutput").also { require(it.length <= 100_000) },
            finishReason = response.requiredString("finishReason"),
            generatedTokens = tokens,
            generationMillis = generation,
            requestRoundTripMillis = response.long("requestRoundTripMillis"),
            promptEvaluationMillis = promptEval,
            promptTokens = promptTokens,
            origin = "host_model_execution",
        )
    }
    return ImportedHostEvaluation(
        EvalVariant(
            id = "qwen3-0.6b-q8_0-imported-host-result",
            modelId = LOCAL_MODEL_LABEL,
            quantization = "Q8_0",
            runtime = results.requiredString("runtimeVersion"),
            promptVersion = baseline.requiredString("promptVersion"),
            temperature = baseline.double("temperature"),
            maxOutputTokens = maxTokens,
            outputSource = "host_model_execution_result",
            timingSource = "host_runtime_observed",
            systemPrompt = promptConfiguration.systemPrompt,
            additionalInstructions = null,
            responses = parsedResponses,
            modelArtifactSha256 = results.requiredString("modelSha256"),
            llamaCppCommit = llamaCommit,
            runtimeBackend = results.requiredString("runtimeBackend"),
        ),
        eligibleCaseIds,
    )
}

/**
 * Sends the production extractor's exact system/user text to the pinned llama.cpp server.
 * The server first formats those two messages with its legacy template path, matching Android's
 * `use_jinja=false`, then completes that exact rendered prompt. Raw prompts/responses stay in
 * memory and are never written into the evaluation report.
 */
private suspend fun runLocalLlamaServer(
    baseUrl: String,
    systemPrompt: String,
    userPrompt: String,
    maxTokens: Int,
    grammar: String,
    postJson: (String, JsonObject, (Long) -> Unit) -> JsonObject = ::postLocalJson,
): LocalServerCompletion = withContext(Dispatchers.IO) {
    val base = validateLoopbackEndpoint(baseUrl)
    val template = postJson("$base/apply-template", buildJsonObject {
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
            add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
        })
        put("add_generation_prompt", true)
    }, {})
    val renderedPrompt = template.string("prompt") ?: error("llama.cpp /apply-template omitted the prompt")
    var requestRoundTripMillis = 0L
    val completion = postJson("$base/completion", buildJsonObject {
        put("prompt", renderedPrompt)
        put("n_predict", maxTokens)
        put("seed", -1)
        put("temperature", 0.3)
        put("top_k", 40)
        put("top_p", 0.95)
        put("min_p", 0.05)
        put("top_n_sigma", -1.0)
        put("typical_p", 1.0)
        put("xtc_probability", 0.0)
        put("xtc_threshold", 0.1)
        put("repeat_last_n", 64)
        put("repeat_penalty", 1.0)
        put("frequency_penalty", 0.0)
        put("presence_penalty", 0.0)
        put("dry_multiplier", 0.0)
        put("dry_base", 1.75)
        put("dry_allowed_length", 2)
        put("dry_penalty_last_n", 64)
        put("dry_sequence_breakers", buildJsonArray {
            listOf("\n", ":", "\"", "*").forEach { add(JsonPrimitive(it)) }
        })
        put("grammar", grammar)
        put("samplers", buildJsonArray {
            listOf("penalties", "dry", "top_n_sigma", "top_k", "typ_p", "top_p", "min_p", "xtc", "temperature")
                .forEach { add(JsonPrimitive(it)) }
        })
        put("cache_prompt", false)
        put("timings_per_token", true)
    }, { requestRoundTripMillis = it })
    val timing = completion["timings"]?.jsonObject
    val finish = when (completion.string("stop_type")) {
        "eos" -> "END_OF_SEQUENCE"
        "limit" -> "TOKEN_LIMIT"
        "word" -> "STOP_WORD"
        "none" -> "UNKNOWN"
        else -> "UNKNOWN"
    }
    LocalServerCompletion(
        EvalResponse(
            response = completion.string("content") ?: error("llama.cpp /completion omitted content"),
            finishReason = finish,
            generatedTokens = timing?.get("predicted_n")?.jsonPrimitive?.intOrNull
                ?: completion["tokens_predicted"]?.jsonPrimitive?.intOrNull,
            generationMillis = timing?.get("predicted_ms")?.jsonPrimitive?.doubleOrNull?.toLong(),
            requestRoundTripMillis = requestRoundTripMillis,
            promptEvaluationMillis = timing?.get("prompt_ms")?.jsonPrimitive?.doubleOrNull?.toLong(),
            promptTokens = timing?.get("prompt_n")?.jsonPrimitive?.intOrNull,
        ),
    )
}

private fun validateLoopbackEndpoint(value: String): String {
    val uri = URI(value.trimEnd('/'))
    require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1") &&
        uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in 1..65535) {
        "Local model evaluation only accepts an explicit loopback HTTP endpoint."
    }
    return uri.toASCIIString().trimEnd('/')
}

private fun postLocalJson(url: String, payload: JsonObject, onRoundTrip: (Long) -> Unit = {}): JsonObject {
    val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = 10_000
    connection.readTimeout = 300_000
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    try {
        val bytes = Json.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8)
        val started = System.nanoTime()
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseBytes = input?.use { stream -> stream.readNBytes(1_048_577) } ?: byteArrayOf()
        onRoundTrip(((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L))
        require(status in 200..299) { "Local llama.cpp server returned HTTP $status; response body is intentionally omitted." }
        require(responseBytes.size <= 1_048_576) { "Local llama.cpp response exceeded the 1 MiB safety limit." }
        return Json.parseToJsonElement(responseBytes.toString(Charsets.UTF_8)).jsonObject
    } finally {
        connection.disconnect()
    }
}

private suspend fun evaluateCase(
    case: EvalCase,
    variant: EvalVariant,
    localServer: String?,
    onPromptCapture: ((systemPrompt: String, userPrompt: String, maxTokens: Int, page: WebPageText) -> Unit)? = null,
): EvaluatedCase {
    val replayResponse = variant.responses[case.id]
    val promptConfiguration = LocalBottleExtractionPromptConfiguration(
        version = variant.promptVersion,
        systemPrompt = variant.systemPrompt ?: LocalBottleExtractionPromptConfiguration.DEFAULT.systemPrompt,
        additionalInstructions = variant.additionalInstructions,
        maxOutputTokens = variant.maxOutputTokens,
    )
    var invokedModel = false
    var observedPrompt: String? = null
    var actualResponse: EvalResponse? = null
    var activeExtractionPage: WebPageText? = null
    val events = mutableListOf<BottleLookupDebugEvent>()
    val localExtractor = LocalBottleDetailExtractor(
        modelPath = { "/evaluation/not-a-real-model.gguf" },
        runner = LocalInferenceRunner { _, systemPrompt, userPrompt, maxTokens ->
            invokedModel = true
            observedPrompt = userPrompt
            if (onPromptCapture != null) {
                val promptPage = activeExtractionPage ?: error("An extraction prompt was created without its source page")
                onPromptCapture(systemPrompt, userPrompt, maxTokens, promptPage)
                "{\"facts\":[]}"
            } else if (localServer != null) {
                val grammar = readLocalRuntimeConfig().getValue("androidBaseline").jsonObject.getValue("grammar").jsonPrimitive.content
                val completion = runLocalLlamaServer(localServer, systemPrompt, userPrompt, maxTokens, grammar)
                actualResponse = completion.response
                if (completion.response.finishReason == "TOKEN_LIMIT") {
                    throw LocalInferenceFailureException(LocalInferenceFailureCode.MODEL_OUTPUT_TRUNCATED)
                }
                completion.response.response
            } else {
                if (replayResponse?.finishReason == "TOKEN_LIMIT") {
                    throw LocalInferenceFailureException(LocalInferenceFailureCode.MODEL_OUTPUT_TRUNCATED)
                }
                replayResponse?.response ?: ""
            }
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
            activeExtractionPage = page
            val result = localExtractor.extractWithDiagnostics(page, variant.modelIdForApp(), barcode, events::add)
            BottleProviderCall(result, 200)
        },
    )
    val outcome = coordinator.processScannedBarcode(
        barcode = case.barcode,
        plan = ScannedBottlePlan(
            openFoodFactsEnabled = false,
            firecrawlEnabled = case.sourceSelectionMode == "barcode_search",
            extractor = AutomaticBottleExtractor.ON_DEVICE,
        ),
        onDebugEvent = events::add,
    )
    val evaluatedOutcome = if (case.sourceSelectionMode == "independent_product_source") {
        val selectedPage = case.pages.single { it.sourceId == case.expectedSourceId }.page
        activeExtractionPage = selectedPage
        val extraction = localExtractor.extractWithDiagnostics(selectedPage, variant.modelIdForApp(), null, events::add)
        outcome.copy(page = WebPageResult.Found(selectedPage), extraction = extraction)
    } else outcome
    // If a fixture has a response, it must either be consumed or be deliberately unreachable because retrieval abstained.
    require(onPromptCapture != null || localServer != null || !invokedModel || replayResponse != null) { "Unexpected model call without a replay response for ${case.id}/${variant.id}" }
    if (onPromptCapture == null && localServer == null && replayResponse == null) require(!invokedModel) { "Model ran without a recorded response for ${case.id}/${variant.id}" }
    return EvaluatedCase(
        case, evaluatedOutcome, events, invokedModel, observedPrompt,
        (actualResponse ?: replayResponse).takeIf { invokedModel },
        inferenceExecuted = localServer != null && invokedModel,
    )
}

private fun EvalVariant.modelIdForApp(): String = when {
    modelId == "Qwen3-0.6B-Q8_0" -> LOCAL_MODEL_LABEL
    else -> LOCAL_MODEL_LABEL
}

private fun reportVariant(
    variant: EvalVariant,
    evaluated: List<EvaluatedCase>,
    skippedWithoutRecordedOutput: List<String> = emptyList(),
): JsonObject {
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
        put("runtimeBackend", variant.runtimeBackend ?: System.getProperty("bottle.eval.local.backend") ?: "not_applicable_or_unverified")
        put("modelArtifactSha256", variant.modelArtifactSha256 ?: System.getProperty("bottle.eval.local.model.sha256") ?: "not_run")
        put("llamaCppCommit", variant.llamaCppCommit ?: System.getProperty("bottle.eval.local.llama.commit") ?: "not_run")
        put("promptVersion", variant.promptVersion)
        val systemPrompt = variant.systemPrompt ?: LocalBottleExtractionPromptConfiguration.DEFAULT.systemPrompt
        put("systemPromptSha256", sha256(systemPrompt))
        put("additionalInstructionsSha256", variant.additionalInstructions?.let(::sha256) ?: "none")
        put("promptConfigurationSha256", promptConfigurationFingerprint(variant, systemPrompt))
        put("temperature", variant.temperature?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("maxOutputTokens", variant.maxOutputTokens)
        put("outputSource", variant.outputSource)
        put("timingSource", variant.timingSource)
        put("inferenceLocation", if (variant.outputSource == "host_model_execution_result") "host_gpu_imported" else "not_run_in_host_runner")
        put("recordedSourceRunGenerationMillisMedian", measuredGenerationMedian(variant, evaluated)?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("hostRequestRoundTripMillisMedian", evaluated.mapNotNull { it.response?.requestRoundTripMillis }.sorted()
            .takeIf(List<Long>::isNotEmpty)?.let { JsonPrimitive(it[it.size / 2]) } ?: JsonPrimitive("unavailable"))
        put("hostPromptEvaluationMillisMedian", medianLong(evaluated.mapNotNull { it.response?.promptEvaluationMillis })
            ?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("hostPromptTokensMedian", medianInt(evaluated.mapNotNull { it.response?.promptTokens })
            ?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("hostGeneratedTokensMedian", medianInt(evaluated.mapNotNull { it.response?.generatedTokens })
            ?.let(::JsonPrimitive) ?: JsonPrimitive("unavailable"))
        put("sourceSupportedFactMetrics", realMetrics)
        put("reviewedProductCasesSkippedWithoutRecordedModelOutput", buildJsonArray {
            skippedWithoutRecordedOutput.forEach { add(JsonPrimitive(it)) }
        })
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
    val eligible = cases.filter { it.case.sourceSelectionMode == "barcode_search" && it.case.expectedSourceId != null }
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
    val executed = cases.filter { it.outcome.extraction != null }
    var confirmed = 0
    var matched = 0
    executed.forEach { item ->
        val output = (item.outcome.extraction as? BottleDetailExtractionResult.Found)?.suggestions?.facts.orEmpty().associateBy(BottleFactEvidence::field)
        item.case.inventoryTruth.forEach { (field, label) ->
            if (label.status == InventoryLabelStatus.CONFIRMED.name) {
                confirmed++
                if (output[field]?.let { normalizeEvalFact(field, it.value) == normalizeEvalFact(field, label.value.orEmpty()) } == true) matched++
            }
        }
    }
    put("eligibleCases", executed.size)
    put("confirmedInventoryFields", confirmed)
    put("exactMatches", matched)
    put("matchRate", if (confirmed == 0) JsonPrimitive("unavailable") else JsonPrimitive(matched.toDouble() / confirmed))
    put("note", "Inventory truth is scored separately from facts supported by each supplied source.")
}

private fun measuredGenerationMedian(variant: EvalVariant, evaluated: List<EvaluatedCase>): Long? {
    if (variant.timingSource !in setOf("device_observed", "host_runtime_observed")) return null
    val values = evaluated.mapNotNull { item -> item.response?.generationMillis }.sorted()
    return values.takeIf(List<Long>::isNotEmpty)?.let { it[it.size / 2] }
}

private fun medianLong(values: List<Long>): Long? = values.sorted().takeIf(List<Long>::isNotEmpty)?.let { it[it.size / 2] }

private fun medianInt(values: List<Int>): Int? = values.sorted().takeIf(List<Int>::isNotEmpty)?.let { it[it.size / 2] }

private fun reportCase(item: EvaluatedCase) = buildJsonObject {
    val extraction = item.outcome.extraction
    put("caseId", item.case.id)
    put("kind", item.case.kind)
    put("split", item.case.split)
    put("retrieval", when {
        item.case.sourceSelectionMode == "independent_product_source" -> "not_evaluated_manual_source_selection"
        item.outcome.search is WebSearchResult.Found && item.outcome.barcodeVerifiedInPage -> "verified_in_page"
        item.outcome.barcodeVerifiedByStructuredProduct -> "verified_structured_product"
        item.outcome.search is WebSearchResult.Found -> "searched_but_no_verified_source"
        else -> "no_search_result"
    })
    put("selectedSourceId", if (item.case.sourceSelectionMode == "independent_product_source") {
        item.case.expectedSourceId ?: "unavailable"
    } else item.case.pages.firstOrNull { it.hitUrl == item.outcome.selectedHit?.url }?.sourceId ?: "unavailable")
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
    put("inferenceExecutedByHarness", item.inferenceExecuted)
    put("sourceCaptureStatus", item.case.sourceCaptureStatus)
    put("researchTargetCount", item.case.researchTargets.size)
    put("replayResponseConsumed", item.invokedModel && item.response?.origin != "host_model_execution")
    put("hostModelExecutionImported", item.response?.origin == "host_model_execution")
    put("localModelExecution", item.inferenceExecuted || item.response?.origin == "host_model_execution")
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
