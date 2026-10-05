package com.nuvio.tv.ui.screens.player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapabilityReportTest {
    private val hdmiNote = "HDMI device = the TV or receiver this box is plugged into, not a soundbar behind its ARC"

    @Test
    fun format_listsSupportedAndAbsentAndMode() {
        assertEquals(
            "Box opens: AC3 EAC3 TrueHD\nBox cannot open: DTS DTS-HD\nHDMI device lists: AC3 EAC3 PCM16\n" +
                "Android surround: MANUAL\nHDMI device max PCM: 8 ch\n$hdmiNote",
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
            "Box opens: none\nBox cannot open: AC3\nHDMI device lists: unknown\n" +
                "Android surround: NEVER\nHDMI device max PCM: unknown\n$hdmiNote",
            AudioCapabilityReport.format(emptyList(), listOf("AC3"), "unknown", "NEVER", "unknown")
        )
        assertEquals(
            "Box opens: AC3\nBox cannot open: none\nHDMI device lists: AC3\n" +
                "Android surround: AUTO\nHDMI device max PCM: 2 ch\n$hdmiNote",
            AudioCapabilityReport.format(listOf("AC3"), emptyList(), "AC3", "AUTO", "2 ch")
        )
    }

    @Test
    fun format_namesFormatsTheHdmiDeviceListsButTheBoxCannotOpen() {
        val text = AudioCapabilityReport.format(
            supported = listOf("AC3", "EAC3", "EAC3-JOC", "TrueHD"),
            absent = listOf("DTS", "DTS-HD"),
            negotiated = "PCM16 AC3 DTS",
            surroundMode = "AUTO",
            maxPcm = "6 ch"
        )
        val lines = text.split("\n")
        assertEquals(7, lines.size)
        assertEquals("Listed by HDMI, not opened by this box: DTS", lines[5])
        assertEquals(hdmiNote, lines[6])
    }

    @Test
    fun format_showsThePlugReportWhenAndroidHasOne() {
        val text = AudioCapabilityReport.format(
            supported = listOf("AC3", "EAC3"),
            absent = listOf("DTS"),
            negotiated = "PCM16 AC3 EAC3",
            surroundMode = "AUTO",
            maxPcm = "2 ch",
            plugReport = "PCM16 AC3 EAC3"
        )
        val lines = text.split("\n")
        assertEquals("HDMI plug report: PCM16 AC3 EAC3", lines[5])
        assertEquals(hdmiNote, lines.last())
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
        assertEquals(6, lines.size)
        assertTrue(lines[0].startsWith("Box opens: "))
        assertTrue(lines[1].startsWith("Box cannot open: "))
        assertTrue(lines[2].startsWith("HDMI device lists: "))
        assertTrue(lines[3].startsWith("Android surround: "))
        assertTrue(lines[4].startsWith("HDMI device max PCM: "))
        assertFalse(text.contains("not opened by this box"))
    }

    @Test
    fun latest_isNullUntilCaptured() {
        AudioCapabilityReport.reset()
        assertNull(AudioCapabilityReport.latest)
    }
}
