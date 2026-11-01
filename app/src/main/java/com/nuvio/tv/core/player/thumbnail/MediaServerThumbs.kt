package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.ui.screens.player.SlowStartServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Our own thumbnails for a media server's stream (Silo, Jellyfin, Emby) that has none of its own. A server away from
 * the home network is usually someone else's: its stream only gets asked for the picture a seek lands on, one request
 * at a time. The full set may come from a copy of the title instead ([copyFills]). No media server's stream is read
 * again once the player closes.
 */
internal object MediaServerThumbs {
    /** Thumbnail requests to a server away from the home network at once. */
    const val ON_DEMAND_FETCHES = 1

    /** No coverage pass on the stream itself: only the spot a seek asks for (and the pictures that check a copy). */
    fun onDemandOnly(mediaServer: Boolean, url: String): Boolean = mediaServer && !onHomeNetwork(url)

    /**
     * A server away from home whose title has a copy that fits ([fits]) with copies on in settings: the copy makes the
     * full set and the generating screen may come up. Without one only the spot a seek asks for, as before.
     */
    fun copyFills(onDemand: Boolean, copiesOn: Boolean, identityExact: Boolean, fits: Boolean): Boolean =
        onDemand && copiesOn && identityExact && fits

    /**
     * A copy that fits turns up while the server's batched pass is still running: the copy takes over, the server
     * then gives only the spot a seek asks for and the picture check. Once the batched pass is done it stays as it is.
     */
    fun copyTakesOver(fits: Boolean, remoteBatched: Boolean, onDemand: Boolean, passFinished: Boolean): Boolean =
        fits && remoteBatched && !onDemand && !passFinished

    /** A private, link-local or Tailscale address, a home-network name, or the box itself. */
    fun onHomeNetwork(url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        return SlowStartServer.isLocalHost(host) || isLoopback(host)
    }

    /** An unfinished 4K coverage pass may carry on after the player closes, never on a media server's stream. */
    fun continuesAfterClose(mediaServer: Boolean): Boolean = !mediaServer

    /**
     * Requests at once for a copy from another version on a media server: as for any link on the home network, one
     * for a server away from it (its upload is the owner's own line), none without an address.
     */
    fun copyLanes(serverAddress: String?): Int = when {
        serverAddress == null -> 0
        onHomeNetwork(serverAddress) -> Int.MAX_VALUE
        else -> ON_DEMAND_FETCHES
    }

    fun fetchLimit(onDemand: Boolean, limit: Int): Int = if (onDemand) minOf(limit, ON_DEMAND_FETCHES) else limit

    /** A remote server's thumbnails in batches stop here: about the keyframes of a 4K remux's full set. */
    const val REMOTE_DATA_CAP_BYTES = 150L * 1024 * 1024

    private fun isLoopback(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}
