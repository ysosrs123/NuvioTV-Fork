package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdjacentPrefetchJobsTest {
    @Test fun bothNeighboursFinishAndDuplicateRequestsShareWork() = runTest {
        val queue = AdjacentPrefetchJobs()
        val loaded = mutableListOf<String>()
        queue.launch(this, "next") { delay(100); loaded += "next" }
        queue.launch(this, "previous") { delay(100); loaded += "previous" }
        queue.launch(this, "next") { error("Duplicate fetch") }
        advanceUntilIdle()
        assertEquals(setOf("next", "previous"), loaded.toSet())
    }

    @Test fun rapidNavigationCancelsStaleNeighbourButKeepsRelevantOne() = runTest {
        val queue = AdjacentPrefetchJobs()
        val cancelled = mutableListOf<String>()
        for (id in listOf("a", "b")) {
            queue.launch(backgroundScope, id) {
                try { awaitCancellation() } finally { cancelled += id }
            }
        }
        runCurrent()
        queue.launch(backgroundScope, "a") { error("Still active") }
        queue.launch(backgroundScope, "c") { awaitCancellation() }
        runCurrent()
        assertEquals(listOf("b"), cancelled)
    }

    @Test fun completedAttemptCanBeRetried() = runTest {
        val queue = AdjacentPrefetchJobs()
        var attempts = 0
        queue.launch(this, "next") { attempts++ }
        advanceUntilIdle()
        queue.launch(this, "next") { attempts++ }
        advanceUntilIdle()
        assertEquals(2, attempts)
    }
}
