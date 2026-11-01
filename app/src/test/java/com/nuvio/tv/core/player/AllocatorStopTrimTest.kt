package com.nuvio.tv.core.player

import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Test

class AllocatorStopTrimTest {

    private val pieceBytes = 64 * 1024

    private fun allocatorWithIdlePieces(pieces: Int): DefaultAllocator {
        val allocator = DefaultAllocator(/* trimOnReset= */ false, pieceBytes)
        allocator.setTargetBufferSize(pieces * pieceBytes)
        val held = List(pieces) { allocator.allocate() }
        held.forEach(allocator::release)
        return allocator
    }

    @Test
    fun `a stopped player gives the idle memory back straight away`() {
        val allocator = allocatorWithIdlePieces(8)
        assertEquals(8 * pieceBytes, allocator.availableBytes)

        AllocatorStopTrim(allocator).onPlayerGone()

        assertEquals(0, allocator.availableBytes)
    }

    @Test
    fun `a deferred trim leaves the memory to the background pass`() {
        val allocator = allocatorWithIdlePieces(8)
        val queued = mutableListOf<Runnable>()
        val trim = AllocatorStopTrim(allocator, background = { queued += it }, settleMs = 0L)
        trim.deferred = true

        trim.onPlayerGone()
        assertEquals(8 * pieceBytes, allocator.availableBytes)

        trim.trimInBackground()
        assertEquals(1, queued.size)
        queued.single().run()
        assertEquals(0, allocator.availableBytes)
    }
}
