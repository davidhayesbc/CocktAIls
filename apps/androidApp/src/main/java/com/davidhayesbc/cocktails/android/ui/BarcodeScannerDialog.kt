package com.davidhayesbc.cocktails.android.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.util.concurrent.atomic.AtomicInteger

@Composable
internal fun BarcodeScannerDialog(
    onDetected: (String) -> Unit,
    onDismiss: () -> Unit,
    continuous: Boolean = false,
    fullScreen: Boolean = false,
    batchBottleCount: Int? = null,
    readyCount: Int = 0,
    runningCount: Int = 0,
    pendingCount: Int = 0,
    onReviewReady: () -> Unit = {},
    onPause: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var scannerMessage by remember { mutableStateOf("Starting camera preview…") }
    val completed = remember { AtomicBoolean(false) }
    val armed = remember { AtomicBoolean(true) }
    val absentFrames = remember { AtomicInteger(0) }
    var lastAccepted by remember { mutableStateOf<String?>(null) }
    var acceptedCount by remember { mutableStateOf(0) }
    val readiness = remember(previewView, lifecycleOwner) { ScannerReadiness() }
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
        fun publishReadiness() {
            ContextCompat.getMainExecutor(context).execute {
                if (!disposed.get()) scannerMessage = readiness.message()
            }
        }
        var previewStateObserver: Observer<PreviewView.StreamState>? = null
        if (view != null) {
            previewStateObserver = Observer { streamState ->
                if (streamState == PreviewView.StreamState.STREAMING && !disposed.get()) {
                    if (readiness.markPreviewStreaming()) {
                        LocalDiagnostics.recordEvent("CAMERA_PREVIEW_STREAMING")
                        publishReadiness()
                    }
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
                            if (disposed.get() || (!continuous && completed.get())) return@setAnalyzer
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
                                    } else if (!continuous && completed.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            if (!disposed.get()) {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                currentOnDetected(value)
                                            }
                                        }
                                    }
                                    if (continuous) {
                                        absentFrames.set(0)
                                        if (armed.compareAndSet(true, false)) {
                                            ContextCompat.getMainExecutor(context).execute {
                                                if (!disposed.get()) {
                                                    lastAccepted = value
                                                    acceptedCount += 1
                                                    scannerMessage = "Bottle $acceptedCount added. Move its barcode out of view before scanning the next one."
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    currentOnDetected(value)
                                                }
                                            }
                                        }
                                    }
                                    Unit
                                }
                                .onFailure { error ->
                                    if (continuous && error is ReaderException && absentFrames.incrementAndGet() >= 8) {
                                        armed.set(true)
                                    }
                                    if (error is ReaderException && readiness.markAnalyzerReady()) {
                                        publishReadiness()
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

    LaunchedEffect(Unit) { LocalDiagnostics.recordEvent("CAMERA_SCAN_OPENED") }

    if (fullScreen) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().testTag("barcode-intake-scanner"), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (continuous) "Add bottles" else "Scan the next bottle",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.testTag("barcode-intake-scanner-title"),
                    )
                    Text(scannerMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("barcode-camera-frame")) {
                        AndroidView(
                            factory = { viewContext -> PreviewView(viewContext).also {
                                // Use TextureView-backed rendering so the camera preview stays
                                // inside its weighted Box instead of covering the Compose header.
                                it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                previewView = it
                            } },
                            modifier = Modifier.fillMaxSize().clipToBounds().testTag("barcode-camera-preview"),
                        )
                        Canvas(Modifier.align(Alignment.Center).fillMaxWidth(0.84f).height(152.dp)) {
                            val inset = 3.dp.toPx()
                            drawRoundRect(
                                color = Color(0xFF9BC9AE),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - inset * 2, size.height - inset * 2),
                                cornerRadius = CornerRadius(18.dp.toPx()),
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.testTag("scanner-queue-summary"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$readyCount ready", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("scanner-ready-count"))
                                Text("$runningCount running", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("scanner-running-count"))
                                Text("$pendingCount pending", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("scanner-pending-count"))
                            }
                            Text("${batchBottleCount ?: acceptedCount} bottles in this batch", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (readyCount > 0) {
                                    TextButton(onClick = onReviewReady, modifier = Modifier.weight(1f).testTag("scanner-review-ready")) { Text("Review ready") }
                                } else {
                                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Enter manually") }
                                }
                                TextButton(onClick = onPause, modifier = Modifier.weight(1f).testTag("scanner-pause")) { Text("Pause") }
                            }
                        }
                    }
                    if (continuous && lastAccepted != null) {
                        TextButton(onClick = { lastAccepted?.let(currentOnDetected); acceptedCount += 1 }, modifier = Modifier.fillMaxWidth()) {
                            Text("Add another bottle with this barcode")
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close scanner") }
                }
            }
        }
    } else AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (continuous) "Add bottles" else "Scan a bottle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidView(
                    factory = { viewContext -> PreviewView(viewContext).also { previewView = it } },
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag("barcode-camera-preview"),
                )
                Text(scannerMessage, style = MaterialTheme.typography.bodySmall)
                if (continuous && lastAccepted != null) {
                    TextButton(onClick = { lastAccepted?.let(currentOnDetected); acceptedCount += 1 }) {
                        Text("Add another bottle with this barcode")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Enter manually") } },
    )
}
