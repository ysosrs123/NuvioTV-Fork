package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Production open/close/reopen ownership; transport mocked, shared Resources unchanged. */
class ParallelRangeReopenOwnershipTest {
    private enum class Mode { BOUNDED, FALLBACK, SUBTITLE }
    private class Fixture(mode: Mode, isolated: Boolean = false) {
        val uri = mockk<Uri>(relaxed = true)
        val upstream = mockk<OkHttpDataSource>(relaxed = true)
        val factory = mockk<OkHttpDataSource.Factory>()
        val bytes = ByteArray(8) { (it + 1).toByte() }
        var offset = 0
        init {
            every { uri.toString() } returns "https://example.invalid/reopen"
            every { uri.getQueryParameter(any()) } returns null
            if (mode == Mode.SUBTITLE) {
                val builder = mockk<Uri.Builder>()
                every { uri.getQueryParameter("nuvio_type") } returns "subtitle"
                every { uri.queryParameterNames } returns setOf("nuvio_type")
                every { uri.buildUpon() } returns builder
                every { builder.clearQuery() } returns builder
                every { builder.build() } returns uri
            }
            every { factory.createDataSource() } returns upstream
            every { upstream.uri } returns uri
            every { upstream.responseCode } returns if (mode == Mode.BOUNDED) 206 else 200
            every { upstream.responseHeaders } returns if (mode == Mode.BOUNDED)
                mapOf("Content-Range" to listOf("bytes 0-7/8")) else emptyMap()
            every { upstream.open(any()) } answers { offset = 0; 8L }
            every { upstream.read(any(), any(), any()) } answers {
                if (offset == bytes.size) C.RESULT_END_OF_INPUT else {
                    val n = minOf(thirdArg<Int>(), bytes.size - offset)
                    bytes.copyInto(firstArg(), secondArg(), offset, offset + n); offset += n; n
                }
            }
        }
        val source = ParallelRangeDataSource(factory, parallelConnections = 1, chunkSize = 262144,
            shouldAllowBackgroundPrefetch = { false }, isolateSession = isolated)
        val spec = DataSpec.Builder().setUri(uri).build()
        fun count(): Int {
            val resourcesField = ParallelRangeDataSource::class.java.getDeclaredField("resources")
            resourcesField.isAccessible = true
            val resources = resourcesField.get(source)
            val countField = resources.javaClass.getDeclaredField("activeInstances")
            countField.isAccessible = true
            return (countField.get(resources) as AtomicInteger).get()
        }
    }
    private fun withPeer(mode: Mode, action: (Fixture, Int) -> Unit) {
        val f = Fixture(mode); val baseline = f.count() - 1
        val peer = Fixture(Mode.FALLBACK)
        try {
            assertEquals(8L, peer.source.open(peer.spec))
            assertEquals(baseline + 2, f.count())
            action(f, baseline)
            assertEquals(baseline + 1, peer.count())
            // The peer is still open and can read its original response.
            val actual = ByteArray(1)
            assertEquals(1, peer.source.read(actual, 0, 1))
            assertEquals(peer.bytes[0], actual[0])
        } finally {
            f.source.close(); peer.source.close(); ParallelRangeDataSource.releaseRetainedSession()
        }
        assertEquals(baseline, f.count())
    }
    private fun cycle(f: Fixture, baseline: Int) {
        assertEquals(8L, f.source.open(f.spec))
        assertEquals(baseline + 2, f.count())
        f.source.close()
        assertEquals(baseline + 1, f.count())
    }
    @Test fun `bounded datasource reacquires ownership across three reopen cycles`() = withPeer(Mode.BOUNDED) { f, baseline ->
        repeat(3) { cycle(f, baseline) }
    }
    @Test fun `fallback datasource reacquires ownership on reopen`() = withPeer(Mode.FALLBACK) { f, baseline ->
        repeat(2) { cycle(f, baseline) }
    }
    @Test fun `subtitle datasource reacquires ownership on reopen`() = withPeer(Mode.SUBTITLE) { f, baseline ->
        repeat(2) { cycle(f, baseline) }
    }
    @Test fun `failed fallback reopen stays owned until caller closes it`() = withPeer(Mode.FALLBACK) { f, baseline ->
        cycle(f, baseline)
        val error = IOException("fixture reopen")
        every { f.upstream.open(any()) } throws error
        assertSame(error, assertThrows(IOException::class.java) { f.source.open(f.spec) })
        assertEquals(baseline + 2, f.count())
        f.source.close(); assertEquals(baseline + 1, f.count())
    }
    @Test fun `failed subtitle reopen stays owned until caller closes it`() = withPeer(Mode.SUBTITLE) { f, baseline ->
        cycle(f, baseline)
        val error = IOException("fixture subtitle reopen")
        every { f.upstream.open(any()) } throws error
        assertSame(error, assertThrows(IOException::class.java) { f.source.open(f.spec) })
        assertEquals(baseline + 2, f.count())
        f.source.close(); assertEquals(baseline + 1, f.count())
    }
    @Test fun `close before initial open can reopen without undercounting peer`() = withPeer(Mode.FALLBACK) { f, baseline ->
        f.source.close(); assertEquals(baseline + 1, f.count())
        cycle(f, baseline)
    }
    @Test fun `repeated close remains idempotent after reopened fallback`() = withPeer(Mode.FALLBACK) { f, baseline ->
        cycle(f, baseline); cycle(f, baseline)
        f.source.close(); assertEquals(baseline + 1, f.count())
    }
    @Test fun `active warm reopen keeps the same reservation and avoids another probe`() = withPeer(Mode.BOUNDED) { f, baseline ->
        assertEquals(8L, f.source.open(f.spec))
        assertEquals(8L, f.source.open(f.spec))
        assertEquals(baseline + 2, f.count())
        verify(exactly = 1) { f.upstream.open(any()) }
        f.source.close(); assertEquals(baseline + 1, f.count())
    }
    @Test fun `isolated diagnostic cannot reopen after permanent close`() {
        val f = Fixture(Mode.FALLBACK, isolated = true)
        try {
            assertEquals(1, f.count()); assertEquals(8L, f.source.open(f.spec))
            f.source.close(); assertEquals(0, f.count())
            assertThrows(IOException::class.java) { f.source.open(f.spec) }
            assertEquals(0, f.count()); assertTrue(f.source.diagnosticWorkersStopped)
        } finally { f.source.close() }
    }
}
