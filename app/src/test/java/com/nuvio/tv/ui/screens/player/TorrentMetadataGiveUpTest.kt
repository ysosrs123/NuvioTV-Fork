package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMetadataGiveUpTest {

    @Test
    fun `waits the first minute even with nothing arriving`() {
        assertFalse(TorrentMetadataGiveUp.shouldGiveUp(59_999L, msSinceLastDownload = null))
    }

    @Test
    fun `gives up after a minute of 0 B per second`() {
        assertTrue(TorrentMetadataGiveUp.shouldGiveUp(60_000L, msSinceLastDownload = null))
        assertTrue(TorrentMetadataGiveUp.shouldGiveUp(578_000L, msSinceLastDownload = null))
    }

    @Test
    fun `keeps waiting while peers are sending`() {
        assertFalse(TorrentMetadataGiveUp.shouldGiveUp(90_000L, msSinceLastDownload = 2_000L))
        assertFalse(TorrentMetadataGiveUp.shouldGiveUp(179_999L, msSinceLastDownload = 0L))
    }

    @Test
    fun `gives up once downloading has gone quiet`() {
        assertTrue(TorrentMetadataGiveUp.shouldGiveUp(90_000L, msSinceLastDownload = TorrentMetadataGiveUp.QUIET_WINDOW_MS))
    }

    @Test
    fun `stops at the ceiling even while downloading`() {
        assertTrue(TorrentMetadataGiveUp.shouldGiveUp(TorrentMetadataGiveUp.ACTIVE_LIMIT_MS, msSinceLastDownload = 0L))
    }
}
