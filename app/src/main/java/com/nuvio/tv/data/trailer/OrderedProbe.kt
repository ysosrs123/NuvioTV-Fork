package com.nuvio.tv.data.trailer

import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The result for the earliest item in [items] that [probe] accepts. At most [atOnce] probes run
 * together, in order, and nothing later than an accepted item is started. After [timeoutMs] the
 * earliest accepted result so far is returned. Cancelling the caller cancels every probe.
 */
internal suspend fun <T, R : Any> firstAcceptedInOrder(
    items: List<T>,
    atOnce: Int,
    timeoutMs: Long,
    probe: suspend (T) -> R?
): R? {
    if (items.isEmpty()) return null
    val accepted = AtomicReferenceArray<R?>(items.size)
    val settled = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val finished = Channel<Int>(Channel.UNLIMITED)
            val done = BooleanArray(items.size)
            var next = 0
            var running = 0
            var limit = items.size
            var winner: R? = null
            while (true) {
                while (running < atOnce.coerceAtLeast(1) && next < limit) {
                    val index = next++
                    running++
                    launch {
                        val result = try {
                            probe(items[index])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (result != null) accepted.set(index, result)
                        finished.send(index)
                    }
                }
                if (running == 0) break
                val index = finished.receive()
                running--
                done[index] = true
                if (accepted.get(index) != null && index < limit) limit = index
                val lead = (0 until items.size).firstOrNull { !done[it] || accepted.get(it) != null }
                if (lead != null && done[lead]) {
                    winner = accepted.get(lead)
                    break
                }
            }
            coroutineContext.job.cancelChildren()
            winner
        }
    }
    return settled ?: (0 until items.size).firstNotNullOfOrNull { accepted.get(it) }
}
