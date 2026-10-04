package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Production open/read/close ownership with a finite scripted HTTP reader. */
class ParallelRangeBootstrapProgressTest {
    private class Fixture(vararg steps: Int, private val isolated: Boolean = true) {
        val uri = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "https://example.invalid/bootstrap-progress"
            every { it.getQueryParameter(any()) } returns null
        }
        val upstream = mockk<OkHttpDataSource>(relaxed = true)
        val factory = mockk<OkHttpDataSource.Factory>()
        val spec = DataSpec.Builder().setUri(uri).build()
        val bytes = ByteArray(8) { (it + 1).toByte() }
        val published = mutableListOf<ParallelRangeDataSource.BootstrapCacheEntry?>()
        private val script = ArrayDeque(steps.toList())
        private var offset = 0
        var reads = 0
        var beforeRead: (() -> Unit)? = null
        init {
            if (!isolated) ParallelRangeDataSource.releaseRetainedSession()
            every { factory.createDataSource() } returns upstream
            every { upstream.uri } returns uri
            every { upstream.responseCode } returns 206
            every { upstream.responseHeaders } returns mapOf("Content-Range" to listOf("bytes 0-7/8"))
            every { upstream.open(any()) } returns 8L
            every { upstream.read(any(), any(), any()) } answers {
                reads++
                beforeRead?.invoke()
                val step = if (script.isEmpty()) bytes.size - offset else script.removeFirst()
                when {
                    step < 0 || offset == bytes.size -> C.RESULT_END_OF_INPUT
                    step == 0 -> 0
                    else -> {
                        val count = minOf(step, thirdArg<Int>(), bytes.size - offset)
                        bytes.copyInto(firstArg(), secondArg(), offset, offset + count)
                        offset += count
                        count
                    }
                }
            }
        }
        val source = ParallelRangeDataSource(factory, parallelConnections = 1, chunkSize = 262144,
            shouldAllowBackgroundPrefetch = { false }, isolateSession = isolated,
            updateBootstrapCache = { published += it })
        fun assertPayload() {
            val actual = ByteArray(8)
            assertEquals(8, source.read(actual, 0, 8))
            assertArrayEquals(bytes, actual)
        }
        fun close() {
            source.close()
            if (!isolated) ParallelRangeDataSource.releaseRetainedSession()
            else assertTrue(source.diagnosticWorkersStopped)
        }
    }

    @Test fun `three initial zero reads fail before cache publication and close local body`() {
        val f = Fixture(0, 0, 0, 8, isolated = false)
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec) }
            assertEquals("No bootstrap read progress after 3 attempts", failure.message)
            assertEquals(3, f.reads)
            assertTrue(f.published.isEmpty())
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
        verify(exactly = 1) { f.upstream.close() }
    }

    @Test fun `two transient zero reads recover with complete payload`() {
        val f = Fixture(0, 0, 8)
        try {
            assertEquals(8L, f.source.open(f.spec))
            assertEquals(3, f.reads)
            f.assertPayload()
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `three zero reads after payload progress still reject unpublished window`() {
        val f = Fixture(1, 0, 0, 0, 7, isolated = false)
        try {
            assertThrows(IOException::class.java) { f.source.open(f.spec) }
            assertEquals(4, f.reads)
            assertTrue(f.published.isEmpty())
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `payload progress resets zero read count before next transient stall`() {
        val f = Fixture(0, 0, 3, 0, 0, 5, isolated = false)
        try {
            assertEquals(8L, f.source.open(f.spec))
            assertEquals(6, f.reads)
            f.assertPayload()
            assertEquals(1, f.published.size)
            assertEquals(8, f.published.single()!!.bootstrapSize)
            assertArrayEquals(f.bytes, f.published.single()!!.bootstrapData)
        } finally { f.close() }
    }

    @Test fun `zero progress error stays primary when local body close fails`() {
        val f = Fixture(0, 0, 0, 8)
        val closeFailure = IOException("fixture close")
        every { f.upstream.close() } throws closeFailure
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec) }
            assertEquals("No bootstrap read progress after 3 attempts", failure.message)
            assertArrayEquals(arrayOf(closeFailure), failure.suppressed)
            assertEquals(3, f.reads)
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `close during zero read cancels bootstrap and retains one local close`() {
        val f = Fixture(0, 8)
        f.beforeRead = { f.source.close() }
        try {
            val failure = assertThrows(IOException::class.java) { f.source.open(f.spec) }
            assertEquals("DataSource closed", failure.message)
            assertEquals(1, f.reads)
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `ordinary single fallback keeps upstream zero read semantics`() {
        val f = Fixture(0, 8)
        every { f.upstream.responseCode } returns 200
        every { f.upstream.responseHeaders } returns emptyMap()
        try {
            assertEquals(8L, f.source.open(f.spec))
            assertEquals(0, f.reads)
            val actual = ByteArray(8)
            assertEquals(0, f.source.read(actual, 0, 8))
            assertEquals(8, f.source.read(actual, 0, 8))
            assertArrayEquals(f.bytes, actual)
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
        verify(exactly = 2) { f.upstream.close() }
    }

    @Test fun `transient zero then short EOF retains separate existing behavior`() {
        val f = Fixture(0, 3, C.RESULT_END_OF_INPUT)
        try {
            assertEquals(8L, f.source.open(f.spec))
            assertEquals(3, f.reads)
            val actual = ByteArray(3)
            assertEquals(3, f.source.read(actual, 0, 3))
            assertArrayEquals(f.bytes.copyOf(3), actual)
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }
}
