package com.nuvio.tv.core.iptv

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentCaptureTransportTest {
    @get:Rule val temp = TemporaryFolder()
    private class Source(val pull: suspend () -> CaptureInput?) : CaptureSegmentSource {
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        @Volatile var confirmed = true
        override suspend fun next(): CaptureInput? { calls.incrementAndGet(); return pull() }
        override suspend fun close(): Boolean { closes.incrementAndGet(); return confirmed }
    }
    private suspend fun SegmentCaptureTransport.await(state: CaptureTransportState) {
        withTimeout(5000) { this@await.state.first { it == state } }
    }
    @Test fun segmentBodiesCloseBeforeNextPullAndCommittedLocalBytesAreReadable() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 12, 4).use { store ->
            var count = 0
            val bodiesClosed = AtomicInteger()
            val source = Source {
                assertEquals(count, bodiesClosed.get())
                if (count == 3) null else {
                    val start = count++ * 1000L
                    CaptureInput(start, start + 1000, 0, object : ByteArrayInputStream(byteArrayOf(1,2,3,4)) {
                        override fun close() { bodiesClosed.incrementAndGet(); super.close() }
                    })
                }
            }
            val transport = SegmentCaptureTransport(store, source)
            transport.start(); transport.await(CaptureTransportState.COMPLETE)
            assertTrue(transport.close())
            assertEquals(4, source.calls.get())
            assertEquals(3, bodiesClosed.get())
            store.openSnapshotFrom(0).use { assertEquals(12, it.readBytes().size) }
        }
    }

    @Test fun pinnedCapacityStopsWithoutRetryOrDroppingCommittedMedia() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            store.append(0, 1000, 0, ByteArrayInputStream(byteArrayOf(1,2,3,4)))
            val pin = store.pinFrom(0)
            val bodiesClosed = AtomicInteger()
            val source = Source { CaptureInput(1000, 2000, 0, object : ByteArrayInputStream(byteArrayOf(5,6,7,8)) {
                override fun close() { bodiesClosed.incrementAndGet(); super.close() }
            }) }
            val transport = SegmentCaptureTransport(store, source)
            transport.start(); transport.await(CaptureTransportState.BACKPRESSURE)
            assertTrue(transport.close())
            assertEquals(1, source.calls.get()); assertEquals(1, bodiesClosed.get())
            assertEquals(listOf(0L), store.snapshot().map { it.sequence })
            pin.close()
        }
    }

    @Test fun oversizedInputClosesBodyAndStopsWithoutPublishingPartialData() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            val bodiesClosed = AtomicInteger()
            val source = Source { CaptureInput(0, 1000, 0, object : ByteArrayInputStream(ByteArray(5)) {
                override fun close() { bodiesClosed.incrementAndGet(); super.close() }
            }) }
            val transport = SegmentCaptureTransport(store, source)
            transport.start(); transport.await(CaptureTransportState.FAILED)
            assertTrue(transport.close()); assertEquals(1, source.calls.get())
            assertEquals(1, bodiesClosed.get()); assertTrue(store.snapshot().isEmpty())
        }
    }

    @Test fun lateBodyAfterCancellationIsClosedWithoutAppend() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            val pulling = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
            val bodiesClosed = AtomicInteger()
            val source = object : CaptureSegmentSource {
                override suspend fun next(): CaptureInput {
                    pulling.complete(Unit)
                    withContext(NonCancellable) { unblock.await() }
                    return CaptureInput(0, 1000, 0, object : ByteArrayInputStream(ByteArray(4)) {
                        override fun close() { bodiesClosed.incrementAndGet(); super.close() }
                    })
                }
                override suspend fun close(): Boolean { unblock.complete(Unit); return true }
            }
            val transport = SegmentCaptureTransport(store, source)
            transport.start(); pulling.await()
            assertTrue(transport.close()); assertEquals(1, bodiesClosed.get())
            assertTrue(store.snapshot().isEmpty())
        }
    }

    @Test fun unconfirmedSourceClosureCannotBecomeClosed() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            val pulling = CompletableDeferred<Unit>()
            val source = Source { pulling.complete(Unit); awaitCancellation() }.apply { confirmed = false }
            val transport = SegmentCaptureTransport(store, source)
            transport.start(); pulling.await()
            assertFalse(transport.close())
            assertNotEquals(CaptureTransportState.CLOSED, transport.state.value)
            source.confirmed = true
            assertTrue(transport.close())
        }
    }

    @Test fun failedBodyClosureRetainsHandleForCleanupRetry() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            var allowClose = false
            val source = Source { CaptureInput(0, 1000, 0, object : ByteArrayInputStream(ByteArray(4)) {
                override fun close() { if (!allowClose) throw IOException("fixture") else super.close() }
            }) }
            val transport = SegmentCaptureTransport(store, source)
            transport.start(); transport.await(CaptureTransportState.FAILED)
            assertFalse(transport.close())
            allowClose = true
            assertTrue(transport.close())
            assertEquals(1, source.calls.get())
        }
    }

    @Test fun blockedBodyCloseCannotPreventTheTransportCloseTimeout() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            val closing = CountDownLatch(1); val release = CountDownLatch(1)
            val source = Source { CaptureInput(0, 1000, 0, object : ByteArrayInputStream(ByteArray(4)) {
                override fun close() { closing.countDown(); release.await(); super.close() }
            }) }
            val transport = SegmentCaptureTransport(store, source, closeTimeoutMs = 25)
            transport.start()
            try {
                assertTrue(withContext(Dispatchers.IO) { closing.await(5, TimeUnit.SECONDS) })
                assertFalse(withTimeout(5000) { transport.close() })
                assertNotEquals(CaptureTransportState.CLOSED, transport.state.value)
            } finally { release.countDown() }
            // Allow a generous timeout for the retry, independent of device scheduler timing.
            withTimeout(5000) {
                while (!transport.close()) yield()
            }
            assertEquals(CaptureTransportState.CLOSED, transport.state.value)
        }
    }

    @Test fun blockedCleanupRetryAlsoTimesOutWithoutConcurrentBodyCloses() = runBlocking {
        CaptureSegmentStore(temp.newFolder(), 4, 4).use { store ->
            val attempts = AtomicInteger(); val retryEntered = CountDownLatch(1); val release = CountDownLatch(1)
            val source = Source { CaptureInput(0, 1000, 0, object : ByteArrayInputStream(ByteArray(4)) {
                override fun close() {
                    if (attempts.incrementAndGet() == 1) throw IOException("fixture")
                    retryEntered.countDown(); release.await(); super.close()
                }
            }) }
            val transport = SegmentCaptureTransport(store, source, closeTimeoutMs = 25)
            transport.start(); transport.await(CaptureTransportState.FAILED)
            try {
                assertFalse(withTimeout(5000) { transport.close() })
                assertTrue(withContext(Dispatchers.IO) { retryEntered.await(5, TimeUnit.SECONDS) })
                assertFalse(withTimeout(5000) { transport.close() })
                assertEquals(2, attempts.get())
            } finally { release.countDown() }
            withTimeout(5000) { while (!transport.close()) yield() }
            assertEquals(2, attempts.get())
        }
    }
}
