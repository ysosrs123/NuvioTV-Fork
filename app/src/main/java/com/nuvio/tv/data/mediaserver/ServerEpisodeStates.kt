package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Singleton
class ServerEpisodeStates @Inject constructor(
    private val repository: ServerRepository,
    private val matcher: ServerMatcher
) {
    suspend fun load(type: String, contentId: String): List<ServerUserState> {
        if (ServerMediaKind.fromContentType(type) != ServerMediaKind.SERIES && !ServerItemRef.isServerId(contentId)) {
            return emptyList()
        }
        ServerItemRef.parse(contentId)?.let { ref ->
            val connection = repository.connection(ref.connectionId)?.takeIf { it.enabled } ?: return emptyList()
            return statesOrEmpty(connection, ref.itemId)
        }
        val request = matcher.titleRequest(type, contentId) ?: return emptyList()
        val connections = repository.enabledConnections()
            .filter { it.useCatalogMetadata && matcher.supports(it, ServerMediaKind.SERIES) }
        return coroutineScope {
            val matches = connections.map { connection ->
                connection to async { quietly { matcher.matchTitle(connection, request) }?.firstOrNull() }
            }
            var states = emptyList<ServerUserState>()
            for ((connection, match) in matches) {
                val series = match.await() ?: continue
                states = statesOrEmpty(connection, series.itemId)
                if (states.isNotEmpty()) break
            }
            matches.forEach { it.second.cancel() }
            states
        }
    }

    private suspend fun statesOrEmpty(connection: ServerConnection, seriesItemId: String): List<ServerUserState> {
        val session = repository.session(connection.id) ?: return emptyList()
        val provider = repository.provider(connection)?.takeIf { it.supports(ServerCapability.USER_STATE_READ) }
            ?: return emptyList()
        val states = quietly { provider.episodeStates(session, seriesItemId) }.orEmpty()
        return if (connection.importContinueWatching) states else states.map { it.copy(positionMs = 0L) }
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
}

/** An episode un-marked in Nuvio stays unwatched unless the server played it after the un-mark. */
fun List<ServerUserState>.withoutUnmarked(unmarkedAt: Map<Pair<Int, Int>, Long>): List<ServerUserState> {
    if (unmarkedAt.isEmpty()) return this
    return map { state ->
        val key = (state.season ?: return@map state) to (state.episode ?: return@map state)
        val unmarked = unmarkedAt[key] ?: return@map state
        val playedSince = state.lastPlayedEpochMs?.let { it > unmarked } == true
        if (playedSince) state else state.copy(played = false, positionMs = 0L)
    }
}

/**
 * Adds what the server knows about a series to the local episode progress, so the details page picks its next
 * episode from both. A local entry newer than the server's last play is kept.
 */
fun mergeServerEpisodeStates(
    states: List<ServerUserState>,
    localProgress: Map<Pair<Int, Int>, WatchProgress>,
    localWatched: Set<Pair<Int, Int>>,
    contentId: String,
    episodes: List<Video>
): Pair<Map<Pair<Int, Int>, WatchProgress>, Set<Pair<Int, Int>>> {
    if (states.isEmpty()) return localProgress to localWatched
    val progress = localProgress.toMutableMap()
    val watched = localWatched.toMutableSet()
    for (state in states) {
        val key = (state.season ?: continue) to (state.episode ?: continue)
        val video = episodes.firstOrNull { it.season == key.first && it.episode == key.second } ?: continue
        val lastPlayed = state.lastPlayedEpochMs
        val local = progress[key]
        val serverIsNewer = lastPlayed != null && (local == null || local.lastWatched < lastPlayed)
        when {
            state.played -> {
                if (local == null || serverIsNewer) watched += key
                if (serverIsNewer) {
                    progress[key] = serverProgress(contentId, video, key, lastPlayed!!, position = 1L, duration = 1L, percent = 100f)
                }
            }
            state.positionMs > 0L && state.durationMs > 0L && serverIsNewer -> {
                progress[key] = serverProgress(contentId, video, key, lastPlayed!!, state.positionMs, state.durationMs, percent = null)
            }
        }
    }
    return progress to watched
}

private fun serverProgress(
    contentId: String,
    video: Video,
    key: Pair<Int, Int>,
    lastPlayed: Long,
    position: Long,
    duration: Long,
    percent: Float?
) = WatchProgress(
    contentId = contentId,
    contentType = "series",
    name = "",
    poster = null,
    backdrop = null,
    logo = null,
    videoId = video.id,
    season = key.first,
    episode = key.second,
    episodeTitle = video.title,
    position = position,
    duration = duration,
    lastWatched = lastPlayed,
    progressPercent = percent
)
