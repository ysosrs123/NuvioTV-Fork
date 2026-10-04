package com.nuvio.tv.data.trailer

import androidx.media3.common.C
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YoutubeChunkReaderTest {

    private class FakeStream(
        private val data: ByteArray,
        private val failOpenAt: Long? = null,
        private val pastEndIsRefused: Boolean = false
    ) {
        val openedRanges = mutableListOf<Pair<Long, Long>>()
        private var position = 0
        private var chunkLimit = 0

        fun open(start: Long, end: Long): Boolean {
            openedRanges += start to end
            if (start == failOpenAt) throw IOException("403")
            if (start >= data.size && pastEndIsRefused) return false
            position = minOf(start, data.size.toLong()).toInt()
            chunkLimit = minOf(end + 1, data.size.toLong()).toInt()
            return true
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= chunkLimit) return C.RESULT_END_OF_INPUT
            val count = minOf(length, chunkLimit - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
    }

    private fun reader(stream: FakeStream, chunkSize: Long) =
        YoutubeChunkReader(chunkSize, stream::open, stream::read, closeChunk = {})

    private fun YoutubeChunkReader.readAll(bufferSize: Int = 7): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `a trailer shorter than one chunk ends normally`() {
        val data = bytes(40)
        val stream = FakeStream(data)
        val reader = reader(stream, chunkSize = 100)

        reader.open(position = 0, length = C.LENGTH_UNSET.toLong())

        assertArrayEquals(data, reader.readAll())
        assertEquals(listOf(0L to 99L), stream.openedRanges)
        assertEquals(C.RESULT_END_OF_INPUT, reader.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `a trailer spanning several chunks is read to its end`() {
        val data = bytes(250)
        val stream = FakeStream(data)
        val reader = reader(stream, chunkSize = 100)

        reader.open(position = 0, length = C.LENGTH_UNSET.toLong())

        assertArrayEquals(data, reader.readAll())
        assertEquals(listOf(0L to 99L, 100L to 199L, 200L to 299L), stream.openedRanges)
    }

    @Test
    fun `a trailer that is an exact number of chunks ends normally`() {
        val data = bytes(200)

        val emptyLastChunk = reader(FakeStream(data), chunkSize = 100)
        emptyLastChunk.open(position = 0, length = C.LENGTH_UNSET.toLong())
        assertArrayEquals(data, emptyLastChunk.readAll())

        val refusedLastChunk = reader(FakeStream(data, pastEndIsRefused = true), chunkSize = 100)
        refusedLastChunk.open(position = 0, length = C.LENGTH_UNSET.toLong())
        assertArrayEquals(data, refusedLastChunk.readAll())
    }

    @Test
    fun `a known length is not read past`() {
        val data = bytes(500)
        val stream = FakeStream(data)
        val reader = reader(stream, chunkSize = 100)

        reader.open(position = 50, length = 150)

        assertArrayEquals(data.copyOfRange(50, 200), reader.readAll())
        assertEquals(listOf(50L to 149L, 150L to 199L), stream.openedRanges)
    }

    @Test
    fun `a next chunk that fails to open is an error, not the end of the trailer`() {
        val stream = FakeStream(bytes(250), failOpenAt = 100)
        val reader = reader(stream, chunkSize = 100)
        reader.open(position = 0, length = C.LENGTH_UNSET.toLong())

        val buffer = ByteArray(100)
        assertEquals(100, reader.read(buffer, 0, buffer.size))

        assertThrows(IOException::class.java) { reader.read(buffer, 0, buffer.size) }
    }
}
