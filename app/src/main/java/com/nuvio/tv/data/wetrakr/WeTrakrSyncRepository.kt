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
    suspend fun loadCheck(profileId: Int): String? = null
    suspend fun saveCheck(profileId: Int, payload: String, checkScope: () -> Unit) {}
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
        factory.get(profileId, CHECK_FEATURE).edit { preferences -> preferences.remove(CHECK) }
    }

    override suspend fun loadCheck(profileId: Int): String? =
        factory.get(profileId, CHECK_FEATURE).data.first()[CHECK]?.ifEmpty { null }

    override suspend fun saveCheck(profileId: Int, payload: String, checkScope: () -> Unit) {
        factory.get(profileId, CHECK_FEATURE).edit { preferences ->
            checkScope()
            if (factory.isProfileDeleted(profileId)) throw WeTrakrAccountChangedException("WeTrakr profile removed")
            preferences[CHECK] = payload
        }
    }

    private companion object {
        const val FEATURE = "wetrakr_sync"
        const val CHECK_FEATURE = "wetrakr_check"
        val SNAPSHOT = stringPreferencesKey("snapshot")
        val CHECK = stringPreferencesKey("check")
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
    /** The snapshot as last written to or read from disk, for the profile it belongs to. */
    @Volatile private var persisted: Pair<Int, WeTrakrSnapshot>? = null
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
        val saved = storage.load(scope.profileId)
            ?.let { runCatching { json.decodeFromString(WeTrakrSnapshot.serializer(), it) }.getOrNull() }
            ?.takeIf { it.accountId == accountId }
        persisted = saved?.let { scope.profileId to it }
        val check = saved?.let { base ->
            storage.loadCheck(scope.profileId)
                ?.let { runCatching { json.decodeFromString(WeTrakrCheckState.serializer(), it) }.getOrNull() }
                ?.takeIf { it.accountId == accountId && it.snapshotRevision == base.storageRevision }
        }
        val snapshot = saved?.let { base -> check?.applyTo(base) ?: base } ?: WeTrakrSnapshot(accountId)
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
        val onDisk = persisted?.takeIf { it.first == scope.profileId }?.second
        val historyUnchanged = onDisk != null && saveJob?.isActive != true && onDisk.sameHistoryAs(stored)
        if (historyUnchanged) {
            storage.saveCheck(scope.profileId, json.encodeToString(WeTrakrCheckState.serializer(), WeTrakrCheckState.of(onDisk!!, stored))) {
                checkScope(scope)
            }
        }
        saveJob?.cancel()
        saveJob = null
        if (historyUnchanged) {
            Unit
        } else if (saveDelayMs <= 0L) {
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
        persisted = scope.profileId to snapshot
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

private fun WeTrakrSnapshot.sameHistoryAs(other: WeTrakrSnapshot): Boolean =
    accountId == other.accountId &&
        watchedMovies === other.watchedMovies &&
        watchedEpisodes === other.watchedEpisodes &&
        playback === other.playback &&
        lists === other.lists &&
        customLists === other.customLists &&
        showIds === other.showIds &&
        appliedEntryIds === other.appliedEntryIds &&
        pendingPlays === other.pendingPlays

/** The last check, kept apart so a check that finds nothing new does not rewrite the whole history. */
@kotlinx.serialization.Serializable
internal data class WeTrakrCheckState(
    val accountId: Long,
    val snapshotRevision: String? = null,
    val activities: Map<String, String> = emptyMap(),
    val journalMark: String? = null,
    val checkedAtEpochMs: Long? = null,
    val customListsCheckedAtEpochMs: Long? = null,
    val isInitialized: Boolean = false,
    val invalidated: Boolean = false
) {
    fun applyTo(snapshot: WeTrakrSnapshot): WeTrakrSnapshot = snapshot.copy(
        activities = activities,
        journalMark = journalMark,
        checkedAtEpochMs = checkedAtEpochMs,
        customListsCheckedAtEpochMs = customListsCheckedAtEpochMs,
        isInitialized = isInitialized,
        invalidated = invalidated
    )

    companion object {
        fun of(onDisk: WeTrakrSnapshot, current: WeTrakrSnapshot) = WeTrakrCheckState(
            accountId = current.accountId,
            snapshotRevision = onDisk.storageRevision,
            activities = current.activities,
            journalMark = current.journalMark,
            checkedAtEpochMs = current.checkedAtEpochMs,
            customListsCheckedAtEpochMs = current.customListsCheckedAtEpochMs,
            isInitialized = current.isInitialized,
            invalidated = current.invalidated
        )
    }
}
