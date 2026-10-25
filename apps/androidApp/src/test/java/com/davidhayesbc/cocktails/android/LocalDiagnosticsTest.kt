package com.davidhayesbc.cocktails.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

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
        val reopened = DiagnosticStore(File(dir, "diagnostics.json"), mapOf("versionName" to "0.1.0"))
        val export = reopened.export()
        assertEquals(export, reopened.preview())
        assertTrue(Json.parseToJsonElement(export).jsonObject.containsKey("formatVersion"))
        assertTrue(Json.parseToJsonElement(export).jsonObject.containsKey("crashes"))
        assertTrue(export.contains("CAMERA_PERMISSION_GRANTED"))
        assertTrue(export.contains("IllegalStateException"))
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

    @Test fun uncaughtHandlerRecordsAndChainsToPreviousHandler() {
        val chained = AtomicBoolean(false)
        val recorded = AtomicBoolean(false)
        val thread = Thread("test")
        val failure = IllegalStateException("must not be retained")
        val handler = LocalDiagnostics.installHandlerForTest(Thread.UncaughtExceptionHandler { t, e ->
            assertEquals(thread, t)
            assertEquals(failure, e)
            chained.set(true)
        }) { t, e ->
            assertEquals(thread, t)
            assertEquals(failure, e)
            recorded.set(true)
        }
        handler.uncaughtException(thread, failure)
        assertTrue(recorded.get())
        assertTrue(chained.get())
    }

    @Test fun clearRemovesLocalDiagnosticFile() {
        val (dir, store) = tempStore()
        store.recordEvent("APP_STARTED")
        store.clear()
        assertFalse(File(dir, "diagnostics.json").exists())
        dir.deleteRecursively()
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}
