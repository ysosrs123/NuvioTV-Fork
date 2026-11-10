package com.nuvio.tv.core.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamPrefetchCapTest {
    private fun cap(streamsAtMs: Long?, doneAtMs: Long, check: (Boolean, Long) -> Unit) = runTest {
        var has = false
        val t0 = currentTime
        val cut = StreamPrefetchCache.collectCapped(3_000, 20_000, { has }) {
            if (streamsAtMs != null) {
                delay(streamsAtMs)
                has = true
                delay(doneAtMs - streamsAtMs)
            } else {
                delay(doneAtMs)
            }
        }
        check(cut, currentTime - t0)
    }

    @Test fun aSearchThatEndsBeforeTheCapIsNotCut() = cap(1_000, 2_000) { cut, ms ->
        assertFalse(cut)
        assertEquals(2_000, ms)
    }

    @Test fun streamsBeforeTheCapStopAtTheCap() = cap(1_000, 60_000) { cut, ms ->
        assertTrue(cut)
        assertEquals(3_000, ms)
    }

    @Test fun aSlowSourceIsGivenTimeForItsFirstStreams() = cap(8_000, 60_000) { cut, ms ->
        assertTrue(cut)
        assertTrue("stopped at $ms", ms in 8_000..8_200)
    }

    @Test fun nothingAtAllStopsAtTheLongerWait() = cap(null, 60_000) { cut, ms ->
        assertTrue(cut)
        assertEquals(20_000, ms)
    }
}
