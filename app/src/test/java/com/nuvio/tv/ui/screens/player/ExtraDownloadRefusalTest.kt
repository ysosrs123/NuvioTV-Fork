package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit

class ExtraDownloadRefusalTest {
    private val spec: DataSpec by lazy { DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build() }

    private fun refused(code: Int) =
        IOException(HttpDataSource.InvalidResponseCodeException(code, "x", null, emptyMap(), spec, byteArrayOf()))

    @Test fun `refused answers and cut-off connections count, timeouts and our own closes do not`() {
        assertTrue(ExtraDownloadRefusal.isRefusal(refused(403)))
        assertTrue(ExtraDownloadRefusal.isRefusal(refused(401)))
        assertTrue(ExtraDownloadRefusal.isRefusal(IOException(SocketException("Connection reset"))))
        assertTrue(ExtraDownloadRefusal.isRefusal(IOException(EOFException())))
        assertFalse(ExtraDownloadRefusal.isRefusal(refused(404)))
        assertFalse(ExtraDownloadRefusal.isRefusal(refused(429)))
        assertFalse(ExtraDownloadRefusal.isRefusal(IOException(SocketTimeoutException("read timed out"))))
        assertFalse(ExtraDownloadRefusal.isRefusal(IOException(SocketException("Socket closed"))))
    }

    @Test fun `only another piece delivering just now counts`() {
        assertTrue(ExtraDownloadRefusal.othersDelivering(mapOf(1L to 9_000L, 2L to 1_000L), refused = 2L, nowMs = 10_000L))
        assertFalse(ExtraDownloadRefusal.othersDelivering(mapOf(2L to 9_900L), refused = 2L, nowMs = 10_000L))
        assertFalse(ExtraDownloadRefusal.othersDelivering(mapOf(1L to 5_000L), refused = 2L, nowMs = 10_000L))
    }

    @Test fun `first strike holds one fewer, a second holds one, a burst counts once`() {
        val strikes = ExtraDownloadStrikes(holdMs = 1_800_000L, burstMs = 10_000L)
        assertEquals(3, strikes.limitAfter("host", running = 4, nowMs = 0L))
        assertEquals(3, strikes.limitAfter("host", running = 4, nowMs = 5_000L))
        assertEquals(1, strikes.limitAfter("host", running = 3, nowMs = 60_000L))
        assertEquals(1, strikes.limitAfter("other", running = 2, nowMs = 60_000L))
    }

    @Test fun `a strike past the hold period starts over`() {
        val strikes = ExtraDownloadStrikes(holdMs = 1_800_000L, burstMs = 10_000L)
        assertEquals(2, strikes.limitAfter("host", running = 3, nowMs = 0L))
        assertEquals(4, strikes.limitAfter("host", running = 5, nowMs = 1_800_000L))
    }

    @Test fun `a remembered limit eases back one download per half hour`() {
        val held = HostSpeeds.Limit(1, atMs = 0L)
        assertEquals(1, HostSpeeds.easedLimit(held, HostSpeeds.LIMIT_EASE_MS - 1))
        assertEquals(2, HostSpeeds.easedLimit(held, HostSpeeds.LIMIT_EASE_MS))
        assertEquals(null, HostSpeeds.easedLimit(held, HostSpeeds.LIMIT_EASE_MS * (AutoSpeedup.MAX_IN_FLIGHT - 1)))
    }

    @Test fun `a server held to one starts on one download`() {
        val p = AutoSpeedupPolicy(10)
        p.requestLimit = 1
        p.preset(4)
        assertEquals(1, p.downloads)
    }

    // The download layer, with a piece that keeps delivering next to one that is refused.

    private val chunk = 2L * 1024L * 1024L
    private val total = 20L * 1024L * 1024L

    @Before fun start() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L }
    }

    @After fun finish() {
        unmockkStatic(SystemClock::class)
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    private fun learnt(
        refuse: (Long) -> Boolean,
        pauseAfterOpenMs: Long,
        allFailed: MutableList<AllDownloadsFailed> = mutableListOf()
    ): List<Int> {
        val link = mockk<Uri>(relaxed = true) {
            every { host } returns "files.example"
            every { this@mockk.toString() } returns "https://files.example/film.mkv"
            every { getQueryParameter(any()) } returns null
        }
        val learnt: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val factory = mockk<OkHttpDataSource.Factory> {
            every { createDataSource() } answers {
                val ds = mockk<OkHttpDataSource>(relaxed = true)
                var position = 0L
                var end = 0L
                every { ds.open(any()) } answers {
                    val spec = firstArg<DataSpec>()
                    val piece = spec.position / chunk
                    if (spec.position > 0L && refuse(piece)) {
                        Thread.sleep(200)
                        throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, byteArrayOf())
                    }
                    position = spec.position
                    end = if (spec.length > 0) spec.position + spec.length else total
                    end - position
                }
                every { ds.uri } returns link
                every { ds.responseCode } returns 206
                every { ds.responseHeaders } answers { mapOf("Content-Range" to listOf("bytes $position-${end - 1}/$total")) }
                every { ds.read(any(), any(), any()) } answers {
                    if (position >= end) return@answers -1
                    if (position >= chunk) Thread.sleep(40)
                    val n = minOf(thirdArg<Int>().toLong(), end - position, 64L * 1024L).toInt()
                    position += n
                    n
                }
                ds
            }
        }
        val source = ParallelRangeDataSource(
            factory,
            parallelConnections = 2,
            chunkSize = chunk,
            shouldAllowBackgroundPrefetch = { false },
            isolateSession = true,
            requestMeter = RequestMeter(),
            onExtraRefused = { _, running -> learnt += running },
            onAllFailed = { _, f -> synchronized(allFailed) { allFailed += f } }
        )
        try {
            source.open(DataSpec.Builder().setUri(link).setPosition(0L).build())
            Thread.sleep(pauseAfterOpenMs)
            val schedule = source.javaClass.getDeclaredMethod("ensureChunkScheduled", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            schedule.invoke(source, 1L)
            Thread.sleep(150)
            schedule.invoke(source, 2L)
            val resources = source.javaClass.getDeclaredField("resources").apply { isAccessible = true }.get(source)
            val session = resources.javaClass.getDeclaredField("currentChunkSession").apply { isAccessible = true }
                .get(resources)!!
            @Suppress("UNCHECKED_CAST")
            val futures = session.javaClass.getMethod("getFutures").invoke(session) as Map<Long, java.util.concurrent.Future<*>>
            listOf(1L, 2L).forEach { runCatching { futures.getValue(it).get(10, TimeUnit.SECONDS) } }
        } finally {
            source.close()
        }
        return learnt.toList()
    }

    @Test fun `a second download refused while the first keeps delivering is learnt`() {
        val learnt = learnt(refuse = { it == 2L }, pauseAfterOpenMs = 0L)
        assertTrue("learnt $learnt", learnt.isNotEmpty())
        assertTrue("learnt $learnt", learnt.all { it >= 2 })
    }

    @Test fun `every download failing teaches nothing`() {
        val allFailed = mutableListOf<AllDownloadsFailed>()
        val learnt = learnt(refuse = { it >= 1L }, pauseAfterOpenMs = ExtraDownloadRefusal.DELIVERING_WITHIN_MS + 500L, allFailed)
        assertEquals(emptyList<Int>(), learnt)
        synchronized(allFailed) {
            assertTrue("allFailed $allFailed", allFailed.isNotEmpty())
            assertTrue("allFailed $allFailed", allFailed.all { it.block })
        }
    }
}
