package com.nuvio.tv.core.plugin

import java.util.concurrent.ConcurrentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/**
 * Joins a run already going for [key], or starts one. A run that dies because the screen that
 * started it went away is not passed on to whoever joined it: they run it again themselves.
 */
internal suspend fun <T> runSingleFlight(
    flights: ConcurrentMap<String, Deferred<T>>,
    key: String,
    fallback: T,
    onFailure: (Exception) -> Unit = {},
    block: suspend () -> T
): T {
    while (true) {
        val existing = flights[key] ?: break
        try {
            return existing.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            flights.remove(key, existing)
        } catch (e: Exception) {
            return fallback
        }
    }

    return supervisorScope {
        val deferred = async { block() }
        flights[key] = deferred
        try {
            deferred.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            fallback
        } catch (e: Exception) {
            onFailure(e)
            fallback
        } finally {
            flights.remove(key, deferred)
        }
    }
}
