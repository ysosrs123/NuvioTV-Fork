package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val snapshotStorage: ContinueWatchingSnapshotStorage = ContinueWatchingSnapshotStorage(context)
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
    }

    private val gson = Gson()
    private val mutex = snapshotStorage.mutex
    // Write throttles belong to a profile and are checked while holding the file lock.
    private data class WriteStamp(val hash: Int, val atMs: Long, val fileEpoch: Int)
    private val nextUpWrites = mutableMapOf<Int, WriteStamp>()
    private val inProgressWrites = mutableMapOf<Int, WriteStamp>()

    /** Incremented when cache is cleared; also invalidates writes admitted before a clear. */
    private val _cacheCleared get() = snapshotStorage.cacheCleared
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = snapshotStorage.cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    private fun snapshotFile(kind: String, profileId: Int): File {
        return snapshotStorage.snapshotFile(kind, profileId)
    }

    // Keep the existing entry points; capture ownership before dispatching or waiting for the mutex.
    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> =
        getNextUpSnapshot(profileManager.activeProfileId.value)

    suspend fun getNextUpSnapshot(profileId: Int): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (snapshotStorage.isRetired(profileId)) return@withContext emptyList()
                val file = snapshotFile("nextup", profileId)
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedNextUpItem>>() {}.type)
                    ?: emptyList()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> =
        getInProgressSnapshot(profileManager.activeProfileId.value)

    suspend fun getInProgressSnapshot(profileId: Int): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (snapshotStorage.isRetired(profileId)) return@withContext emptyList()
                val file = snapshotFile("inprogress", profileId)
                if (!file.exists()) return@withContext emptyList()
                gson.fromJson(file.readText(), object : TypeToken<List<CachedInProgressItem>>() {}.type)
                    ?: emptyList()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) =
        saveNextUpSnapshot(items, force, profileManager.activeProfileId.value)

    /** An explicit profile keeps a pipeline's snapshot owned even if the active profile changes. */
    suspend fun saveNextUpSnapshot(
        items: List<CachedNextUpItem>,
        force: Boolean = false,
        profileId: Int,
        expectedClearVersion: Int? = null
    ) = saveSnapshot("nextup", items.toList(), force, profileId, expectedClearVersion, nextUpWrites)

    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) =
        saveInProgressSnapshot(items, force, profileManager.activeProfileId.value)

    suspend fun saveInProgressSnapshot(
        items: List<CachedInProgressItem>,
        force: Boolean = false,
        profileId: Int,
        expectedClearVersion: Int? = null
    ) = saveSnapshot("inprogress", items.toList(), force, profileId, expectedClearVersion, inProgressWrites)

    private suspend fun saveSnapshot(
        kind: String,
        items: List<*>,
        force: Boolean,
        profileId: Int,
        expectedClearVersion: Int?,
        writes: MutableMap<Int, WriteStamp>
    ) {
        val clearVersion = expectedClearVersion ?: _cacheCleared.value
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (clearVersion != _cacheCleared.value || snapshotStorage.isRetired(profileId)) return@withContext
                val contentHash = items.hashCode()
                val previous = writes[profileId]
                val file = snapshotFile(kind, profileId)
                if (!force && previous != null && previous.fileEpoch == snapshotStorage.fileEpoch(profileId) && file.exists() &&
                    (contentHash == previous.hash || System.currentTimeMillis() - previous.atMs < THROTTLE_MS)) {
                    return@withContext
                }
                try {
                    atomicWrite(file, gson.toJson(items))
                    writes[profileId] = WriteStamp(contentHash, System.currentTimeMillis(), snapshotStorage.fileEpoch(profileId))
                    _snapshotVersion.value++
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write $kind cache: ${e.message}")
                }
            }
        }
    }

    /** Deletes snapshots for the profile where the request started. */
    suspend fun clearAll() = clearAll(profileManager.activeProfileId.value)

    suspend fun clearAll(profileId: Int) {
        try {
            snapshotStorage.clearProfile(profileId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}
