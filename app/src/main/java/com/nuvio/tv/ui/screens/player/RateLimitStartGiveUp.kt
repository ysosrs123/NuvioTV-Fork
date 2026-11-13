package com.nuvio.tv.ui.screens.player

/**
 * A host that has answered 429 to every request since the stream began, after the data source has
 * already spent its own paced retries, will not change its mind on a second player-level round.
 * Such a start skips the player retry and goes on to the next source or the limit card.
 */
internal object RateLimitStartGiveUp {
    fun skipsPlayerRetry(httpStatus: Int?, firstFrameRendered: Boolean, mediaBytesRead: Long?): Boolean =
        httpStatus == 429 && !firstFrameRendered && mediaBytesRead == 0L
}
