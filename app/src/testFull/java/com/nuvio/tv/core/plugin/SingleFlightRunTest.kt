package com.nuvio.tv.core.plugin

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightRunTest {

    private val flights = ConcurrentHashMap<String, Deferred<String>>()

    @Test
    fun `a second caller joins the run already going`() = runTest {
        val gate = CompletableDeferred<String>()
        var runs = 0
        val first = async { runSingleFlight(flights, "k", "") { runs++; gate.await() } }
        runCurrent()
        val second = async { runSingleFlight(flights, "k", "") { runs++; "other" } }
        runCurrent()
        gate.complete("result")
        assertEquals("result", first.await())
        assertEquals("result", second.await())
        assertEquals(1, runs)
    }

    @Test
    fun `a caller joined to a run that gets cancelled runs it again`() = runTest {
        val gate = CompletableDeferred<String>()
        var runs = 0
        val owner = launch { runSingleFlight(flights, "k", "") { runs++; gate.await() } }
        runCurrent()
        val joiner = async { runSingleFlight(flights, "k", "") { runs++; "fresh" } }
        runCurrent()
        owner.cancel()
        assertEquals("fresh", joiner.await())
        assertEquals(2, runs)
    }

    @Test
    fun `a caller that is cancelled itself leaves the run alone`() = runTest {
        val gate = CompletableDeferred<String>()
        val owner = async { runSingleFlight(flights, "k", "") { gate.await() } }
        runCurrent()
        val joiner = launch { runSingleFlight(flights, "k", "") { "other" } }
        runCurrent()
        joiner.cancel()
        runCurrent()
        gate.complete("result")
        assertEquals("result", owner.await())
        assertTrue(joiner.isCancelled)
    }

    @Test
    fun `a run that fails gives the fallback and is forgotten`() = runTest {
        var reported: Exception? = null
        val result = runSingleFlight(flights, "k", "fallback", onFailure = { reported = it }) {
            error("boom")
        }
        assertEquals("fallback", result)
        assertEquals("boom", reported?.message)
        assertTrue(flights.isEmpty())
    }
}
