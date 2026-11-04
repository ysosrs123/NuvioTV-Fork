package com.nuvio.tv.data.wetrakr

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.tracking.TrackingRefreshGate
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class WeTrakrSyncError { UNAVAILABLE, INVALID_RESPONSE, RATE_LIMIT, DAILY_LIMIT, AUTHORIZATION_REVOKED, APP_SUSPENDED }

data class WeTrakrSyncState(
    val scope: WeTrakrAuthScope? = null,
    val snapshot: WeTrakrSnapshot? = null,
    val isLoading: Boolean = false,
    val error: WeTrakrSyncError? = null,
    val retryAtEpochMs: Long? = null,
    val attemptedAtEpochMs: Long? = null
) {
    val hasLoaded: Boolean
        get() = snapshot?.isInitialized == true
}

internal data class WeTrakrProjectionState(
    val scope: WeTrakrAuthScope? = null,
    val projection: WeTrakrProgressProjection = WeTrakrProgressProjection.Empty
)

internal fun Throwable.toWeTrakrSyncError(): WeTrakrSyncError = when {
    this is WeTrakrAuthException && error == WeTrakrAuthError.APP_SUSPENDED -> WeTrakrSyncError.APP_SUSPENDED
    this is WeTrakrAuthException -> WeTrakrSyncError.AUTHORIZATION_REVOKED
    this is WeTrakrDecodingException -> WeTrakrSyncError.INVALID_RESPONSE
    this is WeTrakrApiException && isKeySuspended -> WeTrakrSyncError.APP_SUSPENDED
    this is WeTrakrApiException && isDailyLimit -> WeTrakrSyncError.DAILY_LIMIT
    this is WeTrakrApiException && status == 429 -> WeTrakrSyncError.RATE_LIMIT
    else -> WeTrakrSyncError.UNAVAILABLE
}

interface WeTrakrSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit)
    suspend fun remove(profileId: Int, checkScope: () -> Unit)
}

@Singleton
class AndroidWeTrakrSyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : WeTrakrSyncStorage {
    override suspend fun load(profileId: Int): String? = factory.get(profileId, FEATURE).data.first()[SNAPSHOT]

    override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw WeTrakrAccountChangedException("WeTrakr profile removed")
            preferences[SNAPSHOT] = payload
        }
    }

    override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
        factory.get(profileId, FEATURE).edit { preferences ->
            checkScope()
            preferences.remove(SNAPSHOT)
        }
    }

    private companion object {
        const val FEATURE = "wetrakr_sync"
        val SNAPSHOT = stringPreferencesKey("snapshot")
    }
}

class WeTrakrSyncRepository(
    private val storage: WeTrakrSyncStorage,
    private val auth: WeTrakrAuthStore,
    private val api: WeTrakrApiClient,
    private val activeProfileId: StateFlow<Int>,
    private val coroutineScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remote: (WeTrakrAuthScope) -> WeTrakrSyncRemote = { WeTrakrHttpSyncRemote(api, it) }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var saveJob: Job? = null
    private val gate = TrackingRefreshGate()
    private val mutableState = MutableStateFlow(WeTrakrSyncState())
    private val mutableProjection = MutableStateFlow(WeTrakrProjectionState())
    val state = mutableState.asStateFlow()
    internal val projectionState = mutableProjection.asStateFlow()

    init {
        coroutineScope.launch {
            combine(activeProfileId, auth.state) { profileId, authorization ->
                Triple(profileId, authorization.scope, authorization.isAuthenticated)
            }.distinctUntilChanged().collectLatest {
                if (!matchesCurrent(mutableState.value.scope)) clearPublished()
                try {
                    ensureLoaded()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (matchesCurrent(auth.scope())) {
                        mutableState.value = WeTrakrSyncState(scope = auth.scope(), error = error.toWeTrakrSyncError())
                    }
                }
            }
        }
    }

    fun currentScope(): WeTrakrAuthScope = auth.scope().also(::checkScope)

    fun currentSnapshot(): WeTrakrSnapshot? = mutableState.value.takeIf { matchesCurrent(it.scope) }?.snapshot

    internal fun currentProjection(): WeTrakrProgressProjection = mutableProjection.value
        .takeIf { matchesCurrent(it.scope) && auth.state.value.isAuthenticated }?.projection
        ?: WeTrakrProgressProjection.Empty

    suspend fun ensureLoaded() = withContext(dispatcher) { mutex.withLock { loadCurrent() } }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        coroutineScope.launch { runCatching { refresh(intent) } }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(dispatcher) {
        val scope = auth.scope()
        gate.runIfNeeded(scope.generation, shouldRun = {
            auth.state.value.isAuthenticated && matchesCurrent(scope) && shouldRefresh(intent) &&
                (intent != TrackingRefreshIntent.AUTOMATIC || api.hasQuotaToSpare(scope))
        }) {
            checkScope(scope)
            val attemptedAt = now()
            try {
                if (auth.state.value.user == null) api.refreshUser(scope)
                var base = mutex.withLock { loadCurrent() } ?: return@runIfNeeded
                mutableState.value = mutableState.value.copy(isLoading = true, error = null, attemptedAtEpochMs = attemptedAt)
                for (attempt in 1..SYNC_ATTEMPTS) {
                    val synced = WeTrakrSyncEngine(remote(scope), now).synchronize(base)
                    val committed = mutex.withLock {
                        checkScope(scope)
                        val current = mutableState.value.snapshot
                        if (current == null || current.storageRevision == base.storageRevision) {
                            commit(scope, synced)
                            true
                        } else {
                            base = current
                            false
                        }
                    }
                    if (committed) break
                }
                mutableState.value = mutableState.value.copy(isLoading = false, attemptedAtEpochMs = attemptedAt)
            } catch (error: CancellationException) {
                if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(isLoading = false)
                throw error
            } catch (error: Exception) {
                if (matchesCurrent(scope)) mutableState.value = mutableState.value.copy(
                    scope = scope,
                    isLoading = false,
                    error = error.toWeTrakrSyncError(),
                    retryAtEpochMs = (error as? WeTrakrApiException)?.retryAtEpochMs,
                    attemptedAtEpochMs = attemptedAt
                )
            }
        }
    }

    internal suspend fun <T> mutate(
        scope: WeTrakrAuthScope,
        block: suspend (WeTrakrSnapshot) -> Pair<WeTrakrSnapshot, T>
    ): T = withContext(dispatcher) {
        checkScope(scope)
        if (auth.state.value.user == null) api.refreshUser(scope)
        mutex.withLock {
            checkScope(scope)
            val previous = loadCurrent() ?: throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
            val (next, result) = block(previous)
            if (next != previous) commit(scope, next, SAVE_DELAY_MS)
            checkScope(scope)
            result
        }
    }

    internal suspend fun invalidate(scope: WeTrakrAuthScope) {
        mutate(scope) { previous -> previous.copy(invalidated = true) to Unit }
    }

    private suspend fun loadCurrent(): WeTrakrSnapshot? {
        val scope = auth.scope()
        checkScope(scope, requireAuthorization = false)
        val authorization = auth.state.value
        if (!authorization.isAuthenticated) {
            clearPublished()
            storage.remove(scope.profileId) { checkScope(scope, requireAuthorization = false) }
            return null
        }
        val accountId = authorization.user?.id ?: return null
        val current = mutableState.value
        if (current.scope == scope && current.snapshot?.accountId == accountId) return current.snapshot
        val snapshot = storage.load(scope.profileId)
            ?.let { runCatching { json.decodeFromString(WeTrakrSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { it.accountId == accountId }
            ?: WeTrakrSnapshot(accountId)
        checkScope(scope)
        val projection = runCatching { WeTrakrProgressProjection(snapshot) }.getOrNull()
        val usable = if (projection != null) snapshot else WeTrakrSnapshot(accountId)
        mutableProjection.value = WeTrakrProjectionState(scope, projection ?: WeTrakrProgressProjection.Empty)
        mutableState.value = WeTrakrSyncState(scope, usable)
        return usable
    }

    private suspend fun commit(scope: WeTrakrAuthScope, snapshot: WeTrakrSnapshot, saveDelayMs: Long = 0L) {
        checkScope(scope)
        val previous = mutableState.value.snapshot
        val stored = snapshot.copy(storageRevision = UUID.randomUUID().toString())
        val projection = if (previous != null && previous.watchedMovies === snapshot.watchedMovies &&
            previous.watchedEpisodes === snapshot.watchedEpisodes && previous.playback === snapshot.playback &&
            previous.lists === snapshot.lists
        ) {
            mutableProjection.value.projection
        } else {
            WeTrakrProgressProjection(stored)
        }
        saveJob?.cancel()
        saveJob = null
        if (saveDelayMs <= 0L) {
            save(scope, stored)
        } else {
            saveJob = coroutineScope.launch {
                delay(saveDelayMs)
                try {
                    withContext(dispatcher) { save(scope, stored) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "snapshot save skipped: ${error.javaClass.simpleName}")
                }
            }
        }
        checkScope(scope)
        mutableProjection.value = WeTrakrProjectionState(scope, projection)
        mutableState.value = WeTrakrSyncState(scope, stored)
    }

    private suspend fun save(scope: WeTrakrAuthScope, snapshot: WeTrakrSnapshot) {
        storage.save(scope.profileId, json.encodeToString(WeTrakrSnapshot.serializer(), snapshot)) { checkScope(scope) }
    }

    private fun shouldRefresh(intent: TrackingRefreshIntent): Boolean {
        val current = mutableState.value.takeIf { matchesCurrent(it.scope) } ?: return true
        val now = now()
        if (current.retryAtEpochMs?.let { it > now } == true) return false
        if (intent != TrackingRefreshIntent.AUTOMATIC) return true
        if (current.error != null) return current.attemptedAtEpochMs?.let { now - it !in 0 until ERROR_RETRY_MS } ?: true
        if (current.snapshot?.invalidated == true) return true
        return current.snapshot?.checkedAtEpochMs?.let { now - it !in 0 until AUTOMATIC_INTERVAL_MS } ?: true
    }

    private fun checkScope(scope: WeTrakrAuthScope, requireAuthorization: Boolean = true) {
        if (!matchesCurrent(scope)) throw WeTrakrAccountChangedException()
        if (requireAuthorization && !auth.state.value.isAuthenticated) {
            throw WeTrakrAuthException(WeTrakrAuthError.AUTHORIZATION_REVOKED)
        }
    }

    private fun matchesCurrent(scope: WeTrakrAuthScope?): Boolean =
        scope != null && scope.profileId == activeProfileId.value && auth.isCurrent(scope)

    private fun clearPublished() {
        mutableProjection.value = WeTrakrProjectionState()
        mutableState.value = WeTrakrSyncState()
    }

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 15L * 60 * 1_000
        const val ERROR_RETRY_MS = 2L * 60 * 1_000
        const val SAVE_DELAY_MS = 5_000L
        private const val SYNC_ATTEMPTS = 3
        private const val TAG = "WeTrakrSync"
    }
}
