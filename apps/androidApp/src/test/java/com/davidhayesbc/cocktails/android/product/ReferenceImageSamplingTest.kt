package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.android.ui.referenceImageSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceImageSamplingTest {
    @Test fun ordinaryImagesUsePositiveSampleSize() {
        assertEquals(1, referenceImageSampleSize(640, 480))
        assertEquals(1, referenceImageSampleSize(1600, 1))
    }

    @Test fun largeImagesUseBoundedPowerOfTwoSampleSize() {
        assertEquals(2, referenceImageSampleSize(1, 1601))
        assertEquals(4, referenceImageSampleSize(4096, 100))
    }

    @Test fun invalidOrUnavailableDimensionsAreRejected() {
        assertNull(referenceImageSampleSize(-1, -1))
        assertNull(referenceImageSampleSize(1, 0))
        assertNull(referenceImageSampleSize(Int.MAX_VALUE, 100))
    }
}
