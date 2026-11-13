package com.nuvio.tv.ui.screens.player

/**
 * How long a torrent may sit on "Fetching torrent metadata" before Arc moves on. A swarm that sends
 * nothing for a minute rarely recovers; one that is downloading gets a longer ceiling.
 */
internal object TorrentMetadataGiveUp {
    const val IDLE_LIMIT_MS = 60_000L
    const val ACTIVE_LIMIT_MS = 180_000L
    const val QUIET_WINDOW_MS = 15_000L
    const val CHECK_INTERVAL_MS = 2_000L

    /**
     * @param waitedMs time spent in the metadata step so far.
     * @param msSinceLastDownload time since a download speed above 0 was last seen, null if never.
     */
    fun shouldGiveUp(waitedMs: Long, msSinceLastDownload: Long?): Boolean {
        if (waitedMs >= ACTIVE_LIMIT_MS) return true
        if (waitedMs < IDLE_LIMIT_MS) return false
        return msSinceLastDownload == null || msSinceLastDownload >= QUIET_WINDOW_MS
    }
}
