package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * Ranks during the prefetch instead of after the Play press. Selection costs
 * a few hundred ms on a 4K DV list and StreamPrefetchCache has the stream list
 * seconds before the click, so the rank can leave the critical path.
 *
 * The winner is decided by the settings read at rank time. If any of them
 * changed before the press, the cached winner is discarded and the rank is
 * redone live. Pure code: no Android, no coroutines, no ViewModel.
 *
 * [installedAddonOrder] is in the snapshot as an ordered list because
 * StreamQualityRank.rank is a stable sort, so incoming order decides ties.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */

/** Everything that decides the auto-play winner, captured at rank time. */
data class SelectionSnapshot(
    val inputs: AutoPlaySelection.Inputs,
    val installedAddonOrder: List<String>,
    val preferences: DebridStreamPreferences?
)

/** A winner computed during the prefetch, with the snapshot it was computed under. */
data class PrefetchedSelection(
    val snapshot: SelectionSnapshot,
    val winner: Stream
)

/** Outcome of consulting a prefetched selection at press time. */
sealed interface SelectionOutcome {
    /** Use this stream; it is the instance from the live list, not the cached twin. */
    data class Hit(val stream: Stream) : SelectionOutcome

    /** Rank live. [reason] is logged so a silent fallback cannot read as a win. */
    data class Live(val reason: String) : SelectionOutcome
}

object PrefetchedSelectionGate {

    const val REASON_NO_ENTRY = "no-entry"
    const val REASON_INPUTS_CHANGED = "inputs-changed"
    const val REASON_KEY_MISS = "key-miss"

    /**
     * Resolves a prefetched winner against the list actually being presented.
     *
     * [identityOf] must be badge-independent. applySuccess badge-merges via
     * stream.copy(badges = ...) before the ranking pass, so the cached winner
     * can be a badge-less twin of the instance in [streams]; data-class equality
     * would miss it, and returning the cached instance would drop badges the UI
     * has already computed. StreamScreenViewModel.badgeMergeKey is exactly this
     * identity and is what the badge merge itself keys on.
     */
    fun resolve(
        prefetched: PrefetchedSelection?,
        snapshot: SelectionSnapshot,
        streams: List<Stream>,
        identityOf: (Stream) -> String
    ): SelectionOutcome {
        if (prefetched == null) return SelectionOutcome.Live(REASON_NO_ENTRY)
        if (prefetched.snapshot != snapshot) return SelectionOutcome.Live(REASON_INPUTS_CHANGED)
        val wantedKey = identityOf(prefetched.winner)
        val match = streams.firstOrNull { identityOf(it) == wantedKey }
            ?: return SelectionOutcome.Live(REASON_KEY_MISS)
        return SelectionOutcome.Hit(match)
    }
}
