package com.nuvio.tv.data.mdblist

import com.nuvio.tv.domain.model.LibraryEntryInput
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Watchlist writes for Library transfer: up to [BATCH_SIZE] titles per request instead of one each. */
@Singleton
class MdbListWatchlistBatch @Inject constructor(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository
) {
    suspend fun add(items: List<LibraryEntryInput>): Int = write(items, "add")

    suspend fun remove(items: List<LibraryEntryInput>): Int = write(items, "remove")

    private suspend fun write(items: List<LibraryEntryInput>, action: String): Int {
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return 0
        val targets = items.mapNotNull { item ->
            val target = runCatching { item.mdbListLibraryItem() }.getOrNull() ?: return@mapNotNull null
            target.watchlistEntry()?.let { target.type to it }
        }.distinct()
        var written = 0
        for (batch in targets.chunked(BATCH_SIZE)) {
            val (movies, shows) = batch.partition { it.first == MdbListItemType.MOVIE }
            val body = buildJsonObject {
                if (movies.isNotEmpty()) put("movies", JsonArray(movies.map { it.second }))
                if (shows.isNotEmpty()) put("shows", JsonArray(shows.map { it.second }))
            }
            try {
                api.post("${mdbListLibraryItemsPath(MDBLIST_WATCHLIST_KEY)}/$action", body.toString(), scope)
            } catch (_: MdbListApiException) {
                break
            } catch (_: MdbListAuthException) {
                break
            }
            written += batch.size
        }
        return written
    }

    private fun MdbListLibraryItem.watchlistEntry(): JsonObject? {
        val ids = buildJsonObject {
            media.ids.imdb?.let { put("imdb", it) }
            media.ids.tmdb?.let { put("tmdb", it) }
            media.ids.tvdb?.let { put("tvdb", it) }
            media.ids.trakt?.let { put("trakt", it) }
        }
        return if (ids.isEmpty()) null else buildJsonObject { put("ids", ids) }
    }

    private companion object {
        const val BATCH_SIZE = 100
    }
}
