package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionRowTest {
    @Test fun `under an hour reads m colon ss on both sides`() {
        assertEquals(StatsRow("Position", "4:05 / 42:30"), positionStatsRow(245_000L, 2_550_000L, isLive = false))
        assertEquals("0:00 / 0:07", positionStatsRow(0L, 7_400L, isLive = false).value)
    }

    @Test fun `over an hour reads h colon mm colon ss`() {
        assertEquals(StatsRow("Position", "1:02:15 / 2:10:14"),
            positionStatsRow(3_735_000L, 7_814_000L, isLive = false))
        assertEquals("12:00:00 / 12:00:00", positionStatsRow(43_200_000L, 43_200_000L, isLive = false).value)
    }

    @Test fun `each side picks its own shape`() {
        assertEquals("59:59 / 1:00:00", positionStatsRow(3_599_000L, 3_600_000L, isLive = false).value)
    }

    @Test fun `an unknown length shows the position alone`() {
        assertEquals("1:02:15", positionStatsRow(3_735_000L, 0L, isLive = false).value)
        assertEquals("4:05", positionStatsRow(245_000L, -1L, isLive = false).value)
    }

    @Test fun `a live window shows the position alone`() {
        assertEquals("4:05", positionStatsRow(245_000L, 2_550_000L, isLive = true).value)
    }

    @Test fun `negative positions read as the start`() {
        assertEquals("0:00 / 42:30", positionStatsRow(-500L, 2_550_000L, isLive = false).value)
        assertEquals(StatsDot.NONE, positionStatsRow(0L, 0L, isLive = false).dot)
    }

    @Test fun `the row sits first in its own section and survives compact mode`() {
        val sections = buildStatsSections(listOf(
            StatsRow("Add-on", "Tortoise"), positionStatsRow(245_000L, 2_550_000L, isLive = false),
            StatsRow("Video", "HEVC"), StatsRow("Buffer", "20s")))
        assertEquals(StatsGroup.PLAYBACK, sections.first().group)
        assertEquals(listOf("Position"), sections.first().rows.map { it.label })
        assertEquals(StatsGroup.SOURCE, sections[1].group)
        val compact = visibleStatsSections(sections, compact = true)
        assertEquals(listOf("Position", "Video", "Buffer"), compact.flatMap { it.rows }.map { it.label })
    }
}
