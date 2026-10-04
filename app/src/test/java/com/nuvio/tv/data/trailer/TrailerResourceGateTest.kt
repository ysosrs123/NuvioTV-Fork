package com.nuvio.tv.data.trailer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrailerResourceGateTest {
    @Test fun `timeout destroys resource before the next request can construct one`() = runTest {
        val gate = TrailerResourceGate(StandardTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        val first = launch {
            withTimeoutOrNull(50) {
                gate.use({ events += "create A"; "A" }, { events += "destroy $it" }) { awaitCancellation() }
            }
        }
        runCurrent()
        val second = launch {
            gate.use({ events += "create B"; "B" }, { events += "destroy $it" }) { }
        }
        first.join(); second.join()
        assertEquals(listOf("create A", "destroy A", "create B", "destroy B"), events)
    }

    @Test fun `cancelling a queued request does not allocate a resource or lose the permit`() = runTest {
        val gate = TrailerResourceGate(StandardTestDispatcher(testScheduler))
        var live = 0
        val first = launch { gate.use({ ++live }, { --live }) { awaitCancellation() } }
        runCurrent()
        val queued = launch { gate.use({ fail("Cancelled waiter must not construct"); 0 }, {}) {} }
        runCurrent()
        queued.cancelAndJoin()
        first.cancelAndJoin()
        assertEquals(0, live)
        assertEquals(42, gate.use({ ++live }, { --live }) { 42 })
        assertEquals(0, live)
    }

    @Test fun `cancellation during construction still enters cleanup before dispatcher return`() = runTest {
        val gate = TrailerResourceGate(StandardTestDispatcher(testScheduler))
        var destroyed = false
        val job = launch(start = CoroutineStart.LAZY) {
            gate.use(create = { coroutineContext[kotlinx.coroutines.Job]!!.cancel(); Any() },
                destroy = { destroyed = true }) { awaitCancellation() }
        }
        job.start(); job.join()
        assertTrue(destroyed)
    }

    @Test fun `failure destroys resource and allows retry`() = runTest {
        val gate = TrailerResourceGate(StandardTestDispatcher(testScheduler))
        var destroyed = 0
        val result = runCatching { gate.use({ Any() }, { destroyed++ }) { error("resolve failed") } }
        assertTrue(result.isFailure)
        gate.use({ Any() }, { destroyed++ }) { }
        assertEquals(2, destroyed)
    }
}
