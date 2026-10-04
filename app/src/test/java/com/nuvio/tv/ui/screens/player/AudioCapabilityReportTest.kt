package com.nuvio.tv.ui.screens.player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapabilityReportTest {

    @Test
    fun format_listsSupportedAndAbsentAndMode() {
        assertEquals(
            "Direct: AC3 EAC3 TrueHD\nAbsent: DTS DTS-HD\nNegotiated: AC3 EAC3 PCM16\nSurround: MANUAL\nMax PCM: 8 ch",
            AudioCapabilityReport.format(
                supported = listOf("AC3", "EAC3", "TrueHD"),
                absent = listOf("DTS", "DTS-HD"),
                negotiated = "AC3 EAC3 PCM16",
                surroundMode = "MANUAL",
                maxPcm = "8 ch"
            )
        )
    }

    @Test
    fun format_saysNoneRatherThanEmptyList() {
        assertEquals(
            "Direct: none\nAbsent: AC3\nNegotiated: unknown\nSurround: NEVER\nMax PCM: unknown",
            AudioCapabilityReport.format(emptyList(), listOf("AC3"), "unknown", "NEVER", "unknown")
        )
        assertEquals(
            "Direct: AC3\nAbsent: none\nNegotiated: AC3\nSurround: AUTO\nMax PCM: 2 ch",
            AudioCapabilityReport.format(listOf("AC3"), emptyList(), "AC3", "AUTO", "2 ch")
        )
    }

    @Test
    fun format_isOneLabelledLinePerFacet() {
        val text = AudioCapabilityReport.format(
            listOf("AC3", "EAC3", "EAC3-JOC", "TrueHD", "DTS", "DTS-HD"),
            emptyList(),
            "AC3 EAC3 PCM16",
            "MANUAL",
            "8 ch"
        )
        val lines = text.split("\n")
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("Direct: "))
        assertTrue(lines[1].startsWith("Absent: "))
        assertTrue(lines[2].startsWith("Negotiated: "))
        assertTrue(lines[3].startsWith("Surround: "))
        assertTrue(lines[4].startsWith("Max PCM: "))
    }

    @Test
    fun latest_isNullUntilCaptured() {
        AudioCapabilityReport.reset()
        assertNull(AudioCapabilityReport.latest)
    }
}
