package com.nuvio.tv.ui.screens.home

/** Main-thread confined, like Home lifecycle events and its prefetch collectors. */
internal class HomePrefetchLifecycle {
    data class Current<out T>(val value: T)

    private var active = true
    private var generation = 0L

    fun setActive(value: Boolean) {
        if (value != active) {
            active = value
            generation++
        }
    }

    /** Reject a settings read that suspended across leaving Home, including leave/return. */
    suspend fun <T> readIfActive(read: suspend () -> T): Current<T>? {
        if (!active) return null
        val startedGeneration = generation
        val result = read()
        return if (active && generation == startedGeneration) Current(result) else null
    }
}
