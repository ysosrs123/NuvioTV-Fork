package com.nuvio.tv.core.iptv

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalGuideFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun onlyGuideFilesDirectlyInsideImportFoldersAreListedAndOpened() {
        val internal = temp.newFolder("internal"); val usb = temp.newFolder("usb"); val outside = temp.newFolder("outside")
        File(internal, "guide.xml").writeText("<tv/>"); File(internal, "guide.xml").setLastModified(1_000_000)
        File(usb, "provider.xml.gz").writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 1)); File(usb, "provider.xml.gz").setLastModified(2_000_000)
        File(internal, "notes.txt").writeText("x"); File(internal, ".hidden.xml").writeText("<tv/>"); File(internal, "empty.xml").writeText("")
        File(internal, "nested").mkdir(); File(internal, "nested/deep.xml").writeText("<tv/>")
        val secret = File(outside, "secret.xml").apply { writeText("<tv/>") }
        Files.createSymbolicLink(File(internal, "link.xml").toPath(), secret.toPath())
        val files = LocalGuideFiles({ listOf(internal, usb, File(temp.root, "missing")) })
        val listed = files.list()
        assertEquals(listOf("provider.xml.gz", "guide.xml"), listed.map { it.name })
        assertEquals(listOf(1, 0), listed.map { it.rootIndex })
        assertEquals("<tv/>", files.open(listed.last().uri).use { it.readBytes().decodeToString() })
        for (uri in listOf(secret.toURI().toString(), File(internal, "link.xml").toURI().toString(), File(internal, "nested/deep.xml").toURI().toString(),
                File(internal, "notes.txt").toURI().toString(), "content://x/guide.xml", "file://host/guide.xml", listed.last().uri + "?x=1"))
            try { files.open(uri).close(); fail(uri) } catch (_: SecurityException) { }
        assertTrue(LocalGuideFiles.isLocalGuide(listed.first().uri))
    }
    @Test fun sizeAndCountLimitsApply() {
        val root = temp.newFolder("guides")
        (1..5).forEach { File(root, "g$it.xml").writeText("<tv>${"x".repeat(it * 10)}</tv>") }
        val files = LocalGuideFiles({ listOf(root) }, maxBytes = 40, maxFiles = 2)
        assertEquals(2, files.list().size); assertTrue(files.list().all { it.bytes <= 40 })
        assertNull(files.resolve(File(root, "g5.xml").toURI().toString()))
    }
}
