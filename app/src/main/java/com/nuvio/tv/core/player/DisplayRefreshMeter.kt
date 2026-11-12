package com.nuvio.tv.core.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import com.nuvio.tv.core.report.PlaybackRecorder
import com.nuvio.tv.core.report.RecorderEvent
import com.nuvio.tv.core.report.ReportFactsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Times the screen's own frame ticks after a frame rate switch. Android reports the rate of the mode it selected;
 * a TV link that changes rate without a real mode change (QMS, VRR) can run at another one, and only the ticks show it.
 */
object DisplayRefreshMeter {
    data class Reading(
        val measuredHz: Float,
        val androidHz: Float,
        val seamless: Boolean?,
        val ticks: Int,
        val reliable: Boolean = true
    )

    private const val TAG = "DisplayRefresh"
    private const val MEASURE_NS = 4_000_000_000L
    private const val MIN_TICKS = 40
    /** 24.000 against 23.976 is 0.1 %; a four second reading is good to a few thousandths of a percent. */
    private const val DIFFERS_FRACTION = 0.0004f

    @Volatile var latest: Reading? = null
        private set
    /** Every finished reading, for the player to act on. */
    val readings = kotlinx.coroutines.flow.MutableSharedFlow<Reading>(extraBufferCapacity = 4)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var run = 0
    /** Fire OS keeps timing apps at the old rate after a real mode change, so its count says nothing about the screen. */
    @Volatile private var ticksIgnoreModeChanges = false

    /** [seamless]: Android listed the new rate as one it reaches without a mode change; null when nothing was switched. */
    fun measure(androidHz: Float, seamless: Boolean?, fromHz: Float? = null) {
        main.post {
            val mine = ++run
            val ticks = ArrayList<Long>(512)
            val choreographer = runCatching { Choreographer.getInstance() }.getOrNull() ?: return@post
            choreographer.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (mine != run) return
                    ticks += frameTimeNanos
                    if (frameTimeNanos - ticks[0] < MEASURE_NS) {
                        choreographer.postFrameCallback(this)
                        return
                    }
                    val hz = hzFrom(ticks) ?: return
                    if (ticksStayedAt(fromHz, hz, androidHz, seamless)) ticksIgnoreModeChanges = true
                    val reading = Reading(hz, androidHz, seamless, ticks.size, reliable(seamless, ticksIgnoreModeChanges))
                    latest = reading
                    readings.tryEmit(reading)
                    Log.i(TAG, "DISPLAY_REFRESH ${describe(reading)} ticks=${ticks.size}")
                    PlaybackRecorder.event(
                        RecorderEvent.DISPLAY_MODE,
                        "measured hz=${format(hz)} androidHz=${format(androidHz)} seamless=${seamless ?: "n/a"} differs=${differs(reading)}"
                    )
                }
            })
        }
    }

    fun clear() {
        main.post { run++ }
        latest = null
    }

    /**
     * Ticks per second from tick times. A missed callback shows as an interval of two or more periods and is counted
     * as that many ticks, so a busy main thread does not bend the result.
     */
    internal fun hzFrom(tickNanos: List<Long>): Float? {
        if (tickNanos.size < MIN_TICKS) return null
        val intervals = (1 until tickNanos.size).map { tickNanos[it] - tickNanos[it - 1] }.filter { it > 0 }
        if (intervals.size < MIN_TICKS - 1) return null
        val period = intervals.sorted()[intervals.size / 2]
        val periods = intervals.sumOf { (it.toDouble() / period).roundToInt().coerceAtLeast(1) }
        val span = tickNanos.last() - tickNanos.first()
        if (span <= 0) return null
        return (periods * 1_000_000_000.0 / span).toFloat()
    }

    fun differs(reading: Reading): Boolean = reading.reliable && off(reading.measuredHz, reading.androidHz)

    private fun off(measuredHz: Float, androidHz: Float): Boolean =
        androidHz > 0f && abs(measuredHz - androidHz) / androidHz > DIFFERS_FRACTION

    /** A mode change that left the count at the rate it came from, not at the one Android switched to. */
    internal fun ticksStayedAt(fromHz: Float?, measuredHz: Float, androidHz: Float, seamless: Boolean?): Boolean =
        seamless == false && fromHz != null && off(measuredHz, androidHz) && !off(measuredHz, fromHz)

    /** Without a mode change (QMS or VRR) the count is the only evidence there is, so it always stands. */
    internal fun reliable(seamless: Boolean?, ticksIgnoreModeChanges: Boolean): Boolean =
        seamless == true || !ticksIgnoreModeChanges

    fun describe(reading: Reading): String = buildString {
        append("measured ${format(reading.measuredHz)} Hz, Android says ${format(reading.androidHz)} Hz")
        when (reading.seamless) {
            true -> append(", switched without a mode change (QMS or VRR)")
            false -> append(", switched with a mode change")
            null -> {}
        }
        if (!reading.reliable) append(", count does not follow mode changes on this box")
    }

    internal fun format(hz: Float): String = String.format(Locale.US, "%.3f", hz)
}

@Singleton
class DisplayRefreshFacts @Inject constructor() : ReportFactsProvider {
    override suspend fun reportFacts(): Map<String, String> =
        DisplayRefreshMeter.latest?.let { mapOf("Screen refresh" to DisplayRefreshMeter.describe(it)) } ?: emptyMap()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DisplayRefreshFactsModule {
    @Binds
    @IntoSet
    abstract fun displayRefresh(facts: DisplayRefreshFacts): ReportFactsProvider
}
