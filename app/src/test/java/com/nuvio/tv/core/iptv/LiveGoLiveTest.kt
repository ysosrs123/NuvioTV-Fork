package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGoLiveTest {
    private val now = 1_000_000L

    private fun route(catchup: Boolean = false, fromLive: Boolean = false, stop: Long? = null, local: Boolean = false, localBehind: Boolean = false,
        paused: Boolean = false, behind: Long? = null, cushion: Long = 0L) =
        LiveGoLive.route(catchup, fromLive, stop, now, local, localBehind, paused, behind, cushion)

    @Test fun liveAtTheEdgeOffersNothing() {
        assertEquals(GoLiveRoute.NONE, route())
        assertEquals(GoLiveRoute.NONE, route(behind = 2_000, cushion = 20_000))
        assertEquals(GoLiveRoute.NONE, route(behind = 60_000, cushion = 0))
        assertEquals(GoLiveRoute.NONE, route(local = true))
    }

    @Test fun safetyCushionBehindLiveSkipsAhead() {
        assertEquals(GoLiveRoute.CUSHION, route(behind = LiveGoLive.BEHIND_MIN_MILLIS, cushion = 20_000))
        assertEquals(GoLiveRoute.CUSHION, route(behind = 30_000, cushion = 30_000))
    }

    @Test fun localTimeshiftBehindOrPausedReturnsToTheRingEdge() {
        assertEquals(GoLiveRoute.LOCAL, route(local = true, localBehind = true))
        assertEquals(GoLiveRoute.LOCAL, route(local = true, paused = true, behind = 60_000, cushion = 20_000))
    }

    @Test fun catchupNearLiveRetunesTheChannel() {
        assertEquals(GoLiveRoute.RETUNE, route(catchup = true, fromLive = true, stop = now - 60_000))
        assertEquals(GoLiveRoute.RETUNE, route(catchup = true, stop = now + 60_000))
        assertEquals(GoLiveRoute.RETUNE, route(catchup = true))
        assertEquals(GoLiveRoute.NONE, route(catchup = true, stop = now - 1))
        assertEquals(GoLiveRoute.NONE, route(catchup = true, stop = now - 3_600_000, paused = true))
    }

    @Test fun pausedLiveRetunes() {
        assertEquals(GoLiveRoute.RETUNE, route(paused = true))
        assertEquals(GoLiveRoute.RETUNE, route(paused = true, behind = 1_000, cushion = 20_000))
    }
}
