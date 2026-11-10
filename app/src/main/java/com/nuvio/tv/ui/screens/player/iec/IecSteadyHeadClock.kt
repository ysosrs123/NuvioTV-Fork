package com.nuvio.tv.ui.screens.player.iec

import kotlin.math.abs

/**
 * Watches the position the player is given and, when it keeps jumping against the wall clock, takes over: the head
 * position averaged over a few seconds, so a driver that reports it in big steps still gives the picture a steady clock.
 */
internal class IecSteadyHeadClock {
    /** `adb shell setprop debug.nuvio.iec.steady 1` starts it engaged, to check sync on a box whose clock never needs it. */
    var engaged = forcedOn()
        private set

    private var settleFromUs = UNSET
    private var windowStartUs = UNSET
    private var windowStartPositionUs = UNSET
    private val rough = ArrayDeque<Boolean>()

    private val offsets = LongArray(OFFSET_SAMPLES)
    private var offsetCount = 0
    private var offsetNext = 0
    private var lastOffsetAtUs = UNSET
    private var lastHeadUs = UNSET

    fun observe(nowUs: Long, positionUs: Long, playing: Boolean) {
        if (engaged) return
        if (!playing) {
            resetWatch()
            return
        }
        if (settleFromUs == UNSET) settleFromUs = nowUs + SETTLE_US
        if (nowUs < settleFromUs) return
        if (windowStartUs == UNSET) {
            windowStartUs = nowUs
            windowStartPositionUs = positionUs
            return
        }
        val wallUs = nowUs - windowStartUs
        if (wallUs < WINDOW_US) return
        val driftUs = abs((positionUs - windowStartPositionUs) - wallUs)
        windowStartUs = nowUs
        windowStartPositionUs = positionUs
        // A gap this big is a stall or a seek, not a jumpy clock.
        if (driftUs > IMPLAUSIBLE_DRIFT_US || wallUs > 2 * WINDOW_US) return
        rough.addLast(driftUs >= ROUGH_DRIFT_US)
        if (rough.size > WINDOWS) rough.removeFirst()
        if (rough.count { it } >= ROUGH_WINDOWS_TO_ENGAGE) engaged = true
    }

    fun positionUs(nowUs: Long, headUs: Long, playing: Boolean): Long {
        if (!playing) {
            clearOffsets()
            return headUs
        }
        val last = lastHeadUs
        if (last != UNSET && abs(headUs - last) > SNAP_US) clearOffsets()
        lastHeadUs = headUs
        if (lastOffsetAtUs == UNSET || nowUs - lastOffsetAtUs >= OFFSET_INTERVAL_US) {
            offsets[offsetNext] = headUs - nowUs
            offsetNext = (offsetNext + 1) % OFFSET_SAMPLES
            if (offsetCount < OFFSET_SAMPLES) offsetCount++
            lastOffsetAtUs = nowUs
        }
        var sum = 0L
        for (i in 0 until offsetCount) sum += offsets[i]
        return (nowUs + sum / offsetCount).coerceAtLeast(0L)
    }

    /** A pause or a seek: the watch and the average start over, the decision stands. */
    fun reset() {
        resetWatch()
        clearOffsets()
    }

    fun release() {
        reset()
        rough.clear()
        engaged = forcedOn()
    }

    private fun resetWatch() {
        settleFromUs = UNSET
        windowStartUs = UNSET
        windowStartPositionUs = UNSET
    }

    private fun clearOffsets() {
        offsetCount = 0
        offsetNext = 0
        lastOffsetAtUs = UNSET
        lastHeadUs = UNSET
    }

    companion object {
        private const val FORCE_PROPERTY = "debug.nuvio.iec.steady"

        private fun forcedOn(): Boolean = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, FORCE_PROPERTY) == "1"
        }.getOrDefault(false)

        private const val UNSET = Long.MIN_VALUE
        const val SETTLE_US = 5_000_000L
        const val WINDOW_US = 1_000_000L
        const val WINDOWS = 8
        const val ROUGH_WINDOWS_TO_ENGAGE = 4
        const val ROUGH_DRIFT_US = 100_000L
        const val IMPLAUSIBLE_DRIFT_US = 2_000_000L
        const val SNAP_US = 2_000_000L
        const val OFFSET_INTERVAL_US = 100_000L
        const val OFFSET_SAMPLES = 40
    }
}
