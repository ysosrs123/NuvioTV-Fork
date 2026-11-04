package com.nuvio.tv.data.wetrakr

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class WeTrakrTarget(
    val type: WeTrakrType,
    val requestIds: JsonObject,
    val ids: WeTrakrIds,
    val title: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val number: Int? = null,
    val animeOnly: Boolean = false
) {
    val sessionKey: String
        get() = "$type:${requestIds}:${season ?: -1}:${number ?: -1}"

    fun scrobbleBody(progress: Double, appVersion: String): JsonObject = buildJsonObject {
        val media = buildJsonObject {
            put("ids", requestIds)
            title?.let { put("title", it) }
            year?.let { put("year", it) }
        }
        if (type == WeTrakrType.MOVIE) {
            put("movie", media)
        } else {
            put("show", media)
            put("episode", buildJsonObject {
                if (!animeOnly) season?.let { put("season", it) }
                put("number", requireNotNull(number))
            })
        }
        put("progress", BigDecimal.valueOf(progress.coerceIn(0.0, 100.0)).setScale(2, RoundingMode.DOWN).toDouble())
        put("app_version", appVersion)
    }
}

internal fun TrackingMediaReference.toWeTrakrTarget(): WeTrakrTarget? {
    val requestIds = ids.toWeTrakrRequestIds() ?: return null
    val movie = kind == TrackingMediaKind.MOVIE || catalog?.contentType?.lowercase() in setOf("movie", "film")
    val episode = episode.takeUnless { movie }
    if (!movie && episode == null) {
        return WeTrakrTarget(WeTrakrType.SHOW, requestIds, ids.toWeTrakrIds(), title, year)
    }
    if (episode != null && (episode.number < 1 || (episode.season ?: 1) < 0)) return null
    val animeOnly = ids.imdb == null && ids.tmdb == null && ids.tvdb == null
    if (episode != null && episode.season == null && !animeOnly) return null
    return WeTrakrTarget(
        type = if (movie) WeTrakrType.MOVIE else WeTrakrType.EPISODE,
        requestIds = requestIds,
        ids = ids.toWeTrakrIds(),
        title = title,
        year = year,
        season = episode?.season,
        number = episode?.number,
        animeOnly = animeOnly
    )
}

internal fun TrackingExternalIds.toWeTrakrRequestIds(): JsonObject? = buildJsonObject {
    imdb?.takeIf { it.matches(Regex("tt\\d{5,12}")) }?.let { put("imdb", it) }
    tmdb?.takeIf { it > 0L }?.let { put("tmdb", it) }
    tvdb?.toLongOrNull()?.takeIf { it > 0L }?.let { put("tvdb", it) }
    trakt?.takeIf { it > 0L }?.let { put("trakt", it) }
    simkl?.takeIf { it > 0L }?.let { put("simkl", it) }
    mal?.takeIf { it > 0L }?.let { put("myanimelist", it) }
    anilist?.takeIf { it > 0L }?.let { put("anilist", it) }
    kitsu?.takeIf { it > 0L }?.let { put("kitsu", it) }
    anidb?.takeIf { it > 0L }?.let { put("anidb", it) }
}.takeIf { it.isNotEmpty() }

internal fun TrackingExternalIds.toWeTrakrIds() = WeTrakrIds(
    imdb = imdb?.takeIf { it.matches(Regex("tt\\d{5,12}")) },
    tmdb = tmdb?.takeIf { it > 0L },
    tvdb = tvdb?.toLongOrNull()?.takeIf { it > 0L }
)

internal fun WeTrakrSnapshot.withLocalPlay(target: WeTrakrTarget, at: Long): WeTrakrSnapshot {
    if (target.ids.isEmpty) return this
    val stamp = Instant.ofEpochMilli(at).toString()
    val pending = pendingPlays + WeTrakrPendingPlay(target.type, target.ids, target.season, target.number, at)
    return when (target.type) {
        WeTrakrType.MOVIE -> {
            val current = watchedMovies.firstOrNull { it.ids.matches(target.ids) }
            copy(
                watchedMovies = watchedMovies.filterNot { it === current } +
                    WeTrakrWatchedMovie(current?.ids?.merged(target.ids) ?: target.ids, stamp, (current?.plays ?: 0) + 1),
                playback = playback.filterNot { it.type == WeTrakrType.MOVIE && it.title.ids.matches(target.ids) },
                pendingPlays = pending
            )
        }
        WeTrakrType.EPISODE -> {
            val season = target.season ?: return this
            val number = target.number ?: return this
            val current = watchedEpisodes.firstOrNull {
                it.showIds.matches(target.ids) && it.season == season && it.number == number
            }
            val showId = current?.showId ?: watchedEpisodes.firstOrNull { it.showIds.matches(target.ids) }?.showId
            copy(
                watchedEpisodes = watchedEpisodes.filterNot { it === current } + WeTrakrWatchedEpisode(
                    showId = showId,
                    showIds = current?.showIds?.merged(target.ids) ?: target.ids,
                    season = season,
                    number = number,
                    episodeId = current?.episodeId,
                    lastWatchedAt = stamp,
                    plays = (current?.plays ?: 0) + 1
                ),
                playback = playback.filterNot {
                    it.type == WeTrakrType.EPISODE && it.title.ids.matches(target.ids) && it.season == season && it.number == number
                },
                pendingPlays = pending
            )
        }
        WeTrakrType.SHOW -> this
    }
}

internal fun WeTrakrSnapshot.withLocalProgress(target: WeTrakrTarget, progress: Float, at: Long): WeTrakrSnapshot {
    if (target.ids.isEmpty || target.type == WeTrakrType.SHOW) return this
    val matches = { session: WeTrakrPlayback ->
        session.type == target.type && session.title.ids.matches(target.ids) &&
            session.season == target.season && session.number == target.number
    }
    val current = playback.firstOrNull(matches)
    val title = current?.title ?: WeTrakrTitle(
        type = if (target.type == WeTrakrType.MOVIE) WeTrakrType.MOVIE else WeTrakrType.SHOW,
        ids = target.ids,
        title = target.title,
        year = target.year
    )
    val session = WeTrakrPlayback(
        type = target.type,
        title = title,
        progress = progress.coerceIn(0f, 100f),
        updatedAt = Instant.ofEpochMilli(at).toString(),
        season = target.season,
        number = target.number,
        episodeId = current?.episodeId,
        episodeTitle = current?.episodeTitle,
        runtimeSeconds = current?.runtimeSeconds
    )
    return copy(playback = playback.filterNot(matches) + session)
}

internal fun WeTrakrSnapshot.withoutPlays(target: WeTrakrTarget): WeTrakrSnapshot = when (target.type) {
    WeTrakrType.MOVIE -> copy(
        watchedMovies = watchedMovies.filterNot { it.ids.matches(target.ids) },
        lists = lists + (WeTrakrList.WATCHED to list(WeTrakrList.WATCHED).filterNot {
            it.title.type == WeTrakrType.MOVIE && it.title.ids.matches(target.ids)
        }),
        pendingPlays = pendingPlays.filterNot { it.type == WeTrakrType.MOVIE && it.ids.matches(target.ids) }
    )
    WeTrakrType.EPISODE -> copy(
        watchedEpisodes = watchedEpisodes.filterNot {
            it.showIds.matches(target.ids) && it.season == target.season && it.number == target.number
        },
        pendingPlays = pendingPlays.filterNot {
            it.type == WeTrakrType.EPISODE && it.ids.matches(target.ids) && it.season == target.season && it.number == target.number
        }
    )
    WeTrakrType.SHOW -> this
}

internal fun trackingBody(
    targets: List<WeTrakrTarget>,
    status: String,
    trackedAt: (WeTrakrTarget) -> Long? = { null },
    allowRewatch: Boolean? = null
): JsonObject = buildJsonObject {
    val movies = targets.filter { it.type == WeTrakrType.MOVIE }
    val shows = targets.filter { it.type == WeTrakrType.SHOW }
    val episodes = targets.filter { it.type == WeTrakrType.EPISODE }
    if (movies.isNotEmpty()) putJsonArray("movies") {
        movies.forEach { movie ->
            add(buildJsonObject {
                put("ids", movie.requestIds)
                put("status", status)
                trackedAt(movie)?.let { put("tracked_at", Instant.ofEpochMilli(it).toString()) }
            })
        }
    }
    val showItems = shows.map { show ->
        buildJsonObject {
            put("ids", show.requestIds)
            put("status", status)
        }
    } + episodes.groupBy { it.requestIds }.map { (ids, items) ->
        buildJsonObject {
            put("ids", ids)
            put("status", status)
            put("seasons", buildJsonArray {
                items.groupBy { it.season ?: 1 }.forEach { (season, inSeason) ->
                    add(buildJsonObject {
                        put("number", season)
                        put("episodes", buildJsonArray {
                            inSeason.forEach { episode ->
                                add(buildJsonObject {
                                    put("number", requireNotNull(episode.number))
                                    trackedAt(episode)?.let { put("tracked_at", Instant.ofEpochMilli(it).toString()) }
                                })
                            }
                        })
                    })
                }
            })
        }
    }
    if (showItems.isNotEmpty()) put("shows", JsonArray(showItems))
    allowRewatch?.let { put("allow_rewatch", it) }
}

internal fun notFoundCount(body: String): Int = runCatching {
    val root = weTrakrJson(body).obj() ?: return@runCatching 0
    val missing = root.obj("not_found") ?: root.obj("notFound") ?: return@runCatching 0
    missing.values.sumOf { (it as? JsonArray)?.size ?: 0 }
}.getOrDefault(0)

class WeTrakrScrobbleService(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val shortPlayMs: Long = SHORT_PLAY_MS
) {
    private val lock = Mutex()
    private var pending: Pending? = null
    private var live: String? = null

    private class Pending(val key: String, val job: Job)

    suspend fun scrobble(authScope: WeTrakrAuthScope, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!event.progressPercent.isFinite()) return
        val target = event.media.toWeTrakrTarget()?.takeIf { it.type != WeTrakrType.SHOW } ?: return
        val progress = event.progressPercent.coerceIn(0.0, 100.0)
        val key = target.sessionKey
        val send: Boolean = lock.withLock {
            when (action) {
                TrackingScrobbleAction.START -> {
                    if (live == key) return@withLock true
                    pending?.job?.cancel()
                    live = null
                    val job = scope.launch {
                        delay(shortPlayMs)
                        val confirmed = lock.withLock {
                            if (pending?.key != key) false else {
                                pending = null
                                live = key
                                true
                            }
                        }
                        if (!confirmed) return@launch
                        val playedOn = event.durationSeconds?.takeIf { it > 0L }
                            ?.let { progress + shortPlayMs / 10.0 / it }
                            ?.coerceAtMost(100.0) ?: progress
                        try {
                            post(authScope, TrackingScrobbleAction.START, target, playedOn)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "wetrakr delayed start failed error=${error.javaClass.simpleName}")
                        }
                    }
                    pending = Pending(key, job)
                    false
                }
                TrackingScrobbleAction.PAUSE -> {
                    if (pending?.key == key) {
                        pending?.job?.cancel()
                        pending = null
                        false
                    } else live == key
                }
                TrackingScrobbleAction.STOP -> {
                    val wasLive = live == key
                    if (pending?.key == key) {
                        pending?.job?.cancel()
                        pending = null
                    }
                    if (wasLive) live = null
                    wasLive || progress >= WeTrakrProgressProjection.COMPLETION_PERCENT
                }
            }
        }
        if (send) post(authScope, action, target, progress)
    }

    private suspend fun post(authScope: WeTrakrAuthScope, action: TrackingScrobbleAction, target: WeTrakrTarget, progress: Double) {
        val response = api.post(
            "/scrobble/${action.wireValue}",
            target.scrobbleBody(progress, appVersion).toString(),
            authScope,
            query = mapOf("min_progress" to WeTrakrProgressProjection.COMPLETION_PERCENT.toInt().toString()),
            acceptedStatuses = setOf(404)
        )
        if (response.status == 404 || action != TrackingScrobbleAction.STOP) return
        val at = now()
        sync.mutate(authScope) { snapshot ->
            val next = if (action == TrackingScrobbleAction.STOP && response.status == 201) {
                snapshot.withLocalPlay(target, at)
            } else {
                snapshot.withLocalProgress(target, progress.toFloat(), at)
            }
            next to Unit
        }
    }

    suspend fun clear(authScope: WeTrakrAuthScope, contentId: String, season: Int?, episode: Int?) {
        sync.ensureLoaded()
        val sessions = sync.currentSnapshot()?.playback.orEmpty().filter { session ->
            contentId in session.title.ids.aliases() &&
                (season == null || episode == null || (session.season == season && session.number == episode))
        }
        for (session in sessions) {
            val body = buildJsonObject {
                val media = buildJsonObject { put("ids", session.title.ids.requestIds()) }
                if (session.type == WeTrakrType.MOVIE) {
                    put("movie", media)
                } else {
                    put("show", media)
                    put("episode", buildJsonObject {
                        session.season?.let { put("season", it) }
                        session.number?.let { put("number", it) }
                    })
                }
            }
            api.delete("/scrobble/playing", body.toString(), authScope, acceptedStatuses = setOf(404))
            sync.mutate(authScope) { snapshot -> snapshot.copy(playback = snapshot.playback.filterNot { it.key == session.key }) to Unit }
        }
    }

    companion object {
        const val SHORT_PLAY_MS = 60_000L
    }
}

internal fun WeTrakrIds.requestIds(): JsonObject = buildJsonObject {
    wetrakr?.let { put("wetrakr", it) }
    imdb?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
    tvdb?.let { put("tvdb", it) }
}

class WeTrakrHistoryService(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun add(authScope: WeTrakrAuthScope, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val targets = items.mapNotNull { item -> item.media.toWeTrakrTarget()?.takeIf { it.type != WeTrakrType.SHOW }?.let { it to item } }
        if (targets.isEmpty()) return TrackingMutationResult(items.size, items.size)
        val notFound = targets.chunked(BATCH).sumOf { chunk ->
            val body = trackingBody(chunk.map { it.first }, "watched", { target ->
                chunk.firstOrNull { it.first === target }?.second?.watchedAtEpochMs
            }, allowRewatch = false)
            notFoundCount(api.post("/sync/tracking", body.toString(), authScope).body)
        }
        val at = now()
        sync.mutate(authScope) { snapshot ->
            targets.fold(snapshot) { next, (target, item) ->
                val known = when (target.type) {
                    WeTrakrType.MOVIE -> next.watchedMovies.any { it.ids.matches(target.ids) }
                    else -> next.watchedEpisodes.any {
                        it.showIds.matches(target.ids) && it.season == target.season && it.number == target.number
                    }
                }
                if (known) next else next.withLocalPlay(target, item.watchedAtEpochMs ?: at)
            } to Unit
        }
        return TrackingMutationResult(items.size, notFound + (items.size - targets.size))
    }

    suspend fun remove(authScope: WeTrakrAuthScope, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val targets = items.mapNotNull { it.toWeTrakrTarget()?.takeIf { target -> target.type != WeTrakrType.SHOW } }
        if (targets.isEmpty()) return TrackingMutationResult(items.size, items.size)
        targets.chunked(BATCH).forEach { chunk ->
            api.post("/sync/tracking/remove/all", trackingBody(chunk, "watched").toString(), authScope)
        }
        sync.mutate(authScope) { snapshot -> targets.fold(snapshot) { next, target -> next.withoutPlays(target) } to Unit }
        return TrackingMutationResult(items.size, items.size - targets.size)
    }

    private companion object {
        const val BATCH = 1_000
    }
}

@javax.inject.Singleton
class WeTrakrPlanningBatch @javax.inject.Inject constructor(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository
) {
    suspend fun add(items: List<com.nuvio.tv.domain.model.LibraryEntryInput>): Int = write(items, "/sync/tracking") { snapshot, targets ->
        val added = targets.map { (target, item) ->
            WeTrakrListEntry(WeTrakrTitle(target.type, target.ids, item.title, item.year, item.poster), Instant.now().toString())
        }
        val kept = snapshot.list(WeTrakrList.PLANNING).filterNot { entry -> added.any { it.title.ids.matches(entry.title.ids) } }
        snapshot.copy(lists = snapshot.lists + (WeTrakrList.PLANNING to added + kept))
    }

    suspend fun remove(items: List<com.nuvio.tv.domain.model.LibraryEntryInput>): Int = write(items, "/sync/tracking/remove") { snapshot, targets ->
        snapshot.copy(lists = snapshot.lists + (WeTrakrList.PLANNING to snapshot.list(WeTrakrList.PLANNING).filterNot { entry ->
            targets.any { (target, _) -> target.type == entry.title.type && target.ids.matches(entry.title.ids) }
        }))
    }

    private suspend fun write(
        items: List<com.nuvio.tv.domain.model.LibraryEntryInput>,
        path: String,
        apply: (WeTrakrSnapshot, List<Pair<WeTrakrTarget, com.nuvio.tv.domain.model.LibraryEntryInput>>) -> WeTrakrSnapshot
    ): Int {
        sync.ensureLoaded()
        val scope = sync.currentScope()
        val targets = items.mapNotNull { item -> item.toWeTrakrTarget()?.takeUnless { it.ids.isEmpty }?.let { it to item } }
        if (targets.isEmpty()) return 0
        var missing = 0
        targets.chunked(1_000).forEach { chunk ->
            missing += notFoundCount(api.post(path, trackingBody(chunk.map { it.first }, WeTrakrList.PLANNING.wireValue).toString(), scope).body)
        }
        sync.mutate(scope) { snapshot -> apply(snapshot, targets) to Unit }
        return (targets.size - missing).coerceAtLeast(0)
    }
}
