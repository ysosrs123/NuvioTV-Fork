package com.nuvio.tv.core.iptv

enum class GoLiveRoute { NONE, CUSHION, LOCAL, RETUNE }

object LiveGoLive {
    const val BEHIND_MIN_MILLIS = 5_000L

    fun route(catchup: Boolean, catchupFromLive: Boolean, catchupStop: Long?, now: Long, localTimeshift: Boolean, localBehind: Boolean,
        paused: Boolean, behindLiveMillis: Long?, cushionMillis: Long): GoLiveRoute = when {
        catchup -> if (catchupFromLive || catchupStop == null || catchupStop > now) GoLiveRoute.RETUNE else GoLiveRoute.NONE
        localTimeshift -> if (localBehind || paused) GoLiveRoute.LOCAL else GoLiveRoute.NONE
        paused -> GoLiveRoute.RETUNE
        cushionMillis > 0 && (behindLiveMillis ?: 0L) >= BEHIND_MIN_MILLIS -> GoLiveRoute.CUSHION
        else -> GoLiveRoute.NONE
    }
}
