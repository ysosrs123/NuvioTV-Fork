package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.mdblist.MdbListWatchlistBatch
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.SavedLibraryItem
import javax.inject.Inject
import javax.inject.Singleton
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.data.simkl.SimklDestructiveRemovalRequiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

data class LibraryTransferResult(
    val copied: Int,
    val alreadyPresent: Int,
    val unmatched: Int,
    val duplicates: Int,
    val removedFromSource: Int,
    val keptAtSource: Int,
    val failed: Int
)

/**
 * Executes a provider-agnostic library transfer between two locations (local,
 * Trakt, Simkl, MDBList). Copy is the default; move is copy-then-remove from the
 * source. Reads and writes go through the same registry / preferences the rest
 * of the app uses; the actual planning is [computeTransferPlan].
 *
 * This is the engine only - no UI wires it yet. The destination write is
 * best-effort per item and failures are counted rather than aborting the batch.
 * For an MDBList destination the whole batch is one bulk request instead of one
 * write per item, which keeps a large copy inside the free-tier budget.
 */
@Singleton
class LibraryTransferService @Inject constructor(
    private val trackingProviders: TrackingLibraryProviderRegistry,
    private val libraryPreferences: LibraryPreferences,
    private val mdbListWatchlist: MdbListWatchlistBatch,
    private val librarySyncService: LibrarySyncService,
    private val profileManager: ProfileManager
) {
    /** The current contents of a library location. */
    suspend fun read(
        mode: LibrarySourceMode,
        watchlistOnly: Boolean = false
    ): List<LibraryEntry> {
        val providerId = mode.providerId
        return if (providerId == null) {
            libraryPreferences.getAllItems().map { it.toLibraryEntry() }
        } else {
            val provider = trackingProviders.provider(providerId) ?: return emptyList()
            provider.refresh(TrackingRefreshIntent.USER_INITIATED)
            val items = provider.items.first()
            if (watchlistOnly) {
                // Restrict to the watchlist / plan-to-watch tab so a transfer
                // doesn't sweep in completed/watching/history and dump it on the
                // destination's watchlist. Other lists/statuses are opt-in later.
                val watchlistKey = provider.tabs.first()
                    .firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key
                if (watchlistKey != null) {
                    items.filter { watchlistKey in it.listKeys }
                } else {
                    items
                }
            } else {
                items
            }
        }
    }

    /** Dry run: what a copy from [from] to [to] would do, without writing. */
    suspend fun preview(from: LibrarySourceMode, to: LibrarySourceMode): LibraryTransferPlan {
        if (from == to) return LibraryTransferPlan(emptyList(), 0, 0, 0, 0)
        val source = read(from, watchlistOnly = true)
        val destinationKeys = read(to).map(::transferKey).toSet()
        return computeTransferPlan(source, destinationKeys)
    }

    /** Performs the transfer. Safe to call after [preview] has been shown. */
    suspend fun execute(
        from: LibrarySourceMode,
        to: LibrarySourceMode,
        mode: LibraryTransferMode
    ): LibraryTransferResult {
        if (from == to) return LibraryTransferResult(0, 0, 0, 0, 0, 0, 0)
        val source = read(from, watchlistOnly = true)
        val destinationKeysBefore = read(to).map(::transferKey).toSet()
        val plan = computeTransferPlan(source, destinationKeysBefore)
        val written = writeAll(to, plan.toWrite)

        var removed = 0
        var kept = 0
        if (mode == LibraryTransferMode.MOVE) {
            // Remove from the source everything confirmed at the destination
            // after the copy - both newly written and already present - by
            // re-reading the destination, not by trusting write counts.
            val destinationKeysAfter = read(to).map(::transferKey).toSet()
            val removable = source
                .filter { it.hasResolvableId() && transferKey(it) in destinationKeysAfter }
                .distinctBy { transferKey(it) }
                .map { it.toTransferInput() }
            val outcome = removeFromSource(from, removable)
            removed = outcome.removed
            kept = outcome.kept
        }

        // Refresh both endpoints so the Library screen reflects the transfer
        // (the source shrank on a Move; the destination grew). Local libraries
        // are DataStore-backed and update reactively, so only tracking providers
        // need an explicit nudge.
        setOfNotNull(from.providerId, to.providerId).forEach { providerId ->
            trackingProviders.provider(providerId)?.refresh(TrackingRefreshIntent.USER_INITIATED)
        }

        // If the local Nuvio library changed, push it to the cloud so local and
        // cloud stay consistent - otherwise a Move that cleared local could be
        // undone by the next pull. The normal add/remove path does this too.
        val localWritten = to.providerId == null ||
            (from.providerId == null && mode == LibraryTransferMode.MOVE)
        if (localWritten) {
            runCatching { librarySyncService.pushToRemote(profileManager.activeProfileId.value) }
        }

        return LibraryTransferResult(
            copied = written,
            alreadyPresent = plan.alreadyPresent,
            unmatched = plan.unmatched,
            duplicates = plan.duplicates,
            removedFromSource = removed,
            keptAtSource = kept,
            failed = plan.willWrite - written
        )
    }

    private suspend fun writeAll(to: LibrarySourceMode, items: List<LibraryEntryInput>): Int {
        if (items.isEmpty()) return 0
        if (to == LibrarySourceMode.MDBLIST) return mdbListWatchlist.add(items)
        val providerId = to.providerId
        if (providerId != null) {
            val provider = trackingProviders.provider(providerId) ?: return 0
            val desired = ListMembershipChanges(provider.toggledDefaultMembership(emptyMap()))
            var ok = 0
            var consecutiveFailures = 0
            for (item in items) {
                val success = runCatching {
                    provider.applyMembershipChanges(item, desired, destructiveRemovalConfirmed = false)
                }.isSuccess
                if (success) {
                    ok++
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break
                delay(TRACKING_THROTTLE_MS)
            }
            return ok
        }
        var ok = 0
        for (item in items) {
            runCatching { libraryPreferences.addItem(item.toSavedLibraryItem()) }.onSuccess { ok++ }
        }
        return ok
    }

    private data class RemovalOutcome(val removed: Int, val kept: Int)

    /**
     * Removes [items] from the source. Uses each item's *full* current
     * membership (all lists/statuses), so an item is cleared from the source
     * entirely, not just its default list. Never forces a destructive removal:
     * if clearing an item would also wipe watched history or a rating (Simkl's
     * guard throws), the item is left in place and counted as kept.
     */
    private suspend fun removeFromSource(
        from: LibrarySourceMode,
        items: List<LibraryEntryInput>
    ): RemovalOutcome {
        if (items.isEmpty()) return RemovalOutcome(0, 0)

        if (from == LibrarySourceMode.MDBLIST) {
            val removed = mdbListWatchlist.remove(items)
            return RemovalOutcome(removed, items.size - removed)
        }

        val providerId = from.providerId
        if (providerId != null) {
            val provider = trackingProviders.provider(providerId)
                ?: return RemovalOutcome(0, items.size)
            // Remove only the watchlist/plan-to-watch membership, preserving any
            // other lists/statuses the item belongs to (transfers are currently
            // watchlist-scoped, so a Move must not clear personal lists too).
            val watchlistKey = provider.tabs.first()
                .firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key
            var removed = 0
            var kept = 0
            var consecutiveFailures = 0
            for (item in items) {
                val current = runCatching {
                    provider.getMembershipSnapshot(item).listMembership
                }.getOrNull()
                if (current == null) {
                    kept++
                    continue
                }
                val desired = if (watchlistKey != null && current[watchlistKey] == true) {
                    current.toMutableMap().apply { put(watchlistKey, false) }
                } else {
                    current
                }
                if (desired == current) {
                    removed++ // not in the watchlist; nothing to remove
                    continue
                }
                val error = runCatching {
                    provider.applyMembershipChanges(
                        item,
                        ListMembershipChanges(desired),
                        destructiveRemovalConfirmed = false
                    )
                }.exceptionOrNull()
                when {
                    error == null -> {
                        removed++
                        consecutiveFailures = 0
                    }
                    error is SimklDestructiveRemovalRequiredException -> {
                        kept++ // expected safe-skip, not a rate-limit signal
                        consecutiveFailures = 0
                    }
                    else -> {
                        kept++
                        consecutiveFailures++
                    }
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break
                delay(TRACKING_THROTTLE_MS)
            }
            return RemovalOutcome(removed, kept)
        }

        var removed = 0
        for (item in items) {
            runCatching {
                libraryPreferences.removeItem(item.itemId, item.itemType)
            }.onSuccess { removed++ }
        }
        return RemovalOutcome(removed, items.size - removed)
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val TRACKING_THROTTLE_MS = 30L
    }
}

private fun SavedLibraryItem.toLibraryEntry(): LibraryEntry {
    val parsed = parseContentIds(id)
    return LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        listedAt = addedAt,
        imdbId = parsed.imdb,
        tmdbId = parsed.tmdb,
        traktId = parsed.trakt
    )
}

private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem = SavedLibraryItem(
    id = itemId,
    type = itemType,
    name = title,
    poster = poster,
    posterShape = posterShape,
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    logo = logo,
    addedAt = System.currentTimeMillis()
)
