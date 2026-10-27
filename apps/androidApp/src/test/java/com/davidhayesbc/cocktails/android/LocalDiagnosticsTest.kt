package com.davidhayesbc.cocktails.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugEvent
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugReport
import com.davidhayesbc.cocktails.android.product.BottleLookupDebugStage

class LocalDiagnosticsTest {
    private fun tempStore(): Pair<File, DiagnosticStore> {
        val dir = Files.createTempDirectory("cocktails-diagnostics").toFile()
        val file = File(dir, "diagnostics.json")
        return dir to DiagnosticStore(file, mapOf("versionName" to "0.1.0", "device" to "Pixel 10 Pro"))
    }

    @Test fun recordsPersistAcrossStoreRestartAndExportHasOnlyAllowlistedFields() {
        val (dir, store) = tempStore()
        store.recordEvent("CAMERA_PERMISSION_GRANTED", 10)
        store.recordCrash(Thread("camera"), IllegalStateException("BARCODE_CANARY_998877 /secret"), 11)
        val reopened = DiagnosticStore(File(dir, "diagnostics.json"), mapOf("versionName" to "0.2.0", "device" to "Pixel 8"))
        val export = reopened.export()
        val exported = Json.parseToJsonElement(export).jsonObject
        assertEquals(export, reopened.preview())
        assertTrue(exported.containsKey("formatVersion"))
        assertTrue(exported.containsKey("crashes"))
        assertTrue(export.contains("CAMERA_PERMISSION_GRANTED"))
        assertTrue(export.contains("IllegalStateException"))
        assertEquals("0.2.0", exported["metadata"]!!.jsonObject["versionName"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", exported["crashes"]!!.jsonArray.single().jsonObject["build"]!!.jsonObject["versionName"]!!.jsonPrimitive.content)
        assertFalse(export.contains("BARCODE_CANARY_998877"))
        assertFalse(export.contains("/secret"))
        assertTrue(export.contains("versionName"))
        assertFalse(export.contains("householdName"))
        dir.deleteRecursively()
    }

    @Test fun rejectsFreeFormEventAndScannerErrorText() {
        val (dir, store) = tempStore()
        val secret = "BARCODE_CANARY_998877"
        store.recordEvent(secret)
        store.recordScannerFailure("CAMERA_FRAME_FAILED", IllegalStateException("$secret /private/path/photo.jpg"), 20)
        val export = store.export()
        assertFalse(export.contains(secret))
        assertFalse(export.contains("/private/path"))
        assertTrue(export.contains("IllegalStateException"))
        assertTrue(export.contains("CAMERA_FRAME_FAILED"))
        dir.deleteRecursively()
    }

    @Test fun eventAndCrashRetentionAndFileSizeAreBounded() {
        val (dir, store) = tempStore()
        repeat(200) { store.recordEvent("APP_STARTED", it.toLong()) }
        repeat(20) { store.recordCrash(Thread("worker"), IllegalArgumentException("ignored $it"), it.toLong()) }
        assertTrue(File(dir, "diagnostics.json").length() <= DiagnosticStore.MAX_BYTES)
        assertTrue(store.export().countOccurrences("APP_STARTED") <= DiagnosticStore.MAX_EVENTS)
        assertTrue(store.export().countOccurrences("\"thread\"") <= DiagnosticStore.MAX_CRASHES)
        dir.deleteRecursively()
    }

    @Test fun productionUncaughtHandlerRecordsAndChainsToPreviousHandler() {
        val chained = AtomicBoolean(false)
        val recorded = AtomicBoolean(false)
        val thread = Thread("test")
        val failure = IllegalStateException("must not be retained")
        val handler = LocalDiagnostics.createUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { t, e ->
            assertEquals(thread, t)
            assertEquals(failure, e)
            chained.set(true)
        }, recorder = { t, e ->
            assertEquals(thread, t)
            assertEquals(failure, e)
            recorded.set(true)
        }, noPriorHandler = { _, _ -> fail("The prior handler should be called") })
        handler.uncaughtException(thread, failure)
        assertTrue(recorded.get())
        assertTrue(chained.get())
    }

    @Test fun productionUncaughtHandlerStillChainsWhenRecorderFails() {
        val chained = AtomicBoolean(false)
        val failure = IllegalStateException("crash")
        val prior = Thread.UncaughtExceptionHandler { _, error ->
            assertEquals(failure, error)
            chained.set(true)
        }
        val handler = LocalDiagnostics.createUncaughtExceptionHandler(
            prior = prior,
            recorder = { _, _ -> error("diagnostics disk failure") },
            noPriorHandler = { _, _ -> fail("The prior handler should be called") },
        )
        handler.uncaughtException(Thread("worker"), failure)
        assertTrue(chained.get())
    }

    @Test fun clearRemovesLocalDiagnosticFile() {
        val (dir, store) = tempStore()
        store.recordEvent("APP_STARTED")
        store.clear()
        assertFalse(File(dir, "diagnostics.json").exists())
        dir.deleteRecursively()
    }

    @Test fun clearReportsFailureAndLeavesFileForRecovery() {
        val dir = Files.createTempDirectory("cocktails-diagnostics-clear").toFile()
        val file = File(dir, "diagnostics.json")
        val store = DiagnosticStore(file, mapOf("versionName" to "0.1.0"), deleteFile = { false })
        store.recordEvent("APP_STARTED")
        assertFalse(store.clear())
        assertTrue(file.exists())
        dir.deleteRecursively()
    }

    @Test fun latestLookupReportPersistsWithBarcodeAndSanitizedStagesOnly() {
        val (dir, store) = tempStore()
        val report = BottleLookupDebugReport(
            createdAtMillis = 77,
            versionName = "preview.5",
            versionCode = 5,
            originalBarcode = "5011333150725",
            normalizedBarcode = "5011333150725",
            openFoodFactsEnabled = true,
            openFoodFactsConfigured = true,
            firecrawlEnabled = true,
            firecrawlConfigured = true,
            extractorProvider = "Ollama Cloud",
            modelIdentifier = "qwen-fixture",
            query = "\"5011333150725\"",
            onDeviceAiEnabled = false,
            onDeviceModelInstalled = false,
            ollamaCloudEnabled = true,
            ollamaCloudConfigured = true,
            ollamaCloudModelIdentifier = "qwen-fixture",
        )
        report.record(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.FIRECRAWL_SEARCH,
            provider = "Firecrawl Authorization: HEADER_CANARY",
            result = "FOUND",
            durationMillis = 34,
            httpStatus = 200,
            resultCount = 1,
            sourceUrls = listOf(
                "https://shop.example/item?api_key=URL_CANARY&token=URL_TOKEN#fragment",
                "https://shop.example/item/token/PATH_TOKEN_12345",
                "https://shop.example/%74oken/abc",
                "https://shop.example/product/sk-shortSecret",
                "https://192.168.1.10/private",
            ),
            reasonCodes = listOf("HIT_TEXT_MISSING_BARCODE", "Authorization: HEADER_CANARY"),
        ))
        report.record(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.LOCAL_GENERATION,
            provider = "On-device Qwen3",
            result = "COMPLETE",
            outputChunks = 49,
            generatedTokens = 49,
            finishReason = "END_OF_SEQUENCE",
        ))
        report.record(BottleLookupDebugEvent(
            stage = BottleLookupDebugStage.LOCAL_GENERATION,
            provider = "On-device Qwen3",
            result = "COMPLETE",
            generatedTokens = Int.MAX_VALUE,
            finishReason = "raw_native_output_secret",
        ))
        report.finish("PARTIAL")
        store.recordBottleLookup(report)

        val reopened = DiagnosticStore(File(dir, "diagnostics.json"), mapOf("versionName" to "updated"))
        val exported = reopened.export()
        assertTrue(exported.contains("latestBottleLookup"))
        assertTrue(exported.contains("5011333150725"))
        assertTrue(exported.contains("https://shop.example/"))
        assertTrue(exported.contains("httpStatus"))
        assertTrue(exported.contains("\"generatedTokens\": 49"))
        assertTrue(exported.contains("\"generatedTokens\": 1024"))
        assertTrue(exported.contains("\"finishReason\": \"END_OF_SEQUENCE\""))
        assertFalse(exported.contains("raw_native_output_secret"))
        assertTrue(exported.contains("\"ollamaCloud\""))
        assertTrue(exported.contains("\"configured\": true"))
        assertFalse(exported.contains("URL_CANARY"))
        assertFalse(exported.contains("URL_TOKEN"))
        assertFalse(exported.contains("HEADER_CANARY"))
        assertFalse(exported.contains("api_key="))
        assertFalse(exported.contains("fragment"))
        assertFalse(exported.contains("PATH_TOKEN_12345"))
        assertFalse(exported.contains("%74oken"))
        assertFalse(exported.contains("shortSecret"))
        assertFalse(exported.contains("192.168.1.10"))
        assertFalse(exported.contains("raw response"))
        assertTrue(reopened.latestBottleLookupPreview()!!.contains("BARCODE"))
        assertTrue(File(dir, "diagnostics.json").length() <= DiagnosticStore.MAX_BYTES)
        dir.deleteRecursively()
    }

    @Test fun clearingDiagnosticsAlsoClearsLatestLookupReport() {
        val (dir, store) = tempStore()
        val report = BottleLookupDebugReport(1, "1.0", 1, "5011333150725", "5011333150725", true, true, false, false, null, null, null)
        store.recordBottleLookup(report)
        assertTrue(store.latestBottleLookupPreview()!!.contains("5011333150725"))
        assertTrue(store.clear())
        assertFalse(File(dir, "diagnostics.json").exists())
        dir.deleteRecursively()
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}
