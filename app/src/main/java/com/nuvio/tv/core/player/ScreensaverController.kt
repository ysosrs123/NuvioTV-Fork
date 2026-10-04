package com.nuvio.tv.core.player

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine for the OLED idle screensaver (dim overlay).
 *
 * - [notifyInteraction] records user input; it never engages or wakes by itself.
 * - [notifyWake] hides the overlay, lifts trailer suppression and restarts the idle clock.
 * - [setPlaybackActive] blocks engagement while playing or buffering; resuming wakes,
 *   pausing restarts the idle clock.
 * - [setWindowFocused] pauses eligibility while another window (e.g. a dialog) has focus.
 * - [maybeEngage] is the only entry point that can show the overlay, called from a 1 Hz ticker.
 *
 * On engage the trailer pool is stopped and suppressed, so a hero trailer cannot hold the
 * screen on or keep playing under the overlay.
 */
@Singleton
class ScreensaverController @Inject constructor(
    private val trailerPlayerPool: TrailerPlayerPool
) {

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _playbackActive = MutableStateFlow(false)

    @Volatile
    private var lastInteractionMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var windowFocused: Boolean = true

    /** Record user input. Timer bookkeeping only; wake/consume decisions live with the caller. */
    fun notifyInteraction() {
        lastInteractionMs = SystemClock.elapsedRealtime()
    }

    /** Hide the overlay (if shown), lift trailer suppression, restart the idle clock. */
    fun notifyWake() {
        lastInteractionMs = SystemClock.elapsedRealtime()
        if (_overlayVisible.value) {
            _overlayVisible.value = false
            trailerPlayerPool.setScreensaverSuppressed(false)
        }
    }

    fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
        if (focused) {
            lastInteractionMs = SystemClock.elapsedRealtime()
        }
    }

    fun setPlaybackActive(active: Boolean) {
        val was = _playbackActive.value
        if (was == active) return
        _playbackActive.value = active
        if (active) {
            // Resume can arrive via MediaSession without a key ever reaching the Activity.
            notifyWake()
        } else {
            // Pausing starts a fresh idle count; without this a two-hour film would dim
            // the moment it is paused (the last key press was hours ago).
            lastInteractionMs = SystemClock.elapsedRealtime()
        }
    }

    /** Called at ~1 Hz. Engages the overlay only when every condition holds. */
    fun maybeEngage(timeoutMs: Long) {
        if (_overlayVisible.value) return
        if (!windowFocused) return
        if (_playbackActive.value) return
        if (SystemClock.elapsedRealtime() - lastInteractionMs < timeoutMs) return
        _overlayVisible.value = true
        trailerPlayerPool.setScreensaverSuppressed(true)
        trailerPlayerPool.stop()
    }
}
