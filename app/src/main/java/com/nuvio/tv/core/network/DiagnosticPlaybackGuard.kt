package com.nuvio.tv.core.network

/** Tracks player ownership, including paused/starting/native sessions, rather than isPlaying. */
internal class DiagnosticPlaybackGuard {
    private val lock = Any()
    private var players = 0
    private val listeners = mutableSetOf<() -> Unit>()

    fun enterPlayback(): AutoCloseable {
        val cancel = synchronized(lock) { players++; listeners.toList() }
        cancel.forEach { it() }
        var closed = false
        return AutoCloseable {
            synchronized(lock) { if (!closed) { closed = true; players-- } }
        }
    }

    fun register(onPlayback: () -> Unit): AutoCloseable? = synchronized(lock) {
        if (players > 0) null else {
            listeners.add(onPlayback)
            AutoCloseable { synchronized(lock) { listeners.remove(onPlayback) } }
        }
    }

    companion object { val shared = DiagnosticPlaybackGuard() }
}
