package com.nuvio.tv.ui.screens.player.iec

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SourceCopyPoolTest {

    private fun source(size: Int, seed: Int = 1): ByteBuffer {
        val buffer = ByteBuffer.allocate(size + 8)
        buffer.position(8)
        for (i in 0 until size) buffer.put((i * seed).toByte())
        buffer.position(8)
        return buffer
    }

    @Test
    fun `a copy holds the remaining bytes and leaves the source where it was`() {
        val pool = SourceCopyPool()
        val buffer = source(100)

        val copy = pool.copyOf(buffer)

        assertEquals(8, buffer.position())
        assertEquals(100, buffer.remaining())
        val expected = ByteArray(100) { it.toByte() }
        assertArrayEquals(expected, copy.copyOf(100))
    }

    @Test
    fun `a written copy is reused for the next access unit`() {
        val pool = SourceCopyPool()
        val first = pool.copyOf(source(3_000))
        pool.recycle(first)

        val second = pool.copyOf(source(2_500, seed = 3))

        assertSame(first, second)
        assertArrayEquals(ByteArray(2_500) { (it * 3).toByte() }, second.copyOf(2_500))
        assertEquals(0, pool.pooled)
    }

    @Test
    fun `a pooled array too small for the unit is replaced`() {
        val pool = SourceCopyPool()
        val small = pool.copyOf(source(100))
        pool.recycle(small)

        val large = pool.copyOf(source(10_000))

        assertNotSame(small, large)
        assertEquals(12_288, large.size)
    }

    @Test
    fun `copies that are never recycled are never handed out again`() {
        val pool = SourceCopyPool()
        val held = pool.copyOf(source(100))

        val next = pool.copyOf(source(100))

        assertNotSame(held, next)
    }

    @Test
    fun `the pool keeps at most its limit`() {
        val pool = SourceCopyPool(maxPooled = 2)
        repeat(5) { pool.recycle(ByteArray(4096)) }
        assertEquals(2, pool.pooled)
    }
}
