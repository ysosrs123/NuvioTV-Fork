package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.MetaCaller
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.formatHeroRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * The hero's look-ups, one per card. Moving to the next card leaves a running one alone, so a card visited again
 * finds its details; past [max] running the oldest is stopped.
 */
internal class HeroSeriesFactsJobs(private val max: Int = HERO_SERIES_FACTS_RUNNING_MAX) {
    private val jobs = LinkedHashMap<String, Job>()
    val warmGate = Semaphore(1)

    fun isRunning(key: String): Boolean = synchronized(this) { jobs[key]?.isActive == true }

    fun add(key: String, job: Job) {
        val stopped = synchronized(this) {
            jobs.remove(key)?.let { if (it !== job) it.cancel() }
            jobs[key] = job
            buildList { while (jobs.size > max) add(jobs.remove(jobs.keys.first())!!) }
        }
        stopped.forEach { it.cancel() }
    }

    fun finished(key: String, job: Job) {
        synchronized(this) { if (jobs[key] === job) jobs.remove(key) }
    }

    fun cancelAll() {
        val all = synchronized(this) { jobs.values.toList().also { jobs.clear() } }
        all.forEach { it.cancel() }
    }
}

private const val HERO_SERIES_FACTS_RUNNING_MAX = 4
private const val HERO_SERIES_FACTS_WARM_COUNT = 6
private const val HERO_SERIES_FACTS_WARM_DELAY_MS = 1_500L

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
    requestHeroSeriesFacts(ref, gate = null)
}

/**
 * Soon after start, fills the hero for the first Continue watching cards: saved details at once, the add-ons one
 * card at a time, so the first moves along the row find the logo and text ready.
 */
internal fun HomeViewModel.startHeroSeriesFactsWarmup() {
    viewModelScope.launch {
        _uiState
            .map { state ->
                state.continueWatchingItems.asSequence()
                    .take(HERO_SERIES_FACTS_WARM_COUNT)
                    .mapNotNull { it.heroSeriesRef() }
                    .toList()
            }
            .distinctUntilChanged()
            .collectLatest { refs ->
                if (refs.isEmpty()) return@collectLatest
                withContext(Dispatchers.IO) { ensureCwMetaStoreLoaded() }
                val missing = refs.filter { ref ->
                    if (ref.key in _heroSeriesFacts.value) return@filter false
                    val cached = cachedHeroSeriesFacts(ref) ?: return@filter true
                    publishHeroSeriesFacts(ref, cached)
                    false
                }
                if (missing.isEmpty()) return@collectLatest
                delay(HERO_SERIES_FACTS_WARM_DELAY_MS)
                missing.forEach { requestHeroSeriesFacts(it, gate = heroSeriesFactsJobs.warmGate) }
            }
    }
}

private fun HomeViewModel.requestHeroSeriesFacts(ref: HeroSeriesRef, gate: Semaphore?) {
    if (ref.key in _heroSeriesFacts.value || heroSeriesFactsJobs.isRunning(ref.key)) return
    if (!heroSeriesFactsAttempted.add(ref.key)) return
    val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
        // Right after start the saved Continue watching details may not be read in yet.
        ensureCwMetaStoreLoaded()
        cachedHeroSeriesFacts(ref)?.let {
            publishHeroSeriesFacts(ref, it)
            return@launch
        }
        val meta = if (gate != null) gate.withPermit { fetchHeroSeriesMeta(ref) } else fetchHeroSeriesMeta(ref)
        if (meta is NetworkResult.Success) publishHeroSeriesFacts(ref, meta.data.toHeroSeriesFacts(ref.season, ref.episode))
    }
    job.invokeOnCompletion { cause ->
        heroSeriesFactsJobs.finished(ref.key, job)
        // A look-up stopped early may be asked for again when that card comes back.
        if (cause is CancellationException) heroSeriesFactsAttempted.remove(ref.key)
    }
    heroSeriesFactsJobs.add(ref.key, job)
    job.start()
}

private suspend fun HomeViewModel.fetchHeroSeriesMeta(ref: HeroSeriesRef): NetworkResult<Meta>? = try {
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

internal fun HomeViewModel.clearHeroSeriesFacts() {
    heroSeriesFactsJobs.cancelAll()
    heroSeriesFactsAttempted.clear()
    _heroSeriesFacts.value = emptyMap()
}
