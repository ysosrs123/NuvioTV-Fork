package com.nuvio.tv.core.tracking

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

data class TrackingProgressRefreshFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingProgressRefreshCoordinator @Inject constructor(
    private val providers: TrackingProgressProviderRegistry
) {
    /** [oneAtATime] runs the trackers one after another, for refreshes nobody is waiting on. */
    suspend fun refreshConnected(
        intent: TrackingRefreshIntent,
        oneAtATime: Boolean = false
    ): List<TrackingProgressRefreshFailure> =
        supervisorScope {
            val connected = providers.providers().filter { provider -> provider.isAuthenticated.first() }
            suspend fun refresh(provider: TrackingProgressProvider): TrackingProgressRefreshFailure? =
                try {
                    provider.refresh(intent)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    TrackingProgressRefreshFailure(provider.providerId, error)
                }
            if (oneAtATime) {
                connected.mapNotNull { refresh(it) }
            } else {
                connected.map { provider -> async { refresh(provider) } }.mapNotNull { operation -> operation.await() }
            }
        }
}
