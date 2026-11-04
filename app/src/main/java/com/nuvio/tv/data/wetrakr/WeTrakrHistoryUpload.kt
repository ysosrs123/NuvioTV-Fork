package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.trackingMediaKind
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class WeTrakrUploadPlan(
    internal val watched: List<Pair<WeTrakrTarget, Long>>,
    internal val library: List<WeTrakrTarget>
) {
    val watchedCount: Int get() = watched.size
    val libraryCount: Int get() = library.size
}

data class WeTrakrUploadResult(val added: Int, val alreadyThere: Int, val notFound: Int)

@Singleton
class WeTrakrHistoryUpload @Inject constructor(
    private val watchedItems: WatchedItemsPreferences,
    private val library: LibraryPreferences,
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository
) {
    suspend fun plan(): WeTrakrUploadPlan {
        sync.ensureLoaded()
        return weTrakrUploadPlan(watchedItems.getAllItems(), library.getAllItems(), sync.currentSnapshot())
    }

    suspend fun send(plan: WeTrakrUploadPlan): WeTrakrUploadResult {
        sync.ensureLoaded()
        val scope = sync.currentScope()
        var sent = 0
        var errored = 0
        var notFound = 0
        plan.watched.chunked(BATCH).forEach { chunk ->
            val stamps = chunk.associate { (target, at) -> target to at }
            val body = trackingBody(chunk.map { it.first }, "watched", { stamps[it] }, allowRewatch = false)
            val response = api.post("/sync/tracking", body.toString(), scope)
            sent += chunk.size
            errored += countEntries(response.body, "errored")
            notFound += notFoundCount(response.body)
        }
        plan.library.chunked(BATCH).forEach { chunk ->
            val response = api.post("/sync/tracking", trackingBody(chunk, WeTrakrList.PLANNING.wireValue).toString(), scope)
            sent += chunk.size
            errored += countEntries(response.body, "existing")
            notFound += notFoundCount(response.body)
        }
        sync.invalidate(scope)
        sync.refreshAsync(com.nuvio.tv.core.tracking.TrackingRefreshIntent.INVALIDATED)
        return WeTrakrUploadResult((sent - errored - notFound).coerceAtLeast(0), errored, notFound)
    }

    private fun countEntries(body: String, field: String): Int = runCatching {
        when (val value = weTrakrJson(body).obj()?.get(field)) {
            is JsonArray -> value.size
            is JsonObject -> value.values.sumOf { (it as? JsonArray)?.size ?: 0 }
            else -> 0
        }
    }.getOrDefault(0)

    private companion object {
        const val BATCH = 1_000
    }
}

internal fun weTrakrUploadPlan(
    watched: List<WatchedItem>,
    saved: List<SavedLibraryItem>,
    snapshot: WeTrakrSnapshot?
): WeTrakrUploadPlan {
    val known = buildList {
        snapshot?.lists?.values?.forEach { entries -> entries.forEach { add(it.title.ids) } }
        snapshot?.watchedMovies?.forEach { add(it.ids) }
        snapshot?.watchedEpisodes?.forEach { add(it.showIds) }
    }
    val watchedTargets = watched.mapNotNull { item ->
        val ids = parseTrackingExternalIds(item.contentId)
        val reference = TrackingMediaReference(
            kind = trackingMediaKind(item.contentType, ids),
            title = item.title,
            ids = ids,
            episode = item.episode?.let { TrackingEpisode(season = item.season, number = it) }
        )
        val target = reference.toWeTrakrTarget()?.takeIf { it.type != WeTrakrType.SHOW && !it.ids.isEmpty } ?: return@mapNotNull null
        target to item.watchedAt
    }.distinctBy { it.first.sessionKey }
    val watchedMovies = watchedTargets.filter { it.first.type == WeTrakrType.MOVIE }.map { it.first.ids }
    val libraryTargets = saved.mapNotNull { item ->
        val ids = parseTrackingExternalIds(item.id)
        val movie = item.type.lowercase() in setOf("movie", "film")
        val requestIds = ids.toWeTrakrRequestIds() ?: return@mapNotNull null
        val weTrakrIds = ids.toWeTrakrIds().takeUnless { it.isEmpty } ?: return@mapNotNull null
        if (movie && watchedMovies.any { it.matches(weTrakrIds) }) return@mapNotNull null
        if (known.any { it.matches(weTrakrIds) }) return@mapNotNull null
        WeTrakrTarget(if (movie) WeTrakrType.MOVIE else WeTrakrType.SHOW, requestIds, weTrakrIds, item.name)
    }.distinctBy { it.sessionKey }
    return WeTrakrUploadPlan(watchedTargets, libraryTargets)
}
