package com.nuvio.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** File ownership shared by snapshots, profile retirement and account reset, without a profile dependency. */
@Singleton
class ContinueWatchingSnapshotStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    internal val mutex = Mutex()
    internal val lifecycleMutex = Mutex()
    private val cleared = MutableStateFlow(0)
    val cacheCleared: StateFlow<Int> = cleared
    private val retiredProfiles = mutableSetOf<Int>()
    private val profileFileEpochs = mutableMapOf<Int, Int>()
    private var resetFileEpoch = 0
    internal fun fileEpoch(profileId: Int): Int = profileFileEpochs[profileId] ?: resetFileEpoch
    // Null before a reset: existing persisted profiles are available. A reset admits only fresh creations.
    private var profilesAfterReset: MutableSet<Int>? = null

    /** Called only while the file mutex is held. */
    internal fun isRetired(profileId: Int): Boolean = profileId in retiredProfiles ||
        profilesAfterReset?.let { profileId !in it } == true

    internal fun snapshotFile(kind: String, profileId: Int): File {
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "${kind}_${profileId}.json")
    }

    internal suspend fun clearProfile(profileId: Int, retire: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (retire) { retiredProfiles.add(profileId); profilesAfterReset?.remove(profileId) }
            try {
                listOf("nextup", "inprogress").forEach { kind ->
                    val file = snapshotFile(kind, profileId)
                    file.delete()
                    File(file.parentFile, "${file.name}.tmp").delete()
                }
            } finally { profileFileEpochs[profileId] = cleared.value + 1; cleared.value++ }
        }
    }

    internal suspend fun activateProfile(profileId: Int) = mutex.withLock {
        activateLocked(profileId)
    }

    /** Synced metadata can restore a listed profile without going through local creation. */
    internal suspend fun reopenListedProfile(profileId: Int): Boolean = mutex.withLock {
        if (!isRetired(profileId)) return@withLock false
        activateLocked(profileId)
        true
    }

    private fun activateLocked(profileId: Int) {
        retiredProfiles.remove(profileId)
        profilesAfterReset?.add(profileId)
        profileFileEpochs[profileId] = cleared.value + 1
        // A request admitted while this numeric ID was retired cannot cross its recreation.
        cleared.value++
    }

    internal suspend fun clearAllProfiles() = withContext(Dispatchers.IO) {
        mutex.withLock {
            profilesAfterReset = mutableSetOf(1)
            retiredProfiles.clear()
            try { File(context.filesDir, "cw_enrichment").deleteRecursively() }
            finally { resetFileEpoch = cleared.value + 1; profileFileEpochs.clear(); cleared.value++ }
        }
    }
}
