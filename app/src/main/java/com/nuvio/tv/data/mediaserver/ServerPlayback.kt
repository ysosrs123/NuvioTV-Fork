package com.nuvio.tv.data.mediaserver

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ServerPlayback internal constructor(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long
) {
    @Inject
    constructor(repository: ServerRepository) : this(
        repository = repository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = SystemClock::elapsedRealtime
    )

    private val lock = Any()
    private val active = mutableMapOf<String, ActivePlayback>()
    private val segmentCache = LinkedHashMap<String, List<ServerSegment>>()

    suspend fun prepare(target: ServerPlaybackTarget): ServerPlaybackSession {
        val (provider, session, playback) = repository.call(target.item.connectionId) { provider, session ->
            Triple(provider, session, provider.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities())))
        }
        val orphans = synchronized(lock) {
            val unstarted = active.filterValues { !it.started }.keys.toList()
            active[playback.url] = ActivePlayback(provider, session, playback)
            unstarted.mapNotNull(active::remove)
        }
        orphans.forEach { it.stop() }
        return playback
    }

    fun canFallback(url: String?): Boolean {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return false
        return current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY &&
            current.playback.transcodeOffered &&
            !current.transcodeRefused
    }

    suspend fun fallback(url: String?): ServerPlaybackSession? {
        val failed = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (!canFallback(url)) return null
        val session = restart(url, failed, audioStreamIndex = null, subtitleStreamIndex = null)
        if (session == null) failed.transcodeRefused = true
        return session
    }

    suspend fun switchAudio(url: String?, audioStreamIndex: Int): ServerPlaybackSession? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, current, audioStreamIndex, current.playback.burnInSubtitles.selectedIndex())
    }

    suspend fun switchSubtitle(url: String?, subtitleStreamIndex: Int?): ServerPlaybackSession? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (current.playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return null
        return restart(url, current, current.playback.audioTracks.selectedIndex(), subtitleStreamIndex)
    }

    fun audioTracks(url: String?): List<ServerTrack> {
        val playback = session(url) ?: return emptyList()
        if (playback.playMethod == ServerPlayMethod.DIRECT_PLAY || playback.audioTracks.size < 2) return emptyList()
        return playback.audioTracks
    }

    fun burnInSubtitles(url: String?): List<ServerTrack> {
        val playback = session(url) ?: return emptyList()
        if (playback.playMethod == ServerPlayMethod.DIRECT_PLAY) return emptyList()
        return playback.burnInSubtitles
    }

    fun session(url: String?): ServerPlaybackSession? = url?.let { synchronized(lock) { active[it] } }?.playback

    fun providerName(url: String?): String? = url?.let { synchronized(lock) { active[it] } }?.provider?.displayName

    /** Intro, recap and credit markers for the item behind [url], fetched once per file version. */
    suspend fun segments(url: String?): List<ServerSegment> {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return emptyList()
        val target = current.playback.target
        val key = "${target.item.encode()}:${current.playback.mediaSourceId}"
        synchronized(lock) { segmentCache[key] }?.let { return it }
        val segments = try {
            withTimeoutOrNull(SEGMENTS_TIMEOUT_MS) {
                current.provider.segments(current.liveSession(), target.item.itemId, current.playback.mediaSourceId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Segments failed: ${error.serverFailure()}")
            null
        } ?: return emptyList()
        synchronized(lock) {
            segmentCache[key] = segments
            if (segmentCache.size > SEGMENT_CACHE_SIZE) segmentCache.remove(segmentCache.keys.first())
        }
        return segments
    }

    /** The server's own watch state for the item behind [url]; null when it has none or does not answer in time. */
    suspend fun resumeState(url: String?): ServerUserState? {
        val current = url?.let { synchronized(lock) { active[it] } } ?: return null
        if (!current.provider.supports(ServerCapability.USER_STATE_READ)) return null
        val details = try {
            withTimeoutOrNull(RESUME_TIMEOUT_MS) {
                current.provider.details(current.liveSession(), current.playback.target.item.itemId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Resume state failed: ${error.serverFailure()}")
            return null
        }
        if (details == null) Log.w(TAG, "Resume state timed out")
        return details?.userStates?.singleOrNull()
    }

    fun isServerSource(url: String?): Boolean = url != null && synchronized(lock) { url in active }

    fun onPlaybackSnapshot(url: String?, positionMs: Long, isPlaying: Boolean, isLoading: Boolean, isEnded: Boolean) {
        val playback = url?.let { synchronized(lock) { active[it] } } ?: return
        if (isEnded) {
            playback.lastPositionMs = positionMs
            stop(url)
            return
        }
        playback.onSnapshot(positionMs, isPlaying, isLoading)
    }

    fun stop(url: String?) {
        val playback = url?.let { synchronized(lock) { active.remove(it) } } ?: return
        playback.stop()
    }

    private suspend fun restart(
        url: String,
        current: ActivePlayback,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?
    ): ServerPlaybackSession? {
        val request = ServerPlaybackRequest(
            target = current.playback.target,
            capabilities = ServerPlayerCapabilities(allowDirectPlay = false),
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex
        )
        val (provider, session, playback) = try {
            repository.call(request.target.item.connectionId) { provider, session ->
                Triple(provider, session, provider.preparePlayback(session, request))
            }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Playback restart cancelled: server settings changed")
            return null
        } catch (error: Exception) {
            Log.w(TAG, "Playback restart failed: ${error.serverFailure()}")
            return null
        }
        stop(url)
        synchronized(lock) { active[playback.url] = ActivePlayback(provider, session, playback) }
        return playback
    }

    private fun List<ServerTrack>.selectedIndex(): Int? = firstOrNull { it.selected }?.index

    private inner class ActivePlayback(
        val provider: ServerProvider,
        val session: ServerSession,
        val playback: ServerPlaybackSession
    ) {
        private val events = Channel<ServerPlaybackEvent>(Channel.UNLIMITED)
        var started = false
            private set
        var lastPositionMs = 0L

        @Volatile
        var transcodeRefused = false
        private var paused = false
        private var lastReportPositionMs = 0L
        private var lastReportAtMs = 0L

        init {
            scope.launch {
                for (event in events) {
                    try {
                        withTimeoutOrNull(REPORT_TIMEOUT_MS) { provider.report(liveSession(), playback, event) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Playback report ${event.type} failed: ${error.serverFailure()}")
                    }
                }
            }
        }

        fun liveSession(): ServerSession = repository.session(session.connection.id) ?: session

        fun onSnapshot(positionMs: Long, isPlaying: Boolean, isLoading: Boolean) = synchronized(lock) {
            if (!isLoading || positionMs > 0L) lastPositionMs = positionMs
            if (!started) {
                if (isPlaying && !isLoading) {
                    started = true
                    send(ServerPlaybackEventType.START, positionMs, isPaused = false)
                }
                return@synchronized
            }
            if (isLoading) return@synchronized
            val now = clock()
            val elapsed = now - lastReportAtMs
            val expected = lastReportPositionMs + if (paused) 0L else elapsed
            when {
                paused == isPlaying -> {
                    paused = !isPlaying
                    send(if (paused) ServerPlaybackEventType.PAUSE else ServerPlaybackEventType.RESUME, positionMs, paused)
                }
                abs(positionMs - expected) > SEEK_THRESHOLD_MS ||
                    (isPlaying && elapsed >= PROGRESS_INTERVAL_MS) -> {
                    send(ServerPlaybackEventType.PROGRESS, positionMs, paused)
                }
            }
        }

        /** A session that never started sends nothing: a stop at 0 would replace the server's resume point. */
        fun stop() = synchronized(lock) {
            if (started) send(ServerPlaybackEventType.STOP, lastPositionMs, isPaused = true)
            events.close()
        }

        private fun send(type: ServerPlaybackEventType, positionMs: Long, isPaused: Boolean) {
            lastReportAtMs = clock()
            lastReportPositionMs = positionMs
            events.trySend(ServerPlaybackEvent(type, positionMs, isPaused))
        }
    }

    private companion object {
        const val TAG = "ServerPlayback"
        const val PROGRESS_INTERVAL_MS = 10_000L
        const val SEEK_THRESHOLD_MS = 5_000L
        const val REPORT_TIMEOUT_MS = 10_000L
        const val SEGMENTS_TIMEOUT_MS = 3_000L
        const val SEGMENT_CACHE_SIZE = 16
        const val RESUME_TIMEOUT_MS = 1_500L
    }
}

/**
 * Where a server title starts: the server's resume point when it is newer than Nuvio's [saved] progress and the
 * item is not played there, otherwise [saved]. [blank] gives the entry to fill when Nuvio has no progress yet.
 */
fun newerServerResume(saved: WatchProgress?, state: ServerUserState?, blank: () -> WatchProgress?): WatchProgress? {
    val lastPlayed = state?.lastPlayedEpochMs
        ?.takeIf { !state.played && state.positionMs > 0L && state.durationMs > 0L }
        ?: return saved
    if (saved != null && saved.lastWatched >= lastPlayed) return saved
    val base = saved ?: blank() ?: return null
    return base.copy(
        position = state.positionMs,
        duration = state.durationMs,
        lastWatched = lastPlayed,
        progressPercent = null,
        source = WatchProgress.SOURCE_LOCAL
    )
}
