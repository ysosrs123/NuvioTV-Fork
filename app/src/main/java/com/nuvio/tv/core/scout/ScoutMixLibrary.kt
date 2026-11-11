package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.EpisodeShufflePlayback
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixNameWords
import com.nuvio.tv.domain.model.suggestScoutMixName
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.domain.model.ScoutMixEpisodes
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val LOOKUP_TIMEOUT_MS = 20_000L
private const val ID_TIMEOUT_MS = 5_000L

/** Turns a poster into a saved show, and a saved show into the episodes it can offer. */
@Singleton
class ScoutMixLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    private val shufflePlayback: EpisodeShufflePlayback,
    private val watchProgress: WatchProgressRepository,
    private val tmdbService: TmdbService,
    private val finder: ScoutPicksFinder
) {
    private val words = ScoutMixNameWords(
        oneShow = { local().getString(R.string.scout_mixes_name_default, it) },
        genre = { local().getString(R.string.scout_mixes_name_default, it) },
        twoShows = { first, second -> local().getString(R.string.scout_mixes_name_two, first, second) },
        moreShows = { first, second, more ->
            local().resources.getQuantityString(R.plurals.scout_mixes_name_more, more, first, second, more)
        }
    )

    private fun local() = context.withAppLocale()

    fun suggestedName(shows: List<ScoutMixShow>, taken: List<String>): String =
        suggestScoutMixName(shows, taken, words)

    /** A name the viewer has not chosen follows the shows. An emptied mix keeps the one it has. */
    fun withSuggestedName(mix: ScoutMix, others: List<ScoutMix>): ScoutMix =
        if (!mix.autoName || mix.shows.isEmpty()) mix
        else mix.copy(name = suggestedName(mix.shows, others.filter { it.id != mix.id }.map { it.name }))

    suspend fun showFrom(item: MetaPreview): ScoutMixShow {
        val id = addonFriendlyId(item)
        return ScoutMixShow(
            id = id,
            name = item.name,
            poster = item.rawPosterUrl ?: item.poster,
            backdrop = item.background ?: item.landscapePoster,
            type = item.apiType,
            imdbId = item.imdbId ?: id.takeIf { it.startsWith("tt") },
            addonBaseUrl = item.sourceAddonBaseUrl?.takeUnless { it.startsWith(BuiltInCatalogs.BASE_PREFIX) },
            genres = item.genres,
            logo = item.logo?.takeIf(String::isNotBlank)
        )
    }

    private suspend fun addonFriendlyId(item: MetaPreview): String {
        if (!item.id.startsWith("tmdb:")) return item.id
        if (!item.imdbId.isNullOrBlank()) return item.imdbId
        val number = item.id.substringAfter(':').substringBefore(':').toIntOrNull() ?: return item.id
        return withTimeoutOrNull(ID_TIMEOUT_MS) { tmdbService.tmdbToImdb(number, "tv") }?.takeIf(String::isNotBlank) ?: item.id
    }

    /** The same artwork source as the show's own page, where an add-on's logo can be a dark one. */
    suspend fun bestLogo(show: ScoutMixShow): String? = withTimeoutOrNull(ID_TIMEOUT_MS) {
        val id = runCatching { tmdbService.ensureTmdbId(show.imdbId ?: show.id, "tv")?.toIntOrNull() }.getOrNull() ?: return@withTimeoutOrNull null
        finder.logo(id, tv = true)
    }

    /** The episode of these shows stopped partway most recently, from saved progress alone, with no lookup. */
    suspend fun startedEpisode(shows: List<ScoutMixShow>): Pair<ScoutMixShow, com.nuvio.tv.domain.model.WatchProgress>? {
        val partway = watchProgress.allProgress.first().filter { it.season != null && it.episode != null && it.isInProgress() }
        if (partway.isEmpty()) return null
        return shows.mapNotNull { show ->
            partway.filter { it.contentId == show.id || it.contentId == show.imdbId }.maxByOrNull { it.lastWatched }?.let { show to it }
        }.maxByOrNull { it.second.lastWatched }
    }

    suspend fun episodes(profileId: Int, show: ScoutMixShow): ScoutMixEpisodes? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        val result = metaRepository.getMetaFromAllAddons(show.type, show.id)
            .first { it !is NetworkResult.Loading }
        val meta = (result as? NetworkResult.Success)?.data ?: return@withTimeoutOrNull null
        val state = shufflePlayback.observe(profileId, meta.id, meta.apiType).first()
        val elsewhere = meta.videos.filter { video ->
            video.episode?.let { watchProgress.isWatchedByVideoId(video.id, it) } == true
        }.mapNotNull { video -> video.season?.let { season -> video.episode?.let { season to it } } }.toSet()
        ScoutMixEpisodes(meta, state.watched + elsewhere, state.progress)
    }
}
