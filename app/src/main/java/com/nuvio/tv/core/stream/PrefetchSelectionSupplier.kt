package com.nuvio.tv.core.stream

import android.os.SystemClock
import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.core.player.PrefetchedSelection
import com.nuvio.tv.core.player.SelectionSnapshot
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Local ranking of already obtained source metadata; never resolves or opens media. */
@Singleton
class PrefetchSelectionSupplier @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val streamBadgePresentation: com.nuvio.tv.core.streams.StreamBadgePresentation
) {

    private val signalStore = SourcePrefetchSignalStore()

    /** Hero-target source line reports the locally ranked candidate, never link readiness. */
    val uiSignals: StateFlow<SourcePrefetchSignal?> = signalStore.signals

    /** Cache the winner and its settings snapshot for validation at the Play press. */
    suspend fun rankForPrefetch(
        groups: List<AddonStreams>,
        contentId: String?,
        season: Int?,
        episode: Int?,
        bingeOverride: String? = null,
        uiKey: String? = null
    ): PrefetchedSelection? {
        val generation = uiKey?.let(signalStore::begin)
        val rankT0 = SystemClock.elapsedRealtime()
        val selection = rank(groups, contentId, bingeOverride)
        // Local ranking excludes provider resolution and media I/O.
        val winnerLabel = if (selection == null) "none" else "yes"
        android.util.Log.i(
            TAG,
            "PREFETCH rank_only winner=$winnerLabel " +
                "ms=${SystemClock.elapsedRealtime() - rankT0}"
        )
        if (selection == null) {
            if (uiKey != null) {
                signalStore.publish(generation, SourcePrefetchSignal(uiKey, SourcePrefetchPhase.EMPTY, null))
            }
            return null
        }
        if (uiKey != null) {
            val facts = selection.snapshot.preferences?.let {
                com.nuvio.tv.core.debrid.DirectDebridStreamFilter.factsFor(selection.winner, it)
            }
            val badges = streamBadgePresentation.badgesFor(selection.winner)
            val rankedSignal = SourcePrefetchSignal(uiKey, SourcePrefetchPhase.RANKED, facts, badges,
                com.nuvio.tv.core.debrid.StreamTextSizeParser.effectiveSizeBytes(selection.winner)?.takeIf { it > 0 })
            signalStore.publish(generation, rankedSignal)
        }
        // Ranking is local. Provider create/resolve/unrestrict and media URL access
        // require explicit playback selection, including addon-provided direct URLs.
        return selection
    }

    /**
     * Every input is read HERE and snapshotted; the stream screen compares its
     * own snapshot before consuming the winner, so a setting that moved in
     * between discards the cached pick rather than acting on a stale one.
     *
     * The ordering step is not optional: StreamQualityRank.rank is a stable
     * sort, so incoming order decides ties. Ranking the raw scrape output would
     * break ties differently from the presented list.
     */
    private suspend fun rank(
        groups: List<AddonStreams>,
        contentId: String?,
        bingeOverride: String?
    ): PrefetchedSelection? {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) return null

        val installedAddonOrder = addonRepository.getInstalledAddons()
            .first()
            .enabledAddons()
            .map { it.displayName }

        // A caller that KNOWS the group the press will prefer supplies
        // it. The binge lookahead does -- it is running inside the playback
        // whose group the next-episode path will match against -- and
        // without it the two disagree whenever Prefer Binge Group is on and
        // Reuse Binge Group is off. The lookahead then pre-resolves a
        // different stream from the one the press selects, so the press
        // misses DirectDebridResolver's cache and the prewarm warms the
        // wrong node, costing seconds. The cache-backed derivation stays the
        // default for the three callers with no playback in progress.
        val preferredBingeGroup = bingeOverride ?: if (
            settings.streamAutoPlayPreferBingeGroupForNextEpisode &&
            settings.streamAutoPlayReuseBingeGroup
        ) {
            contentId?.let { bingeGroupCacheDataStore.get(it) }
        } else {
            null
        }

        val preferences = debridSettingsDataStore.settings.first().streamPreferences

        val inputs = AutoPlaySelection.Inputs(
            mode = settings.streamAutoPlayMode,
            regexPattern = settings.streamAutoPlayRegex,
            source = settings.streamAutoPlaySource,
            installedAddonNames = installedAddonOrder.toSet(),
            selectedAddons = settings.streamAutoPlaySelectedAddons,
            selectedPlugins = settings.streamAutoPlaySelectedPlugins,
            preferredBingeGroup = preferredBingeGroup
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(groups, installedAddonOrder)
        val allStreams = ordered.flatMap { it.streams }
        val winner = AutoPlaySelection.select(
            streams = allStreams,
            inputs = inputs,
            debridStreamPreferences = preferences
        ) ?: return null

        return PrefetchedSelection(
            snapshot = SelectionSnapshot(
                inputs = inputs,
                installedAddonOrder = installedAddonOrder,
                preferences = preferences
            ),
            winner = winner
        )
    }

    private companion object {
        // Logged under StreamPrefetch rather than a per-ViewModel tag: these are
        // prefetch-phase events and belong beside PREFETCH rank in the log.
        const val TAG = "StreamPrefetch"
    }
}
