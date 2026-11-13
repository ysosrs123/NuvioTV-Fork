package com.nuvio.tv.core.network

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Fed by the app's default-network callback, so playback can tell a short drop from a dead link. */
object NetworkValidation {
    data class State(
        val known: Boolean = false,
        val validated: Boolean = true,
        val dropId: Int = 0,
        val droppedAtMs: Long = 0L
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun note(validated: Boolean) {
        val now = SystemClock.elapsedRealtime()
        _state.update { next(it, validated, now) }
    }

    internal fun next(current: State, validated: Boolean, nowMs: Long): State = when {
        validated -> current.copy(known = true, validated = true)
        current.known && !current.validated -> current
        else -> State(known = true, validated = false, dropId = current.dropId + 1, droppedAtMs = nowMs)
    }
}
