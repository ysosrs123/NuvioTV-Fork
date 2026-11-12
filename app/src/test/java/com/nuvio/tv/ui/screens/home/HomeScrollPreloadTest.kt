package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.image.HomeScrollSignal
import com.nuvio.tv.core.image.ImagePriorityQueue
import com.nuvio.tv.core.image.KnownDiskKeys
import com.nuvio.tv.core.image.homePosterBackgroundLanes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScrollPreloadTest {
    @Test fun heroWaitsLongerWhileKeysFollowQuickly() {
        assertEquals(MODERN_HERO_QUICK_KEYS_SETTLE_MS, heroSettleDelayMs(0))
        assertEquals(MODERN_HERO_QUICK_KEYS_SETTLE_MS, heroSettleDelayMs(300))
        assertEquals(MODERN_HERO_QUICK_KEYS_SETTLE_MS, heroSettleDelayMs(MODERN_HERO_QUICK_KEYS_GAP_MS - 1))
    }

    @Test fun heroKeepsItsUsualDelayForUnhurriedMoves() {
        assertEquals(MODERN_HERO_FOCUS_DEBOUNCE_MS, heroSettleDelayMs(MODERN_HERO_QUICK_KEYS_GAP_MS))
        assertEquals(MODERN_HERO_FOCUS_DEBOUNCE_MS, heroSettleDelayMs(2_000))
        // The first move has no earlier one to measure from, and a clock set back gives a negative gap.
        assertEquals(MODERN_HERO_FOCUS_DEBOUNCE_MS, heroSettleDelayMs(Long.MAX_VALUE))
        assertEquals(MODERN_HERO_FOCUS_DEBOUNCE_MS, heroSettleDelayMs(-5))
    }

    @Test fun scrollingLeavesOneBackgroundLane() {
        assertEquals(1, homePosterBackgroundLanes(2, scrolling = true))
        assertEquals(1, homePosterBackgroundLanes(3, scrolling = true))
        assertEquals(1, homePosterBackgroundLanes(1, scrolling = true))
        assertEquals(2, homePosterBackgroundLanes(2, scrolling = false))
        assertEquals(3, homePosterBackgroundLanes(3, scrolling = false))
    }

    @Test fun postersOnScreenKeepTheirLanesWhileScrolling() = runTest {
        var scrolling = true
        val queue = ImagePriorityQueue(concurrency = 4, backgroundConcurrency = 2) { scrolling }
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { queue.run({ 5_000 }, { true }) { order += "prefetch 1"; release.await() } }
        launch { queue.run({ 5_001 }, { true }) { order += "prefetch 2"; release.await() } }
        launch { queue.run({ 0 }) { order += "visible 1"; release.await() } }
        launch { queue.run({ 1 }) { order += "visible 2"; release.await() } }
        launch { queue.run({ 2 }) { order += "visible 3"; release.await() } }
        runCurrent()
        assertEquals(listOf("prefetch 1", "visible 1", "visible 2", "visible 3"), order)
        scrolling = false
        queue.recheck()
        runCurrent()
        assertEquals(listOf("prefetch 1", "visible 1", "visible 2", "visible 3"), order)
        release.complete(Unit)
        runCurrent()
        assertEquals("prefetch 2", order.last())
    }

    @Test fun aFreedBackgroundLaneIsHandedOutOnRecheck() = runTest {
        var scrolling = true
        val queue = ImagePriorityQueue(concurrency = 4, backgroundConcurrency = 2) { scrolling }
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { queue.run({ 5_000 }, { true }) { order += "prefetch 1"; release.await() } }
        launch { queue.run({ 5_001 }, { true }) { order += "prefetch 2"; release.await() } }
        runCurrent()
        assertEquals(listOf("prefetch 1"), order)
        scrolling = false
        queue.recheck()
        runCurrent()
        assertEquals(listOf("prefetch 1", "prefetch 2"), order)
        release.complete(Unit)
    }

    @Test fun scrollReportEndsWhenStoppedOrGoneStale() {
        var now = 10_000L
        var stopped = 0
        val signal = HomeScrollSignal { now }
        signal.onStopped = { stopped++ }
        assertFalse(signal.scrolling)
        signal.reportScrolling()
        now += 500
        assertTrue(signal.scrolling)
        signal.reportStopped()
        assertFalse(signal.scrolling)
        assertEquals(1, stopped)
        signal.reportScrolling()
        assertTrue(signal.scrolling)
        now += 60_000
        assertFalse(signal.scrolling)
    }

    @Test fun knownDiskKeysForgetOnRemoveClearAndWhenFull() {
        val known = KnownDiskKeys(limit = 3)
        known.add("a")
        known.add("b")
        assertTrue("a" in known)
        known.remove("a")
        assertFalse("a" in known)
        assertTrue("b" in known)
        known.clear()
        assertFalse("b" in known)
        listOf("1", "2", "3", "4").forEach(known::add)
        assertFalse("1" in known)
        assertTrue("4" in known)
    }

    @Test fun warmUpHoldsOffUntilKeysHaveBeenQuiet() {
        assertEquals(HOME_WARMUP_FOCUS_QUIET_MS, homeWarmupFocusWaitMs(0))
        assertEquals(500L, homeWarmupFocusWaitMs(HOME_WARMUP_FOCUS_QUIET_MS - 500))
        assertEquals(0L, homeWarmupFocusWaitMs(HOME_WARMUP_FOCUS_QUIET_MS))
        assertEquals(0L, homeWarmupFocusWaitMs(60_000))
        assertEquals(HOME_WARMUP_FOCUS_QUIET_MS, homeWarmupFocusWaitMs(-100))
    }

    @Test fun verticalPreloadStartsFewerRequestsWhileScrolling() {
        assertEquals(8, verticalPosterPrefetchInFlightLimit(scrolling = true))
        assertEquals(32, verticalPosterPrefetchInFlightLimit(scrolling = false))
    }
}
