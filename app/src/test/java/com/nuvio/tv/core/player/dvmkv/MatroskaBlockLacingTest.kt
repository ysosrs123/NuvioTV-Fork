package com.nuvio.tv.core.player.dvmkv

import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaBlockLacingTest {
    @Test fun skipsAllEbmlLaceSizesIncludingSignedDeltas() {
        val block = byteArrayOf(2, 0xa0.toByte(), 0xbf.toByte(), 0x0b, 0x77)
        assertEquals(3, MatroskaBlockLacing.sampleDataOffset(block, 0, block.size, 3))
        val multiByte = byteArrayOf(3, 0x40, 0x80.toByte(), 0xbf.toByte(), 0x40, 0x80.toByte(), 0x0b, 0x77)
        assertEquals(6, MatroskaBlockLacing.sampleDataOffset(multiByte, 0, multiByte.size, 3))
    }
    @Test fun handlesNoLacingFixedAndXiph() {
        assertEquals(1, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(0, 11, 119), 1, 3, 0))
        assertEquals(1, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(2, 11, 119), 0, 3, 2))
        assertEquals(4, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(2, -1, 5, 16, 11, 119), 0, 6, 1))
    }
    @Test fun truncatedOrInvalidSizesRetainTheLateFallback() {
        assertEquals(-1, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(2, 0xa0.toByte(), 0x40), 0, 3, 3))
        assertEquals(-1, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(2, 0, 0), 0, 3, 3))
        assertEquals(-1, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(1, -1), 0, 2, 1))
        assertEquals(-1, MatroskaBlockLacing.sampleDataOffset(byteArrayOf(0), 0, 0, 2))
    }
}
