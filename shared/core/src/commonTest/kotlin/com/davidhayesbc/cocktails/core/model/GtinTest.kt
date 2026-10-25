package com.davidhayesbc.cocktails.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GtinTest {
    @Test
    fun validatesAndNormalizesSupportedGtinFormsWithoutDroppingLeadingZeros() {
        assertEquals("0036000291452", normalizeGtinForLookup("036000291452"))
        assertEquals("0036000291452", normalizeGtinForLookup(" 0-360-00291-452 "))
        assertEquals("4006381333931", normalizeGtinForLookup("4006381333931"))
        assertEquals("96385074", normalizeGtinForLookup("96385074"))
        assertEquals("10012345000017", normalizeGtinForLookup("10012345000017"))
    }

    @Test
    fun rejectsMalformedOrInvalidCheckDigits() {
        assertNull(normalizeGtinForLookup("4006381333932"))
        assertNull(normalizeGtinForLookup("03600029145"))
        assertNull(normalizeGtinForLookup("0360x0291452"))
    }
}
