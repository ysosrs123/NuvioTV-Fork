package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentRetryTargetTest {
    private val live = TorrentRetryTarget("aaaa", 2)
    private val saved = TorrentRetryTarget("bbbb", null)

    @Test
    fun `uses the running torrent first`() {
        assertEquals(live, TorrentRetryTarget.choose(streamIsTorrent = true, live = live, saved = saved))
    }

    @Test
    fun `uses the saved torrent once the engine stream was stopped`() {
        assertEquals(saved, TorrentRetryTarget.choose(streamIsTorrent = true, live = null, saved = saved))
    }

    @Test
    fun `an ordinary link never restarts an old torrent`() {
        assertNull(TorrentRetryTarget.choose(streamIsTorrent = false, live = null, saved = saved))
    }

    @Test
    fun `nothing to restart without a hash`() {
        assertNull(TorrentRetryTarget.choose(streamIsTorrent = true, live = null, saved = null))
    }
}
