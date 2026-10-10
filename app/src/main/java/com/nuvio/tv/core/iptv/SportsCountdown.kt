package com.nuvio.tv.core.iptv

data class Countdown(val days: Int, val hours: Int, val minutes: Int)

object SportsCountdown {
    private const val MINUTE = 60_000L

    fun until(nowMillis: Long, startMillis: Long): Countdown? {
        val left = startMillis - nowMillis
        if (left <= 0) return null
        val total = (left + MINUTE - 1) / MINUTE
        return Countdown((total / 1440).toInt(), (total % 1440 / 60).toInt(), (total % 60).toInt())
    }
}
