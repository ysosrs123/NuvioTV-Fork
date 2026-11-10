package com.nuvio.tv.core.plugin.cloudstream

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RunProvidersTest {

    @Test
    fun `every provider runs, not just the first`() = runTest {
        val results = runProviders(listOf("first", "second", "third"), maxAtOnce = 4, budgetMs = 1_000) {
            listOf("$it link")
        }
        assertEquals(setOf("first link", "second link", "third link"), results.toSet())
    }

    @Test
    fun `a slow provider does not lose the finished ones`() = runTest {
        val results = runProviders(listOf("fast", "stuck"), maxAtOnce = 4, budgetMs = 1_000) { name ->
            if (name == "stuck") awaitCancellation()
            listOf(name)
        }
        assertEquals(listOf("fast"), results)
    }

    @Test
    fun `no more than the limit run at once`() = runTest {
        val running = AtomicInteger()
        val highest = AtomicInteger()
        runProviders((1..10).toList(), maxAtOnce = 3, budgetMs = 10_000) {
            highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(100)
            running.decrementAndGet()
            emptyList<Int>()
        }
        assertEquals(3, highest.get())
    }

    @Test
    fun `an extension with no providers returns nothing`() = runTest {
        val results = runProviders(emptyList<String>(), maxAtOnce = 4, budgetMs = 1_000) { listOf(it) }
        assertEquals(emptyList<String>(), results)
    }
}
