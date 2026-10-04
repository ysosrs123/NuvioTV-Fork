package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePrefetchLifecycleTest {
    @Test fun `inactive Home does not start a settings read`() = runTest {
        val gate = HomePrefetchLifecycle()
        gate.setActive(false)
        var reads = 0
        assertNull(gate.readIfActive { reads++; 500L })
        assertEquals(0, reads)
    }

    @Test fun `normal settings read retains its result`() = runTest {
        assertEquals(500L, HomePrefetchLifecycle().readIfActive { 500L }?.value)
    }

    @Test fun `null completion cap is a valid current settings value`() = runTest {
        val current = HomePrefetchLifecycle().readIfActive<Long?> { null }
        assertNotNull(current)
        assertNull(current!!.value)
    }

    @Test fun `Home pause while settings is suspended prevents prefetch admission`() = runTest {
        val gate = HomePrefetchLifecycle()
        val settings = CompletableDeferred<Long>()
        val read = async { gate.readIfActive { settings.await() } }
        runCurrent()
        gate.setActive(false)
        settings.complete(500L)
        assertNull(read.await())
    }

    @Test fun `leave and return cannot revive an old settings read`() = runTest {
        val gate = HomePrefetchLifecycle()
        val settings = CompletableDeferred<Long>()
        val read = async { gate.readIfActive { settings.await() } }
        runCurrent()
        gate.setActive(false)
        gate.setActive(true)
        settings.complete(500L)
        assertNull(read.await())
        assertEquals(600L, gate.readIfActive { 600L }?.value)
    }

    @Test fun `repeated active notification does not invalidate legitimate work`() = runTest {
        val gate = HomePrefetchLifecycle()
        val settings = CompletableDeferred<Long>()
        val read = async { gate.readIfActive { settings.await() } }
        runCurrent()
        gate.setActive(true)
        settings.complete(500L)
        assertEquals(500L, read.await()?.value)
    }

    @Test fun `caller cancellation reaches the suspended read`() = runTest {
        val gate = HomePrefetchLifecycle()
        val settings = CompletableDeferred<Long>()
        var cleanedUp = false
        val read = launch {
            gate.readIfActive { try { settings.await() } finally { cleanedUp = true } }
            fail("Cancelled read must not admit work")
        }
        runCurrent()
        read.cancel()
        read.join()
        assertTrue(cleanedUp)
        assertTrue(read.isCancelled)
    }

    @Test fun `settings failure stays visible and later reads still work`() = runTest {
        val gate = HomePrefetchLifecycle()
        val failure = IllegalStateException("fixture failure")
        try {
            gate.readIfActive<Long> { throw failure }
            fail("Expected settings failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(500L, gate.readIfActive { 500L }?.value)
    }
}
