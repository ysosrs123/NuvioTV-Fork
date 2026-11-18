package com.nuvio.tv.core.player.amlfel

import android.os.Build
import android.view.Surface
import androidx.media3.common.C

/**
 * The frame-rate hint MediaCodec renderers give through media3's release helper: while playing, with the
 * player's strategy at ONLY_IF_SEAMLESS, the output surface is told the content rate so the display may switch
 * when it can do so without a blackout (QMS / seamless modes). Stopped, disabled or strategy OFF clears it.
 */
internal class FelFrameRateHint<T : Any>(private val apply: (T, Float) -> Unit) {
    private var strategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    private var output: T? = null
    private var frameRate = 0f
    private var started = false
    private var applied = 0f

    fun onStrategy(value: Int) {
        strategy = value
        update()
    }

    fun onOutput(value: T?) {
        if (value === output) return
        output?.let { if (applied != 0f) apply(it, 0f) }
        applied = 0f
        output = value
        update()
    }

    fun onFrameRate(rate: Float) {
        frameRate = if (rate > 1f && rate < 241f) rate else 0f
        update()
    }

    fun onStarted() {
        started = true
        update()
    }

    fun onStopped() {
        started = false
        update()
    }

    private fun update() {
        val target = output ?: return
        val wanted = if (started && strategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS) frameRate else 0f
        if (wanted == applied) return
        applied = wanted
        apply(target, wanted)
    }

    companion object {
        fun forSurfaces(): FelFrameRateHint<Surface> = FelFrameRateHint { surface, rate -> setSurfaceFrameRate(surface, rate) }

        private fun setSurfaceFrameRate(surface: Surface, rate: Float) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surface.isValid) return
            val compatibility = if (rate == 0f) Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            else Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    surface.setFrameRate(rate, compatibility, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
                } else {
                    surface.setFrameRate(rate, compatibility)
                }
            } catch (e: IllegalStateException) {
                android.util.Log.w(AmlDvFelVideoRenderer.TAG, "surface frame rate hint failed: ${e.message}")
            }
        }
    }
}
