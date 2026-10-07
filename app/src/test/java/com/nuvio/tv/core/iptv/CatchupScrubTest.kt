package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class CatchupScrubTest {
    private val minute = 60_000L
    private val base = 1_800_000_000_000L
    private fun programme(start: Long, stop: Long?, title: String = "P$start") =
        GuideProgramme("one", GuideTimestamp(base + start * minute, 14, "s"), stop?.let { GuideTimestamp(base + it * minute, 14, "e") }, listOf(LocalizedGuideText(title, null)), emptyList())
    private val first = programme(0, 60, "First")
    private val second = programme(60, 120, "Second")
    private val third = programme(120, 180, "Third")
    private val guide = listOf(first, second, third)
    private fun at(minutes: Long) = base + minutes * minute

    @Test fun seeksInsideTheCurrentStreamAndReportsTheProgrammeShown() {
        val stream = CatchupStream(at(0), at(60), seekable = true)
        val step = CatchupScrub.plan(at(30), stream, guide, at(300))
        assertEquals(CatchupStep.Seek(30 * minute, first), step)
    }

    @Test fun crossesIntoTheNextProgrammeByRetuning() {
        val stream = CatchupStream(at(0), at(60), seekable = true)
        assertEquals(CatchupStep.Tune(second, null), CatchupScrub.plan(at(60), stream, guide, at(300)))
        assertEquals(CatchupStep.Tune(second, at(75)), CatchupScrub.plan(at(75) + 20_000, stream, guide, at(300)))
    }

    @Test fun crossesBackIntoThePreviousProgrammeNearItsEnd() {
        val stream = CatchupStream(at(60), at(120), seekable = true)
        assertEquals(CatchupStep.Tune(first, at(59)), CatchupScrub.plan(at(60) - 30_000, stream, guide, at(300)))
    }

    @Test fun aBehindLiveStreamKeepsSeekingAcrossTheProgrammeBoundary() {
        val stream = CatchupStream(at(100), at(240), seekable = true)
        val step = CatchupScrub.plan(at(125), stream, guide, at(180))
        assertEquals(CatchupStep.Seek(25 * minute, third), step)
    }

    @Test fun returnsToLiveWithinTenSecondsOfTheEdge() {
        val stream = CatchupStream(at(100), at(240), seekable = true)
        val now = at(170)
        assertEquals(CatchupStep.Live, CatchupScrub.plan(now - 9_000, stream, guide, now))
        assertTrue(CatchupScrub.plan(now - 11_000, stream, guide, now) is CatchupStep.Seek)
        assertTrue(CatchupScrub.nearLive(now - 10_000, now))
        assertFalse(CatchupScrub.nearLive(now - 10_001, now))
    }

    @Test fun anUnseekableStreamRetunesEvenInsideTheCurrentProgramme() {
        val stream = CatchupStream(at(0), at(60), seekable = false)
        assertEquals(CatchupStep.Tune(first, at(40)), CatchupScrub.plan(at(40), stream, guide, at(300)))
    }

    @Test fun withoutGuideDataItStaysOrRewindsToTheStreamStart() {
        assertEquals(CatchupStep.Stay, CatchupScrub.plan(at(500), CatchupStream(at(400), at(460), seekable = true), guide, at(900)))
        assertEquals(CatchupStep.Seek(0, null), CatchupScrub.plan(at(390), CatchupStream(at(400), at(460), seekable = true), guide, at(900)))
        assertEquals(CatchupStep.Tune(first, null), CatchupScrub.plan(at(-30), CatchupStream(at(30), at(60), seekable = false), guide, at(900)))
    }

    @Test fun continuesIntoTheNextProgrammeAtTheEnd() {
        assertEquals(CatchupStep.Tune(second, null), CatchupScrub.afterEnd(first, at(60), guide, at(300)))
        assertEquals(CatchupStep.Tune(third, at(120)), CatchupScrub.afterEnd(second, at(120), guide, at(150)))
        assertEquals(CatchupStep.Live, CatchupScrub.afterEnd(third, at(180), guide, at(300)))
        assertEquals(CatchupStep.Live, CatchupScrub.afterEnd(second, at(120), guide, at(120) + 5_000))
    }

    @Test fun streamEndsAtTheProgrammeOrAnHourPastNowWhileAiring() {
        assertEquals(at(60), CatchupScrub.streamEnd(first, null, at(300)))
        assertEquals(at(60), CatchupScrub.streamEnd(first, at(20), at(300)))
        assertEquals(at(180) + CatchupScrub.TAIL_MILLIS, CatchupScrub.streamEnd(third, at(130), at(150)))
        assertEquals(at(0) + CatchupScrub.TAIL_MILLIS, CatchupScrub.streamEnd(programme(0, null), null, at(300)))
    }

    @Test fun barPlacesPositionAndLiveEdge() {
        val bar = CatchupScrub.bar(third, at(150), at(165))
        assertEquals(at(120), bar.startMillis); assertEquals(at(180), bar.endMillis)
        assertEquals(.5f, bar.position, .001f)
        assertEquals(.75f, bar.live!!, .001f)
        assertNull(CatchupScrub.bar(first, at(30), at(300)).live)
    }

    @Test fun programmeAtIgnoresGapsAndUsesHalfOpenRanges() {
        assertEquals(second, CatchupScrub.programmeAt(guide, at(60)))
        assertNull(CatchupScrub.programmeAt(guide, at(180)))
    }
}
