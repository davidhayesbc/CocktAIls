package com.davidhayesbc.cocktails.android.product
import com.davidhayesbc.cocktails.core.data.*
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test

class UserReferencePackTest {
    private val bytes=byteArrayOf(1,2,3,4)
    private val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun catalog(): String {
        val file=generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it,"catalog-packs/bc-products/products.json") }.first { it.exists() }
        val pack=ReferenceProductCodec.decode(file.readText())
        return ReferenceProductCodec.encode(pack.copy(sourceId="user-fixture",supplementalSourceSha256="a".repeat(64),supplementalNotice=USER_CONTENT_NOTICE,
            records=listOf(pack.records.first().copy(referenceDescription="Plain user description",imageSha256=hash,imageMediaType="image/png"))))
    }
    private fun archive(entries: Map<String,ByteArray>): ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } }
        return output.toByteArray()
    }
    @Test fun validatedFilesPublishBeforeDatabaseAndRepeatWithoutReplacingPhotos() {
        val root=Files.createTempDirectory("user-pack").toFile()
        try {
            val assets=File(root,"assets");val store=UserReferencePack(root,assets)
            val userPhoto=File(root,"user-photo").apply { writeBytes(byteArrayOf(9,8)) }
            val input=archive(mapOf("catalog.json" to catalog().toByteArray(),"images/$hash.png" to bytes))
            repeat(2) { iteration ->
                val prepared=store.prepare(input.inputStream(),ReferenceProductCodec::decode) { it.readBytes().contentEquals(bytes) }
                assertEquals(if(iteration==0) 1 else 0,store.install(prepared) { text ->
                    assertTrue(store.image(hash,"image/png")!!.readBytes().contentEquals(bytes))
                    assertEquals("Plain user description",ReferenceProductCodec.decode(text).records.single().referenceDescription)
                    if(iteration==0) 1 else 0
                })
                assertFalse(prepared.directory.exists())
            }
            assertTrue(userPhoto.readBytes().contentEquals(byteArrayOf(9,8)))
            assertEquals(1,assets.listFiles()!!.size)
        } finally {root.deleteRecursively()}
    }
    @Test fun rejectsPathsMissingAssetsCorruptionAndPixelValidationBeforeCommit() {
        val root=Files.createTempDirectory("user-pack-invalid").toFile()
        try {
            val store=UserReferencePack(root,File(root,"assets"))
            for(entries in listOf(mapOf("../escape" to bytes), mapOf("catalog.json" to catalog().toByteArray()),
                mapOf("catalog.json" to catalog().toByteArray(),"images/$hash.png" to byteArrayOf(5)))) {
                assertThrows(Exception::class.java) { store.prepare(archive(entries).inputStream(),ReferenceProductCodec::decode) {true} }
            }
            val valid=archive(mapOf("catalog.json" to catalog().toByteArray(),"images/$hash.png" to bytes))
            assertThrows(IllegalArgumentException::class.java) {store.prepare(valid.inputStream(),ReferenceProductCodec::decode) {false}}
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun databaseFailureRemovesOnlyNewAssetsAndRetainsExistingFiles() {
        val root=Files.createTempDirectory("user-pack-rollback").toFile()
        try {
            val assets=File(root,"assets").apply {mkdirs()};val existing=File(assets,"existing").apply {writeText("keep")}
            val store=UserReferencePack(root,assets)
            val prepared=store.prepare(archive(mapOf("catalog.json" to catalog().toByteArray(),"images/$hash.png" to bytes)).inputStream(),ReferenceProductCodec::decode) {true}
            assertThrows(IllegalStateException::class.java) {store.install(prepared) {error("transaction rejected")}}
            assertEquals(listOf(existing),assets.listFiles()!!.toList())
            assertEquals("keep",existing.readText());assertFalse(prepared.directory.exists())
            assertThrows(IllegalArgumentException::class.java) {store.installText(catalog()) {error("must not reach database")}}
        } finally {root.deleteRecursively()}
    }
}
