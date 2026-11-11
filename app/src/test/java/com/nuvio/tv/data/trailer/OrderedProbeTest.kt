package com.nuvio.tv.data.trailer

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedProbeTest {

    @Test
    fun `the first item wins without the rest being asked`() = runTest {
        val asked = Collections.synchronizedList(mutableListOf<Int>())
        val result = firstAcceptedInOrder(listOf(1, 2, 3, 4), atOnce = 1, timeoutMs = 5_000L) {
            asked += it
            "ok$it"
        }
        assertEquals("ok1", result)
        assertEquals(listOf(1), asked.toList())
    }

    @Test
    fun `a later item is taken only when the earlier ones fail`() = runTest {
        val asked = Collections.synchronizedList(mutableListOf<Int>())
        val result = firstAcceptedInOrder(listOf(1, 2, 3, 4), atOnce = 2, timeoutMs = 5_000L) {
            asked += it
            if (it < 3) null else "ok$it"
        }
        assertEquals("ok3", result)
        assertTrue(asked.containsAll(listOf(1, 2, 3)))
    }

    @Test
    fun `an earlier item that answers later still wins`() = runTest {
        val result = firstAcceptedInOrder(listOf(1, 2), atOnce = 2, timeoutMs = 5_000L) {
            if (it == 1) delay(300)
            "ok$it"
        }
        assertEquals("ok1", result)
    }

    @Test
    fun `no more than the limit run together`() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val result = firstAcceptedInOrder((1..6).toList(), atOnce = 2, timeoutMs = 5_000L) {
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(50)
            running.decrementAndGet()
            null as String?
        }
        assertNull(result)
        assertEquals(2, peak.get())
    }

    @Test
    fun `the best answer so far is returned at the time limit`() = runTest {
        val result = firstAcceptedInOrder(listOf(1, 2), atOnce = 2, timeoutMs = 1_000L) {
            if (it == 1) awaitCancellation()
            "ok$it"
        }
        assertEquals("ok2", result)
    }

    @Test
    fun `a probe that throws counts as a failure`() = runTest {
        val result = firstAcceptedInOrder(listOf(1, 2), atOnce = 1, timeoutMs = 5_000L) {
            if (it == 1) error("refused")
            "ok$it"
        }
        assertEquals("ok2", result)
    }

    @Test
    fun `cancelling the caller stops every probe`() = runTest {
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            firstAcceptedInOrder(listOf(1, 2, 3), atOnce = 2, timeoutMs = 60_000L) {
                started.incrementAndGet()
                try {
                    gate.await()
                    "ok$it"
                } finally {
                    stopped.incrementAndGet()
                }
            }
        }
        advanceUntilIdle()
        assertEquals(2, started.get())
        job.cancel()
        advanceUntilIdle()
        assertEquals(2, stopped.get())
        assertEquals(2, started.get())
    }
}
