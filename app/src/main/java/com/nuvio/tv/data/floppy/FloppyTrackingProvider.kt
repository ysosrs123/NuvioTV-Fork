package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class FloppyTrackingProvider @Inject constructor(
    store: FloppyAuthStore,
    override val scrobbler: FloppyTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.FLOPPY,
        "Floppy",
        setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.SCROBBLE, TrackingCapability.WATCHED_WRITE)
    )

    override val isAuthenticated: StateFlow<Boolean> = store.state
        .map { it.isAuthenticated }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, store.state.value.isAuthenticated)
}

@Singleton
class FloppyTrackingScrobbler @Inject constructor(
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val ledger: FloppyPlayLedger
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.FLOPPY
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART
    override val stopsWhenLeftPaused = true

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!store.state.value.isAuthenticated) return
        val body = floppyScrobbleBody(action, event) ?: return
        val scope = store.scope()
        api.scrobble(scope, body)
        if (action == TrackingScrobbleAction.STOP && event.progressPercent >= FLOPPY_COMPLETED_PERCENT) {
            event.media.filmTmdbId()?.let { ledger.record(scope.profileId, it, System.currentTimeMillis()) }
        }
    }
}

@Singleton
class FloppyTrackingHistoryWriter @Inject constructor(
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient,
    private val ledger: FloppyPlayLedger
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.FLOPPY

    override val isConnected: Flow<Boolean> = store.state.map { it.isAuthenticated }.distinctUntilChanged()

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = store.scope()
        if (scope.profileId != profileId || !store.state.value.isAuthenticated) return TrackingMutationResult(0)
        val marks = items.filterNot(TrackingHistoryItem::isWholeSeriesMark).map { it to floppyManualMarkBody(it) }
        var notFound = 0
        sendEach(marks) { (item, body) ->
            if (body == null) {
                notFound++
                return@sendEach
            }
            try {
                retryingOnce { api.scrobble(scope, body) }
                item.media.filmTmdbId()?.let {
                    ledger.record(scope.profileId, it, item.watchedAtEpochMs ?: System.currentTimeMillis())
                }
            } catch (error: FloppyApiException) {
                if (error.status != 404) throw error
                notFound++
            }
        }
        return TrackingMutationResult(attemptedCount = marks.size, notFoundCount = notFound)
    }

    /** Floppy retracts each title itself. A server too old for that gets the plays this box added to a film taken back; its episodes stay. */
    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        val scope = store.scope()
        if (scope.profileId != profileId || !store.state.value.isAuthenticated) return TrackingMutationResult(0)
        var attempted = 0
        var notFound = 0
        var oldServer = false
        sendEach(items.filterNot { TrackingHistoryItem(it).isWholeSeriesMark }.distinct()) { media ->
            when (if (oldServer) UnwatchAnswer.OLD_SERVER else sendUnwatch(scope, media)) {
                UnwatchAnswer.RETRACTED -> {
                    attempted++
                    media.filmTmdbId()?.let { ledger.forget(profileId, it) }
                }
                UnwatchAnswer.NOT_FOUND -> {
                    attempted++
                    notFound++
                }
                UnwatchAnswer.OLD_SERVER -> {
                    oldServer = true
                    if (media.filmTmdbId()?.let { takeBackPlaysMadeHere(scope, it) } == true) attempted++
                }
            }
        }
        return TrackingMutationResult(attemptedCount = attempted, notFoundCount = notFound)
    }

    private suspend fun sendUnwatch(scope: FloppyAuthScope, media: TrackingMediaReference): UnwatchAnswer {
        val body = floppyUnwatchBody(media) ?: return UnwatchAnswer.NOT_FOUND
        return try {
            if (retryingOnce { api.unwatch(scope, body) }) UnwatchAnswer.RETRACTED else UnwatchAnswer.OLD_SERVER
        } catch (error: FloppyApiException) {
            if (error.status != 404) throw error
            UnwatchAnswer.NOT_FOUND
        }
    }

    private suspend fun takeBackPlaysMadeHere(scope: FloppyAuthScope, tmdb: Long): Boolean {
        val stamps = ledger.stamps(scope.profileId, tmdb)
        if (stamps.isEmpty()) return false
        floppyPlaysMadeHere(api.filmPlays(scope, tmdb), stamps).forEach { play ->
            api.deleteFilmPlay(scope, tmdb, play.id)
        }
        ledger.forget(scope.profileId, tmdb)
        return true
    }

    /** One title failing doesn't cost the rest of a season; a server that keeps failing ends the batch. */
    private suspend fun <T> sendEach(items: List<T>, send: suspend (T) -> Unit) {
        var firstFailure: FloppyApiException? = null
        var failuresInARow = 0
        for (item in items) {
            try {
                send(item)
                failuresInARow = 0
            } catch (error: FloppyApiException) {
                firstFailure = firstFailure ?: error
                if (++failuresInARow == MAX_FAILURES_IN_A_ROW) break
            }
        }
        firstFailure?.let { throw it }
    }

    /** Safe to repeat: the body keeps its client_event_id, so Floppy answers a retry from the first result. */
    private suspend fun <T> retryingOnce(send: suspend () -> T): T = try {
        send()
    } catch (error: FloppyApiException) {
        if (error.status != 0 && error.status != 429 && error.status !in 500..599) throw error
        delay(RETRY_DELAY_MS)
        send()
    }

    private enum class UnwatchAnswer { RETRACTED, NOT_FOUND, OLD_SERVER }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 3
        const val RETRY_DELAY_MS = 1_000L
    }
}

private fun TrackingMediaReference.filmTmdbId(): Long? =
    ids.tmdb?.takeIf { it > 0L && kind == TrackingMediaKind.MOVIE }
