package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class DirectDebridStreamPreparer @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val resolver: DirectDebridResolver
) {
    suspend fun prepare(
        streams: List<Stream>,
        season: Int?,
        episode: Int?,
        playerSettings: PlayerSettings,
        installedAddonNames: Set<String>,
        onPrepared: (original: Stream, prepared: Stream) -> Unit
    ) {
        val settings = dataStore.settings.first()
        val limit = settings.instantPlaybackPreparationLimit
        val debridStreamPreferences = settings.streamPreferences
        if (!settings.canResolvePlayableLinks || limit <= 0) return

        val candidates = prioritizeCandidates(
            streams = streams,
            limit = limit,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames,
            debridStreamPreferences = debridStreamPreferences
        )
        for (stream in candidates) {
            if (!resolver.shouldResolveToPlayableStream(stream)) continue
            // Listing/focus is not playback consent. Reuse an existing link, but
            // never create, resolve or unrestrict one for an unselected candidate.
            resolver.cachedPlayableStream(stream, season, episode)?.let { cached ->
                onPrepared(stream, cached)
            }
        }
    }

    internal fun prioritizeCandidates(
        streams: List<Stream>,
        limit: Int,
        playerSettings: PlayerSettings,
        installedAddonNames: Set<String>,
        debridStreamPreferences: DebridStreamPreferences? = null
    ): List<Stream> {
        if (limit <= 0) return emptyList()
        val candidates = streams
            .filter { (it.isDirectDebrid() || it.isCachedLocalDebridTorrent()) && it.getStreamUrl() == null }
            .distinctBy { it.preparationKey() }
        if (candidates.isEmpty()) return emptyList()

        val prioritized = mutableListOf<Stream>()
        // preferredBingeGroup was left at its default (null) here, so the
        // derived preferBingeGroupInSelection is false -- identical to the
        // default this call relied on.
        val autoPlaySelection = AutoPlaySelection.select(
            streams = streams,
            inputs = AutoPlaySelection.Inputs(
                mode = playerSettings.streamAutoPlayMode,
                regexPattern = playerSettings.streamAutoPlayRegex,
                source = playerSettings.streamAutoPlaySource,
                installedAddonNames = installedAddonNames,
                selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                preferredBingeGroup = null
            ),
            debridStreamPreferences = debridStreamPreferences
        )
        if (autoPlaySelection?.let { it.isDirectDebrid() || it.isCachedLocalDebridTorrent() } == true) {
            candidates.firstOrNull { it.preparationKey() == autoPlaySelection.preparationKey() }
                ?.let(prioritized::add)
        }

        if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
            val regex = runCatching {
                Regex(playerSettings.streamAutoPlayRegex.trim(), RegexOption.IGNORE_CASE)
            }.getOrNull()
            if (regex != null) {
                candidates
                    .filter { candidate ->
                        prioritized.none { it.preparationKey() == candidate.preparationKey() } &&
                            regex.containsMatchIn(candidate.searchableText())
                    }
                    .forEach(prioritized::add)
            }
        }

        candidates
            .filter { candidate -> prioritized.none { it.preparationKey() == candidate.preparationKey() } }
            .forEach(prioritized::add)

        return prioritized.take(limit)
    }

    fun replacePreparedStream(
        groups: List<AddonStreams>,
        original: Stream,
        prepared: Stream
    ): List<AddonStreams> {
        val key = original.preparationKey()
        return groups.map { group ->
            var changed = false
            val updatedStreams = group.streams.map { stream ->
                if (stream.preparationKey() == key) {
                    changed = true
                    prepared.copy(
                        addonName = stream.addonName,
                        addonLogo = stream.addonLogo,
                        badges = stream.badges
                    )
                } else {
                    stream
                }
            }
            if (changed) group.copy(streams = updatedStreams) else group
        }
    }

}

private fun Stream.preparationKey(): String {
    val resolve = clientResolve
    if (resolve != null) {
        return listOf(
            resolve.service.orEmpty().lowercase(),
            resolve.infoHash.orEmpty().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            resolve.filename.orEmpty().lowercase(),
            resolve.torrentName.orEmpty().lowercase(),
            resolve.magnetUri.orEmpty().lowercase()
        ).joinToString("|")
    }

    return listOf(
        addonName.lowercase(),
        getEffectiveInfoHash().orEmpty().lowercase(),
        torrentMagnetUri().orEmpty().lowercase(),
        getEffectiveFileIdx()?.toString().orEmpty(),
        getStreamUrl().orEmpty().lowercase(),
        name.orEmpty().lowercase(),
        title.orEmpty().lowercase()
    ).joinToString("|")
}

private fun Stream.searchableText(): String =
    buildString {
        append(addonName).append(' ')
        append(name.orEmpty()).append(' ')
        append(title.orEmpty()).append(' ')
        append(description.orEmpty()).append(' ')
        append(getStreamUrl().orEmpty())
    }

private fun Stream.isCachedLocalDebridTorrent(): Boolean =
    needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED
