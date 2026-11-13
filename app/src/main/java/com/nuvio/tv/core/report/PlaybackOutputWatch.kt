package com.nuvio.tv.core.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.audio.AudioSink

/**
 * What the TV and the HDMI link did while a film played: picture mode changes from any cause, and HDMI audio plug
 * reports. Listens only while the recorder does; one line per real change.
 */
internal object PlaybackOutputWatch {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var context: Context? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var hdmiReceiver: BroadcastReceiver? = null
    private val displays = ChangeFilter()
    private val hdmi = ChangeFilter()

    fun start(appContext: Context) = main.post {
        if (context != null) return@post
        context = appContext
        displays.clear()
        hdmi.clear()
        runCatching { watchDisplay(appContext) }
        runCatching { watchHdmi(appContext) }
    }

    fun stop() = main.post {
        val app = context ?: return@post
        context = null
        displayListener?.let { listener ->
            runCatching { app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        hdmiReceiver?.let { receiver -> runCatching { app.unregisterReceiver(receiver) } }
        hdmiReceiver = null
    }

    private fun watchDisplay(app: Context) {
        val manager = app.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                noteDisplay(manager.getDisplay(displayId) ?: return, initial = false)
            }
        }
        manager.registerDisplayListener(listener, main)
        displayListener = listener
        manager.getDisplay(Display.DEFAULT_DISPLAY)?.let { noteDisplay(it, initial = true) }
    }

    @Suppress("DEPRECATION")
    private fun noteDisplay(display: Display, initial: Boolean) {
        val mode = display.mode
        val hdr = runCatching { display.hdrCapabilities?.supportedHdrTypes }.getOrNull()
        val line = PlaybackOutputLines.display(mode.modeId, mode.refreshRate, mode.physicalWidth, mode.physicalHeight, hdr)
        if (displays.changed(line)) PlaybackRecorder.event(RecorderEvent.DISPLAY_CHANGE, if (initial) "$line initial" else line)
    }

    private fun watchHdmi(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val line = PlaybackOutputLines.hdmi(
                    intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1),
                    intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS),
                    intent.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, -1)
                )
                if (hdmi.changed(line)) PlaybackRecorder.event(RecorderEvent.HDMI_PLUG, if (isInitialStickyBroadcast) "$line initial" else line)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG), null, main, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        hdmiReceiver = receiver
    }
}

/** Remembers the last line and says whether a new one differs. */
internal class ChangeFilter {
    private var last: String? = null

    fun changed(line: String): Boolean {
        if (line == last) return false
        last = line
        return true
    }

    fun clear() {
        last = null
    }
}

internal object PlaybackOutputLines {
    private val HDR_NAMES = mapOf(1 to "DV", 2 to "HDR10", 3 to "HLG", 4 to "HDR10+")

    fun display(modeId: Int, refreshHz: Float, width: Int, height: Int, hdrTypes: IntArray?): String {
        val hz = String.format(java.util.Locale.ROOT, "%.3f", refreshHz)
        val hdr = hdrTypes?.distinct()?.sorted()?.joinToString("+") { HDR_NAMES[it] ?: "type$it" }?.ifEmpty { "none" } ?: "unknown"
        return "mode=$modeId hz=$hz size=${width}x$height hdr=$hdr"
    }

    fun hdmi(state: Int, encodings: IntArray?, maxChannels: Int): String {
        val plugged = when (state) {
            1 -> "plugged"
            0 -> "unplugged"
            else -> "unknown"
        }
        val list = encodings?.distinct()?.sorted()?.joinToString(",")
        return "state=$plugged" + (list?.let { " encodings=${it.ifEmpty { "none" }}" } ?: "") +
            if (maxChannels > 0) " maxChannels=$maxChannels" else ""
    }

    /** What failed in the sound output, by kind and code only; the platform text can carry a link. */
    fun sinkError(error: Exception): String = when (error) {
        is AudioSink.InitializationException ->
            "sink_error kind=init state=${error.audioTrackState} recoverable=${error.isRecoverable}"
        is AudioSink.WriteException -> "sink_error kind=write code=${error.errorCode} recoverable=${error.isRecoverable}"
        is AudioSink.UnexpectedDiscontinuityException ->
            "sink_error kind=discontinuity offMs=${(error.actualPresentationTimeUs - error.expectedPresentationTimeUs) / 1_000L}"
        else -> "sink_error kind=${error.javaClass.simpleName.ifEmpty { "other" }}"
    }
}

/** A failing sound output can raise errors many times a second; one line per [intervalMs] keeps the rest of the events. */
internal class SinkErrorLimiter(private val intervalMs: Long = 2_000L) {
    private var lastMs = Long.MIN_VALUE
    private var heldBack = 0

    @Synchronized
    fun line(nowMs: Long, text: String): String? {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs < intervalMs) {
            heldBack += 1
            return null
        }
        lastMs = nowMs
        val held = heldBack
        heldBack = 0
        return if (held > 0) "$text heldBack=$held" else text
    }
}
