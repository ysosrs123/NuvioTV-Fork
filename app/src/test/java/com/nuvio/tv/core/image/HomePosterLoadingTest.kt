package com.nuvio.tv.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePosterLoadingTest {
    @Test fun decodedWindowScalesToViewportAndReversalWithoutGrowingWithCatalogue() {
        val right = homePosterWarmWindow(1000, 100, 111, 99)
        assertEquals((112..135).toList(), right.take(24))
        assertEquals((99 downTo 88).toList(), right.drop(24))
        val left = homePosterWarmWindow(1000, 99, 110, 100)
        assertEquals((98 downTo 75).toList(), left.take(24))
        assertTrue(right.none { it in 100..111 })
        assertEquals(right.size, right.distinct().size)
        assertTrue(homePosterWarmWindow(Int.MAX_VALUE, 100, 199, 99).size <= 48)
        assertTrue(homePosterWarmWindow(5, 0, 4, 0).isEmpty())
    }

    @Test fun idleCoverageIncludesLoadedRowsBeyondTheLazyViewportInPriorityOrder() {
        assertEquals(listOf(3, 4, 5, 2, 6, 1, 7, 0), progressivePosterRows(8, 3, 4).toList())
        assertEquals(listOf(0, 1, 2), progressivePosterRows(3, 0, 1).toList())
        assertTrue(progressivePosterRows(0, 0, 1).none())
    }

    @Test fun progressiveCoverageIncludesBothFlanksAndTheRestWithoutVisibleDuplicates() {
        val forward = progressivePosterIndices(30, 0, 4).toList()
        assertEquals((5..29).toSet(), forward.toSet())
        assertEquals(forward.size, forward.distinct().size)
        val reversed = progressivePosterIndices(30, 18, 23).toList()
        assertEquals((0..29).filter { it !in 18..23 }.toSet(), reversed.toSet())
        assertEquals(listOf(24, 17, 25, 16), reversed.take(4))
        assertTrue(progressivePosterIndices(0, 0, 0).none())
        assertEquals(listOf(5, 6, 7), progressivePosterIndices(Int.MAX_VALUE, 0, 4).take(3).toList())
    }

    @Test fun progressiveWorkWaitsForVisibleRequestsAndRechecksScrolling() = runTest {
        val viewport = HomePosterViewport()
        viewport.visibleRequests.incrementAndGet()
        var calls = 0
        var idle = true
        val waiter = launch { viewport.warmWhenIdle({ idle }) { calls++ } }
        runCurrent()
        assertEquals(0, calls)
        idle = false
        viewport.visibleRequests.decrementAndGet()
        advanceUntilIdle()
        waiter.join()
        assertEquals(0, calls)
        viewport.scrolling = true
        viewport.warmWhenIdle({ true }) { calls++ }
        assertEquals(0, calls)
        viewport.scrolling = false
        viewport.warmWhenIdle({ true }) { calls++ }
        assertEquals(1, calls)
    }

    @Test fun progressiveDownloadConcurrencyIsOneAndCancellationReleasesTheLane() = runTest {
        val viewport = HomePosterViewport()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val first = launch { viewport.warmWhenIdle({ true }) { order += 1; release.await() } }
        launch { viewport.warmWhenIdle({ true }) { order += 2 } }
        runCurrent()
        assertEquals(listOf(1), order)
        first.cancel()
        runCurrent()
        assertEquals(listOf(1, 2), order)
        assertTrue(HomePosterPriority(viewport, 0, true, true).rank() > HomePosterPriority(viewport, 3, true).rank())
    }

    @Test fun verticalWarmupReversesWithoutGrowingItsBudgetOrIncludingVisibleRows() {
        assertEquals(listOf(7, 8, 3), homePosterPrefetchRows(4, 6, 3))
        assertEquals(listOf(3, 2, 7), homePosterPrefetchRows(4, 6, 5))
        assertEquals(listOf(2), homePosterPrefetchRows(0, 1, 1))
        assertTrue(homePosterPrefetchRows(-1, -1, 0).isEmpty())
    }
    @Test fun diskLookupMatchesImageMapperWithoutChangingOtherHostsOrBackdrops() {
        val original = "https://image.tmdb.org/t/p/original/poster.jpg"
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", sizedHomeArtworkUrl(original, 180))
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", sizedHomeArtworkUrl(original, 400))
        assertEquals(original, sizedHomeArtworkUrl(original, 1920))
        assertEquals(original, sizedHomeArtworkUrl(original, null))
        assertEquals("https://other.test/original/poster.jpg", sizedHomeArtworkUrl("https://other.test/original/poster.jpg", 180))
    }

    @Test fun identicalRequestsWaitAndRecheckWhileOtherPostersProceed() = runTest {
        val locks = PosterRequestLocks()
        val release = CompletableDeferred<Unit>()
        var cached = false
        val order = mutableListOf<String>()
        launch { locks.run("poster") { order += "decode"; release.await(); cached = true } }
        runCurrent()
        launch { locks.run("poster") { order += if (cached) "memory" else "duplicate" } }
        launch { locks.run("other") { order += "other" } }
        runCurrent()
        assertEquals(listOf("decode", "other"), order)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("decode", "other", "memory"), order)
        val cancelled = launch { locks.run("poster") { CompletableDeferred<Unit>().await() } }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertEquals("released", locks.run("poster") { "released" })
    }

    @Test fun visibleRowsPrecedePrefetchAndFollowTheViewport() {
        val viewport = HomePosterViewport()
        val rows = listOf(4, 1, 0, 2).map { HomePosterPriority(viewport, it) }
        assertEquals(listOf(0, 1, 2, 4), rows.sortedBy { it.rank() }.map { it.row })
        assertTrue(HomePosterPriority(viewport, 1).rank() < HomePosterPriority(viewport, 0, true).rank())
        viewport.firstRow = 4
        viewport.lastRow = 5
        assertEquals(4, rows.minBy { it.rank() }.row)
        assertTrue(HomePosterPriority(viewport, 4).rank() < HomePosterPriority(viewport, 4, true).rank())
    }

    @Test fun queuedWorkReordersWhenTheUserScrollsAndCancelledWorkDoesNotRun() = runTest {
        val queue = ImagePriorityQueue(1)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { queue.run({ 0 }) { order += "active"; release.await() } }
        runCurrent()
        var nextRank = 20
        launch { queue.run({ 10 }) { order += "old viewport" } }
        val cancelled = launch { queue.run({ 0 }) { order += "cancelled" } }
        launch { queue.run({ nextRank }) { order += "new viewport" } }
        runCurrent()
        cancelled.cancel()
        nextRank = -1
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("active", "new viewport", "old viewport"), order)
    }

    @Test fun failureAndCancellationReleaseSlots() = runTest {
        val queue = ImagePriorityQueue(1)
        val active = launch { queue.run({ 0 }) { CompletableDeferred<Unit>().await() } }
        runCurrent()
        active.cancel()
        runCurrent()
        runCatching { queue.run({ 0 }) { error("decode failed") } }
        assertEquals("next", queue.run({ 0 }) { "next" })
    }

    @Test fun prefetchUsesTheVisibleCardKeyAndRevalidationRemainsDistinct() {
        assertEquals("poster_180x270_v0", homePosterCacheKey("poster", 180, 270))
        assertEquals("poster_180x270_v1", homePosterCacheKey("poster", 180, 270, 1))
    }

    @Test fun diskDecodeSlotsFollowTheDeviceClassAndNeverExceedBitmapParallelism() {
        val mib = 1024L * 1024
        assertEquals(4, homePosterDiskDecodeSlots(3_800L * mib, lowRamDevice = false, cores = 8))
        assertEquals(3, homePosterDiskDecodeSlots(1_950L * mib, lowRamDevice = false, cores = 4))
        assertEquals(2, homePosterDiskDecodeSlots(3_800L * mib, lowRamDevice = true, cores = 8))
        assertEquals(2, homePosterDiskDecodeSlots(1_024L * mib, lowRamDevice = false, cores = 4))
        assertEquals(2, homePosterDiskDecodeSlots(3_800L * mib, lowRamDevice = false, cores = 2))
        assertEquals(2, homePosterDiskDecodeSlots(0L, lowRamDevice = false, cores = 8))
        listOf(0L, 1_024L, 2_048L, 2_560L, 4_096L, 16_384L).forEach { total ->
            assertTrue(homePosterDiskDecodeSlots(total * mib, false, 8) <= HOME_POSTER_BITMAP_PARALLELISM)
        }
    }

    @Test fun onlyBoxesAboveThreeGigabytesGetTheLargerArtworkCache() {
        val mib = 1024L * 1024
        assertEquals(176L * mib, homeArtworkMemoryCacheBytes(96L * mib, 3_800L * mib))
        assertEquals(96L * mib, homeArtworkMemoryCacheBytes(96L * mib, 3_072L * mib))
        assertEquals(57L * mib, homeArtworkMemoryCacheBytes(57L * mib, 1_950L * mib))
        assertEquals(256L * mib, homeArtworkMemoryCacheBytes(256L * mib, 8_192L * mib))
        assertEquals(132L * mib, ArtworkMemoryCache(176L * mib).posterMaxSize)
    }

    @Test fun idleMemoryWarmingTakesAThirdOfThePosterPartition() {
        val mib = 1024L * 1024
        assertEquals(44L * mib, homePosterMemoryWarmBudget(132L * mib))
        assertEquals(0L, homePosterMemoryWarmBudget(0L))
        assertEquals(0L, homePosterMemoryWarmBudget(-1L))
    }

    @Test fun aFailedPosterIsRetriedOnceThenGivesUp() {
        assertTrue(homePosterRetryAfterError(0))
        assertFalse(homePosterRetryAfterError(1))
        assertFalse(homePosterRetryAfterError(2))
    }

    @Test fun memoryRowsAreTheVisibleRowsThenTheNearestRowsInTheDirectionOfTravel() {
        assertEquals(listOf(3, 4, 5, 6, 7), homePosterMemoryWarmRows(20, 3, 4, 2))
        assertEquals(listOf(3, 4, 5, 6, 7), homePosterMemoryWarmRows(20, 3, 4, -1))
        assertEquals(listOf(3, 4, 2, 1, 0), homePosterMemoryWarmRows(20, 3, 4, 5))
        assertEquals(listOf(1, 2, 0), homePosterMemoryWarmRows(20, 1, 2, 4))
        assertEquals(listOf(3, 4, 5), homePosterMemoryWarmRows(6, 3, 4, 2))
        assertTrue(homePosterMemoryWarmRows(0, 0, 1, -1).isEmpty())
        assertTrue(homePosterMemoryWarmRows(10, -1, -1, -1).isEmpty())
    }

    @Test fun memoryColumnsCoverOneScreenBeyondAndHalfAScreenBehind() {
        assertEquals(0..11, homePosterMemoryWarmColumns(0, 6))
        assertEquals(7..21, homePosterMemoryWarmColumns(10, 6))
        assertEquals(0..7, homePosterMemoryWarmColumns(0, 1))
        assertEquals(0..31, homePosterMemoryWarmColumns(0, 100))
    }

    @Test fun startedAheadWorkIsKeptNearTheViewportAndDroppedOnceLeftBehind() {
        val keep = homePosterAheadKeepRows(4, 6, 2)
        assertEquals(1..9, keep)
        assertTrue(homePosterPrefetchRows(4, 6, 3, 2).all { it in keep })
        assertTrue(homePosterPrefetchRows(4, 6, 5, 2).all { it in keep })
        assertFalse(0 in homePosterAheadKeepRows(4, 6, 2))
    }
}
