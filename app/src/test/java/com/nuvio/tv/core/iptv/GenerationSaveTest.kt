package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GenerationSaveTest {
    private class Table(var published: List<Int> = listOf(1, 2, 3)) : GenerationWriter<Int> {
        val pending = mutableListOf<Int>()
        val chunks = mutableListOf<Int>()
        var failOnChunk: Int? = null
        var stale = false
        var discards = 0
        override fun write(rows: List<Int>) {
            if (chunks.size == failOnChunk) throw IllegalStateException("disk full")
            chunks += rows.size; pending += rows
        }
        override fun publish(): Boolean {
            if (stale) return false
            published = pending.toList(); pending.clear(); return true
        }
        override fun discard() { discards++; pending.clear() }
    }

    @Test fun rowsAreWrittenInBoundedChunksThenPublishedOnce() {
        val table = Table()
        assertTrue(saveGeneration((1..6_001).toList(), table, chunkRows = 2_500))
        assertEquals(listOf(2_500, 2_500, 1_001), table.chunks)
        assertEquals((1..6_001).toList(), table.published)
        assertEquals(0, table.discards)
    }

    @Test fun failureMidSaveLeavesThePreviousListPublishedAndDiscardsThePartialGeneration() {
        val table = Table().apply { failOnChunk = 1 }
        assertThrows(IllegalStateException::class.java) { saveGeneration((1..10).toList(), table, chunkRows = 4) }
        assertEquals(listOf(1, 2, 3), table.published)
        assertTrue(table.pending.isEmpty())
        assertEquals(1, table.discards)
    }

    @Test fun cancellationBetweenChunksDiscardsWithoutPublishing() {
        val table = Table()
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            saveGeneration((1..10).toList(), table, chunkRows = 4) { if (++checks == 2) throw InterruptedException() }
        }
        assertEquals(listOf(4), table.chunks)
        assertEquals(listOf(1, 2, 3), table.published)
        assertEquals(1, table.discards)
    }

    @Test fun refusedPublishDiscardsAndReportsNotPublished() {
        val table = Table().apply { stale = true }
        assertFalse(saveGeneration(listOf(9), table))
        assertEquals(listOf(1, 2, 3), table.published)
        assertEquals(1, table.discards)
    }

    @Test fun discardFailureIsSuppressedBehindTheOriginalError() {
        val writer = object : GenerationWriter<Int> {
            override fun write(rows: List<Int>) = throw IllegalStateException("write")
            override fun publish() = true
            override fun discard() = throw IllegalArgumentException("cleanup")
        }
        val error = assertThrows(IllegalStateException::class.java) { saveGeneration(listOf(1), writer) }
        assertEquals("cleanup", error.suppressed.single().message)
    }

    @Test fun emptyListStillReachesThePublishDecision() {
        val table = Table()
        assertTrue(saveGeneration(emptyList(), table))
        assertTrue(table.published.isEmpty())
    }
}
