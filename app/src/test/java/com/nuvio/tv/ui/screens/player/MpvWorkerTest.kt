package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MpvWorkerTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val worker = MpvWorker(executor)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `release runs away from the calling thread`() {
        val caller = Thread.currentThread()
        var releaseThread: Thread? = null
        worker.release { releaseThread = Thread.currentThread() }
        worker.awaitIdle()
        assertNotEquals(caller, releaseThread)
    }

    @Test
    fun `release returns before a slow teardown finishes`() {
        val gate = CountDownLatch(1)
        worker.release { gate.await(5, TimeUnit.SECONDS) }
        assertTrue(worker.isBusy)
        gate.countDown()
        worker.awaitIdle()
        assertFalse(worker.isBusy)
    }

    @Test
    fun `a new instance waits for the previous release`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        worker.release {
            gate.await(5, TimeUnit.SECONDS)
            order += "released"
        }
        val creator = thread {
            worker.awaitIdle()
            order += "created"
        }
        creator.join(200)
        assertTrue(creator.isAlive)
        assertTrue(order.isEmpty())

        gate.countDown()
        creator.join(5_000)
        assertEquals(listOf("released", "created"), order)
    }

    @Test
    fun `releases run one at a time in order`() {
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        var overlapped = false
        repeat(5) { index ->
            worker.release {
                if (running.incrementAndGet() > 1) overlapped = true
                Thread.sleep(5)
                order += index
                running.decrementAndGet()
            }
        }
        worker.awaitIdle()
        assertEquals(listOf(0, 1, 2, 3, 4), order)
        assertFalse(overlapped)
    }

    @Test
    fun `a failed release does not block later work`() {
        var ran = false
        worker.release { error("native teardown failed") }
        worker.release { ran = true }
        worker.awaitIdle()
        assertTrue(ran)
        assertFalse(worker.isBusy)
    }

    @Test
    fun `reads on the dispatcher count as busy and queue behind a release`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        worker.release {
            gate.await(5, TimeUnit.SECONDS)
            order += "released"
        }
        val read = thread {
            runBlocking { withContext(worker.dispatcher) { order += "read" } }
        }
        gate.countDown()
        read.join(5_000)
        assertEquals(listOf("released", "read"), order)
    }

    @Test
    fun `suspending wait resumes after the release`() = runBlocking {
        val gate = CountDownLatch(1)
        var released = false
        worker.release {
            gate.await(5, TimeUnit.SECONDS)
            released = true
        }
        thread {
            Thread.sleep(50)
            gate.countDown()
        }
        worker.awaitIdleSuspending()
        assertTrue(released)
        assertFalse(worker.isBusy)
    }

    @Test
    fun `only work on the worker counts as the worker thread`() {
        var inside = false
        worker.release { inside = worker.isWorkerThread }
        worker.awaitIdle()
        assertTrue(inside)
        assertFalse(worker.isWorkerThread)
    }

    @Test
    fun `a read that loses its core to a release mid-way does not lock the worker`() {
        val view = object {
            @Volatile var core: Any? = Any()
            fun coreOrWait(): Any? = core ?: run {
                worker.awaitIdle()
                null
            }
        }
        val readStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        var closed = false
        val read = thread {
            runBlocking {
                withContext(worker.dispatcher) {
                    readStarted.countDown()
                    released.await(5, TimeUnit.SECONDS)
                    view.coreOrWait()
                }
            }
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        view.core = null
        worker.release { closed = true }
        released.countDown()

        val waiter = thread { worker.awaitIdle() }
        waiter.join(5_000)
        read.join(5_000)
        assertFalse(waiter.isAlive)
        assertFalse(read.isAlive)
        assertTrue(closed)
    }

    @Test
    fun `waiting from inside work on the worker returns instead of waiting on itself`() {
        worker.release { }
        var returned = false
        val read = thread {
            runBlocking {
                withContext(worker.dispatcher) {
                    worker.awaitIdle()
                    worker.awaitIdleSuspending()
                    returned = worker.awaitIdle(timeoutMs = 10_000L)
                }
            }
        }
        read.join(5_000)
        assertFalse(read.isAlive)
        assertTrue(returned)
    }

    @Test
    fun `a bounded wait gives up on a close that hangs`() {
        val gate = CountDownLatch(1)
        worker.release { gate.await(5, TimeUnit.SECONDS) }
        assertFalse(worker.awaitIdle(timeoutMs = 100L))
        assertTrue(worker.isBusy)
        gate.countDown()
        assertTrue(worker.awaitIdle(timeoutMs = 5_000L))
    }

    @Test
    fun `waiting with nothing queued returns at once`() = runBlocking {
        assertFalse(worker.isBusy)
        worker.awaitIdle()
        worker.awaitIdleSuspending()
    }
}
