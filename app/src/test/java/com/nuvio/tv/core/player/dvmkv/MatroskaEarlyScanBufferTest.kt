package com.nuvio.tv.core.player.dvmkv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaEarlyScanBufferTest {

    private val budget = 8 * 1024 * 1024
    private val cap = budget + 20

    @Test
    fun `starts at 64 KiB and doubles`() {
        assertEquals(64 * 1024, MatroskaExtractor.nextScanBufferSize(0, 12))
        assertEquals(128 * 1024, MatroskaExtractor.nextScanBufferSize(64 * 1024, 64 * 1024 + 1))
        assertEquals(4 * 1024 * 1024, MatroskaExtractor.nextScanBufferSize(64 * 1024, 3 * 1024 * 1024))
    }

    @Test
    fun `never grows to 16 MB for a scan at the end of its budget`() {
        val size = MatroskaExtractor.nextScanBufferSize(4 * 1024 * 1024, budget + 12)
        assertEquals(cap, size)
        assertTrue(size < 9 * 1024 * 1024)
    }

    @Test
    fun `a frame past the budget still gets the room it needs`() {
        val needed = budget + 40_000
        assertEquals(needed, MatroskaExtractor.nextScanBufferSize(cap, needed))
    }
}
