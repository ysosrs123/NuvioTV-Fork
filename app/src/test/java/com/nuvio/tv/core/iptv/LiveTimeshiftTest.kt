package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LiveTimeshiftTest {
    private val start = 1_800_000_000_000L
    private fun programme(from: Long = start, to: Long? = start + 3_600_000, precise: Boolean = true) = GuideProgramme("one",
        GuideTimestamp(from, if (precise) 14 else 8, ""), to?.let { GuideTimestamp(it, 14, "") }, listOf(LocalizedGuideText("Show", null)), emptyList())

    @Test fun shortPausesResumeInPlaceAndLongerOnesUseTheArchiveFromTheMinutePaused() {
        val paused = start + 10 * 60_000 + 25_000
        assertNull(LiveTimeshift.resumeFrom(paused, paused + 19_000, programme(), archive = true))
        assertNull(LiveTimeshift.resumeFrom(paused, paused + 120_000, programme(), archive = false))
        assertNull(LiveTimeshift.resumeFrom(paused, paused + 120_000, null, archive = true))
        assertEquals(start + 10 * 60_000, LiveTimeshift.resumeFrom(paused, paused + 120_000, programme(), archive = true))
    }

    @Test fun rewindStaysInsideTheProgrammeAndNeedsAPreciseStart() {
        assertEquals(start + 4 * 60_000, LiveTimeshift.rewindFrom(start + 5 * 60_000 + 10_000, programme(), archive = true))
        assertEquals(start, LiveTimeshift.rewindFrom(start + 30_000, programme(), archive = true))
        assertNull(LiveTimeshift.rewindFrom(start + 5 * 60_000, programme(precise = false), archive = true))
        assertNull(LiveTimeshift.rewindFrom(start - 1, programme(), archive = true))
        assertNull(LiveTimeshift.rewindFrom(start + 4_000_000, programme(), archive = true))
        assertNull(LiveTimeshift.rewindFrom(start + 5 * 60_000, programme(), archive = false))
    }

    @Test fun positionCountsFromTheTimeshiftStartOrTheProgrammeStart() {
        assertEquals(start + 5_000, LiveTimeshift.position(programme(), null, 5_000))
        assertEquals(start + 600_000 + 5_000, LiveTimeshift.position(programme(), start + 600_000, 5_000))
        assertEquals(start, LiveTimeshift.position(programme(), null, -10))
    }
}
