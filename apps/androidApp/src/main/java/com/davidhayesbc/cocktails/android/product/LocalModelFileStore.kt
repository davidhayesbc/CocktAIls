package com.davidhayesbc.cocktails.android.product

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

internal const val QWEN3_06B_Q8_SHA256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
internal const val MAX_LOCAL_MODEL_BYTES = 900L * 1024L * 1024L
internal const val QWEN3_06B_MODEL_PAGE = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF"

internal sealed interface LocalModelInstallResult {
    data class Installed(val file: File) : LocalModelInstallResult
    data object InvalidGguf : LocalModelInstallResult
    data object WrongModel : LocalModelInstallResult
    data object TooLarge : LocalModelInstallResult
    data object Cancelled : LocalModelInstallResult
    data object StorageFailure : LocalModelInstallResult
}

/** Installs only the reviewed model artifact. Writes are private and atomically replace the old file. */
internal class LocalModelFileStore(
    private val directory: File,
    private val expectedSha256: String = QWEN3_06B_Q8_SHA256,
    private val maxBytes: Long = MAX_LOCAL_MODEL_BYTES,
) {
    private val modelFile = File(directory, "qwen3-0.6b-q8_0.gguf")

    fun installedModel(): File? = modelFile.takeIf { it.isFile && it.length() in 1..maxBytes }

    fun import(
        input: InputStream,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): LocalModelInstallResult {
        if (expectedSha256.length != 64 || expectedSha256.any { it.digitToIntOrNull(16) == null }) {
            input.close()
            return LocalModelInstallResult.StorageFailure
        }
        if (!directory.exists() && !directory.mkdirs()) {
            input.close()
            return LocalModelInstallResult.StorageFailure
        }
        val temporary = File(directory, "model-import-${System.nanoTime()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val prefix = ByteArray(4)
        var prefixLength = 0
        try {
            input.use { source ->
                temporary.outputStream().use { rawOutput ->
                    val output = rawOutput.buffered()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) return LocalModelInstallResult.Cancelled
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) return LocalModelInstallResult.TooLarge
                        if (prefixLength < prefix.size) {
                            val copied = minOf(count, prefix.size - prefixLength)
                            buffer.copyInto(prefix, prefixLength, 0, copied)
                            prefixLength += copied
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(total)
                    }
                    output.flush()
                    rawOutput.fd.sync()
                }
            }
            if (prefixLength != 4 || !prefix.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                return LocalModelInstallResult.InvalidGguf
            }
            if (isCancelled()) return LocalModelInstallResult.Cancelled
            val actualHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            if (!actualHash.equals(expectedSha256, ignoreCase = true)) return LocalModelInstallResult.WrongModel
            if (isCancelled()) return LocalModelInstallResult.Cancelled
            return try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    modelFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                LocalModelInstallResult.Installed(modelFile)
            } catch (_: Exception) {
                LocalModelInstallResult.StorageFailure
            }
        } catch (_: Exception) {
            return LocalModelInstallResult.StorageFailure
        } finally {
            temporary.delete()
        }
    }

    fun delete(): Boolean {
        val temporaryFiles = directory.listFiles { file -> file.name.startsWith("model-import-") && file.name.endsWith(".part") }.orEmpty()
        val tempClean = temporaryFiles.all { !it.exists() || it.delete() }
        val modelClean = !modelFile.exists() || modelFile.delete()
        return tempClean && modelClean
    }
}
