package com.davidhayesbc.cocktails.android.ui

import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeFrameConsensusTest {
    @Test
    fun correctionRequiresAValidDifferentCodeAndNeverGuessesBetweenValidCodes() {
        assertEquals(BottleBarcodeCorrection.UNCHANGED, validateBottleBarcodeCorrection("5011333150725", "5011333150725"))
        assertEquals(BottleBarcodeCorrection.CHANGED, validateBottleBarcodeCorrection("5011333150725", "5011733110725"))
        assertEquals(BottleBarcodeCorrection.INVALID, validateBottleBarcodeCorrection("5011333150725", "5011733110724"))
    }

    @Test
    fun waitsForSeparatedMatchingFramesAndPreservesTheScannedDigits() {
        val consensus = BarcodeFrameConsensus()
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe("5011333150725", 100))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe("5011333150725", 165))
        assertEquals(BarcodeConsensusResult.Accepted("5011333150725", "5011333150725"), consensus.observe("5011333150725", 230))
    }

    @Test
    fun conflictingChecksumValidFramesResetConsensusInsteadOfSelectingOne() {
        val firstBottle = "5011333150725"
        val secondBottle = "5011733110725"
        assertTrue(normalizeGtinForLookup(firstBottle) != null)
        assertTrue(normalizeGtinForLookup(secondBottle) != null)

        val consensus = BarcodeFrameConsensus()
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe(firstBottle, 100))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe(firstBottle, 165))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe(secondBottle, 230))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe(firstBottle, 295))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe(firstBottle, 360))
        assertEquals(BarcodeConsensusResult.Accepted(firstBottle, firstBottle), consensus.observe(firstBottle, 425))
    }

    @Test
    fun invalidOrAbsentFramesClearPartialConsensus() {
        val consensus = BarcodeFrameConsensus()
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe("5011333150725", 100))
        assertEquals(BarcodeConsensusResult.Invalid, consensus.observe("5011333150724", 165))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe("5011333150725", 230))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe(null, 295))
        assertEquals(BarcodeConsensusResult.Pending, consensus.observe("5011333150725", 360))
    }
}
