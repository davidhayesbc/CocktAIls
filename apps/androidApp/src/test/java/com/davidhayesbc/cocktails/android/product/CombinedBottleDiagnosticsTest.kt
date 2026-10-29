package com.davidhayesbc.cocktails.android.product

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedBottleDiagnosticsTest {
    @Test
    fun includesEveryAvailableDetailedReportWithAnExplicitCount() {
        val combined = Json.parseToJsonElement(combineBottleDiagnostics(
            """{"appVersion":"preview"}""",
            listOf("""{"draftId":"a","modelResponse":"partial result"}""", """{"draftId":"b"}"""),
        )).jsonObject

        assertEquals("preview", combined["standardDiagnostics"]!!.jsonObject["appVersion"]!!.jsonPrimitive.content)
        assertEquals(2, combined["detailedCaptureCount"]!!.jsonPrimitive.int)
        assertTrue(combined["detailedCapturesIncluded"]!!.jsonPrimitive.boolean)
        assertEquals(2, combined["detailedCaptures"]!!.jsonArray.size)
        assertTrue(combined.toString().contains("partial result"))
    }

    @Test
    fun marksDetailedDataUnavailableInsteadOfImplyingItWasIncluded() {
        val combined = Json.parseToJsonElement(combineBottleDiagnostics("{}", emptyList())).jsonObject
        assertEquals(0, combined["detailedCaptureCount"]!!.jsonPrimitive.int)
        assertFalse(combined["detailedCapturesIncluded"]!!.jsonPrimitive.boolean)
        assertTrue(combined["detailedCaptures"]!!.jsonArray.isEmpty())
    }

    @Test
    fun malformedDetailedRecordsAreNotCopiedAsUnvalidatedText() {
        val combined = Json.parseToJsonElement(combineBottleDiagnostics("{}", listOf("not json"))).jsonObject
        assertEquals(0, combined["detailedCaptureCount"]!!.jsonPrimitive.int)
        assertFalse(combined["detailedCapturesIncluded"]!!.jsonPrimitive.boolean)
        assertFalse(combined.toString().contains("not json"))
    }

    @Test
    fun clipboardSizeGuardRejectsPayloadsAboveSafeIpcLimit() {
        assertTrue(isDiagnosticsCopyWithinLimit("x".repeat(MAX_DIAGNOSTICS_COPY_BYTES)))
        assertFalse(isDiagnosticsCopyWithinLimit("x".repeat(MAX_DIAGNOSTICS_COPY_BYTES + 1)))
    }
}
