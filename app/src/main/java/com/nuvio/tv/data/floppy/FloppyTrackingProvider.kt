package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val api: FloppyApiClient
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.FLOPPY
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!store.state.value.isAuthenticated) return
        val body = floppyScrobbleBody(action, event) ?: return
        api.scrobble(store.scope(), body)
    }
}

/** Unmarking is never sent: Floppy keeps past plays, and its guide asks clients to leave them alone. */
@Singleton
class FloppyTrackingHistoryWriter @Inject constructor(
    private val store: FloppyAuthStore,
    private val api: FloppyApiClient
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.FLOPPY

    override val isConnected: Flow<Boolean> = store.state.map { it.isAuthenticated }.distinctUntilChanged()

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = store.scope()
        if (scope.profileId != profileId || !store.state.value.isAuthenticated) return TrackingMutationResult(0)
        val bodies = items.filterNot(TrackingHistoryItem::isWholeSeriesMark).map(::floppyManualMarkBody)
        var notFound = 0
        bodies.forEach { body ->
            if (body == null) {
                notFound++
                return@forEach
            }
            try {
                api.scrobble(scope, body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: FloppyApiException) {
                if (error.status != 404) throw error
                notFound++
            }
        }
        return TrackingMutationResult(attemptedCount = bodies.size, notFoundCount = notFound)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult = TrackingMutationResult(0)
}
