package com.davidhayesbc.cocktails.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsActionGateTest {
    @Test
    fun dismissalAndBottleSwitchInvalidatePendingReadScopes() {
        val gate = DiagnosticsActionGate()
        val bottleA = gate.open("draft-a")
        assertTrue(gate.isCurrent(bottleA))

        val bottleB = gate.open("draft-b")
        assertFalse(gate.isCurrent(bottleA))
        assertTrue(gate.isCurrent(bottleB))
        gate.dismiss()
        assertFalse(gate.isCurrent(bottleB))
    }

    @Test
    fun onlyOneExportPickerCanBeOutstandingAndStaleResultsAreDiscarded() {
        val gate = DiagnosticsActionGate()
        val first = gate.open("draft-a")
        assertTrue(gate.beginExport(first))
        assertFalse("Repeated taps must not open another picker", gate.beginExport(first))

        val second = gate.open("draft-b")
        assertFalse("The old picker owns the single outstanding slot", gate.beginExport(second))
        assertFalse("Completion for the replaced selection is stale", gate.finishExport(first))
        assertTrue("The picker slot is released after its result arrives", gate.beginExport(second))

        gate.dismiss()
        assertFalse("A result after dismissal must not be applied", gate.finishExport(second))
        assertTrue("The gate can be reused after stale completion", gate.beginExport(gate.open("draft-c")))
    }
}
