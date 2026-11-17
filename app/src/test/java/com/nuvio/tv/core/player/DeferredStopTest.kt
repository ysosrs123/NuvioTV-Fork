package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredStopTest {
    private val idle = mutableListOf<Runnable>()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    private val deferred = DeferredStop({ idle += it }, { ms, run -> delayed += ms to run })
    private var stops = 0

    @Test fun `idle runs the stop once and the cap that follows does nothing`() {
        deferred.schedule { stops++ }
        idle.single().run()
        delayed.single().second.run()
        assertEquals(1, stops)
    }

    @Test fun `the cap stops the player when the main thread never goes idle`() {
        deferred.schedule { stops++ }
        assertEquals(DeferredStop.MAX_WAIT_MS, delayed.single().first)
        delayed.single().second.run()
        idle.single().run()
        assertEquals(1, stops)
    }

    @Test fun `a new trailer cancels a stop that has not run`() {
        deferred.schedule { stops++ }
        deferred.cancel()
        idle.single().run()
        delayed.single().second.run()
        assertEquals(0, stops)
    }

    @Test fun `only the newest stop runs`() {
        var first = 0
        deferred.schedule { first++ }
        deferred.schedule { stops++ }
        (idle + delayed.map { it.second }).forEach { it.run() }
        assertEquals(0, first)
        assertEquals(1, stops)
    }
}
