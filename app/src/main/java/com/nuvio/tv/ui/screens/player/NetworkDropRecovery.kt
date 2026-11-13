package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

internal enum class NetworkDropAction {
    /** Not a network failure, or a source this does not handle: the usual error path runs. */
    NONE,
    /** The usual error path runs, and a drop that starts and ends soon after brings playback back. */
    WATCH,
    /** The network is down right now: hold the retries until it is back. */
    WAIT,
    /** The network dropped and is back: play on from the same spot. */
    RECOVER
}

@androidx.annotation.OptIn(UnstableApi::class)
internal object NetworkDropRecovery {
    const val WINDOW_MS = 10_000L
    const val RETURN_SETTLE_MS = 500L

    fun decide(
        networkError: Boolean,
        msSinceNetworkLost: Long?,
        validatedNow: Boolean,
        alreadyRecoveredForDrop: Boolean,
        torrent: Boolean,
        live: Boolean,
        loopback: Boolean
    ): NetworkDropAction {
        if (!networkError || torrent || live || loopback) return NetworkDropAction.NONE
        if (alreadyRecoveredForDrop) return NetworkDropAction.WATCH
        if (msSinceNetworkLost == null || msSinceNetworkLost !in 0L..WINDOW_MS) return NetworkDropAction.WATCH
        return if (validatedNow) NetworkDropAction.RECOVER else NetworkDropAction.WAIT
    }

    /** A lost connection: no HTTP status anywhere in the chain, only socket, DNS, time-out or read failures. */
    fun isNetworkDropError(errorCode: Int, cause: Throwable?): Boolean {
        if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false
        val chain = generateSequence(cause) { it.cause }.take(12).toList()
        if (chain.any { it is HttpDataSource.InvalidResponseCodeException }) return false
        if (chain.any {
                it is HttpDataSource.HttpDataSourceException &&
                    it.reason == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            }
        ) return false
        return errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            PlaybackNetworkFailure.hasNetworkCause(cause)
    }
}
