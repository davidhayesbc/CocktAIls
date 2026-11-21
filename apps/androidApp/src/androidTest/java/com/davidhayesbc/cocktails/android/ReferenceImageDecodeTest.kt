package com.davidhayesbc.cocktails.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davidhayesbc.cocktails.android.ui.decodeReferenceBitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceImageDecodeTest {
    @Test fun missingAndCorruptFilesAreIgnored() = withTemporaryImage { file ->
        assertNull(decodeReferenceBitmap(file))
        file.writeText("not an image")
        assertNull(decodeReferenceBitmap(file))
    }

    @Test fun ordinaryAndLargeImagesDecode() = withTemporaryImage { file ->
        for ((width, expected) in listOf(640 to 640, 3200 to 1600)) {
            val original = Bitmap.createBitmap(width, 64, Bitmap.Config.ARGB_8888)
            try {
                file.outputStream().use { check(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                original.recycle()
            }
            val decoded = checkNotNull(decodeReferenceBitmap(file))
            try {
                assertEquals(expected, decoded.width)
            } finally {
                decoded.recycle()
            }
        }
    }

    private fun withTemporaryImage(test: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val folder = File(cache, java.util.UUID.randomUUID().toString())
        check(folder.mkdir())
        val file = File(folder, "reference.png")
        try {
            test(file)
        } finally {
            file.delete()
            folder.delete()
        }
    }
}
