package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.toSkipIntervals
import org.junit.Assert.*
import org.junit.Test

class MovieSkipIntegrationTest {
    private fun segment(start: Double, end: Double) = IntroDbSegment(startSec = start, endSec = end)
    private fun recommendation(position: Long, intervals: List<SkipInterval>) = shouldShowPostPlayRecommendation(
        "movie", position, 100000L, intervals, 95, NextEpisodeThresholdMode.PERCENTAGE, 99f, 2f)

    @Test fun `movie credits stop before a post credits scene and never offer to skip that scene`() {
        val intervals = IntroDbSegmentsResponse(outro = segment(80.0, 100.0), postCredits = segment(90.0, 97.0)).toSkipIntervals(true)
        val credits = findActiveSkipInterval(intervals, 85000L)!!
        assertEquals(90.0, credits.endTime, 0.0)
        assertEquals(90.0, credits.followingPostCreditsScene(intervals, 100000L)!!.startTime, 0.0)
        assertNull(findActiveSkipInterval(intervals, 92000L))
        assertFalse(recommendation(96000L, intervals))
        assertTrue(recommendation(97000L, intervals))
    }

    @Test fun `playable scene extending beyond runtime delays recommendations until EOF`() {
        val intervals = IntroDbSegmentsResponse(outro = segment(80.0, 90.0), postCredits = segment(90.0, 105.0)).toSkipIntervals(true)
        assertNotNull(intervals.first().followingPostCreditsScene(intervals, 100000L))
        assertFalse(recommendation(99999L, intervals))
        assertTrue(recommendation(100000L, intervals))
    }

    @Test fun `invalid segment times are rejected and movie only auto skip stays opt in`() {
        val intervals = IntroDbSegmentsResponse(outro = segment(Double.NaN, 90.0), postCredits = segment(-1.0, 20.0)).toSkipIntervals(true)
        assertTrue(intervals.isEmpty())
        assertFalse(AutoSkipSegmentType.MOVIE_CREDITS in PlayerSettings().autoSkipSegmentTypes)
        assertFalse(PlayerSettings().useLibass)
        assertFalse(recommendation(94000L, intervals))
        assertTrue(recommendation(95000L, intervals))
    }
}
