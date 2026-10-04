package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercises the production open/bootstrap/fallback owner with no network or player. */
class ParallelRangeProbeOwnershipTest {
    private class Fixture(private val isolated: Boolean = true, publish: (ParallelRangeDataSource.BootstrapCacheEntry?) -> Unit = {}) {
        val uri = mockk<Uri>(relaxed = true).also {
            every { it.toString() } returns "https://example.invalid/probe"
            every { it.getQueryParameter(any()) } returns null
        }
        val upstream = mockk<OkHttpDataSource>(relaxed = true)
        val factory = mockk<OkHttpDataSource.Factory>()
        val spec = DataSpec.Builder().setUri(uri).build()
        var readOffset = 0
        val bytes = ByteArray(8) { (it + 1).toByte() }
        init {
            every { factory.createDataSource() } returns upstream
            every { upstream.uri } returns uri
            every { upstream.responseCode } returns 206
            every { upstream.responseHeaders } returns mapOf("Content-Range" to listOf("bytes 0-7/8"))
            every { upstream.open(any()) } returns 8L
            every { upstream.read(any(), any(), any()) } answers {
                if (readOffset == bytes.size) C.RESULT_END_OF_INPUT else {
                    val count = minOf(thirdArg<Int>(), bytes.size - readOffset)
                    bytes.copyInto(firstArg(), secondArg(), readOffset, readOffset + count)
                    readOffset += count
                    count
                }
            }
        }
        val source = ParallelRangeDataSource(factory, parallelConnections = 1, chunkSize = 262144,
            shouldAllowBackgroundPrefetch = { false }, isolateSession = isolated, updateBootstrapCache = publish)
        fun close() {
            source.close()
            if (!isolated) ParallelRangeDataSource.releaseRetainedSession()
            else assertTrue(source.diagnosticWorkersStopped)
        }
    }

    @Test fun `bootstrap read failure closes local probe before outer close`() {
        val f = Fixture(); val error = IOException("fixture read")
        every { f.upstream.read(any(), any(), any()) } throws error
        try {
            assertSame(error, assertThrows(IOException::class.java) { f.source.open(f.spec) })
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
        verify(exactly = 1) { f.upstream.close() }
    }

    @Test fun `bootstrap failure keeps primary error if probe close also fails`() {
        val f = Fixture(); val readError = IOException("fixture read"); val closeError = IOException("fixture close")
        every { f.upstream.read(any(), any(), any()) } throws readError
        every { f.upstream.close() } throws closeError
        try {
            assertSame(readError, assertThrows(IOException::class.java) { f.source.open(f.spec) })
            assertArrayEquals(arrayOf(closeError), readError.suppressed)
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `playback cache publication failure closes local probe`() {
        val error = IllegalStateException("fixture cache")
        val f = Fixture(isolated = false, publish = { throw error })
        try {
            assertSame(error, assertThrows(IllegalStateException::class.java) { f.source.open(f.spec) })
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
        verify(exactly = 1) { f.upstream.close() }
    }

    @Test fun `successful bounded probe closes once and cached bytes remain readable`() {
        val f = Fixture()
        try {
            assertEquals(8L, f.source.open(f.spec))
            verify(exactly = 1) { f.upstream.close() }
            val actual = ByteArray(8)
            assertEquals(8, f.source.read(actual, 0, 8))
            assertArrayEquals(f.bytes, actual)
            verify(exactly = 1) { f.upstream.open(any()); f.upstream.read(any(), any(), any()) }
        } finally { f.close() }
        verify(exactly = 1) { f.upstream.close() }
    }

    @Test fun `short bootstrap retains existing EOF behavior and closes once`() {
        val f = Fixture(); var read = false
        every { f.upstream.read(any(), any(), any()) } answers {
            if (read) C.RESULT_END_OF_INPUT else {
                read = true; f.bytes.copyInto(firstArg(), secondArg(), 0, 3); 3
            }
        }
        try {
            assertEquals(8L, f.source.open(f.spec))
            val actual = ByteArray(3)
            assertEquals(3, f.source.read(actual, 0, 3))
            assertArrayEquals(f.bytes.copyOf(3), actual)
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `successful probe close failure propagates without a second close`() {
        val f = Fixture(); val error = IOException("fixture close")
        every { f.upstream.close() } throws error
        try {
            assertSame(error, assertThrows(IOException::class.java) { f.source.open(f.spec) })
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
    }

    @Test fun `unbounded fallback transfers probe ownership to outer source`() {
        val f = Fixture()
        every { f.upstream.responseHeaders } returns emptyMap()
        every { f.upstream.responseCode } returns 200
        try {
            assertEquals(8L, f.source.open(f.spec))
            verify(exactly = 2) { f.upstream.open(any()) }
            // First bounded open closes before reopen; fallback remains caller-owned.
            verify(exactly = 1) { f.upstream.close() }
        } finally { f.close() }
        verify(exactly = 2) { f.upstream.close() }
    }

    @Test fun `probe open failure still closes before body read`() {
        val f = Fixture(); val error = IOException("fixture open")
        every { f.upstream.open(any()) } throws error
        try {
            assertSame(error, assertThrows(IOException::class.java) { f.source.open(f.spec) })
            verify(exactly = 1) { f.upstream.close() }
            verify(exactly = 0) { f.upstream.read(any(), any(), any()) }
        } finally { f.close() }
    }
}
