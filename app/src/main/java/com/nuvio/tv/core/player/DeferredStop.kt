package com.nuvio.tv.core.player

/**
 * Runs a stop when the main thread is next idle, or after [MAX_WAIT_MS] if it never goes idle,
 * whichever comes first. [cancel] or a newer [schedule] drops a stop that has not run yet.
 */
internal class DeferredStop(
    private val whenIdle: (Runnable) -> Unit,
    private val after: (Long, Runnable) -> Unit
) {
    private var token = 0

    fun schedule(stop: () -> Unit) {
        val mine = ++token
        val run = Runnable {
            if (mine == token) {
                token++
                stop()
            }
        }
        whenIdle(run)
        after(MAX_WAIT_MS, run)
    }

    fun cancel() {
        token++
    }

    companion object {
        const val MAX_WAIT_MS = 750L
    }
}
