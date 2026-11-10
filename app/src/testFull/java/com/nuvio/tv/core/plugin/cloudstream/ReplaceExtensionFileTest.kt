package com.nuvio.tv.core.plugin.cloudstream

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplaceExtensionFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a new extension file is written with no temp file left behind`() {
        val target = File(folder.root, "a.cs3")
        replaceFileKeepingOldOnFailure(target, byteArrayOf(1, 2), readOnly = false)
        assertArrayEquals(byteArrayOf(1, 2), target.readBytes())
        assertEquals(listOf("a.cs3"), folder.root.list()!!.toList())
    }

    @Test
    fun `a read-only extension file can be replaced`() {
        val target = File(folder.root, "a.cs3").apply { writeBytes(byteArrayOf(1)); setReadOnly() }
        replaceFileKeepingOldOnFailure(target, byteArrayOf(2), readOnly = true)
        assertArrayEquals(byteArrayOf(2), target.readBytes())
    }

    @Test
    fun `when the new file cannot be written the old one is kept`() {
        val target = File(folder.root, "a.cs3").apply { writeBytes(byteArrayOf(1)) }
        File(folder.root, "a.cs3.tmp").mkdir()
        File(folder.root, "a.cs3.tmp/blocker").writeBytes(byteArrayOf(0))
        runCatching { replaceFileKeepingOldOnFailure(target, byteArrayOf(2), readOnly = false) }
        assertArrayEquals(byteArrayOf(1), target.readBytes())
    }
}
