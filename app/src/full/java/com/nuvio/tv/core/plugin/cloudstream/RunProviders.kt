package com.nuvio.tv.core.plugin.cloudstream

import java.util.Collections
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs every provider of an extension and returns what finished within [budgetMs], so a slow
 * provider cannot lose the results of the others. Results keep the order they arrived in.
 */
internal suspend fun <P, R> runProviders(
    providers: List<P>,
    maxAtOnce: Int,
    budgetMs: Long,
    run: suspend (P) -> List<R>
): List<R> {
    val collected = Collections.synchronizedList(mutableListOf<R>())
    val permits = Semaphore(maxAtOnce)
    withTimeoutOrNull(budgetMs) {
        coroutineScope {
            providers.forEach { provider ->
                launch {
                    permits.withPermit { collected += run(provider) }
                }
            }
        }
    }
    return synchronized(collected) { collected.toList() }
}
