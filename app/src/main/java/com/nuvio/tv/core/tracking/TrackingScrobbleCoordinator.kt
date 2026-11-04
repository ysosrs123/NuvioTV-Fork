package com.nuvio.tv.core.tracking

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingScrobbleCoordinator @Inject constructor(
    private val providerRegistry: TrackingProviderRegistry
) {
    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> {
        val scrobblers = providerRegistry.connectedScrobblers()
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "coordinator dispatch action=${action.wireValue} providers=" +
                scrobblers.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                " ${event.scrobbleDiagnosticSummary()}"
        )
        return dispatch(
            scrobblers = scrobblers,
            action = action,
            event = event
        ).also { failures ->
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "coordinator complete action=${action.wireValue} failures=" +
                    failures.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                    " ${event.scrobbleDiagnosticSummary()}"
            )
        }
    }

    /** [restart] runs per tracker after its own stop, so it is read again each time and may return null to skip the start. */
    suspend fun scrobbleSeekRestart(
        stop: TrackingScrobbleEvent,
        restart: () -> TrackingScrobbleEvent?
    ): List<TrackingScrobbleFailure> = dispatchTrackingSeekRestart(providerRegistry.connectedScrobblers(), stop, restart)
        .also { failures -> failures.log(TrackingScrobbleAction.STOP, true) }

    private suspend fun dispatch(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, false) }

    private fun List<TrackingScrobbleFailure>.log(
        action: TrackingScrobbleAction,
        seek: Boolean
    ) {
        forEach { failure ->
            Log.w(
                "TrackingScrobble",
                "${failure.providerId.storageId} ${if (seek) "seek " else ""}scrobble ${action.wireValue} failed",
                failure.cause
            )
        }
    }
}

suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers.filter { it.providerId !in event.skippedProviders }.map { scrobbler ->
        async { scrobbleCatching(scrobbler, action, event) }
    }.awaitAll().filterNotNull()
}

suspend fun dispatchTrackingSeekRestart(
    scrobblers: Collection<TrackingScrobbler>,
    stop: TrackingScrobbleEvent,
    restart: () -> TrackingScrobbleEvent?
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers
        .filter { it.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART && it.providerId !in stop.skippedProviders }
        .map { scrobbler ->
            async {
                val stopFailure = scrobbleCatching(scrobbler, TrackingScrobbleAction.STOP, stop)
                val startFailure = restart()?.let { scrobbleCatching(scrobbler, TrackingScrobbleAction.START, it) }
                listOfNotNull(stopFailure, startFailure)
            }
        }
        .awaitAll()
        .flatten()
}

/** A tracker's own cancellation (an account switched mid-call) is its failure; only a cancelled caller stops the rest. */
private suspend fun scrobbleCatching(
    scrobbler: TrackingScrobbler,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): TrackingScrobbleFailure? = try {
    scrobbler.scrobble(action, event)
    null
} catch (error: Throwable) {
    currentCoroutineContext().ensureActive()
    TrackingScrobbleFailure(scrobbler.providerId, error)
}
