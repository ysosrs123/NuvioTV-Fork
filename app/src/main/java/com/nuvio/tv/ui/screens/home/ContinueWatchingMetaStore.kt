package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CwMetaStoreEntry(val key: String, val keepUntilMs: Long, val meta: CwMetaSummary)

@Serializable
internal data class CwMetaStoreFile(val version: Int, val context: String, val entries: List<CwMetaStoreEntry>)

/**
 * Continue watching details kept across app starts, one file per profile, so a start does not ask the add-ons
 * again for what is still fresh. Entries past their time, or saved under other settings, are not read back.
 */
internal class ContinueWatchingMetaStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(profileId: Int, context: String, nowMs: Long): List<CwMetaStoreEntry> = runCatching {
        val file = file(profileId)
        if (!file.isFile) return emptyList()
        val stored = json.decodeFromString(CwMetaStoreFile.serializer(), file.readText())
        if (stored.version != VERSION || stored.context != context) emptyList()
        else stored.entries.filter { it.keepUntilMs > nowMs }
    }.getOrElse { emptyList() }

    fun save(profileId: Int, context: String, entries: List<CwMetaStoreEntry>) {
        runCatching {
            dir.mkdirs()
            val target = file(profileId)
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(json.encodeToString(CwMetaStoreFile.serializer(), CwMetaStoreFile(VERSION, context, entries)))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    private fun file(profileId: Int) = File(dir, "cw_meta_$profileId.json")

    companion object {
        const val VERSION = 1
        const val TAG = "CwMetaStore"
    }
}

private const val CW_META_SAVE_DELAY_MS = 5_000L

private suspend fun HomeViewModel.cwMetaStoreContext(profileId: Int): String {
    val source = runCatching { traktSettingsDataStore.watchProgressSource.first() }.getOrNull()
    return "$profileId|$source|${com.nuvio.tv.LocaleCache.localeTag}|$externalMetaPrefetchEnabled"
}

/** Reads the active profile's saved details into the in-memory cache once per profile and settings. */
internal suspend fun HomeViewModel.ensureCwMetaStoreLoaded() {
    val store = cwMetaStore ?: return
    try {
        val profileId = profileManager.activeProfileId.value
        val context = cwMetaStoreContext(profileId)
        if (cwMetaStoreLoadedFor == context) return
        cwMetaStoreMutex.withLock {
            if (cwMetaStoreLoadedFor == context) return
            val nowMs = System.currentTimeMillis()
            val entries = withContext(Dispatchers.IO) { store.load(profileId, context, nowMs) }
            var added = 0
            synchronized(cwMetaCache) {
                entries.forEach { entry ->
                    if (!cwMetaCache.containsKey(entry.key)) {
                        cwMetaCache[entry.key] = entry.meta
                        cwMetaRefreshAtMs[entry.key] = entry.keepUntilMs
                        added++
                    }
                }
            }
            cwMetaStoreLoadedFor = context
            cwMetaStoreProfileId = profileId
            Log.i(ContinueWatchingMetaStore.TAG, "loaded $added of ${entries.size} saved details profile=$profileId")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(ContinueWatchingMetaStore.TAG, "saved details not read", e)
    }
}

/** Writes the cache a few seconds after the last new answer, so a burst of look-ups is one write. */
internal fun HomeViewModel.scheduleCwMetaStoreSave() {
    val store = cwMetaStore ?: return
    val context = cwMetaStoreLoadedFor ?: return
    val profileId = cwMetaStoreProfileId ?: return
    synchronized(store) {
        cwMetaStoreSaveJob?.cancel()
        cwMetaStoreSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(CW_META_SAVE_DELAY_MS)
            if (cwMetaStoreLoadedFor != context) return@launch
            val entries = synchronized(cwMetaCache) {
                cwMetaCache.mapNotNull { (key, meta) ->
                    val keepUntil = cwMetaRefreshAtMs[key] ?: return@mapNotNull null
                    meta?.let { CwMetaStoreEntry(key, keepUntil, it) }
                }
            }
            store.save(profileId, context, entries)
        }
    }
}
