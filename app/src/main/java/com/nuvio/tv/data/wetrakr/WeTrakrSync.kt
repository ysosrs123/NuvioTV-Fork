package com.nuvio.tv.data.wetrakr

import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

interface WeTrakrSyncRemote {
    suspend fun activities(): WeTrakrActivities
    suspend fun watchedMovies(): List<WeTrakrWatchedMovie>
    suspend fun watchedEpisodes(): List<WeTrakrWatchedEpisode>
    suspend fun list(list: WeTrakrList, target: String, since: String? = null): List<WeTrakrListEntry>
    suspend fun playback(): List<WeTrakrPlayback>
    suspend fun journal(since: String): WeTrakrJournalPage
    suspend fun showIds(showId: Long): WeTrakrIds?
    suspend fun customLists(): List<WeTrakrCustomList> = emptyList()

    /** Null when the list can't be read right now, for example locked by the plan quota. */
    suspend fun customListItems(listId: Long): List<WeTrakrListEntry>? = emptyList()
}

class WeTrakrHttpSyncRemote(
    private val api: WeTrakrApiClient,
    private val scope: WeTrakrAuthScope
) : WeTrakrSyncRemote {
    override suspend fun activities(): WeTrakrActivities =
        decodeWeTrakrActivities(api.get("/sync/last_activities", scope = scope).body)

    override suspend fun watchedMovies(): List<WeTrakrWatchedMovie> =
        decodeWeTrakrWatchedMovies(compact("/sync/tracking/watched/movies"))

    override suspend fun watchedEpisodes(): List<WeTrakrWatchedEpisode> =
        decodeWeTrakrWatchedEpisodes(compact("/sync/tracking/watched/episodes"))

    override suspend fun list(list: WeTrakrList, target: String, since: String?): List<WeTrakrListEntry> {
        val type = if (target == "movies") WeTrakrType.MOVIE else WeTrakrType.SHOW
        val query = buildMap {
            put("extended", if (type == WeTrakrType.MOVIE) "movie_level_1" else "show_level_1")
            since?.let { put("from_date", it) }
        }
        return decodeWeTrakrListEntries(paged("/sync/tracking/${list.wireValue}/$target", query), type)
    }

    override suspend fun playback(): List<WeTrakrPlayback> =
        decodeWeTrakrPlayback(paged("/sync/tracking/playing/movies", mapOf("extended" to "movie_level_1")), WeTrakrType.MOVIE) +
            decodeWeTrakrPlayback(
                paged("/sync/tracking/playing/episodes", mapOf("extended" to "show_level_1,episode_level_1")),
                WeTrakrType.EPISODE
            )

    override suspend fun journal(since: String): WeTrakrJournalPage {
        val entries = mutableListOf<WeTrakrJournalEntry>()
        var visibleUntil: String? = null
        for (page in 1..MAX_PAGES) {
            val response = api.get(
                "/sync/journal",
                mapOf("from_date" to since, "limit" to "1000", "page" to page.toString()),
                scope,
                acceptedStatuses = setOf(400)
            )
            if (response.status == 400) {
                if (response.errorCode() == "JOURNAL_EXPIRED") return WeTrakrJournalPage(emptyList(), null, expired = true)
                throw WeTrakrApiException(400, response.errorCode())
            }
            val decoded = decodeWeTrakrJournal(response.body)
            entries += decoded.entries
            if (page == 1) visibleUntil = decoded.visibleUntil
            if (!hasMorePages(response, page)) return WeTrakrJournalPage(entries, visibleUntil)
        }
        throw WeTrakrDecodingException()
    }

    override suspend fun showIds(showId: Long): WeTrakrIds? {
        val response = api.get("/shows/$showId", scope = scope, acceptedStatuses = setOf(404))
        if (response.status == 404) return null
        val root = weTrakrJson(response.body).obj() ?: throw WeTrakrDecodingException()
        return decodeWeTrakrIds(root["ids"], root.long("id") ?: showId).takeUnless { it.isEmpty }
    }

    override suspend fun customLists(): List<WeTrakrCustomList> =
        decodeWeTrakrCustomLists(api.get("/sync/lists", scope = scope).body)

    override suspend fun customListItems(listId: Long): List<WeTrakrListEntry>? {
        val entries = mutableListOf<WeTrakrListEntry>()
        var page = 1
        while (entries.size < MAX_CUSTOM_LIST_ITEMS) {
            val response = api.get(
                "/sync/lists/$listId/items",
                mapOf("extended" to "movie_level_1,show_level_1", "limit" to "100", "page" to page.toString()),
                scope,
                acceptedStatuses = setOf(404, 420)
            )
            if (response.status != 200) return null
            entries += decodeWeTrakrCustomListItems(weTrakrJson(response.body).array())
            if (!hasMorePages(response, page)) break
            page += 1
        }
        return entries.take(MAX_CUSTOM_LIST_ITEMS)
    }

    private suspend fun compact(path: String): List<kotlinx.serialization.json.JsonObject> {
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val query = buildMap {
                put("compact", "true")
                put("limit", "5000")
                cursor?.let { put("after", it) }
            }
            val response = api.get(path, query, scope)
            rows += weTrakrJson(response.body).array()
            cursor = response.header("X-Pagination-Next")?.trim()?.takeIf(String::isNotEmpty) ?: return rows
            if (!seen.add(cursor)) throw WeTrakrDecodingException()
        }
        throw WeTrakrDecodingException()
    }

    private suspend fun paged(path: String, query: Map<String, String>): List<kotlinx.serialization.json.JsonObject> {
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        for (page in 1..MAX_PAGES) {
            val response = api.get(path, query + mapOf("limit" to "100", "page" to page.toString()), scope)
            rows += weTrakrJson(response.body).array()
            if (!hasMorePages(response, page)) return rows
        }
        throw WeTrakrDecodingException()
    }

    private fun hasMorePages(response: WeTrakrHttpResponse, page: Int): Boolean {
        val count = response.header("X-Pagination-Page-Count")?.trim()?.toIntOrNull() ?: return false
        return page < count
    }

    private companion object {
        const val MAX_PAGES = 500
        const val MAX_CUSTOM_LIST_ITEMS = 300
    }
}

class WeTrakrSyncEngine(
    private val remote: WeTrakrSyncRemote,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun synchronize(previous: WeTrakrSnapshot): WeTrakrSnapshot {
        val activities = remote.activities()
        return withCustomLists(trackingSync(previous, activities), previous, activities)
    }

    private suspend fun trackingSync(previous: WeTrakrSnapshot, activities: WeTrakrActivities): WeTrakrSnapshot {
        val mark = previous.journalMark
        if (!previous.isInitialized || previous.invalidated || mark == null) return fullSync(previous, activities)
        val unchanged = activities.stamps["all"] != null && activities.stamps["all"] == previous.activities["all"]
        if (unchanged) return previous.copy(checkedAtEpochMs = now(), activities = activities.stamps)
        val journal = remote.journal(overlap(mark))
        if (journal.expired) return fullSync(previous, activities)
        val fresh = journal.entries.filter { it.entryId !in previous.appliedEntryIds }
        var next = applyWeTrakrJournal(previous, fresh) { remote.showIds(it) }
        for (list in WeTrakrList.entries) {
            if (!activities.changed(previous.activities, list.stampKeys())) continue
            val changed = list.targets.flatMap { remote.list(list, it, overlap(mark)) }
            next = next.withListChanges(list, changed)
        }
        if (activities.changed(previous.activities, WeTrakrActivities.PLAYBACK_KEYS)) {
            next = next.copy(playback = remote.playback())
        }
        val newMark = journal.visibleUntil ?: activities.journalVisibleUntil ?: mark
        val pendingCutoff = now() - PENDING_PLAY_MS
        return next.copy(
            pendingPlays = next.pendingPlays.filter { it.atEpochMs >= pendingCutoff },
            activities = activities.stamps,
            journalMark = newMark,
            appliedEntryIds = journal.entries.mapTo(linkedSetOf()) { it.entryId },
            checkedAtEpochMs = now()
        )
    }

    private suspend fun fullSync(previous: WeTrakrSnapshot, activities: WeTrakrActivities): WeTrakrSnapshot {
        val watchedMovies = remote.watchedMovies()
        val watchedEpisodes = remote.watchedEpisodes()
        val lists = WeTrakrList.entries.associateWith { list -> list.targets.flatMap { remote.list(list, it) } }
        val playback = remote.playback()
        val showIds = watchedEpisodes.mapNotNull { episode -> episode.showId?.let { it to episode.showIds } }.toMap()
        return previous.copy(
            watchedMovies = watchedMovies,
            watchedEpisodes = watchedEpisodes,
            playback = playback,
            lists = lists,
            showIds = previous.showIds + showIds,
            activities = activities.stamps,
            journalMark = activities.journalVisibleUntil ?: Instant.ofEpochMilli(now() - JOURNAL_HOLD_BACK_MS).toString(),
            appliedEntryIds = emptySet(),
            pendingPlays = emptyList(),
            checkedAtEpochMs = now(),
            isInitialized = true,
            invalidated = false
        )
    }

    /**
     * Lists are read when their stamp moved, once a day in case a dynamic list refilled itself, and on the
     * first sync after an update. Only a list that changed downloads its items again. A failure here keeps
     * the lists already held and is tried again next sync, so it never stops the tracking sync.
     */
    private suspend fun withCustomLists(
        snapshot: WeTrakrSnapshot,
        previous: WeTrakrSnapshot,
        activities: WeTrakrActivities
    ): WeTrakrSnapshot {
        val checked = snapshot.customListsCheckedAtEpochMs
        val due = checked == null || now() - checked >= CUSTOM_LISTS_RECHECK_MS ||
            activities.changed(previous.activities, WeTrakrActivities.CUSTOM_LIST_KEYS)
        if (!due) return snapshot
        val known = snapshot.customLists.associateBy { it.id }
        val lists = try {
            remote.customLists().map { list ->
                val held = known[list.id]
                if (held != null && list.updatedAt != null && held.updatedAt == list.updatedAt) {
                    list.copy(items = held.items)
                } else {
                    remote.customListItems(list.id)?.let { list.copy(items = it) }
                        ?: list.copy(items = held?.items.orEmpty(), updatedAt = null)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WeTrakrAccountChangedException) {
            throw error
        } catch (error: Exception) {
            return snapshot
        }
        return snapshot.copy(customLists = lists, customListsCheckedAtEpochMs = now())
    }

    private fun overlap(mark: String): String {
        val time = weTrakrTimestamp(mark).takeIf { it > 0L } ?: return mark
        return Instant.ofEpochMilli(time - JOURNAL_HOLD_BACK_MS).toString()
    }

    private companion object {
        const val JOURNAL_HOLD_BACK_MS = 5_000L
        const val PENDING_PLAY_MS = 30L * 60 * 1_000
        const val CUSTOM_LISTS_RECHECK_MS = 24L * 60 * 60 * 1_000
    }
}

internal suspend fun applyWeTrakrJournal(
    snapshot: WeTrakrSnapshot,
    entries: List<WeTrakrJournalEntry>,
    resolveShow: suspend (Long) -> WeTrakrIds?
): WeTrakrSnapshot {
    if (entries.isEmpty()) return snapshot
    val movies = snapshot.watchedMovies.associateByTo(linkedMapOf()) { it.ids.key }
    val episodes = snapshot.watchedEpisodes.associateByTo(linkedMapOf()) { it.key }
    val showIds = snapshot.showIds.toMutableMap()
    snapshot.watchedEpisodes.forEach { episode -> episode.showId?.let { showIds.putIfAbsent(it, episode.showIds) } }
    val lists = snapshot.lists.mapValuesTo(linkedMapOf()) { it.value }
    val pending = snapshot.pendingPlays.toMutableList()
    fun consumePending(type: WeTrakrType, ids: WeTrakrIds, season: Int? = null, number: Int? = null): Boolean {
        val index = pending.indexOfFirst { it.type == type && it.ids.matches(ids) && it.season == season && it.number == number }
        if (index < 0) return false
        pending.removeAt(index)
        return true
    }
    for (entry in entries.sortedWith(compareBy({ weTrakrTimestamp(it.actionAt) }, { it.entryId }))) {
        when {
            entry.category == "watched" && entry.type == WeTrakrType.MOVIE -> {
                val existing = movies.entries.firstOrNull { it.value.ids.matches(entry.ids) }
                when (entry.status) {
                    "added" -> {
                        val at = entry.watchedAt ?: entry.actionAt
                        val current = existing?.value
                        if (existing != null && consumePending(WeTrakrType.MOVIE, entry.ids)) {
                            movies[existing.key] = existing.value.copy(ids = existing.value.ids.merged(entry.ids))
                            continue
                        }
                        movies.remove(existing?.key)
                        val ids = current?.ids?.merged(entry.ids) ?: entry.ids
                        if (!ids.isEmpty) movies[ids.key] = WeTrakrWatchedMovie(
                            ids = ids,
                            lastWatchedAt = latest(current?.lastWatchedAt, at),
                            plays = (current?.plays ?: 0) + 1
                        )
                    }
                    "removed" -> existing?.let { (key, movie) ->
                        if (movie.plays <= 1) {
                            movies.remove(key)
                            lists[WeTrakrList.WATCHED] = lists[WeTrakrList.WATCHED].orEmpty()
                                .filterNot { it.title.type == WeTrakrType.MOVIE && it.title.ids.matches(movie.ids) }
                        } else {
                            movies[key] = movie.copy(plays = movie.plays - 1)
                        }
                    }
                }
            }
            entry.category == "watched" && entry.type == WeTrakrType.EPISODE -> {
                val showId = entry.showId ?: continue
                val season = entry.season ?: continue
                val number = entry.number ?: continue
                val key = "wetrakr:$showId:$season:$number"
                val current = episodes[key]
                when (entry.status) {
                    "added" -> {
                        val ids = current?.showIds ?: showIds[showId] ?: resolveShow(showId)?.also { showIds[showId] = it }
                            ?: continue
                        if (current != null && consumePending(WeTrakrType.EPISODE, ids, season, number)) continue
                        episodes[key] = WeTrakrWatchedEpisode(
                            showId = showId,
                            showIds = ids.merged(WeTrakrIds(wetrakr = showId)),
                            season = season,
                            number = number,
                            episodeId = entry.id ?: current?.episodeId,
                            lastWatchedAt = latest(current?.lastWatchedAt, entry.watchedAt ?: entry.actionAt),
                            plays = (current?.plays ?: 0) + 1
                        )
                    }
                    "removed" -> if (current != null) {
                        if (current.plays <= 1) episodes.remove(key) else episodes[key] = current.copy(plays = current.plays - 1)
                    }
                }
            }
            entry.status == "removed" && (entry.type == WeTrakrType.MOVIE || entry.type == WeTrakrType.SHOW) -> {
                val list = WeTrakrList.entries.firstOrNull { it.journalCategory == entry.category } ?: continue
                if (list == WeTrakrList.HOLD && entry.type == WeTrakrType.MOVIE) continue
                lists[list] = lists[list].orEmpty().filterNot { it.title.type == entry.type && it.title.ids.matches(entry.ids) }
            }
        }
    }
    return snapshot.copy(
        watchedMovies = movies.values.toList(),
        watchedEpisodes = episodes.values.toList(),
        showIds = showIds,
        lists = lists,
        pendingPlays = pending
    )
}

internal fun WeTrakrSnapshot.withListChanges(list: WeTrakrList, changed: List<WeTrakrListEntry>): WeTrakrSnapshot {
    if (changed.isEmpty()) return this
    val kept = list(list).filterNot { entry -> changed.any { it.title.type == entry.title.type && it.title.ids.matches(entry.title.ids) } }
    return copy(lists = lists + (list to changed + kept))
}

private fun latest(current: String?, candidate: String): String =
    if (current != null && weTrakrTimestamp(current) >= weTrakrTimestamp(candidate)) current else candidate
