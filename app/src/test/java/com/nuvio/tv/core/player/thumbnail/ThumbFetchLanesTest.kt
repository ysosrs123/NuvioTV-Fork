package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How many keyframes are fetched at once, and which ones start next. */
class ThumbFetchLanesTest {
    private fun lanes(phase: FetchPhase, conns: Int = 2, capped: Boolean = false, small: Boolean = false, rl: Boolean = false) =
        ThumbFetchLanes.limit(phase, rl, conns, small, capped)

    @Test fun otherServersFetchEightBeforePlayAndThreeWhilePlaying() {
        assertEquals(8, lanes(FetchPhase.GENERATING))
        assertEquals(8, lanes(FetchPhase.PLAYER_CLOSED))
        assertEquals(3, lanes(FetchPhase.PLAYBACK))
        assertEquals(3, lanes(FetchPhase.PLAYBACK, conns = 6))
    }

    @Test fun torboxStaysWithinFourConnectionsWithPlayback() {
        assertEquals(2, lanes(FetchPhase.GENERATING, conns = 2, capped = true))
        assertEquals(3, lanes(FetchPhase.GENERATING, conns = 1, capped = true))
        assertEquals(2, lanes(FetchPhase.PLAYBACK, conns = 2, capped = true))
        assertEquals(1, lanes(FetchPhase.PLAYBACK, conns = 4, capped = true))
        assertEquals(4, lanes(FetchPhase.PLAYER_CLOSED, conns = 2, capped = true))
    }

    @Test fun torboxLinksAreRecognised() {
        assertTrue(ThumbFetchLanes.isConnectionCapped("https://store-031.weur.tb-cdn.st/dl/abc"))
        assertTrue(ThumbFetchLanes.isConnectionCapped("https://nexus-114.japn.tb-cdn.pw/dl/abc"))
        assertTrue(ThumbFetchLanes.isConnectionCapped("https://api.torbox.app/v1/api/torrents/requestdl?x=1"))
        assertTrue(ThumbFetchLanes.isConnectionCapped("https://torrentio.strem.fun/resolve/torbox/key/hash/1/file.mkv"))
        assertTrue(!ThumbFetchLanes.isConnectionCapped("https://stremthru.example.xyz/v0/store/link/x"))
        assertTrue(!ThumbFetchLanes.isConnectionCapped("http://192.168.1.10:8096/Videos/1/stream.mkv"))
        assertTrue(!ThumbFetchLanes.isConnectionCapped("https://notb-cdn.example.com/dl/abc"))
    }

    @Test fun aRateLimitMeansOneAtATimeEverywhere() {
        for (phase in FetchPhase.values()) for (small in listOf(false, true)) for (capped in listOf(false, true)) {
            assertEquals(1, lanes(phase, conns = 1, capped = capped, small = small, rl = true))
        }
    }

    @Test fun smallBox4K() {
        assertEquals(2, lanes(FetchPhase.GENERATING, small = true))
        assertEquals(2, lanes(FetchPhase.PLAYER_CLOSED, small = true))
        assertEquals(1, lanes(FetchPhase.PLAYBACK, conns = 1, small = true))
    }

    @Test fun alwaysBetweenOneAndMax() {
        for (phase in FetchPhase.values()) for (rl in listOf(false, true)) for (small in listOf(false, true)) for (capped in listOf(false, true)) {
            for (conns in -1..10) {
                val n = ThumbFetchLanes.limit(phase, rl, conns, small, capped)
                assertTrue("$phase $rl $conns $small $capped -> $n", n in 1..ThumbFetchLanes.MAX)
            }
        }
    }

    @Test fun pickKeepsTheOrderAndSkipsWhatIsUnderWay() {
        assertEquals(listOf(7, 3), ThumbFetchLanes.pick(listOf(7, 5, 3, 9), underWay = setOf(5), room = 2))
    }

    @Test fun pickTakesEachKeyframeOnce() {
        // Long GOPs: neighbouring slots share a keyframe.
        assertEquals(listOf(4, 6, 8), ThumbFetchLanes.pick(listOf(4, 4, 6, 4, 8), underWay = emptySet(), room = 3))
    }

    @Test fun pickWithoutRoomStartsNothing() {
        assertEquals(emptyList<Int>(), ThumbFetchLanes.pick(listOf(1, 2), underWay = emptySet(), room = 0))
        assertEquals(emptyList<Int>(), ThumbFetchLanes.pick(listOf(1, 2), underWay = emptySet(), room = -1))
        assertEquals(emptyList<Int>(), ThumbFetchLanes.pick(listOf(1, 2), underWay = setOf(1, 2), room = 4))
    }

    @Test fun pickIgnoresMissingKeyframes() {
        assertEquals(listOf(2), ThumbFetchLanes.pick(listOf(-1, 2), underWay = emptySet(), room = 2))
    }

    @Test fun theSettleTargetGoesFirst() {
        // The worker lists the settle target, then its neighbours, then the coverage pass.
        val p0 = 40
        val neighbours = listOf(41, 39, 42)
        val lattice = listOf(10, 20, 30)
        assertEquals(listOf(p0, 41, 39, 42), ThumbFetchLanes.pick(listOf(p0) + neighbours + lattice, emptySet(), room = 4))
        assertEquals(listOf(41, 39), ThumbFetchLanes.pick(listOf(p0) + neighbours + lattice, setOf(p0, 42), room = 2))
    }
}
