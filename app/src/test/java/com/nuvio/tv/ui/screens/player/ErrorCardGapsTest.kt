package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCardGapsTest {
    private val watchdogCard =
        PlayerErrorCard(PlayerErrorCause.ServerSilent, "Silent", "no data message", null, fromWatchdog = true)
    private val shown = PlayerUiState(error = watchdogCard.message, errorCard = watchdogCard)

    @Test
    fun `a late player error keeps the watchdog card for the same link`() {
        assertTrue(shown.showsStartupWatchdogCard("https://a.example/film.mkv", "https://a.example/film.mkv"))
    }

    @Test
    fun `a player error on another link or after the card was cleared is handled as usual`() {
        assertFalse(shown.showsStartupWatchdogCard("https://a.example/film.mkv", "https://b.example/film.mkv"))
        assertFalse(shown.showsStartupWatchdogCard(null, "https://a.example/film.mkv"))
        assertFalse(PlayerUiState().showsStartupWatchdogCard("https://a.example/film.mkv", "https://a.example/film.mkv"))
    }

    @Test
    fun `an unresponsive decoder with little buffered is checked as a slow or silent source`() {
        assertTrue(watchdogChecksLoad("decoder_unresponsive", 0L))
        assertTrue(watchdogChecksLoad("decoder_unresponsive", StartupWatchdogDecision.WELL_AHEAD_MS - 1))
        assertFalse(watchdogChecksLoad("decoder_unresponsive", StartupWatchdogDecision.WELL_AHEAD_MS))
    }

    @Test
    fun `the load reasons are still checked and decoder reasons are not`() {
        listOf("tracks_not_read", "no_video_track", "no_data_buffered").forEach { assertTrue(it, watchdogChecksLoad(it, 60_000L)) }
        listOf("video_track_unsupported", "video_never_decoded", "video_track_unreadable").forEach {
            assertFalse(it, watchdogChecksLoad(it, 0L))
        }
    }

    @Test
    fun `an MPV start card uses only a fresh HTTP status`() {
        assertEquals(404, mpvStartCardEvidence(404, httpFresh = true, logLine = null, source = "end_file").first)
        assertNull(mpvStartCardEvidence(404, httpFresh = false, logLine = null, source = "end_file").first)
    }

    @Test
    fun `a stalled MPV start ignores the old log line`() {
        val line = "tcp: Connection reset by peer"
        assertNull(mpvStartCardEvidence(null, httpFresh = false, logLine = line, source = "startup_stall").second)
        assertNull(mpvStartCardEvidence(null, httpFresh = false, logLine = line, source = "startup_absolute").second)
        assertEquals(line, mpvStartCardEvidence(null, httpFresh = false, logLine = line, source = "end_file").second)
    }
}
