package com.davidhayesbc.cocktails.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerReadinessTest {
    @Test fun analyzerThenPreviewCallbacksExposeReadyStateAndDoNotRegress() {
        val state = ScannerReadiness()
        state.markAnalyzerReady()
        val pendingMainThreadUpdate = { state.message() }
        assertEquals(true, state.markPreviewStreaming())
        assertEquals(false, state.markPreviewStreaming())
        assertEquals(ScannerReadiness.ANALYZER_READY_MESSAGE, pendingMainThreadUpdate())
        assertEquals(false, state.markPreviewStreaming())
        assertEquals(false, state.markAnalyzerReady())
        assertEquals(ScannerReadiness.ANALYZER_READY_MESSAGE, state.message())
    }

    @Test fun previewThenAnalyzerCallbacksExposeReadyState() {
        val state = ScannerReadiness()
        state.markPreviewStreaming()
        assertEquals(ScannerReadiness.PREVIEW_READY_MESSAGE, state.message())
        state.markAnalyzerReady()
        assertEquals(ScannerReadiness.ANALYZER_READY_MESSAGE, state.message())
    }
}
