package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportTitleDetailTest {
    @Test fun `the provider is shown when there is one`() {
        assertEquals("TorBox", reportSourceNote("TorBox", "1080p\nWEB-DL"))
    }

    @Test fun `without a provider the first line of the source detail is shown`() {
        assertEquals("1080p WEB-DL", reportSourceNote(null, "1080p WEB-DL\nsecond line"))
        assertEquals("1080p WEB-DL", reportSourceNote("  ", "  1080p WEB-DL "))
    }

    @Test fun `nothing is shown without a provider or detail`() {
        assertNull(reportSourceNote(null, null))
        assertNull(reportSourceNote("", "\nonly later"))
    }

    @Test fun `the source joins the file name line and the title keeps its own line`() {
        assertEquals("Show S1 E2\nfile.mkv · TorBox", reportTitleDetail("Show S1 E2", "file.mkv", "TorBox"))
        assertEquals("Show\nfile.mkv", reportTitleDetail("Show", "file.mkv", null))
        assertEquals("Show\nTorBox", reportTitleDetail("Show", null, "TorBox"))
        assertEquals("Show", reportTitleDetail("Show", null, null))
    }
}
