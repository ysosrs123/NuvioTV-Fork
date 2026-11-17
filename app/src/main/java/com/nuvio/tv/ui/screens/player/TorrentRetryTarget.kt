package com.nuvio.tv.ui.screens.player

/** The torrent that Retry starts again, kept after the engine stream has been stopped. */
internal data class TorrentRetryTarget(val infoHash: String, val fileIdx: Int?) {
    companion object {
        fun choose(
            streamIsTorrent: Boolean,
            live: TorrentRetryTarget?,
            saved: TorrentRetryTarget?
        ): TorrentRetryTarget? = if (streamIsTorrent) live ?: saved else null
    }
}
