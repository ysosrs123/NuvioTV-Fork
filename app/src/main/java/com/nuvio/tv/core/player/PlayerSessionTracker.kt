package com.nuvio.tv.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the in-app player is open, from controller creation until the player exits.
 * Unlike TvRecommendationManager.isPlaybackActive it stays set while paused, buffering or seeking,
 * so work hidden behind the player can wait for it to close instead of competing with playback.
 * Sessions are counted because the next player can open before the previous one is cleared.
 *
 * [settled] is false while the newest session starts a stream and for [SETTLE_MS] after its first frame, so
 * optional network work can wait for playback to settle in.
 */
object PlayerSessionTracker {
    const val SETTLE_MS = 60_000L

    /** A stream that never shows a frame does not hold [settled] back for longer than this. */
    const val NO_FRAME_SETTLE_MS = 120_000L

    private val openSessions = MutableStateFlow(0)

    val isSessionOpen: Flow<Boolean> = openSessions.map { it > 0 }.distinctUntilChanged()

    private val settledState = MutableStateFlow(true)

    val settled: StateFlow<Boolean> = settledState.asStateFlow()

    /** The newest session's stream has shown its first frame; false while it starts a stream. */
    @Volatile var firstFrameShown: Boolean = false
        private set

    internal var timerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()
    private var current: Session? = null
    private var timer: Job? = null
    private var generation = 0L

    fun open(): Session {
        openSessions.update { it + 1 }
        return Session().also { it.streamStarting() }
    }

    private fun unsettle(session: Session, ms: Long) = synchronized(lock) {
        firstFrameShown = ms == SETTLE_MS
        current = session
        timer?.cancel()
        val gen = ++generation
        settledState.value = false
        timer = timerScope.launch {
            delay(ms)
            synchronized(lock) { if (generation == gen) settledState.value = true }
        }
    }

    private fun settle(session: Session) = synchronized(lock) {
        if (current === session || openSessions.value == 0) {
            current = null
            firstFrameShown = false
            timer?.cancel()
            timer = null
            generation++
            settledState.value = true
        }
    }

    private fun firstFrameOf(session: Session) = synchronized(lock) {
        if (current === session) unsettle(session, SETTLE_MS)
    }

    class Session internal constructor() {
        private val closed = AtomicBoolean(false)
        private val starting = AtomicBoolean(false)

        /** A stream is being prepared; nothing is settled until its first frame and a minute after it. */
        fun streamStarting() {
            if (closed.get()) return
            starting.set(true)
            unsettle(this, NO_FRAME_SETTLE_MS)
        }

        /** Only the first frame after [streamStarting] counts. */
        fun firstFrame() {
            if (!closed.get() && starting.compareAndSet(true, false)) firstFrameOf(this)
        }

        /** Safe to call more than once; only the first call closes the session. */
        fun close() {
            if (closed.compareAndSet(false, true)) {
                openSessions.update { (it - 1).coerceAtLeast(0) }
                settle(this)
            }
        }
    }
}
