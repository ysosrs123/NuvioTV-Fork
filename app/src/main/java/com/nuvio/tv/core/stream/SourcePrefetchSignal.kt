package com.nuvio.tv.core.stream

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter

/**
 * UI-facing signal for the details-page source line (fork feature).
 *
 * RANKED: the auto-play winner is chosen; [facts] carry its parsed badges.
 * READY: the winner's link is usable (debrid resolve Success, or a
 * direct-URL source needing no resolve).
 * SEARCHING is not emitted here: the ViewModel derives it from "key set,
 * no matching signal yet".
 */
enum class SourcePrefetchPhase { SEARCHING, RANKED, READY, EMPTY }

data class SourcePrefetchSignal(
    val uiKey: String,
    val phase: SourcePrefetchPhase,
    val facts: DirectDebridStreamFilter.StreamFacts?,
    val badges: List<com.nuvio.tv.domain.model.StreamBadge> = emptyList(),
    val fileSizeBytes: Long? = null
)

internal fun SourcePrefetchSignal.withFileSizeVisibility(visible: Boolean) =
    copy(fileSizeBytes = fileSizeBytes?.takeIf { visible && it > 0 })

/** One UI winner, with a generation distinct from its content key (including A -> B -> A). */
internal class SourcePrefetchSignalStore {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<SourcePrefetchSignal?>(null)
    val signals: kotlinx.coroutines.flow.StateFlow<SourcePrefetchSignal?> = state
    private var generation = 0L

    @Synchronized fun begin(key: String): Long {
        generation++
        state.value = SourcePrefetchSignal(key, SourcePrefetchPhase.SEARCHING, null)
        return generation
    }

    @Synchronized fun publish(ticket: Long?, signal: SourcePrefetchSignal) {
        if (ticket == generation) state.value = signal
    }

    @Synchronized fun ready(ticket: Long?) {
        if (ticket == generation && state.value?.phase == SourcePrefetchPhase.RANKED) {
            state.value = state.value?.copy(phase = SourcePrefetchPhase.READY)
        }
    }
}
