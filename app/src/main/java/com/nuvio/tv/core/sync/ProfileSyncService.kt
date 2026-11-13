package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.logging.forLog
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProfileLockState
import com.nuvio.tv.data.remote.supabase.SupabaseProfile
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"
private const val PROFILE_PULL_MIN_INTERVAL_MS = 10_000L

private class ProfilePullOwnerRetiredException : IllegalStateException("Profile sync owner changed")

internal data class ProfilePullFreshness(
    val userId: String? = null,
    val pulledAtMs: Long = 0L
) {
    fun isRecent(candidateUserId: String, nowMs: Long): Boolean {
        return userId == candidateUserId &&
            pulledAtMs > 0L &&
            nowMs >= pulledAtMs &&
            nowMs - pulledAtMs < PROFILE_PULL_MIN_INTERVAL_MS
    }
}

sealed class SetProfilePinResult {
    object Success : SetProfilePinResult()
    object CurrentPinRequired : SetProfilePinResult()
    data class Failure(val throwable: Throwable) : SetProfilePinResult()
}

@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity,
    private val offlinePinStore: com.nuvio.tv.core.profile.OfflinePinStore
) {
    private val pullMutex = Mutex()
    private var pullFreshness = ProfilePullFreshness()
    private var lastPulledProfiles = emptyList<UserProfile>()

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profiles = profileManager.profiles.value

            val params = buildJsonObject {
                put("p_client_max_profiles", ProfileManager.MAX_PROFILES)
                put("p_profiles", buildJsonArray {
                    profiles.forEach { profile ->
                        addJsonObject {
                            put("profile_index", profile.id)
                            put("name", profile.name)
                            put("avatar_color_hex", profile.avatarColorHex)
                            put("uses_primary_addons", profile.usesPrimaryAddons)
                            put("uses_primary_plugins", profile.usesPrimaryPlugins)
                            put("avatar_id", if (profile.avatarUrl.isNullOrBlank()) profile.avatarId else null)
                            put("avatar_url", profile.avatarUrl?.takeIf { it.isNotBlank() })
                            put("profile_background_id", profile.profileBackgroundId)
                            put("profile_background_url", profile.profileBackgroundUrl?.takeIf { it.isNotBlank() })
                        }
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_profiles", params)
            }

            Log.d(TAG, "Pushed ${profiles.size} profiles to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e.forLog())
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(force: Boolean = false): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        pullMutex.withLock {
            val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId
                ?: return@withLock Result.failure(ProfilePullOwnerRetiredException())
            val ownerJob = currentCoroutineContext()[Job]
            fun isCurrentOwner(): Boolean = ownerJob?.isActive != false &&
                (authManager.authState.value as? AuthState.FullAccount)?.userId == userId
            if (!isCurrentOwner()) return@withLock Result.failure(ProfilePullOwnerRetiredException())
            val now = SystemClock.elapsedRealtime()
            if (!force && pullFreshness.isRecent(userId, now)) {
                return@withLock if (isCurrentOwner()) Result.success(lastPulledProfiles)
                else Result.failure(ProfilePullOwnerRetiredException())
            }
            try {
                val response = withJwtRefreshRetry {
                    if (!isCurrentOwner()) throw ProfilePullOwnerRetiredException()
                    postgrest.rpc("sync_pull_profiles")
                }
                if (!isCurrentOwner()) return@withLock Result.failure(ProfilePullOwnerRetiredException())
                val remote = response.decodeList<SupabaseProfile>()

                Log.d(TAG, "pullFromRemote: fetched ${remote.size} profiles from Supabase")

                val profiles = remote.map { entry ->
                    UserProfile(
                        id = entry.profileIndex,
                        name = entry.name,
                        avatarColorHex = entry.avatarColorHex,
                        usesPrimaryAddons = entry.usesPrimaryAddons,
                        usesPrimaryPlugins = entry.usesPrimaryPlugins,
                        avatarId = entry.avatarId,
                        avatarUrl = entry.avatarUrl,
                        profileBackgroundId = entry.profileBackgroundId,
                        profileBackgroundUrl = entry.profileBackgroundUrl
                    )
                }

                if (profiles.isNotEmpty()) {
                    // Recheck inside the actual queued DataStore edit, rather
                    // than only before its asynchronous mutation admission.
                    val previousIds = profileDataStore.profilesList.first().map { it.id }.toSet()
                    if (!profileDataStore.replaceAllProfilesIfCurrent(profiles, ::isCurrentOwner)) {
                        return@withLock Result.failure(ProfilePullOwnerRetiredException())
                    }
                    profileManager.onProfilesReplaced(previousIds, profiles)
                    Log.d(TAG, "Merged ${profiles.size} remote profiles into local store")
                }

                if (!isCurrentOwner()) return@withLock Result.failure(ProfilePullOwnerRetiredException())
                val currentUserId = (authManager.authState.value as? AuthState.FullAccount)?.userId
                if (currentUserId == userId) {
                    lastPulledProfiles = profiles
                    pullFreshness = ProfilePullFreshness(
                        userId = userId,
                        pulledAtMs = SystemClock.elapsedRealtime()
                    )
                }
                Result.success(profiles)
            } catch (e: Exception) {
                if (e is ProfilePullOwnerRetiredException) Log.d(TAG, "Retired profile pull owner")
                else Log.e(TAG, "Failed to pull profiles from remote", e.forLog())
                Result.failure(e)
            }
        }
    }

    suspend fun deleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_profile_data", params)
            }

            Log.d(TAG, "Deleted remote data for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote profile data for profile $profileId", e.forLog())
            Result.failure(e)
        }
    }

    suspend fun pullProfileLockStates(): Result<Map<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profile_locks")
            }
            val remote = response.decodeList<SupabaseProfileLockState>()
            val result = remote.associate { it.profileIndex to it.pinEnabled }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile lock states", e.forLog())
            Result.failure(e)
        }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): SetProfilePinResult = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("set_profile_pin", params)
            }
            offlinePinStore.remember(profileId, pin)
            SetProfilePinResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set profile PIN", e.forLog())
            if (isCurrentPinRequiredError(e)) {
                SetProfilePinResult.CurrentPinRequired
            } else {
                SetProfilePinResult.Failure(e)
            }
        }
    }

    private fun isCurrentPinRequiredError(e: Throwable): Boolean =
        e.message?.contains("Current PIN is required", ignoreCase = true) == true

    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("clear_profile_pin", params)
            }
            offlinePinStore.forget(profileId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear profile PIN", e.forLog())
            Result.failure(e)
        }
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> = withContext(Dispatchers.IO) {
        if (authManager.authState.value !is AuthState.FullAccount) {
            return@withContext offlinePinAnswer(profileId, pin, IllegalStateException("Not signed in"))
        }
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("verify_profile_pin", params)
            }
            val decoded = response.decodeList<SupabaseProfilePinVerifyResult>().firstOrNull()
                ?: SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = 0)
            when {
                decoded.unlocked -> offlinePinStore.remember(profileId, pin)
                // A refusal with a wait is the server's own lockout, not a changed PIN.
                decoded.retryAfterSeconds <= 0 -> offlinePinStore.forgetIfMatches(profileId, pin)
            }
            Result.success(decoded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify profile PIN", e.forLog())
            offlinePinAnswer(profileId, pin, e)
        }
    }

    // The server could not be asked: the box answers from the last PIN the server accepted, if it has one.
    private fun offlinePinAnswer(
        profileId: Int,
        pin: String,
        cause: Exception
    ): Result<SupabaseProfilePinVerifyResult> =
        when (val answer = offlinePinStore.check(profileId, pin)) {
            com.nuvio.tv.core.profile.OfflinePinVault.Answer.NoCopy -> Result.failure(cause)
            com.nuvio.tv.core.profile.OfflinePinVault.Answer.Unlocked ->
                Result.success(SupabaseProfilePinVerifyResult(unlocked = true, retryAfterSeconds = 0))
            is com.nuvio.tv.core.profile.OfflinePinVault.Answer.Refused ->
                Result.success(SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = answer.retryAfterSeconds))
        }
}
