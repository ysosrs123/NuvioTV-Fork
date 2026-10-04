package com.nuvio.tv.ui.screens.home

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Called on the UI thread. Both neighbours may load, with a fixed two-job budget. */
internal class AdjacentPrefetchJobs {
    private val jobs = LinkedHashMap<String, Job>()

    fun launch(
        scope: CoroutineScope,
        id: String,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ) {
        jobs.entries.removeAll { !it.value.isActive }
        // Touch a still-relevant neighbour so rapid navigation evicts stale work first.
        jobs.remove(id)?.let { jobs[id] = it; return }
        if (jobs.size >= 2) {
            val oldest = jobs.entries.first()
            oldest.value.cancel()
            jobs.remove(oldest.key)
        }
        jobs[id] = scope.launch(context, block = block)
    }
}
