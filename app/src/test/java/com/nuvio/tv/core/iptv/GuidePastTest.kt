package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class GuidePastTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val slot = GuideGridWindow.SLOT_MILLIS
    private val now = 1_791_000_000_000L - 1_791_000_000_000L % slot + 10 * minute
    private val nowSlot = now - 10 * minute
    private val home = GuideGridWindow(nowSlot - slot, nowSlot - slot + 12 * hour)

    @Test fun archiveDaysReadXtreamAndPlaylistAttributes() {
        assertEquals(5, GuidePast.archiveDays(mapOf("archive-days" to " 5 ")))
        assertEquals(3, GuidePast.archiveDays(mapOf("catchup-days" to "3")))
        assertNull(GuidePast.archiveDays(mapOf("catchup-days" to "0")))
        assertNull(GuidePast.archiveDays(mapOf("catchup-days" to "seven")))
        assertNull(GuidePast.archiveDays(emptyMap()))
    }

    @Test fun reachUsesLongestArchiveCappedByImportedPastDays() {
        assertEquals(1, GuidePast.reachDays(emptyList(), 7))
        assertEquals(5, GuidePast.reachDays(listOf(2, 5), 7))
        assertEquals(3, GuidePast.reachDays(listOf(2, 5), 3))
        assertEquals(7, GuidePast.reachDays(listOf(2, null), 7))
        assertEquals(1, GuidePast.reachDays(listOf(14), 1))
        assertEquals(1, GuidePast.reachDays(emptyList(), 0))
    }

    @Test fun earliestIsSlotAlignedDaysBack() {
        assertEquals(nowSlot - 3 * day, GuidePast.earliest(now, 3))
        assertEquals(nowSlot - day, GuidePast.earliest(now, 0))
    }

    @Test fun catchupReachFollowsArchiveDays() {
        assertTrue(GuidePast.reaches(true, 2, now - 47 * hour, now))
        assertFalse(GuidePast.reaches(true, 2, now - 49 * hour, now))
        assertTrue(GuidePast.reaches(true, null, now - 30 * day, now))
        assertFalse(GuidePast.reaches(false, 7, now - hour, now))
        assertFalse(GuidePast.reaches(true, 7, now + minute, now))
    }

    @Test fun stayingNearNowKeepsTheNormalWindow() {
        val earliest = GuidePast.earliest(now, 1)
        assertNull(GuidePast.follow(home, null, nowSlot, nowSlot + 3 * hour, earliest))
        assertNull(GuidePast.follow(home, null, home.startMillis - hour, home.startMillis, null))
        assertNull(GuidePast.follow(home, null, home.startMillis - hour, home.startMillis, home.startMillis))
    }

    @Test fun reachingTheWindowStartLoadsEarlierInSteps() {
        val earliest = GuidePast.earliest(now, 7)
        val first = requireNotNull(GuidePast.follow(home, null, home.startMillis, home.startMillis + 3 * hour, earliest))
        assertEquals(home.startMillis - 6 * hour, first.startMillis)
        assertEquals(home.endMillis, first.endMillis)
        assertSame(first, GuidePast.follow(home, first, home.startMillis - 3 * hour, home.startMillis, earliest))
        val second = requireNotNull(GuidePast.follow(home, first, first.startMillis + hour, first.startMillis + 4 * hour, earliest))
        assertEquals(first.startMillis - 5 * hour, second.startMillis)
        assertTrue(second.spanMillis <= GuidePast.SPAN_MILLIS)
        assertEquals(0L, second.startMillis % slot)
    }

    @Test fun windowNeverReachesBeforeTheLimitOrPastTheNormalEnd() {
        val earliest = GuidePast.earliest(now, 1)
        val far = requireNotNull(GuidePast.follow(home, null, earliest + hour, earliest + 4 * hour, earliest))
        assertEquals(earliest, far.startMillis)
        assertEquals(earliest + GuidePast.SPAN_MILLIS, far.endMillis)
        assertSame(far, GuidePast.follow(home, far, earliest, earliest + 3 * hour, earliest))
        val week = GuidePast.earliest(now, 7)
        val slid = requireNotNull(GuidePast.follow(home, null, now - 3 * day, now - 3 * day + 3 * hour, week))
        assertTrue(slid.endMillis <= home.endMillis && slid.spanMillis == GuidePast.SPAN_MILLIS)
        val forward = requireNotNull(GuidePast.follow(home, slid, slid.endMillis - hour, slid.endMillis + 2 * hour, week))
        assertTrue(forward.startMillis > slid.startMillis && forward.endMillis >= slid.endMillis + 2 * hour)
    }

    @Test fun walkingBackToNowLeavesEarlierMode() {
        val earliest = GuidePast.earliest(now, 3)
        val past = requireNotNull(GuidePast.follow(home, null, home.startMillis - slot, home.startMillis + 2 * hour, earliest))
        assertSame(past, GuidePast.follow(home, past, home.startMillis + slot, home.startMillis + 3 * hour, earliest))
        assertNull(GuidePast.follow(home, past, home.startMillis + GuidePast.LEAVE_MILLIS, home.startMillis + 5 * hour, earliest))
    }

    @Test fun jumpsStayInsideTheReachableRange() {
        val earliest = GuidePast.earliest(now, 1)
        assertEquals(now - 3 * hour, GuidePast.jump(now, -1, earliest, home.endMillis))
        assertEquals(earliest, GuidePast.jump(earliest + hour, -1, earliest, home.endMillis))
        assertEquals(home.endMillis - slot, GuidePast.jump(home.endMillis - 2 * hour, 1, earliest, home.endMillis - slot))
        assertEquals(nowSlot - 3 * hour - slot, GuidePast.viewStart(now - 3 * hour, earliest))
        assertEquals(earliest, GuidePast.viewStart(earliest + minute, earliest))
    }

    @Test fun awayMeansTheViewLeftTheCurrentSlots() {
        assertFalse(GuidePast.away(nowSlot, now))
        assertFalse(GuidePast.away(nowSlot - slot, now))
        assertTrue(GuidePast.away(nowSlot - 2 * slot, now))
        assertTrue(GuidePast.away(nowSlot + slot, now))
    }

    @Test fun earlierIsOfferedOnlyWhenTheRowStartsWithAProgrammeAfterTheLimit() {
        val earliest = GuidePast.earliest(now, 1)
        assertTrue(GuidePast.earlierLoadable(home.startMillis, true, earliest))
        assertFalse(GuidePast.earlierLoadable(home.startMillis, false, earliest))
        assertFalse(GuidePast.earlierLoadable(earliest, true, earliest))
        assertFalse(GuidePast.earlierLoadable(home.startMillis, true, null))
        assertFalse(GuidePast.earlierLoadable(null, true, earliest))
    }
}
