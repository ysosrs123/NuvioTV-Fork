@file:OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SpeedTestDownloadsMatchPlaybackTest {

    private val chunkBytes = 256 * 1024
    // Chunks served at once before the gate; past the 1 MB a reader must earn before it reads ahead.
    private val openChunks = 6
    private val file = ByteArray((openChunks + 26) * chunkBytes) { (it * 31 + 7).toByte() }

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns "/file"
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        ParallelRangeDataSource.releaseRetainedSession()
    }

    @After
    fun tearDown() {
        ParallelRangeDataSource.releaseRetainedSession()
        unmockkStatic(Uri::class)
    }

    // The reader schedules depth chunks from the one it holds, so depth - 1 downloads at once.
    private data class Case(val connections: Int, val window: MemoryBudget.DownloadWindow, val depth: Int) {
        val expected get() = minOf(depth - 1, ParallelRangeDataSource.playbackWorkerCount(android.os.Process.is64Bit()))
    }

    private val chunkMb = 8
    private val off = MemoryBudget.DownloadWindow(performanceMode = false, safeNativeLimitMb = 1000, reserveBufferMb = 250)
    private val onAutomaticTarget = NuvioExoPlayerPerformanceHelper.downloadWindow(true, true, 250, 1000)
    private val onOwnTarget = NuvioExoPlayerPerformanceHelper.downloadWindow(true, false, 250, 1000)

    private val cases = listOf(2, 4, 5).flatMap { n ->
        listOf(Case(n, off, n + 1), Case(n, onAutomaticTarget, 2 * n), Case(n, onOwnTarget, 4 * n))
    }

    /** Ranged file server that holds every request past [openChunks] until [expected] are waiting together. */
    private class GatedServer(private val file: ByteArray, private val gateFrom: Long, private val expected: Int) {
        val server = MockWebServer()
        private val lock = Object()
        private var active = 0
        private var reachedAtMs = 0L
        var maxActive = 0
            private set

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")
                        ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                    val parts = range.removePrefix("bytes=").split("-")
                    val from = parts[0].toLong()
                    val to = minOf(parts.getOrNull(1)?.toLongOrNull() ?: (file.size - 1L), file.size - 1L)
                    if (from >= gateFrom) hold()
                    return MockResponse().setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $from-$to/${file.size}")
                        .setBody(Buffer().write(file, from.toInt(), (to - from + 1).toInt()))
                }
            }
            server.start()
        }

        private fun hold() {
            synchronized(lock) {
                active += 1
                maxActive = maxOf(maxActive, active)
                if (active >= expected && reachedAtMs == 0L) reachedAtMs = System.currentTimeMillis()
                lock.notifyAll()
                val deadline = System.currentTimeMillis() + 1_500L
                while (true) {
                    val now = System.currentTimeMillis()
                    // Once enough are waiting, keep holding briefly so any extra download would show up too.
                    if (reachedAtMs != 0L && now - reachedAtMs >= 250L) break
                    if (now >= deadline) break
                    lock.wait(50L)
                }
                active -= 1
            }
        }
    }

    private fun peakDownloads(case: Case, isolated: Boolean): Int {
        val depth = if (isolated) {
            val cell = MemoryBudget.sweepCellPrefetchDepth(case.connections, chunkMb, 4000, case.window)
            assertNotNull(cell)
            cell!!
        } else {
            case.window.prefetchDepth(case.connections, chunkMb)
        }
        val gated = GatedServer(file, openChunks.toLong() * chunkBytes, case.expected)
        val calls = if (isolated) {
            ParallelDiagnosticCalls(
                ParallelRangeDataSource.prefetchDepth(depth, case.connections), OkHttpClient()
            ) { OkHttpClient() }
        } else {
            null
        }
        val playbackClient = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
            .build()
        val source = ParallelRangeDataSource(
            OkHttpDataSource.Factory(calls ?: playbackClient),
            parallelConnections = case.connections,
            chunkSize = chunkBytes.toLong(),
            prefetchDepthChunks = depth,
            isolateSession = isolated
        )
        try {
            source.open(DataSpec.Builder().setUri(uriOf(gated.server.url("/file").toString())).build())
            val buffer = ByteArray(64 * 1024)
            var position = 0L
            while (position < (openChunks + 1L) * chunkBytes) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                position += read
            }
        } finally {
            source.close()
            calls?.close()
            if (!isolated) ParallelRangeDataSource.releaseRetainedSession()
            gated.server.shutdown()
        }
        return gated.maxActive
    }

    @Test
    fun `speed test runs as many downloads at once as playback`() {
        for (case in cases) {
            val label = "${case.connections} connections, ${case.window}"
            val playback = peakDownloads(case, isolated = false)
            val test = peakDownloads(case, isolated = true)
            assertEquals(label, case.expected, playback)
            assertEquals(label, playback, test)
        }
    }

    @Test
    fun `sweep cell depth is the playback window`() {
        for (case in cases) {
            assertEquals(
                case.depth,
                MemoryBudget.sweepCellPrefetchDepth(case.connections, chunkMb, 4000, case.window)
            )
            assertEquals(case.depth, case.window.prefetchDepth(case.connections, chunkMb))
        }
    }

    @Test
    fun `sweep skips a cell whose playback window does not fit its budget`() {
        // 75% of 250 MB holds five 32 MB chunks: enough for 4 + 1, not for the 8 of performance mode.
        assertEquals(5, MemoryBudget.sweepCellPrefetchDepth(4, 32, 250, off))
        assertNull(MemoryBudget.sweepCellPrefetchDepth(4, 32, 250, onAutomaticTarget))
        assertNull(MemoryBudget.sweepCellPrefetchDepth(3, 64, 4000, off))
    }

    @Test
    fun `automatic target keeps performance mode at twice the connections`() {
        for (target in listOf(100, 250, 600)) {
            val window = NuvioExoPlayerPerformanceHelper.downloadWindow(true, true, target, 1000)
            assertEquals(8, window.prefetchDepth(4, 16))
        }
    }
}
