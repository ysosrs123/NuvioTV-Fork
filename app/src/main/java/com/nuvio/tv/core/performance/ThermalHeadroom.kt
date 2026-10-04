package com.nuvio.tv.core.performance

import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * PowerManager.getThermalHeadroom() with a process-wide latch for boxes whose thermal HAL cannot serve it.
 *
 * On such boxes (Xiaomi MiTV-AFMU0, Ugoos AM9) every call makes system_server's TemperatureWatcher retry against
 * the HAL and log a stack trace per attempt, and the retries pile up for as long as the calls continue (gigabytes
 * of log and a busy system_server after an hour of playback). After [FAILURES_BEFORE_DEAD] consecutive NaN answers
 * no caller in this process asks again. Occasional NaN is normal (the API is rate-limited to about 1 Hz per app and
 * Fire OS answers every other read with NaN), so any real answer resets the count.
 */
object ThermalHeadroom {
    private const val TAG = "ThermalHeadroom"
    const val FAILURES_BEFORE_DEAD = 10

    @Volatile
    var halDead: Boolean = false
        private set

    @Volatile
    private var consecutiveFailures: Int = 0

    fun read(pm: PowerManager?, forecastSeconds: Int): Float {
        if (pm == null || halDead || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val headroom = runCatching { pm.getThermalHeadroom(forecastSeconds) }.getOrDefault(Float.NaN)
        record(headroom)
        return headroom
    }

    internal fun record(headroom: Float) {
        if (!headroom.isNaN() && headroom >= 0f) {
            consecutiveFailures = 0
            return
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= FAILURES_BEFORE_DEAD && !halDead) {
            halDead = true
            Log.i(TAG, "thermal headroom unusable ($consecutiveFailures consecutive failures): not asking again in this process")
        }
    }

    internal fun resetForTest() {
        halDead = false
        consecutiveFailures = 0
    }
}
