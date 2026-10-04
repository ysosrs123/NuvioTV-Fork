package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackConnectProbeTest {
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.addLast(command) }
        fun run() = work.removeFirst().run()
    }
    private class FakeSocket(val connectAction: (SocketAddress, Int) -> Unit = { _, _ -> }) : Socket() {
        var closes = 0
        override fun connect(endpoint: SocketAddress, timeout: Int) = connectAction(endpoint, timeout)
        override fun close() { closes++ }
    }
    private class Harness {
        var now = 1_000L
        val endpoint = PlaybackEndpoint("https", "media.invalid", 8443)
        var state = PlaybackTransferSnapshot(1, now, PlaybackTransferCoverage.PROGRESSIVE, 0, 0, endpoint, null)
        val queue = Queue()
        var resolutions = 0
        var sockets = 0
        var resolveAction: () -> Unit = {}
        var connectAction: (SocketAddress, Int) -> Unit = { _, _ -> now += 15 }
        val socket = FakeSocket { address, timeout -> connectAction(address, timeout) }
        val probe = PlaybackConnectProbe({ state.copy(sampledAtMs = now) }, { now }, queue,
            { resolutions++; resolveAction(); InetAddress.getByAddress(byteArrayOf(127,0,0,1)) },
            { sockets++; socket })
    }

    @Test fun `sample includes resolver time and uses the serving port with remaining budget`() = runTest {
        val h = Harness()
        h.resolveAction = { h.now += 80 }
        h.connectAction = { address, timeout ->
            assertEquals(8443, (address as InetSocketAddress).port)
            assertFalse(address.isUnresolved)
            assertEquals(1920, timeout)
            h.now += 15
        }
        val result = async { h.probe.sample() }; runCurrent(); h.queue.run()
        assertEquals(95L, result.await()!!.elapsedMs)
        assertEquals(h.endpoint, result.await()!!.endpoint)
        assertEquals(1, h.socket.closes)
    }

    @Test fun `absent or unsupported endpoint starts no work`() = runTest {
        val h = Harness()
        h.state = h.state.copy(endpoint = null)
        assertNull(h.probe.sample())
        h.state = h.state.copy(endpoint = h.endpoint, coverage = PlaybackTransferCoverage.UNAVAILABLE)
        assertNull(h.probe.sample())
        assertTrue(h.queue.work.isEmpty())
    }

    @Test fun `deadline returns unavailable but retains admission until queued worker exits`() = runTest {
        val h = Harness()
        val result = async { h.probe.sample() }; runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertNull(result.await())
        repeat(20) { assertNull(h.probe.sample()) }
        assertEquals(1, h.queue.work.size)
        h.queue.run()
        assertEquals(0, h.resolutions)
        val next = async { h.probe.sample() }; runCurrent(); h.queue.run()
        assertNotNull(next.await())
    }

    @Test fun `caller cancellation before worker execution performs no DNS or socket work`() = runTest {
        val h = Harness()
        val result = async { h.probe.sample() }; runCurrent(); result.cancel(); runCurrent()
        assertTrue(result.isCancelled)
        assertNull(h.probe.sample())
        h.queue.run()
        assertEquals(0, h.resolutions)
        assertEquals(0, h.sockets)
    }

    @Test fun `cancel during resolver prevents opening a late socket`() = runTest {
        val h = Harness()
        val result = async { h.probe.sample() }; runCurrent()
        h.resolveAction = { result.cancel() }
        h.queue.run(); runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(0, h.sockets)
    }

    @Test fun `source changes before DNS and during DNS reject old work`() = runTest {
        val h = Harness()
        val first = async { h.probe.sample() }; runCurrent()
        h.state = h.state.copy(sessionId = 2)
        h.queue.run(); assertNull(first.await()); assertEquals(0, h.resolutions)
        h.resolveAction = { h.state = h.state.copy(endpoint = h.endpoint.copy(host = "other.invalid")) }
        val second = async { h.probe.sample() }; runCurrent(); h.queue.run()
        assertNull(second.await()); assertEquals(0, h.sockets)
    }

    @Test fun `source change during connection cannot publish a sample`() = runTest {
        val h = Harness()
        h.connectAction = { _, _ -> h.state = h.state.copy(sessionId = 2) }
        val result = async { h.probe.sample() }; runCurrent(); h.queue.run()
        assertNull(result.await()); assertEquals(1, h.socket.closes)
    }

    @Test fun `expired resolver result opens no socket`() = runTest {
        val h = Harness()
        h.resolveAction = { h.now += 2000 }
        val result = async { h.probe.sample() }; runCurrent(); h.queue.run()
        assertNull(result.await()); assertEquals(0, h.sockets)
    }

    @Test fun `cancellation during socket connection closes its socket once`() = runTest {
        val h = Harness()
        val result = async { h.probe.sample() }; runCurrent()
        h.connectAction = { _, _ -> result.cancel(); assertEquals(1, h.socket.closes) }
        h.queue.run(); runCurrent()
        assertTrue(result.isCancelled); assertEquals(1, h.socket.closes)
    }

    @Test fun `resolver and socket failures return unavailable and release admission`() = runTest {
        val h = Harness()
        h.resolveAction = { throw IOException("synthetic DNS failure") }
        val first = async { h.probe.sample() }; runCurrent(); h.queue.run(); assertNull(first.await())
        h.resolveAction = {}
        h.connectAction = { _, _ -> throw IOException("synthetic connect failure") }
        val second = async { h.probe.sample() }; runCurrent(); h.queue.run(); assertNull(second.await())
        assertEquals(1, h.socket.closes)
    }

    @Test fun `executor rejection releases admission without making sockets`() = runTest {
        val h = Harness()
        var submissions = 0
        val probe = PlaybackConnectProbe({ h.state }, { h.now }, Executor {
            submissions++; throw RejectedExecutionException("synthetic rejection")
        }, socketFactory = { fail("No socket expected"); Socket() })
        repeat(2) { assertNull(probe.sample()) }
        assertEquals(2, submissions)
    }

    @Test fun `blocked DNS returns at caller deadline and cannot accumulate more workers`() = runTest {
        val h = Harness()
        val entered = CountDownLatch(1)
        val releaseDns = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val sockets = java.util.concurrent.atomic.AtomicInteger()
        val probe = PlaybackConnectProbe({ h.state }, { h.now }, Executor { task ->
            executor.execute { try { task.run() } finally { exited.countDown() } }
        }, resolve = {
            entered.countDown()
            check(releaseDns.await(5, TimeUnit.SECONDS))
            InetAddress.getByAddress(byteArrayOf(127,0,0,1))
        }, socketFactory = { sockets.incrementAndGet(); FakeSocket() })
        try {
            val result = async { probe.sample() }; runCurrent()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            advanceTimeBy(2000); runCurrent()
            assertNull(result.await())
            repeat(30) { assertNull(probe.sample()) }
            assertEquals(1L, exited.count)
            releaseDns.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals(0, sockets.get())
        } finally {
            releaseDns.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
