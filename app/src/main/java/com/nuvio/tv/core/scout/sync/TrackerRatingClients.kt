package com.nuvio.tv.core.scout.sync

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.mdblist.MdbListApiClient
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingEntryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsRequestDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.simkl.SimklApiClient
import com.nuvio.tv.data.simkl.SimklApiException
import com.nuvio.tv.data.simkl.SimklApiRequest
import com.nuvio.tv.data.simkl.SimklHttpMethod
import com.nuvio.tv.data.simkl.SimklLibraryEntry
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklRetryPolicy
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.idValue
import com.nuvio.tv.data.wetrakr.WeTrakrApiClient
import com.nuvio.tv.data.wetrakr.WeTrakrSyncRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One tracker's ratings for the active profile. Every call throws on a failed request. */
interface TrackerRatingClient {
    val providerId: TrackingProviderId

    fun connected(): Boolean

    suspend fun ratings(): List<TrackerRating>

    suspend fun write(items: List<TrackerRatingWrite>)

    suspend fun remove(items: List<TrackerTitle>)

    /** Simkl files every rated title into a list, so it only gets titles already watched there. */
    suspend fun accepts(title: TrackerTitle): Boolean = true
}

class TrackerRateLimitedException(val retryAfterMs: Long?) : Exception("rate limited")

/** The tracker refused the item for good (bad id, unknown title); trying again would not help. */
class TrackerRejectedException(status: Int?) : Exception("rejected${status?.let { " ($it)" }.orEmpty()}")

internal fun isoMs(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun isoOf(ms: Long): String = Instant.ofEpochMilli(ms).toString()

private fun Int.statusFailure(retryAfterMs: Long? = null): Exception = when (this) {
    429 -> TrackerRateLimitedException(retryAfterMs)
    400, 404, 409, 422 -> TrackerRejectedException(this)
    else -> IllegalStateException("HTTP $this")
}

@Singleton
class TraktRatingClient @Inject constructor(
    private val api: TraktApi,
    private val auth: TraktAuthService,
    private val registry: TrackingProviderRegistry
) : TrackerRatingClient {
    override val providerId = TrackingProviderId.TRAKT

    override fun connected() = registry.isAuthenticated(providerId)

    override suspend fun ratings(): List<TrackerRating> = listOf("movies", "shows").flatMap { type ->
        val response = auth.executeAuthorizedRequest { api.getRatings(it, type) } ?: error("Trakt not reachable")
        if (!response.isSuccessful) throw response.code().statusFailure()
        response.body().orEmpty().mapNotNull { item ->
            val movie = type == "movies"
            val ids = (if (movie) item.movie?.ids else item.show?.ids) ?: return@mapNotNull null
            val name = if (movie) item.movie?.title else item.show?.title
            val title = TrackerTitle(movie, ids.imdb?.takeIf { it.startsWith("tt") }, ids.tmdb?.toLong())
            val value = item.rating ?: return@mapNotNull null
            title.takeIf { it.hasIds }?.let { TrackerRating(it, value.toDouble(), isoMs(item.ratedAt), name) }
        }
    }

    override suspend fun write(items: List<TrackerRatingWrite>) = send(items.map {
        it.title to TraktRatingEntryDto(it.title.traktIds(), it.value.toInt(), isoOf(it.ratedAtMs))
    }, remove = false)

    override suspend fun remove(items: List<TrackerTitle>) = send(items.map { it to TraktRatingEntryDto(it.traktIds()) }, remove = true)

    private suspend fun send(items: List<Pair<TrackerTitle, TraktRatingEntryDto>>, remove: Boolean) {
        if (items.isEmpty()) return
        val body = TraktRatingsRequestDto(
            movies = items.filter { it.first.movie }.map { it.second }.ifEmpty { null },
            shows = items.filterNot { it.first.movie }.map { it.second }.ifEmpty { null }
        )
        val response = auth.executeAuthorizedWriteRequest {
            if (remove) api.removeRatings(it, body) else api.addRatings(it, body)
        } ?: error("Trakt not reachable")
        if (!response.isSuccessful) throw response.code().statusFailure()
    }

    private fun TrackerTitle.traktIds() = TraktIdsDto(imdb = imdb, tmdb = tmdb?.toInt())
}

@Singleton
class SimklRatingClient @Inject constructor(
    private val client: SimklApiClient,
    private val sync: SimklSyncRepository,
    private val registry: TrackingProviderRegistry
) : TrackerRatingClient {
    override val providerId = TrackingProviderId.SIMKL

    override fun connected() = registry.isAuthenticated(providerId)

    override suspend fun ratings(): List<TrackerRating> {
        sync.ensureLoaded()
        return sync.state.value.snapshot.entries.mapNotNull { entry ->
            val value = entry.userRating ?: return@mapNotNull null
            entry.trackerTitle()?.let { TrackerRating(it, value.toDouble(), isoMs(entry.userRatedAt), entry.media?.title) }
        }
    }

    override suspend fun accepts(title: TrackerTitle): Boolean {
        sync.ensureLoaded()
        val keys = title.keys.toSet()
        return sync.state.value.snapshot.entries.any { entry ->
            entry.trackerTitle()?.keys?.any(keys::contains) == true && entry.watchedSomething()
        }
    }

    override suspend fun write(items: List<TrackerRatingWrite>) =
        send("/sync/ratings", ratingsBody(items.map { Triple(it.title, it.value, it.ratedAtMs) }))

    override suspend fun remove(items: List<TrackerTitle>) =
        send("/sync/ratings/remove", ratingsBody(items.map { Triple(it, null, null) }))

    private suspend fun send(path: String, body: JsonObject) {
        if (body.isEmpty()) return
        val response = try {
            client.execute(SimklApiRequest(SimklHttpMethod.POST, path, body = body.toString(), retryPolicy = SimklRetryPolicy.SYNC_WRITE))
        } catch (error: SimklApiException) {
            throw error.status?.statusFailure() ?: error
        }
        if (response.status !in 200..299) throw response.status.statusFailure()
        sync.refreshAsync(TrackingRefreshIntent.INVALIDATED)
    }

    private fun SimklLibraryEntry.trackerTitle(): TrackerTitle? {
        val ids = media?.ids ?: return null
        return TrackerTitle(
            isMovieEntry(),
            ids.idValue("imdb")?.takeIf { it.startsWith("tt") },
            ids.idValue("tmdb")?.toLongOrNull()
        ).takeIf { it.hasIds }
    }

    private fun SimklLibraryEntry.watchedSomething(): Boolean =
        status == SimklListStatus.COMPLETED || watchedEpisodesCount > 0 || !lastWatchedAt.isNullOrBlank()
}

@Singleton
class MdbListRatingClient @Inject constructor(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    private val registry: TrackingProviderRegistry
) : TrackerRatingClient {
    override val providerId = TrackingProviderId.MDBLIST
    private val json = Json { ignoreUnknownKeys = true }

    override fun connected() = registry.isAuthenticated(providerId)

    override suspend fun ratings(): List<TrackerRating> {
        val scope = sync.currentScope()
        val out = ArrayList<TrackerRating>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val query = buildMap {
                put("limit", "1000")
                cursor?.let { put("cursor", it) }
            }
            val body = mdbCall { api.get("/sync/ratings", query, scope).body }
            val page = json.parseToJsonElement(body) as? JsonObject ?: return out
            out += parseTrackerRatings(page)
            cursor = ((page["pagination"] as? JsonObject)?.get("next_cursor") as? JsonPrimitive)?.contentOrNull
            if (cursor.isNullOrBlank()) return out
        }
        return out
    }

    override suspend fun write(items: List<TrackerRatingWrite>) {
        if (items.isEmpty()) return
        val scope = sync.currentScope()
        mdbCall { api.post("/sync/ratings", ratingsBody(items.map { Triple(it.title, it.value, it.ratedAtMs) }).toString(), scope) }
    }

    override suspend fun remove(items: List<TrackerTitle>) {
        if (items.isEmpty()) return
        val scope = sync.currentScope()
        mdbCall { api.post("/sync/ratings/remove", ratingsBody(items.map { Triple(it, null, null) }).toString(), scope) }
    }

    private suspend fun <T> mdbCall(block: suspend () -> T): T = try {
        block()
    } catch (error: com.nuvio.tv.data.mdblist.MdbListApiException) {
        val status = error.status ?: throw error
        throw status.statusFailure(error.retryAtEpochMs?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) })
    }

    private companion object {
        const val MAX_PAGES = 50
    }
}

@Singleton
class WeTrakrRatingClient @Inject constructor(
    private val api: WeTrakrApiClient,
    private val sync: WeTrakrSyncRepository,
    private val registry: TrackingProviderRegistry
) : TrackerRatingClient {
    override val providerId = TrackingProviderId.WETRAKR
    private val json = Json { ignoreUnknownKeys = true }

    override fun connected() = registry.isAuthenticated(providerId)

    override suspend fun ratings(): List<TrackerRating> {
        val scope = sync.currentScope()
        return listOf("movies", "shows").flatMap { target ->
            val body = weCall { api.get("/sync/ratings/$target", scope = scope).body }
            when (val parsed = json.parseToJsonElement(body)) {
                is JsonObject -> parseTrackerRatings(parsed)
                is JsonArray -> parseTrackerRatingList(parsed, target == "movies")
                else -> emptyList()
            }
        }
    }

    override suspend fun write(items: List<TrackerRatingWrite>) {
        if (items.isEmpty()) return
        val scope = sync.currentScope()
        weCall { api.post("/sync/ratings", ratingsBody(items.map { Triple(it.title, it.value, null) }).toString(), scope) }
    }

    override suspend fun remove(items: List<TrackerTitle>) {
        if (items.isEmpty()) return
        val scope = sync.currentScope()
        weCall { api.post("/sync/ratings/remove", ratingsBody(items.map { Triple(it, null, null) }).toString(), scope) }
    }

    private suspend fun <T> weCall(block: suspend () -> T): T = try {
        block()
    } catch (error: com.nuvio.tv.data.wetrakr.WeTrakrApiException) {
        throw error.status?.statusFailure() ?: error
    }
}

internal fun TrackerTitle.idsJson(): JsonObject = buildJsonObject {
    imdb?.let { put("imdb", it) }
    tmdb?.let { put("tmdb", it) }
}

/** `{"movies":[{"ids":{...},"rating":7,"rated_at":"..."}],"shows":[...]}`; no rating means a removal. */
internal fun ratingsBody(items: List<Triple<TrackerTitle, Double?, Long?>>): JsonObject = buildJsonObject {
    listOf(true to "movies", false to "shows").forEach { (movie, key) ->
        val group = items.filter { it.first.movie == movie }
        if (group.isNotEmpty()) put(key, buildJsonArray {
            group.forEach { (title, value, at) ->
                add(buildJsonObject {
                    put("ids", title.idsJson())
                    value?.let { put("rating", if (it % 1.0 == 0.0) JsonPrimitive(it.toInt()) else JsonPrimitive(it)) }
                    at?.let { put("rated_at", isoOf(it)) }
                })
            }
        })
    }
}

/** Reads `movies` and `shows` arrays from a ratings answer. */
internal fun parseTrackerRatings(page: JsonObject): List<TrackerRating> =
    listOf(true to "movies", false to "shows").flatMap { (movie, key) ->
        (page[key] as? JsonArray)?.let { parseTrackerRatingList(it, movie) }.orEmpty()
    }

/**
 * One rated item, in any of the shapes the trackers use: ids nested under `movie`/`show`, an `ids` object on the
 * item, or flat `imdb`/`tmdb` fields. `rating_precise` wins over the rounded `rating`.
 */
internal fun parseTrackerRatingList(items: JsonArray, movie: Boolean): List<TrackerRating> = items.mapNotNull { element ->
    val item = element as? JsonObject ?: return@mapNotNull null
    val media = (item[if (movie) "movie" else "show"] as? JsonObject) ?: item
    val ids = (media["ids"] as? JsonObject) ?: media
    val imdb = (ids["imdb"] ?: ids["imdb_id"]).text()?.takeIf { it.startsWith("tt") }
    val tmdb = (ids["tmdb"] ?: ids["tmdb_id"]).number()?.toLong()?.takeIf { it > 0 }
    val title = TrackerTitle(movie, imdb, tmdb).takeIf { it.hasIds } ?: return@mapNotNull null
    val value = (item["rating_precise"] ?: item["rating"]).number() ?: return@mapNotNull null
    TrackerRating(title, value, isoMs(item["rated_at"].text()), (media["title"] ?: media["name"]).text())
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }
