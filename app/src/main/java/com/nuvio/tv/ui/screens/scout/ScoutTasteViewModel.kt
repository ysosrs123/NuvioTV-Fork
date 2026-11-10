package com.nuvio.tv.ui.screens.scout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.scout.ScoutEngine
import com.nuvio.tv.core.scout.ScoutTasteProgress
import com.nuvio.tv.core.scout.ScoutTasteRepository
import com.nuvio.tv.core.scout.ScoutTasteSummary
import com.nuvio.tv.core.scout.scoutGenreLeaveOutIds
import com.nuvio.tv.core.scout.scoutNudge
import com.nuvio.tv.core.scout.scoutTasteSummary
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutProfile
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.nuvio.tv.core.scout.scoutWatchCounts
import com.nuvio.tv.core.scout.scoutWatchScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val SHAPING_TITLES = 12
private const val RECENT_TITLES = 20

/** A title that counts most towards the picks, shown with the thumb it was given. */
data class ScoutShapingTitle(val id: String, val type: String, val name: String, val poster: String?, val rating: ScoutRating?, val backdrop: String? = null)

fun scoutShapingTitles(watched: List<WatchedItem>, profile: ScoutProfile, backdrops: Map<String, String> = emptyMap(), lookedUp: Map<String, String> = emptyMap()): List<ScoutShapingTitle> {
    class Ranked(val id: String, val tv: Boolean, val name: String, val poster: String?, val at: Long, val score: Double)
    val watches = scoutWatchCounts(watched)
    val seen = watched.filter { it.contentId.isNotBlank() }.groupBy { it.contentId }.map { (id, items) ->
        val newest = items.maxBy { it.watchedAt }
        Ranked(id, newest.contentType.trim().lowercase() != "movie", newest.title, items.firstNotNullOfOrNull { it.poster }, newest.watchedAt, scoutWatchScore(profile.rating(id), watches.getValue(id)))
    }
    val ratedOnly = profile.ratings.values.filter { it.title.id !in watches }.map {
        Ranked(it.title.id, it.title.type.trim().lowercase() != "movie", it.title.name, null, it.ratedAt, scoutWatchScore(it.rating, 1))
    }
    return (seen + ratedOnly)
        .filter { it.score > 0 }
        .sortedWith(compareByDescending<Ranked> { it.score }.thenByDescending { it.at })
        .mapNotNull { title ->
            val poster = profile.ratings[title.id]?.title?.poster ?: title.poster ?: lookedUp[title.id] ?: backdrops[title.id] ?: return@mapNotNull null
            ScoutShapingTitle(title.id, if (title.tv) "series" else "movie", title.name, poster, profile.rating(title.id), backdrops[title.id])
        }
        .take(SHAPING_TITLES)
}

data class ScoutTasteState(
    val profile: ScoutProfile = ScoutProfile(),
    val summary: ScoutTasteSummary = ScoutTasteSummary(),
    val done: Int = 0,
    val total: Int = 0,
    val titlesUsed: Int = 0,
    val watchedCount: Int = 0,
    val shaping: List<ScoutShapingTitle> = emptyList(),
    val nudge: ScoutPick? = null
) {
    val working: Boolean get() = done < total
    val thumbs: Int get() = profile.ratings.size
    val loved: Int get() = profile.ratings.values.count { it.rating == ScoutRating.LOVE }
    val liked: Int get() = profile.ratings.values.count { it.rating == ScoutRating.LIKE }
    val notForMe: Int get() = profile.ratings.values.count { it.rating == ScoutRating.NOT_FOR_ME }
}

@HiltViewModel
class ScoutTasteViewModel @Inject constructor(
    private val store: ScoutStore,
    private val engine: ScoutEngine,
    private val taste: ScoutTasteRepository,
    private val watchProgress: WatchProgressRepository
) : ViewModel() {
    private val progress = MutableStateFlow(ScoutTasteProgress(emptyList(), 0, 0))
    private val skipped = MutableStateFlow<Set<String>>(emptySet())
    private val watched = MutableStateFlow<List<WatchedItem>>(emptyList())

    val state: StateFlow<ScoutTasteState> = combine(store.profile, engine.pool, progress, skipped, watched) { profile, pool, p, skip, items ->
        val distinct = items.filter { it.contentId.isNotBlank() }.distinctBy { it.contentId }
        val recent = distinct.sortedByDescending { it.watchedAt }.take(RECENT_TITLES).map { it.contentId }.toSet()
        ScoutTasteState(
            profile = profile,
            summary = scoutTasteSummary(p.titles, profile.ratings, recent, scoutWatchCounts(items)),
            done = p.done,
            total = p.total,
            titlesUsed = p.titles.size,
            watchedCount = distinct.size,
            shaping = scoutShapingTitles(items, profile, p.titles.mapNotNull { title -> title.backdrop?.let { title.contentId to it } }.toMap(), p.titles.mapNotNull { title -> title.poster?.let { title.contentId to it } }.toMap()),
            nudge = scoutNudge(pool, profile, skip)
        )
    }.flowOn(Dispatchers.Default).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScoutTasteState())

    init {
        viewModelScope.launch { engine.refreshIfDue() }
        viewModelScope.launch {
            watched.value = withContext(Dispatchers.Default) { watchProgress.tasteHistory.first() }
            taste.load { progress.value = it }
        }
    }

    fun rate(pick: ScoutPick, rating: ScoutRating) {
        viewModelScope.launch { store.setRating(pick.toTitle(), rating) }
    }

    fun setRating(title: ScoutTitle, rating: ScoutRating?) {
        viewModelScope.launch { store.setRating(title, rating) }
    }

    fun showRow(row: String) {
        viewModelScope.launch { store.update { it.copy(tasteRow = row) } }
    }

    fun skip(pick: ScoutPick) {
        skipped.value = skipped.value + pick.key
    }

    fun leaveOut(genre: Int) {
        viewModelScope.launch {
            store.update { it.copy(leaveOutGenres = it.leaveOutGenres + scoutGenreLeaveOutIds(genre)) }
        }
    }

    fun startFresh() {
        viewModelScope.launch {
            store.clearRatings()
            skipped.value = emptySet()
            engine.reset()
            engine.refreshIfDue()
        }
    }

    private fun ScoutPick.toTitle() = ScoutTitle("tmdb:$tmdbId", if (tv) "series" else "movie", name, poster)
}
