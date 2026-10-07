package com.nuvio.tv.core.iptv

object LiveTimeshift {
    const val RESUME_IN_PLACE_MILLIS = 20_000L
    const val REWIND_MILLIS = 60_000L
    private const val MINUTE_MILLIS = 60_000L

    fun resumeFrom(pausedAtMillis: Long, nowMillis: Long, programme: GuideProgramme?, archive: Boolean): Long? {
        if (!archive || programme == null || nowMillis - pausedAtMillis < RESUME_IN_PLACE_MILLIS) return null
        return startWithin(pausedAtMillis, programme, nowMillis)
    }

    fun rewindFrom(nowMillis: Long, programme: GuideProgramme?, archive: Boolean): Long? {
        if (!archive || programme == null) return null
        return startWithin(nowMillis - REWIND_MILLIS, programme, nowMillis)
    }

    fun position(programme: GuideProgramme, fromMillis: Long?, playerPositionMillis: Long): Long =
        (fromMillis ?: programme.start.epochMillis) + playerPositionMillis.coerceAtLeast(0)

    private fun startWithin(targetMillis: Long, programme: GuideProgramme, nowMillis: Long): Long? {
        val start = programme.start
        if (!start.precise || start.epochMillis >= nowMillis) return null
        val floored = Math.floorDiv(targetMillis, MINUTE_MILLIS) * MINUTE_MILLIS
        val from = floored.coerceAtLeast(start.epochMillis)
        val stop = programme.stop?.epochMillis
        return from.takeIf { it < nowMillis && (stop == null || it < stop) }
    }
}
