package com.davidhayesbc.cocktails.android.product

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelFileStoreTest {
    @Test
    fun importVerifiesGgufAndDigestBeforeAtomicInstall() {
        val dir = Files.createTempDirectory("cocktails-model").toFile()
        val payload = ggufPayload("local fixture")
        val expected = sha256(payload)
        try {
            val result = LocalModelFileStore(dir, expected, maxBytes = 1_000).import(ByteArrayInputStream(payload))
            assertTrue(result is LocalModelInstallResult.Installed)
            assertEquals(payload.toList(), (result as LocalModelInstallResult.Installed).file.readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidHeaderAndWrongHashNeverReplaceInstalledModel() {
        val dir = Files.createTempDirectory("cocktails-model").toFile()
        val valid = ggufPayload("old model")
        val store = LocalModelFileStore(dir, sha256(valid), maxBytes = 1_000)
        try {
            val installed = store.import(ByteArrayInputStream(valid)) as LocalModelInstallResult.Installed
            assertEquals(LocalModelInstallResult.InvalidGguf, LocalModelFileStore(dir, sha256(valid), 1_000).import(ByteArrayInputStream("NOPEbad".toByteArray())))
            assertEquals(LocalModelInstallResult.WrongModel, store.import(ByteArrayInputStream(ggufPayload("different"))))
            assertEquals(valid.toList(), installed.file.readBytes().toList())
            assertTrue(dir.listFiles().orEmpty().none { it.extension == "part" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizedArtifactIsRejectedAndTemporaryFileRemoved() {
        val dir = Files.createTempDirectory("cocktails-model").toFile()
        try {
            val result = LocalModelFileStore(dir, "0".repeat(64), maxBytes = 8)
                .import(ByteArrayInputStream(ggufPayload("too large")))
            assertEquals(LocalModelInstallResult.TooLarge, result)
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".gguf") })
            assertTrue(dir.listFiles().orEmpty().none { it.extension == "part" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deletingModelAlsoCleansInterruptedImports() {
        val dir = Files.createTempDirectory("cocktails-model").toFile()
        val payload = ggufPayload("fixture")
        val store = LocalModelFileStore(dir, sha256(payload), maxBytes = 1_000)
        try {
            assertTrue(store.import(ByteArrayInputStream(payload)) is LocalModelInstallResult.Installed)
            java.io.File(dir, "model-import-stale.part").writeBytes(byteArrayOf(1))
            assertTrue(store.delete())
            assertEquals(null, store.installedModel())
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun canceledImportRemovesPartialFileAndKeepsPreviousModel() {
        val dir = Files.createTempDirectory("cocktails-model").toFile()
        val previous = ggufPayload("previous")
        val store = LocalModelFileStore(dir, sha256(previous), maxBytes = 1_000)
        try {
            val installed = store.import(ByteArrayInputStream(previous)) as LocalModelInstallResult.Installed
            var bytesRead = 0L
            val result = store.import(
                ByteArrayInputStream(ggufPayload("replacement")),
                isCancelled = { bytesRead > 0 },
                onProgress = { bytesRead = it },
            )

            assertEquals(LocalModelInstallResult.Cancelled, result)
            assertEquals(previous.toList(), installed.file.readBytes().toList())
            assertTrue(dir.listFiles().orEmpty().none { it.extension == "part" })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ggufPayload(text: String) = byteArrayOf(0x47, 0x47, 0x55, 0x46) + text.toByteArray()

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
