package com.davidhayesbc.cocktails.android.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import com.davidhayesbc.cocktails.android.LocalDiagnostics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun BarcodeScannerDialog(
    onDetected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var scannerMessage by remember { mutableStateOf("Starting camera preview…") }
    val completed = remember { AtomicBoolean(false) }
    val currentOnDetected by rememberUpdatedState(onDetected)
    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val reader = MultiFormatReader().apply {
            setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.UPC_A, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13),
                DecodeHintType.TRY_HARDER to true,
            ))
        }
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        val disposed = AtomicBoolean(false)
        val analyzerReady = AtomicBoolean(false)
        var previewStateObserver: Observer<PreviewView.StreamState>? = null
        if (view != null) {
            previewStateObserver = Observer { streamState ->
                if (streamState == PreviewView.StreamState.STREAMING && !disposed.get()) {
                    LocalDiagnostics.recordEvent("CAMERA_PREVIEW_STREAMING")
                    scannerMessage = if (analyzerReady.get()) "Camera preview is live. Center a UPC or EAN barcode in the frame."
                    else "Camera preview is live. Preparing barcode reader…"
                }
            }
            view.previewStreamState.observe(lifecycleOwner, previewStateObserver!!)
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (disposed.get()) return@addListener
                runCatching {
                    provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val useCase = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis = useCase
                    useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            if (disposed.get() || completed.get()) return@setAnalyzer
                            val plane = imageProxy.planes.firstOrNull() ?: return@setAnalyzer
                            val crop = imageProxy.cropRect
                            val frame = copyLumaPlane(
                                buffer = plane.buffer,
                                imageWidth = imageProxy.width,
                                imageHeight = imageProxy.height,
                                rowStride = plane.rowStride,
                                pixelStride = plane.pixelStride,
                                cropLeft = crop.left,
                                cropTop = crop.top,
                                cropWidth = crop.width(),
                                cropHeight = crop.height(),
                            )
                            runCatching { decodeBarcodeFrame(reader, frame, imageProxy.imageInfo.rotationDegrees) }
                                .onSuccess { value ->
                                    if (normalizeGtinForLookup(value) == null) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            if (!disposed.get()) scannerMessage = "That code isn't a valid UPC/EAN barcode. Keep scanning or enter it manually."
                                        }
                                    } else if (completed.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            if (!disposed.get()) currentOnDetected(value)
                                        }
                                    }
                                }
                                .onFailure { error ->
                                    if (error is ReaderException && analyzerReady.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            if (!disposed.get()) scannerMessage = "Camera is live and barcode frames are being analyzed."
                                        }
                                    } else if (error !is ReaderException) {
                                        LocalDiagnostics.recordScannerFailure("BARCODE_DECODE_FAILED", error)
                                        ContextCompat.getMainExecutor(context).execute {
                                            if (!disposed.get()) scannerMessage = "Scanner error. You can enter the code manually instead."
                                        }
                                    }
                                }
                        } catch (failure: Exception) {
                            LocalDiagnostics.recordScannerFailure("CAMERA_FRAME_FAILED", failure)
                            ContextCompat.getMainExecutor(context).execute {
                                if (!disposed.get()) scannerMessage = "Camera frame couldn't be read. Close the scanner or enter the code manually."
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase)
                }.onFailure {
                    LocalDiagnostics.recordScannerFailure("CAMERA_START_FAILED", it)
                    if (!disposed.get()) scannerMessage = "Camera couldn't start. Close this scanner and enter the barcode manually."
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed.set(true)
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            previewStateObserver?.let { observer -> view?.previewStreamState?.removeObserver(observer) }
            analysisExecutor.shutdownNow()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { LocalDiagnostics.recordEvent("CAMERA_SCAN_OPENED") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan a bottle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidView(
                    factory = { viewContext -> PreviewView(viewContext).also { previewView = it } },
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag("barcode-camera-preview"),
                )
                Text(scannerMessage, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Enter manually") } },
    )
}
