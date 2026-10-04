package com.nuvio.tv.data.trailer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** One temporary resolver resource at a time, including cancellation and dispatcher handoff. */
internal class TrailerResourceGate(private val dispatcher: CoroutineDispatcher) {
    private val permit = Semaphore(1)

    suspend fun <T, R> use(create: () -> T, destroy: (T) -> Unit, block: suspend (T) -> R): R =
        permit.withPermit {
            withContext(dispatcher) {
                // Acquire inside the owning context: returning a resource across withContext
                // can lose it to prompt cancellation before the caller enters its finally.
                val resource = create()
                try {
                    block(resource)
                } finally {
                    withContext(NonCancellable) { destroy(resource) }
                }
            }
        }
}
