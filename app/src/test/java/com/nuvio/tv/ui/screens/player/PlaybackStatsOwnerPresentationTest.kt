package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackStatsOwnerPresentationTest {
    @Test fun `whole file average follows Size in Source and server latency belongs to Network`() {
        val mux = StatsRow("Mux bitrate", "71.6 Mbit/s average")
        val tcp = StatsRow("Server latency", "104 ms · new connection")
        val sections = buildStatsSections(listOf(StatsRow("Size", "72.83 GB"), StatsRow("Video", "HEVC"), mux, tcp, StatsRow("App CPU", "34 %")))
        assertEquals(listOf("Size", "File bitrate"), sections.single { it.group == StatsGroup.SOURCE }.rows.map { it.label })
        assertEquals(mux.value, sections.single { it.group == StatsGroup.SOURCE }.rows.last().value)
        assertEquals(listOf(tcp), sections.single { it.group == StatsGroup.NETWORK }.rows)
        assertEquals("Mux bitrate", mux.label)
    }
    @Test fun `removed rows disappear from full and compact HUD without dropping future fields`() {
        val input = listOf(StatsRow("A bitrate", "meas 2.85"), StatsRow("Network reads", "260.9 MB"),
            StatsRow("Cache/local reads", "0 KB"), StatsRow("Future metric", "42"), StatsRow("Buffer", "12.8 s"))
        val sections = buildStatsSections(input)
        assertEquals(listOf("Buffer", "Future metric"), visibleStatsSections(sections, false).flatMap { it.rows }.map { it.label })
        assertEquals(listOf("Buffer"), visibleStatsSections(sections, true).flatMap { it.rows }.map { it.label })
        assertEquals(5, input.size)
    }
    @Test fun `read span estimate remains explicitly Network and is never renamed a whole file average`() {
        val row = StatsRow("Mux estimate", "about 68 Mbit/s, measured")
        assertEquals(listOf(StatsSection(StatsGroup.NETWORK, listOf(row))), buildStatsSections(listOf(row)))
    }
}
