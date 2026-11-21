package com.davidhayesbc.cocktails.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.davidhayesbc.cocktails.android.product.UserReferencePack
import com.davidhayesbc.cocktails.core.data.ReferenceProduct
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reference-only content is never copied into the user's label photo or sent to OCR. */
@Composable
internal fun UserReferenceContent(product: ReferenceProduct?, hasUserPhoto: Boolean) {
    if (product == null) return
    val context = LocalContext.current
    val imageHash = product.imageSha256
    val mediaType = product.imageMediaType
    val bitmap by produceState<ImageBitmap?>(null, imageHash, mediaType, hasUserPhoto) {
        value = null
        if (!hasUserPhoto) {
            value = withContext(Dispatchers.IO) {
                val pack = UserReferencePack(context.cacheDir, File(context.filesDir, "reference-images"))
                val file = pack.image(imageHash, mediaType)
                coroutineContext.ensureActive()
                file?.let { decodeReferenceBitmap(it)?.asImageBitmap() }
            }
        }
    }
    var expanded by remember(product.imageSha256) { mutableStateOf(false) }
    if (bitmap != null && !hasUserPhoto) {
        Text("Reference image · user-provided", style=MaterialTheme.typography.labelMedium)
        Image(bitmap!!, "Reference product image", Modifier.fillMaxWidth().height(180.dp).clickable(onClickLabel="Enlarge reference image") { expanded=true }, contentScale=ContentScale.Fit)
        if(expanded) Dialog(onDismissRequest={expanded=false}) {
            Surface { Column(Modifier.padding(12.dp)) {
                Image(bitmap!!,"Reference product image",Modifier.fillMaxWidth().heightIn(max=560.dp),contentScale=ContentScale.Fit)
                TextButton(onClick={expanded=false}) { Text("Close") }
            } }
        }
    }
    product.referenceDescription?.let {
        Text("Reference description · user-provided", style=MaterialTheme.typography.labelMedium)
        Text(it, style=MaterialTheme.typography.bodySmall)
    }
}


internal fun decodeReferenceBitmap(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val sampleSize = referenceImageSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.path, options)
}

internal fun referenceImageSampleSize(width: Int, height: Int): Int? {
    if (width !in 1..4096 || height !in 1..4096) return null
    val longestSide = maxOf(width, height)
    var sampleSize = 1
    while (longestSide > 1600 * sampleSize) sampleSize *= 2
    return sampleSize
}
