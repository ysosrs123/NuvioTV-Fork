package com.nuvio.tv.core.image

import kotlinx.coroutines.delay

const val HOME_START_CALM_MIN_MS = 3_000L
const val HOME_START_CALM_MAX_MS = 8_000L

/** Still calm: the first [HOME_START_CALM_MIN_MS], and longer while posters on screen are loading, up to the cap. */
internal fun homeStartCalm(sinceStartMs: Long, visibleLoading: Boolean): Boolean =
    sinceStartMs in 0 until HOME_START_CALM_MIN_MS ||
        (visibleLoading && sinceStartMs in 0 until HOME_START_CALM_MAX_MS)

/**
 * The first seconds of Home after Arc opens. Posters on screen and add-on rows load as usual; background poster work
 * waits and row updates are gathered. Starts once per launch of Arc.
 */
object HomeStartCalm {
    @Volatile private var startedAtMs = 0L

    fun begin(nowMs: Long = now()) {
        if (startedAtMs == 0L) startedAtMs = nowMs
    }

    fun active(visibleLoading: Boolean = false, nowMs: Long = now()): Boolean =
        startedAtMs != 0L && homeStartCalm(nowMs - startedAtMs, visibleLoading)

    suspend fun awaitEnd(visibleLoading: () -> Boolean) {
        while (active(visibleLoading())) delay(100)
    }

    internal fun resetForTest() {
        startedAtMs = 0L
    }

    private fun now() = android.os.SystemClock.uptimeMillis()
}
