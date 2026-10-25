package com.davidhayesbc.cocktails.android.ui

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps camera preview and analyzer readiness independent from callback ordering. */
internal class ScannerReadiness {
    private val previewStreaming = AtomicBoolean(false)
    private val analyzerReady = AtomicBoolean(false)

    fun markPreviewStreaming(): Boolean = previewStreaming.compareAndSet(false, true)
    fun markAnalyzerReady(): Boolean = analyzerReady.compareAndSet(false, true)

    fun message(): String = when {
        previewStreaming.get() && analyzerReady.get() -> ANALYZER_READY_MESSAGE
        previewStreaming.get() -> PREVIEW_READY_MESSAGE
        else -> STARTING_MESSAGE
    }

    companion object {
        const val STARTING_MESSAGE = "Starting camera preview…"
        const val PREVIEW_READY_MESSAGE = "Camera preview is live. Preparing barcode reader…"
        const val ANALYZER_READY_MESSAGE = "Camera is live and barcode frames are being analyzed."
    }
}
