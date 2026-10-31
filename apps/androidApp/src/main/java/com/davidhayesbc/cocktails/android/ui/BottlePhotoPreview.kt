package com.davidhayesbc.cocktails.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Uses the retained image itself; the thumbnail is never enlarged or recompressed. */
@Composable
internal fun BottlePhotoPreview(photo: ImageBitmap, description: String, onDismiss: () -> Unit) {
    var zoom by remember(photo) { mutableStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("bottle-photo-preview")) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Label photo", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-bottle-photo")) { Text("Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(photo) {
                    detectTransformGestures { _, delta, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 6f)
                        val limit = Offset(size.width * (zoom - 1) / 2, size.height * (zoom - 1) / 2)
                        val next = pan + delta
                        pan = Offset(next.x.coerceIn(-limit.x, limit.x), next.y.coerceIn(-limit.y, limit.y))
                    }
                }) {
                    Image(photo, description, Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                    }, contentScale = ContentScale.Fit)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f); pan = Offset.Zero }, enabled = zoom > 1f) { Text("Zoom out") }
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Reset") }
                    TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(6f) }, enabled = zoom < 6f, modifier = Modifier.testTag("zoom-bottle-photo")) { Text("Zoom in") }
                }
            }
        }
    }
}
