package com.nuvio.tv.ui.screens.player

import android.util.Log
import java.io.File

/**
 * CPU clock / thermal-throttle indicator for the playback stats HUD, for boxes
 * that expose no app-readable SoC temperature (cpufreq under
 * /sys/devices/system/cpu/ is readable under standard AOSP policy).
 *
 * The SoC throttles by capping clock frequency: the thermal governor lowers
 * scaling_max_freq below cpuinfo_max_freq. Equal = not throttling (green),
 * lower = throttled (red). scaling_cur_freq is load-driven and shown only as
 * context.
 *
 * cpuinfo_max_freq is read once at init. If it or scaling_max_freq is
 * unreadable the row is omitted. Runs on the HUD sampling path only.
 */
internal class PlaybackCpuClockSampler {

    data class Reading(
        val currentKHz: Long,
        val maxKHz: Long,
        val capKHz: Long
    ) {
        /** True when the governor's ceiling has been pulled below the hardware maximum. */
        val isThrottled: Boolean get() = capKHz in 1 until maxKHz
    }

    @Volatile
    private var maxKHzCached: Long = -1L

    @Volatile
    private var maxProbeFailed: Boolean = false

    fun sample(): Reading? {
        val maxKHz = readMaxKHz()
        if (maxKHz <= 0L) return null
        val capKHz = readLong(CAP_PATH) ?: return null
        // current is best-effort context; if it fails, fall back to the cap so the row still renders.
        val currentKHz = readLong(CUR_PATH) ?: capKHz
        return Reading(currentKHz = currentKHz, maxKHz = maxKHz, capKHz = capKHz)
    }

    private fun readMaxKHz(): Long {
        if (maxProbeFailed) return -1L
        val cached = maxKHzCached
        if (cached > 0L) return cached
        val v = readLong(MAX_PATH)
        if (v == null || v <= 0L) {
            maxProbeFailed = true
            Log.i(TAG, "cpuinfo_max_freq unreadable, CPU clock row disabled")
            return -1L
        }
        maxKHzCached = v
        return v
    }

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    private companion object {
        const val TAG = "CpuClockSampler"
        const val CUR_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
        const val MAX_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"
        const val CAP_PATH = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
    }
}
