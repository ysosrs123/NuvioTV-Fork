package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class CompactStatsTest {
    @Test fun fullModePreservesUnknownFutureFieldsAndOriginalSample() {
        val sections = buildStatsSections(listOf(StatsRow("Video", "HEVC"), StatsRow("Future metric", "42")))
        assertSame(sections, visibleStatsSections(sections, compact = false))
        assertEquals(2, sections.sumOf { it.rows.size })
    }

    @Test fun compactRetainsPlaybackHealthAndDropsEmptySections() {
        val sections = buildStatsSections(listOf(StatsRow("File", "movie.mkv"), StatsRow("Video", "HEVC"),
            StatsRow("Dropped", "2", StatsDot.WARN), StatsRow("Underruns", "0"), StatsRow("Buffer", "20s")))
        val compact = visibleStatsSections(sections, compact = true)
        assertFalse(compact.any { it.group == StatsGroup.SOURCE })
        assertEquals(listOf("Video", "Dropped", "Underruns", "Buffer"), compact.flatMap { it.rows }.map { it.label })
        assertEquals(StatsDot.WARN, compact.first().rows.last().dot)
        assertEquals(5, sections.sumOf { it.rows.size })
    }
}
