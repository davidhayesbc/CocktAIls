package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.data.ReferenceProductCodec
import com.davidhayesbc.cocktails.core.data.ReferenceProductPack
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class PreparedReferencePack(val text: String, val pack: ReferenceProductPack, val directory: File) {
    fun discard() { directory.deleteRecursively() }
}

/** A bounded, self-contained user-data archive. Never follows paths or URLs from its contents. */
internal class UserReferencePack(private val cache: File, private val assets: File) {
    fun prepare(input: InputStream, validate: (String) -> ReferenceProductPack, validatePixels: (File) -> Boolean): PreparedReferencePack {
        val stage = File(cache, "reference-stage-${UUID.randomUUID()}")
        require(stage.mkdirs())
        try {
            val entries = mutableSetOf<String>(); var total = 0L
            ZipInputStream(object : java.io.FilterInputStream(input) {
                var consumed = 0L
                override fun read(): Int { val n=super.read(); if(n>=0) { consumed++; require(consumed<=100_000_000) }; return n }
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n=`in`.read(b,off,len); if(n>0) { consumed+=n; require(consumed<=100_000_000) }; return n }
            }).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entries.add(entry.name) && entries.size <= 20_001)
                    require(entry.name == "catalog.json" || entry.name.matches(Regex("images/[0-9a-f]{64}\\.(webp|jpg|png)")))
                    val limit = if (entry.name == "catalog.json") 10_000_000 else 2_000_000
                    val file = File(stage, entry.name); file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        var size = 0; val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            size += n; total += n
                            require(size <= limit && total <= 100_000_000)
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val text = File(stage, "catalog.json").readBytes().decodeToString(throwOnInvalidSequence = true)
            val pack = validate(text)
            val expected = pack.records.mapNotNull { row -> row.imageSha256?.let { imageName(it, row.imageMediaType!!) } }.toSet()
            require(entries == expected + "catalog.json")
            for (name in expected) {
                val file = File(stage, name)
                require(sha256(file) == file.name.substringBefore('.'))
                require(validatePixels(file))
            }
            return PreparedReferencePack(text, pack, stage)
        } catch (failure: Throwable) { stage.deleteRecursively(); throw failure }
    }

    /** Publish immutable content-addressed assets before atomically switching database references.
     * A crash may leave unreferenced images, never missing images for a committed catalogue.
     */
    fun install(prepared: PreparedReferencePack, installDatabase: (String) -> Int): Int = synchronized(INSTALL_LOCK) {
        assets.mkdirs()
        val created = mutableListOf<File>()
        try {
            for (row in prepared.pack.records) {
                val hash = row.imageSha256 ?: continue
                val name = imageName(hash, row.imageMediaType!!).substringAfter('/')
                val destination = File(assets, name)
                if (destination.exists()) { require(sha256(destination) == hash); continue }
                val staged = File(prepared.directory, "images/$name")
                require(sha256(staged) == hash)
                val temporary = File(assets, ".$name-${UUID.randomUUID()}.tmp")
                try {
                    staged.inputStream().use { src -> temporary.outputStream().use { dst -> src.copyTo(dst); dst.fd.sync() } }
                    require(temporary.renameTo(destination))
                    created += destination
                } finally { temporary.delete() }
            }
            installDatabase(prepared.text)
        } catch (failure: Throwable) {
            created.forEach { it.delete() }
            throw failure
        } finally { prepared.discard() }
    }

    fun installText(text: String, installDatabase: (String) -> Int): Int = synchronized(INSTALL_LOCK) {
        val pack = ReferenceProductCodec.decode(text)
        require(pack.records.all { it.imageSha256 == null || image(it.imageSha256, it.imageMediaType) != null })
        installDatabase(text)
    }

    fun image(hash: String?, mediaType: String?): File? {
        if (hash == null || mediaType == null || !hash.matches(Regex("[0-9a-f]{64}"))) return null
        return runCatching { File(assets, imageName(hash, mediaType).substringAfter('/')).takeIf { it.isFile && sha256(it) == hash } }.getOrNull()
    }

    companion object {
        private val INSTALL_LOCK = Any()
        fun imageName(hash: String, type: String) = "images/$hash." + when(type) {
            "image/webp" -> "webp"; "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> error("Unsupported image")
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b=ByteArray(8192); while(true) { val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
