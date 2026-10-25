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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.davidhayesbc.cocktails.core.model.normalizeGtinForLookup
import java.util.EnumMap
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
    var scannerMessage by remember { mutableStateOf("Center a UPC or EAN barcode in the frame.") }
    val completed = remember { AtomicBoolean(false) }
    val currentOnDetected by rememberUpdatedState(onDetected)
    val reader = remember {
        MultiFormatReader().apply {
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
            hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(
                BarcodeFormat.UPC_A,
                BarcodeFormat.EAN_8,
                BarcodeFormat.EAN_13,
            )
            hints[DecodeHintType.TRY_HARDER] = true
            setHints(hints)
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var disposed = false
        if (view != null) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                if (disposed) return@addListener
                runCatching {
                    provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val useCase = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis = useCase
                    useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            if (completed.get()) return@setAnalyzer
                            val plane = imageProxy.planes.firstOrNull() ?: return@setAnalyzer
                            val width = imageProxy.width
                            val height = imageProxy.height
                            val yBytes = ByteArray(width * height)
                            val yBuffer = plane.buffer.duplicate()
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            for (row in 0 until height) {
                                for (column in 0 until width) {
                                    yBytes[row * width + column] = yBuffer.get(row * rowStride + column * pixelStride)
                                }
                            }
                            val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
                            var bitmap = BinaryBitmap(HybridBinarizer(source))
                            repeat((imageProxy.imageInfo.rotationDegrees / 90).coerceIn(0, 3)) {
                                bitmap = bitmap.rotateCounterClockwise()
                            }
                            runCatching { reader.decodeWithState(bitmap).text }
                                .onSuccess { value ->
                                    if (normalizeGtinForLookup(value) == null) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            scannerMessage = "That code isn't a valid UPC/EAN barcode. Keep scanning or enter it manually."
                                        }
                                    } else if (completed.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(context).execute { currentOnDetected(value) }
                                    }
                                }
                                .onFailure { error ->
                                    if (error !is ReaderException) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            scannerMessage = "Scanner error. You can enter the code manually instead."
                                        }
                                    }
                                }
                        } finally {
                            imageProxy.close()
                        }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase)
                }.onFailure {
                    scannerMessage = "Camera couldn't start. Close this scanner and enter the barcode manually."
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            analysisExecutor.shutdown()
            reader.reset()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan a bottle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidView(
                    factory = { viewContext -> PreviewView(viewContext).also { previewView = it } },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                )
                Text(scannerMessage, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Enter manually") } },
    )
}
