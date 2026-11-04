package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.logging.forLog
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerSyncSnapshot
import com.nuvio.tv.data.mediaserver.SyncedServer
import com.nuvio.tv.data.mediaserver.mergeSyncedServers
import com.nuvio.tv.data.mediaserver.toSyncPayload
import com.nuvio.tv.data.mediaserver.withoutDisplayNames
import com.nuvio.tv.data.remote.supabase.SupabaseMediaServers
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.Postgrest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class MediaServerSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val repository: ServerRepository,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var foregroundPullJob: Job? = null
    private var lastForegroundPullAtMs = 0L
    @Volatile private var displayNamesRefused = false

    init {
        observeLocalChanges()
        observeAccountAndProfile()
    }

    suspend fun syncFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runSync(profileId, pushOnly = false)
        }
    }

    /** Turns account sync off and replaces the account's server list with an empty one. Servers on this device stay. */
    suspend fun removeFromAccount(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                check(canSync(profileId)) { "Not signed in to a Nuvio account" }
                repository.setSyncEnabled(false)
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_servers", emptyList<SyncedServer>().toSyncPayload())
                    putSyncOriginClientId(syncClientIdentity)
                }
                withJwtRefreshRetry { postgrest.rpc("sync_push_media_servers", params) }
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Removing media servers from the account failed for profile $profileId", error.forLog())
                Result.failure(error)
            }
        }
    }

    fun requestForegroundPull(force: Boolean = false) {
        if (!authManager.isAuthenticated) return
        val now = SystemClock.elapsedRealtime()
        if (!force && foregroundPullJob?.isActive == true) return
        if (!force && now - lastForegroundPullAtMs < FOREGROUND_MIN_INTERVAL_MS) return
        foregroundPullJob = scope.launch {
            if (!force) delay(FOREGROUND_DELAY_MS)
            syncFromRemote()
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeAccountAndProfile() {
        scope.launch {
            combine(authManager.authState, profileManager.activeProfileId) { state, profileId ->
                (state as? AuthState.FullAccount)?.let { it.userId to profileId }
            }
                .distinctUntilChanged()
                .debounce(PROFILE_SYNC_DEBOUNCE_MS)
                .filterNotNull()
                .collect { (_, profileId) -> syncFromRemote(profileId) }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChanges() {
        scope.launch {
            repository.localChanges
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { profileId ->
                    syncMutex.withLock { runSync(profileId, pushOnly = true) }
                }
        }
    }

    private suspend fun runSync(profileId: Int, pushOnly: Boolean): Result<Boolean> =
        try {
            Result.success(sync(profileId, pushOnly))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Media server sync failed for profile $profileId", error.forLog())
            Result.failure(error)
        }

    private suspend fun sync(profileId: Int, pushOnly: Boolean): Boolean {
        if (!canSync(profileId)) return false
        repository.selectProfile(profileId)
        val local = repository.syncSnapshot(profileId) ?: return false
        if (local.syncedKeys != null && (pushOnly || local.pendingPush)) {
            if (local.pendingPush) push(withAccountImportSwitches(local))
            return false
        }
        val remote = pull(profileId)
        if (!canSync(profileId)) return false
        if (remote == null) {
            if (local.servers.isNotEmpty() || local.pendingPush) push(local)
            return false
        }
        val merged = mergeSyncedServers(local.servers, remote, local.syncedKeys)
        val remoteKeys = remote.mapTo(mutableSetOf()) { it.key }
        if (!repository.applySync(local, merged, remoteKeys)) return false
        if (merged.any { it.key !in remoteKeys }) {
            repository.syncSnapshot(profileId)?.let { push(it) }
        }
        Log.d(TAG, "Synchronized ${merged.size} media servers for profile $profileId")
        lastForegroundPullAtMs = SystemClock.elapsedRealtime()
        return merged != local.servers
    }

    /** Fills in import_watch_state this box has not read yet, so a push does not clear what another app set. */
    private suspend fun withAccountImportSwitches(snapshot: ServerSyncSnapshot): ServerSyncSnapshot {
        if (snapshot.servers.none { it.importWatchState == null }) return snapshot
        val remote = try {
            pull(snapshot.profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the account's import switches before pushing", error.forLog())
            null
        }?.associateBy { it.key } ?: return snapshot
        val servers = snapshot.servers.map { server ->
            if (server.importWatchState != null) server else server.copy(importWatchState = remote[server.key]?.importWatchState)
        }
        return ServerSyncSnapshot(snapshot.profileId, snapshot.version, servers, snapshot.pendingPush, snapshot.syncedKeys)
    }

    private suspend fun pull(profileId: Int): List<SyncedServer>? {
        val params = buildJsonObject { put("p_profile_id", profileId) }
        val response = withJwtRefreshRetry {
            postgrest.rpc("sync_pull_media_servers", params)
        }
        return response.decodeList<SupabaseMediaServers>().firstOrNull()?.servers
    }

    private suspend fun push(snapshot: ServerSyncSnapshot) {
        val hasNames = snapshot.servers.any { it.displayName != null }
        if (!hasNames || displayNamesRefused) {
            pushServers(snapshot.profileId, snapshot.servers.withoutDisplayNames())
        } else {
            try {
                pushServers(snapshot.profileId, snapshot.servers)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is HttpRequestException || error is IOException) throw error
                try {
                    pushServers(snapshot.profileId, snapshot.servers.withoutDisplayNames())
                } catch (retryError: Exception) {
                    retryError.addSuppressed(error)
                    throw retryError
                }
                displayNamesRefused = true
                Log.w(TAG, "The account did not take server display names; they stay on this device", error.forLog())
            }
        }
        repository.markPushed(snapshot)
        Log.d(TAG, "Pushed ${snapshot.servers.size} media servers for profile ${snapshot.profileId}")
    }

    private suspend fun pushServers(profileId: Int, servers: List<SyncedServer>) {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_servers", servers.toSyncPayload())
            putSyncOriginClientId(syncClientIdentity)
        }
        withJwtRefreshRetry {
            postgrest.rpc("sync_push_media_servers", params)
        }
    }

    private fun canSync(profileId: Int): Boolean =
        authManager.authState.value is AuthState.FullAccount &&
            profileManager.activeProfileId.value == profileId

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(error)) throw error
            block()
        }

    private companion object {
        const val TAG = "MediaServerSync"
        const val PUSH_DEBOUNCE_MS = 500L
        const val PROFILE_SYNC_DEBOUNCE_MS = 1000L
        const val FOREGROUND_DELAY_MS = 2500L
        const val FOREGROUND_MIN_INTERVAL_MS = 60_000L
    }
}
