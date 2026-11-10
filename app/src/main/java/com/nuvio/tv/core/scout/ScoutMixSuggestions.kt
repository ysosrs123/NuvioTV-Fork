package com.nuvio.tv.core.scout

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.scoutNormalizedName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val SEEDS = 5
private const val MOST = 12
private const val LOOK_AT = 30
private const val BUDGET_MS = 8_000L

/** Shows like the ones in a mix, for a mix with nothing left to watch. */
@Singleton
class ScoutMixSuggestions @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettings: TmdbSettingsDataStore,
    private val folderSupport: ScoutFolderSupport,
    private val artwork: ScoutArtwork
) {
    suspend fun forMix(mix: ScoutMix): List<MetaPreview> = withTimeoutOrNull(BUDGET_MS) {
        val language = tmdbSettings.settings.first().language
        val seeds = coroutineScope {
            mix.shows.take(SEEDS).map { show ->
                async { runCatching { tmdbService.ensureTmdbId(show.imdbId ?: show.id, "tv")?.toIntOrNull() }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        val lists = coroutineScope {
            seeds.map { id ->
                async {
                    runCatching { tmdbApi.getTvRecommendations(id, BuildConfig.TMDB_API_KEY, language) }.getOrNull()
                        ?.body()?.results.orEmpty().mapNotNull { it.toPick(seedTv = true) }
                }
            }.awaitAll()
        }
        val ranked = rankScoutMixSuggestions(lists, seeds.toSet(), mix.shows.map { scoutNormalizedName(it.name) }.toSet())
        val fresh = folderSupport.withoutSeen(ranked.take(LOOK_AT).map { it.toPreview() })
        artwork.follow(fresh.take(MOST), CustomPosterScreen.COLLECTIONS)
    }.orEmpty()

    private fun ScoutPick.toPreview() = MetaPreview(
        id = "tmdb:$tmdbId",
        type = ContentType.SERIES,
        rawType = "series",
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = backdrop,
        logo = null,
        description = overview,
        releaseInfo = released?.take(4),
        released = released,
        imdbRating = rating,
        voteCount = votes,
        genres = emptyList()
    )
}

/** Shows suggested by several of the mix's shows come first; within that, the higher a list placed them. */
internal fun rankScoutMixSuggestions(
    lists: List<List<ScoutPick>>,
    ownIds: Set<Int>,
    ownNames: Set<String>
): List<ScoutPick> {
    val places = lists.flatMap { list -> list.withIndex().map { (place, pick) -> pick to place } }
        .filter { (pick, _) -> pick.tv && pick.tmdbId !in ownIds && scoutNormalizedName(pick.name) !in ownNames }
        .groupBy { (pick, _) -> pick.tmdbId }
    return places.values
        .sortedWith(compareByDescending<List<Pair<ScoutPick, Int>>> { it.size }.thenBy { entries -> entries.minOf { it.second } })
        .map { it.first().first }
}
