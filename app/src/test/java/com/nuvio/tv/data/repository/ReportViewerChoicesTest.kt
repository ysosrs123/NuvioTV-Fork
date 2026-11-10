package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportViewerChoicesTest {
    @Test fun `release style file names survive cleaning`() {
        listOf(
            "The.Long.Night.S01E04.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.7.1.Atmos.x265-GROUP.mkv",
            "Movie_Name_2024_2160p_UHD_BluRay_REMUX_HEVC_DV_TrueHD_Atmos_7_1-GRP.mkv",
            "the.long.night.s01e04.1080p.web.h264-grp.mp4",
            "Some Film (2019) [1080p] [BluRay] [5.1] [YTS.MX].avi"
        ).forEach { assertEquals(it, ReportText.fileName(it, 300)) }
    }

    @Test fun `a file name is cut at the limit and a link inside it is taken out`() {
        assertEquals(300, ReportText.fileName("a".repeat(500) + ".mkv", 300)!!.length)
        val cleaned = ReportText.fileName("see https://eu1.example.com/x?token=SECRET123456 file.mkv", 300)!!
        assertFalse(cleaned, cleaned.contains("SECRET123456"))
        assertNull(ReportText.fileName("   ", 300))
        assertNull(ReportText.fileName(null, 300))
    }

    @Test fun `the link supplies a file name only when it ends in a video file`() {
        assertEquals(
            "The Long Night S01E04.mkv",
            ReportText.fileNameFromLink("https://cdn.example.com/dl/abc/The%20Long%20Night%20S01E04.mkv?token=x#frag")
        )
        assertEquals("a+b.mp4", ReportText.fileNameFromLink("https://h.example.com/a+b.mp4"))
        assertNull(ReportText.fileNameFromLink("https://h.example.com/stream/12345"))
        assertNull(ReportText.fileNameFromLink("https://h.example.com/playlist.m3u8"))
        assertNull(ReportText.fileNameFromLink("https://h.example.com/.mkv"))
        assertNull(ReportText.fileNameFromLink(null))
    }

    @Test fun `the limit failure is told apart from other failures`() {
        assertTrue(com.nuvio.tv.ui.screens.player.isReportLimitError("Playback report upload failed: HTTP 429"))
        assertFalse(com.nuvio.tv.ui.screens.player.isReportLimitError("Playback report upload failed: HTTP 500"))
        assertFalse(com.nuvio.tv.ui.screens.player.isReportLimitError(null))
    }
}
