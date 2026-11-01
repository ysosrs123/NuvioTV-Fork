package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AllocationRecorderTest {
    /** The runtime's table for a few allocations, in its own layout. */
    private fun table(entries: List<Triple<Int, Int, List<Triple<Int, Int, Int>>>>, classes: List<String>, methods: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        for ((size, cls, stack) in entries) {
            out.writeInt(size); out.writeShort(1); out.writeShort(cls); out.writeByte(stack.size)
            for ((fc, fm, line) in stack) { out.writeShort(fc); out.writeShort(fm); out.writeShort(0); out.writeShort(line) }
        }
        val strings = ByteArrayOutputStream()
        val so = DataOutputStream(strings)
        for (s in classes + methods + listOf("F.kt")) { so.writeInt(s.length); for (c in s) so.writeChar(c.code) }
        val head = ByteArrayOutputStream()
        val ho = DataOutputStream(head)
        ho.writeByte(15); ho.writeByte(9); ho.writeByte(8); ho.writeShort(entries.size)
        ho.writeInt(15 + body.size()); ho.writeShort(classes.size); ho.writeShort(methods.size); ho.writeShort(1)
        return head.toByteArray() + body.toByteArray() + strings.toByteArray()
    }

    @Test fun theTableIsReadBackWithSizesClassesAndSites() {
        val bytes = table(
            listOf(
                Triple(65_536, 0, listOf(Triple(0, 0, 1), Triple(1, 1, 42))),
                Triple(65_536, 0, listOf(Triple(0, 0, 1), Triple(1, 1, 42))),
                Triple(80, 2, listOf(Triple(2, 2, 7))),
            ),
            classes = listOf("[B", "Lcom/nuvio/Reader;", "Ljava/nio/DirectByteBuffer;"),
            methods = listOf("<init>", "fill", "duplicate"),
        )
        val entries = AllocationRecorder.parse(bytes)
        assertEquals(3, entries.size)
        assertEquals(65_536L, entries[0].size)
        assertEquals("[B", entries[0].className)
        assertEquals("Lcom/nuvio/Reader;.fill:42", entries[0].site)
        assertEquals("Ljava/nio/DirectByteBuffer;.duplicate:7", entries[2].site)
        val summary = AllocationRecorder.summarise(bytes)
        assertTrue(summary, summary.startsWith("3 allocations, 0 MB; sites: Lcom/nuvio/Reader;.fill:42 128 KB x2"))
        assertTrue(summary, summary.contains("classes: [B 128 KB x2"))
    }
}
