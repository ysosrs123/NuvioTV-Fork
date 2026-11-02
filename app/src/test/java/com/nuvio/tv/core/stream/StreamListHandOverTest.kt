package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.AddonStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The player starts from the stream screen's list only for the same target, while fresh, and only once. */
class StreamListHandOverTest {
    private val movie = "1|movie|tt1856101|-1|-1"
    private val episode = "1|series|tt0903747:1:2|1|2"
    private val list = listOf(AddonStreams(addonName = "Comet", addonLogo = null, streams = emptyList()))

    @Test
    fun theSameTargetGetsTheListOnce() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000)
        assertEquals(list, handOver.take(movie, nowMs = 2_000)?.streams)
        assertNull(handOver.take(movie, nowMs = 2_001))
    }

    @Test
    fun anotherTitleOrEpisodeLoadsItsOwnListAndLeavesTheHandOver() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000)
        assertNull(handOver.take(episode, nowMs = 2_000))
        assertNull(handOver.take("2|movie|tt1856101|-1|-1", nowMs = 2_000))
        assertEquals(list, handOver.take(movie, nowMs = 3_000)?.streams)
    }

    @Test
    fun aStaleListIsDroppedAndLoadedAgain() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000)
        assertNull(handOver.take(movie, nowMs = 61_001))
        handOver.put(movie, list, nowMs = 1_000)
        assertEquals(list, handOver.take(movie, nowMs = 61_000)?.streams)
    }

    @Test
    fun aClockBeforeTheListIsTreatedAsStale() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 5_000)
        assertNull(handOver.take(movie, nowMs = 4_000))
        assertNull(handOver.take(movie, nowMs = 5_000))
    }

    @Test
    fun theNewestListReplacesTheOlderOne() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000)
        handOver.put(episode, list, nowMs = 2_000)
        assertNull(handOver.take(movie, nowMs = 3_000))
        assertEquals(list, handOver.take(episode, nowMs = 3_000)?.streams)
    }

    @Test
    fun anEmptyListOrBlankKeyHandsNothingOver() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, emptyList(), nowMs = 1_000)
        assertNull(handOver.take(movie, nowMs = 2_000))
        handOver.put("", list, nowMs = 1_000)
        assertNull(handOver.take("", nowMs = 2_000))
    }

    @Test
    fun aListWhoseLoadWasCancelledCanBeTakenAgainWithItsOwnAge() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000)
        val taken = handOver.take(movie, nowMs = 2_000)!!
        handOver.giveBack(taken)
        assertEquals(list, handOver.take(movie, nowMs = 3_000)?.streams)
        handOver.giveBack(taken)
        assertNull(handOver.take(movie, nowMs = 61_001))
    }

    @Test
    fun aGivenBackListNeverReplacesANewerOne() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000)
        val taken = handOver.take(movie, nowMs = 2_000)!!
        handOver.put(episode, list, nowMs = 3_000)
        handOver.giveBack(taken)
        assertNull(handOver.take(movie, nowMs = 4_000))
        assertEquals(list, handOver.take(episode, nowMs = 4_000)?.streams)
    }

    @Test
    fun aHandedOverListIsAsFreshAsAReusedPrefetch() {
        assertEquals(StreamPrefetchCache.PLAYER_REUSE_MS, StreamListHandOver.MAX_AGE_MS)
    }

    @Test
    fun aListFromBeforeAnAutomaticPickIsMarkedPartial() {
        val handOver = StreamListHandOver(maxAgeMs = 60_000)
        handOver.put(movie, list, nowMs = 1_000, partial = true)
        assertEquals(true, handOver.take(movie, nowMs = 2_000)?.partial)
        handOver.put(movie, list, nowMs = 3_000)
        assertEquals(false, handOver.take(movie, nowMs = 4_000)?.partial)
    }

    @Test
    fun theLiveListKeepsThePartialGroupsItHasNotBroughtYet() {
        val a = AddonStreams(addonName = "A", addonLogo = null, streams = emptyList())
        val b = AddonStreams(addonName = "B", addonLogo = null, streams = emptyList())
        val bLive = AddonStreams(addonName = "B", addonLogo = "live", streams = emptyList())
        val c = AddonStreams(addonName = "C", addonLogo = null, streams = emptyList())
        assertEquals(listOf(bLive, c, a), StreamListHandOver.withHanded(listOf(a, b), listOf(bLive, c)))
        assertEquals(listOf(a, b), StreamListHandOver.withHanded(listOf(a, b), emptyList()))
    }
}
