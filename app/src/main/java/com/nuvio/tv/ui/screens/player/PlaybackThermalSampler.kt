package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.nuvio.tv.core.performance.ThermalHeadroom
import java.io.File

/**
 * SoC temperature for the playback stats HUD.
 *
 * Tiers, tried in order:
 *   1. sysfs thermal zone in degrees C (first zone whose type contains "soc"
 *      or "cpu"). Many Amlogic boxes deny the read by SELinux, so it is tried
 *      once and the verdict cached.
 *   2. PowerManager.getThermalHeadroom(0): a fraction of the way to severe
 *      throttling (1.0 = SEVERE). NaN reads as unavailable for that tick.
 *
 * Both dead: sample() returns null and the row is hidden. Headroom is shown
 * raw, never as degrees, because its low anchor is implementation-defined.
 * Runs on the HUD sampling path only.
 */
internal class PlaybackThermalSampler(context: Context) {

    data class Reading(
        /** Exact SoC temperature, only when sysfs is app-readable on this device. */
        val celsius: Float?,
        /** Normalised distance to severe throttling (1.0 = severe), when the HAL serves it. */
        val headroom: Float?
    )

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    // Tier-1 verdict, decided once. null = not yet probed; File = readable zone temp
    // node; NO_SYSFS = probed and denied/absent.
    @Volatile
    private var sysfsTempNode: File? = null

    @Volatile
    private var sysfsProbed: Boolean = false

    fun sample(): Reading? {
        val celsius = readSysfsCelsius()
        val headroom = readHeadroom()
        if (celsius == null && headroom == null) return null
        return Reading(celsius = celsius, headroom = headroom)
    }

    private fun readSysfsCelsius(): Float? {
        if (!sysfsProbed) probeSysfsOnce()
        val node = sysfsTempNode ?: return null
        return runCatching {
            node.readText().trim().toLong().let { milli ->
                if (milli > 1000L) milli / 1000f else milli.toFloat()
            }
        }.getOrElse {
            // Went unreadable after a successful probe (shouldn't happen): demote once.
            Log.w(TAG, "sysfs temp read failed after successful probe: ${it.message}")
            sysfsTempNode = null
            null
        }
    }

    private fun probeSysfsOnce() {
        sysfsProbed = true
        val result = runCatching {
            for (i in 0 until MAX_ZONES) {
                val zone = File("/sys/class/thermal/thermal_zone$i")
                if (!zone.exists()) break
                val type = runCatching { File(zone, "type").readText().trim().lowercase() }
                    .getOrNull() ?: continue
                if ("soc" in type || "cpu" in type) {
                    val temp = File(zone, "temp")
                    // Prove readability now so per-tick reads cannot surprise.
                    temp.readText().trim().toLong()
                    sysfsTempNode = temp
                    Log.i(TAG, "sysfs thermal readable: zone$i type=$type")
                    return@runCatching
                }
            }
            Log.i(TAG, "sysfs thermal: no readable soc/cpu zone (expected under vendor SELinux)")
        }
        result.onFailure {
            Log.i(TAG, "sysfs thermal unreadable (${it.javaClass.simpleName}), using headroom tier")
        }
    }

    private fun readHeadroom(): Float? =
        ThermalHeadroom.read(powerManager, 0).takeIf { !it.isNaN() && it >= 0f }

    private companion object {
        const val TAG = "ThermalSampler"
        const val MAX_ZONES = 16
    }
}
