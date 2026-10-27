package com.davidhayesbc.cocktails.android.product

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBottleDetailExtractorTest {
    @Test
    fun usesLocalRunnerAndAcceptsOnlyQuotedFieldsFromSelectedPage() = runBlocking {
        val page = WebPageText("Example Gin", "https://example.org/gin", "<p>North Star Gin 40% ABV</p><a href=\"https://tracker.example/?user=secret\">Buy</a>", "Firecrawl")
        var receivedPrompt = ""
        val extractor = LocalBottleDetailExtractor({ "/private/model.gguf" }, LocalInferenceRunner { _, system, prompt, tokens ->
            assertTrue(system.contains("untrusted data"))
            assertEquals(256, tokens)
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
        assertTrue(wrongNameExtractor.extract(page, LOCAL_MODEL_LABEL, "5011333150725") is BottleDetailExtractionResult.Rejected)
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
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_MODEL_LOADING))
        assertTrue(stages.contains(BottleLookupDebugStage.LOCAL_PROMPT_SETUP))
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
            assertTrue(loadEvents.any { it.stage == BottleLookupDebugStage.LOCAL_MODEL_LOADING && it.result == "FAILED" })

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
    ) : InferenceEngine {
        override val state = MutableStateFlow(initialState)
        var cleanedUp = false

        override suspend fun loadModel(pathToModel: String) {
            if (throwOn == "load") throw testFailure()
        }

        override suspend fun setSystemPrompt(systemPrompt: String) {
            if (throwOn == "prompt") throw testFailure()
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            if (throwOn == "generation") throw testFailure()
            emit(output)
        }

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
            onEvent: (BottleLookupDebugEvent) -> Unit,
        ): String {
            onEvent(BottleLookupDebugEvent(BottleLookupDebugStage.LOCAL_GENERATION, "On-device Qwen3", "RUNNING"))
            delay(1_000)
            return "{}"
        }
    }
}
