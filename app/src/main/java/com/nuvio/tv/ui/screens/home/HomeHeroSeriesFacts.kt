package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.MetaCaller
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.formatHeroRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The show's own details, used where a Continue watching or Coming up card has none of its own. */
@Immutable
data class HeroSeriesFacts(
    val description: String? = null,
    val episodeDescription: String? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val ageRating: String? = null,
    val backdrop: String? = null,
    val logo: String? = null
)

@Immutable
data class HeroSeriesRef(
    val contentType: String,
    val contentId: String,
    val season: Int?,
    val episode: Int?
) {
    val key: String get() = "$contentType:$contentId:${season ?: 0}:${episode ?: 0}"
}

internal fun ContinueWatchingItem.heroSeriesRef(): HeroSeriesRef? = when (this) {
    is ContinueWatchingItem.InProgress -> HeroSeriesRef(
        progress.contentType, progress.contentId, progress.season, progress.episode
    )
    is ContinueWatchingItem.NextUp -> HeroSeriesRef(
        info.contentType, info.contentId, info.season.takeIf { it > 0 }, info.episode.takeIf { it > 0 }
    )
}.takeIf { it.contentId.isNotBlank() }

internal fun ModernCarouselItem.heroSeriesRef(): HeroSeriesRef? =
    (payload as? ModernPayload.ContinueWatching)?.item?.heroSeriesRef()

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun heroSeriesFacts(
    description: String?,
    genres: List<String>,
    releaseInfo: String?,
    runtime: String?,
    ageRating: String?,
    episodeOverview: String?,
    episodeRuntime: Int?,
    poster: String?,
    backdrop: String?,
    logo: String?
) = HeroSeriesFacts(
    description = description.clean(),
    episodeDescription = episodeOverview.clean(),
    genres = genres.filter { it.isNotBlank() }.take(3),
    releaseInfo = releaseInfo.clean(),
    runtime = episodeRuntime?.takeIf { it > 0 }?.let { "$it min" } ?: runtime.clean(),
    ageRating = ageRating.clean(),
    backdrop = backdrop.clean()?.takeUnless { it == poster.clean() },
    logo = logo.clean()
)

internal fun Meta.toHeroSeriesFacts(season: Int?, episode: Int?): HeroSeriesFacts {
    val video = if (season != null && episode != null) {
        videos.firstOrNull { it.season == season && it.episode == episode }
    } else null
    return heroSeriesFacts(
        description, genres, releaseInfo, runtime, ageRating, video?.overview, video?.runtime,
        poster, background ?: landscapePoster, logo
    )
}

internal fun CwMetaSummary.toHeroSeriesFacts(season: Int?, episode: Int?): HeroSeriesFacts {
    val video = if (season != null && episode != null) {
        videos.firstOrNull { it.season == season && it.episode == episode }
    } else null
    return heroSeriesFacts(
        description, genres, releaseInfo, runtime, ageRating, video?.overview, video?.runtime,
        poster, backdropUrl, logo
    )
}

/**
 * Gives a Continue watching or Coming up hero the lines a catalogue hero has: genre, runtime and year from the show,
 * the episode's synopsis when known and the show's otherwise. The card's own values win.
 */
internal fun HeroPreview.withSeriesFacts(facts: HeroSeriesFacts?): HeroPreview {
    if (facts == null) return this
    val filledGenres = genres.list.ifEmpty { facts.genres }
    return copy(
        description = description.clean() ?: facts.episodeDescription ?: facts.description,
        genres = if (filledGenres === genres.list) genres else filledGenres.asStable(),
        yearText = yearText.clean() ?: extractYearOrRange(facts.releaseInfo),
        runtimeText = runtimeText.clean() ?: formatHeroRuntime(facts.runtime),
        ageRatingText = ageRatingText.clean() ?: facts.ageRating,
        backdrop = backdrop.clean() ?: facts.backdrop,
        logo = logo.clean() ?: facts.logo
    )
}

private const val HERO_SERIES_FACTS_MAX = 48
private const val HERO_SERIES_FACTS_TIMEOUT_MS = 6_000L

private fun HomeViewModel.cachedHeroSeriesFacts(ref: HeroSeriesRef): HeroSeriesFacts? {
    val types = listOf(ref.contentType, "series", "tv").distinct()
    synchronized(cwMetaCache) {
        types.firstNotNullOfOrNull { cwMetaCache["$it:${ref.contentId}"] }
    }?.let { return it.toHeroSeriesFacts(ref.season, ref.episode) }
    return types.firstNotNullOfOrNull { type -> metaRepository.getCachedMeta(type, ref.contentId) }
        ?.toHeroSeriesFacts(ref.season, ref.episode)
}

private fun HomeViewModel.publishHeroSeriesFacts(ref: HeroSeriesRef, facts: HeroSeriesFacts) {
    _heroSeriesFacts.update { current ->
        if (current[ref.key] == facts) return@update current
        val next = LinkedHashMap(current)
        next.remove(ref.key)
        next[ref.key] = facts
        while (next.size > HERO_SERIES_FACTS_MAX) next.remove(next.keys.first())
        next
    }
}

/**
 * Called once the hero has settled on a Continue watching or Coming up card. Details already in memory are used
 * straight away; otherwise the show's details are asked for once, for this card only.
 */
internal fun HomeViewModel.onSeriesHeroSettledPipeline(ref: HeroSeriesRef) {
    if (ref.key in _heroSeriesFacts.value) return
    cachedHeroSeriesFacts(ref)?.let {
        publishHeroSeriesFacts(ref, it)
        return
    }
    if (!heroSeriesFactsAttempted.add(ref.key)) return
    heroSeriesFactsJob?.cancel()
    heroSeriesFactsJob = viewModelScope.launch(Dispatchers.IO) {
        val meta = try {
            withContext(MetaCaller("home-hero")) {
                withTimeoutOrNull(HERO_SERIES_FACTS_TIMEOUT_MS) {
                    metaRepository.getMetaFromPrimaryAddon(ref.contentType, ref.contentId)
                        .first { it !is NetworkResult.Loading }
                        .takeIf { it is NetworkResult.Success }
                        ?: metaRepository.getMetaFromAllAddons(ref.contentType, ref.contentId)
                            .first { it !is NetworkResult.Loading }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (meta is NetworkResult.Success) publishHeroSeriesFacts(ref, meta.data.toHeroSeriesFacts(ref.season, ref.episode))
    }.also { job ->
        // A fetch cut short by the next card may be asked for again when that card comes back.
        job.invokeOnCompletion { cause -> if (cause is CancellationException) heroSeriesFactsAttempted.remove(ref.key) }
    }
}

internal fun HomeViewModel.clearHeroSeriesFacts() {
    heroSeriesFactsJob?.cancel()
    heroSeriesFactsAttempted.clear()
    _heroSeriesFacts.value = emptyMap()
}
