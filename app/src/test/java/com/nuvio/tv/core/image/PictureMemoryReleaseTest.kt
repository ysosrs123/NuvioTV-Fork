package com.nuvio.tv.core.image

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMemoryReleaseTest {
    private val mb = 1024L * 1024L

    @Test
    fun `only boxes sold as 2 GB or less let pictures go before playback`() {
        assertTrue(PictureMemoryRelease.releasesBeforePlayback(1_900L * mb))
        assertTrue(PictureMemoryRelease.releasesBeforePlayback(0L))
        assertFalse(PictureMemoryRelease.releasesBeforePlayback(2_900L * mb))
        assertFalse(PictureMemoryRelease.releasesBeforePlayback(3_800L * mb))
    }

    @Test
    fun `during a film boxes of 3 GB or less let Home's pictures go, bigger boxes only after a warning`() {
        assertTrue(PictureMemoryRelease.releasesDuringFilm(1_900L * mb, warned = false))
        assertTrue(PictureMemoryRelease.releasesDuringFilm(2_921L * mb, warned = false))
        assertTrue(PictureMemoryRelease.releasesDuringFilm(0L, warned = false))
        assertFalse(PictureMemoryRelease.releasesDuringFilm(3_800L * mb, warned = false))
        assertTrue(PictureMemoryRelease.releasesDuringFilm(3_800L * mb, warned = true))
    }

    @Test
    fun `one picture release covers a burst of warnings`() {
        assertTrue(PictureMemoryRelease.warningReleaseDue(10_000L, Long.MIN_VALUE / 2))
        assertFalse(PictureMemoryRelease.warningReleaseDue(14_999L, 10_000L))
        assertTrue(PictureMemoryRelease.warningReleaseDue(15_000L, 10_000L))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `closing the player before the delay keeps Home's pictures`() {
        val scope = TestScope()
        var released = 0
        PictureMemoryRelease.scheduleDuringFilm(scope) { released++ }
        scope.testScheduler.advanceTimeBy(4_000L)
        PictureMemoryRelease.cancelDuringFilm()
        scope.advanceUntilIdle()
        assertEquals(0, released)
        PictureMemoryRelease.scheduleDuringFilm(scope) { released++ }
        scope.advanceUntilIdle()
        assertEquals(1, released)
    }
}
