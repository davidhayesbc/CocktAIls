package com.davidhayesbc.cocktails.android.ui

import android.view.Surface
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.davidhayesbc.cocktails.android.product.LiveBottleLabelBoundary
import com.davidhayesbc.cocktails.android.product.LiveBottleLabelEdgeDetector
import com.davidhayesbc.cocktails.android.product.LiveBottleLabelEdgeStabilizer
import com.davidhayesbc.cocktails.android.product.BottleCaptureToken
import com.davidhayesbc.cocktails.android.product.NormalizedLabelCrop
import com.davidhayesbc.cocktails.android.product.bindBottleCaptureCallback
import com.davidhayesbc.cocktails.android.product.downsampleCameraLuma
import com.davidhayesbc.cocktails.android.product.mapAnalysisCropToPreview
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** In-app label camera with conservative live edges and an explicit manual shutter. */
@Composable
@OptIn(markerClass = [TransformExperimental::class])
internal fun BottleLabelCameraScreen(
    outputFile: File,
    captureToken: BottleCaptureToken,
    onPhotoSaved: (BottleCaptureToken, NormalizedLabelCrop?) -> Unit,
    onCancel: (BottleCaptureToken) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundary by remember { mutableStateOf<LiveBottleLabelBoundary?>(null) }
    var cameraMessage by remember { mutableStateOf("Starting label camera…") }
    var takingPicture by remember { mutableStateOf(false) }
    val bindCurrentViewport = remember { mutableStateOf<(() -> Unit)?>(null) }
    // Bind the callback to this capture file/token. A stale CameraX callback must never
    // be routed through a recomposed screen to a later bottle's token.
    val onSaved = remember(captureToken, onPhotoSaved) {
        { detectedCrop: NormalizedLabelCrop? -> onPhotoSaved(captureToken, detectedCrop) }
    }
    val cancelCapture = remember(captureToken, onCancel) { bindBottleCaptureCallback(captureToken, onCancel) }
    val output = remember(outputFile) { outputFile }

    DisposableEffect(previewView, lifecycleOwner, output) {
        val disposed = AtomicBoolean(false)
        val lastAnalysisNanos = AtomicLong(0)
        val detectorInitialized = AtomicBoolean(false)
        val detectorReady = AtomicBoolean(false)
        val view = previewView
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var analyzer: ImageAnalysis? = null
        val stabilizer = LiveBottleLabelEdgeStabilizer()
        var lastBoundGeometry: Pair<Int, Int>? = null
        var lastBoundRotation: Int? = null
        val imageTransformFactory = ImageProxyTransformFactory().apply {
            setUsingCropRect(true)
            setUsingRotationDegrees(true)
        }
        if (view != null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (disposed.get()) return@addListener
                runCatching {
                    provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    imageCapture = capture
                    val analysisUseCase = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    analyzer = analysisUseCase
                    bindCurrentViewport.value = {
                        if (!disposed.get() && view.width > 0 && view.height > 0) {
                            val geometry = view.width to view.height
                            val rotation = view.display?.rotation ?: Surface.ROTATION_0
                            if (geometry != lastBoundGeometry || rotation != lastBoundRotation) {
                                runCatching {
                                    val viewPort = view.viewPort
                                    if (viewPort == null) {
                                        cameraMessage = "The camera preview could not be aligned. You can still capture and crop by hand."
                                    } else {
                                        capture.targetRotation = rotation
                                        analysisUseCase.targetRotation = rotation
                                        val group = UseCaseGroup.Builder()
                                            .setViewPort(viewPort)
                                            .addUseCase(preview)
                                            .addUseCase(capture)
                                            .addUseCase(analysisUseCase)
                                            .build()
                                        provider?.unbindAll()
                                        provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                                        lastBoundGeometry = geometry
                                        lastBoundRotation = rotation
                                        cameraMessage = "No clear label edge yet. Center it in the guide; you can crop by hand after capture."
                                    }
                                }.onFailure {
                                    cameraMessage = "Camera preview alignment failed. You can still capture and crop by hand."
                                }
                            }
                        }
                    }
                    analysisUseCase.setAnalyzer(analysisExecutor) { proxy ->
                        try {
                            val now = System.nanoTime()
                            val previous = lastAnalysisNanos.get()
                            if (disposed.get() || now - previous < ANALYSIS_INTERVAL_NANOS || !lastAnalysisNanos.compareAndSet(previous, now)) return@setAnalyzer
                            if (detectorInitialized.compareAndSet(false, true)) {
                                detectorReady.set(runCatching { OpenCVLoader.initLocal() }.getOrDefault(false))
                            }
                            if (!detectorReady.get()) {
                                stabilizer.update(null)
                                ContextCompat.getMainExecutor(context).execute {
                                    if (!disposed.get()) {
                                        boundary = null
                                        cameraMessage = "Edge detection is unavailable. You can still take the photo and crop it by hand."
                                    }
                                }
                                return@setAnalyzer
                            }
                            val plane = proxy.planes.firstOrNull() ?: return@setAnalyzer
                            val buffer = plane.buffer.duplicate().slice()
                            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                            val rect = proxy.cropRect
                            val luma = downsampleCameraLuma(
                                plane = bytes,
                                width = proxy.width,
                                height = proxy.height,
                                rowStride = plane.rowStride,
                                pixelStride = plane.pixelStride,
                                cropLeft = rect.left,
                                cropTop = rect.top,
                                cropWidth = rect.width(),
                                cropHeight = rect.height(),
                            ) ?: run {
                                stabilizer.update(null)
                                ContextCompat.getMainExecutor(context).execute { if (!disposed.get()) boundary = null }
                                return@setAnalyzer
                            }
                            val detection = runCatching { LiveBottleLabelEdgeDetector.suggestLuma(luma.width, luma.height, luma.bytes) }.getOrNull()
                            val stable = stabilizer.update(detection)
                            val sourceTransform = stable?.let { runCatching { imageTransformFactory.getOutputTransform(proxy) }.getOrNull() }
                            ContextCompat.getMainExecutor(context).execute {
                                if (!disposed.get()) {
                                    val previewTransform = runCatching { view.outputTransform }.getOrNull()
                                    val mapped = if (stable != null && sourceTransform != null && previewTransform != null) {
                                        mapAnalysisCropToPreview(
                                            crop = stable.crop,
                                            analysisCropWidth = rect.width(),
                                            analysisCropHeight = rect.height(),
                                            sourceTransform = sourceTransform,
                                            previewTransform = previewTransform,
                                            viewWidth = view.width,
                                            viewHeight = view.height,
                                        )
                                    } else null
                                    boundary = mapped?.let { stable?.copy(crop = it) }
                                    cameraMessage = when {
                                        !detectorReady.get() -> "Edge detection is unavailable. You can still take a photo and crop it by hand."
                                        boundary?.stable == true -> "Label edge steady · check it, then take the photo."
                                        boundary != null -> "Adjust the bottle until the label outline steadies."
                                        else -> "No clear label edge yet. Center it in the guide; you can crop by hand after capture."
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            stabilizer.update(null)
                            ContextCompat.getMainExecutor(context).execute {
                                if (!disposed.get()) {
                                    boundary = null
                                    cameraMessage = "Label edges could not be checked. You can still take the photo and crop it by hand."
                                }
                            }
                        } finally {
                            proxy.close()
                        }
                    }
                    view.doOnLayout { bindCurrentViewport.value?.invoke() }
                }.onFailure {
                    if (!disposed.get()) cameraMessage = "Camera could not start. You can skip the photo and keep scanning."
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            disposed.set(true)
            bindCurrentViewport.value = null
            analyzer?.clearAnalyzer()
            provider?.unbindAll()
            imageCapture = null
            analysisExecutor.shutdownNow()
        }
    }

    DisposableEffect(previewView) {
        val view = previewView
        if (view == null) return@DisposableEffect onDispose { }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // PreviewView's ViewPort changes when its aspect/rotation changes. Rebind all
            // use cases as one group so the overlay transform and saved crop stay aligned.
            bindCurrentViewport.value?.invoke()
        }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("label-live-camera")) {
            AndroidView(
                factory = { viewContext -> PreviewView(viewContext).also {
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = it
                } },
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize().testTag("live-label-edge-overlay")) {
                val guide = androidx.compose.ui.geometry.Rect(
                    left = size.width * 0.14f,
                    top = size.height * 0.18f,
                    right = size.width * 0.86f,
                    bottom = size.height * 0.82f,
                )
                drawRoundRect(
                    color = Color(0xFF9BC9AE).copy(alpha = 0.55f),
                    topLeft = guide.topLeft,
                    size = guide.size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val edge = boundary
                if (edge != null) {
                    val rect = edge.crop
                    drawRoundRect(
                        color = if (edge.stable) Color(0xFF9BC9AE) else Color(0xFFE8B66F),
                        topLeft = androidx.compose.ui.geometry.Offset(rect.left * size.width, rect.top * size.height),
                        size = androidx.compose.ui.geometry.Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
        Text(cameraMessage, Modifier.fillMaxWidth().padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = cancelCapture, enabled = !takingPicture, modifier = Modifier.weight(1f).heightIn(min = 54.dp).testTag("cancel-live-label")) { Text("Cancel photo") }
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    val detectedCropAtShutter = boundary?.takeIf { it.stable }?.crop
                    val parent = output.parentFile
                    if (parent == null || (!parent.isDirectory && !parent.mkdirs()) || (output.exists() && !output.delete())) {
                        cameraMessage = "The label photo could not be prepared in local storage. Retry or cancel the photo; the bottle draft is safe."
                        return@Button
                    }
                    takingPicture = true
                    cameraMessage = "Capturing label…"
                    val options = ImageCapture.OutputFileOptions.Builder(output).build()
                    try {
                        capture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                takingPicture = false
                                onSaved(detectedCropAtShutter)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                takingPicture = false
                                output.delete()
                                cameraMessage = "Photo could not be captured. Keep the bottle steady and try again, or skip the photo."
                            }
                        })
                    } catch (_: Exception) {
                        takingPicture = false
                        output.delete()
                        cameraMessage = "Photo could not be captured. Try again or cancel the photo; the bottle draft is safe."
                    }
                },
                enabled = imageCapture != null && !takingPicture,
                modifier = Modifier.weight(1f).heightIn(min = 54.dp).testTag("capture-live-label"),
            ) { Text(if (takingPicture) "Capturing…" else "Take label photo") }
        }
    }
}

private const val ANALYSIS_INTERVAL_NANOS = 180_000_000L
