package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchedItemsPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_items_preferences"
        private const val TAG = "WatchedItemsPrefs"
    }

    // A reused numeric profile ID receives a different DataStore generation.
    private val owners = ConcurrentHashMap<DataStore<Preferences>, WatchedItemsOwner>()
    private fun owner(profileId: Int, generation: WatchedItemsGeneration? = null): WatchedItemsOwner {
        if (generation != null) {
            require(generation.profileId == profileId) { "Watched generation belongs to another profile" }
            (generation.owner.store as? ProfileStoreLifetime)?.checkActive()
            return generation.owner
        }
        val store = factory.get(profileId, FEATURE)
        val result = owners.computeIfAbsent(store) {
            WatchedItemsOwner(store) { retired -> owners.remove(store, retired) }
        }
        // Retirement can finish before computeIfAbsent publishes its new entry.
        if ((store as? ProfileStoreLifetime)?.retired?.value == true) owners.remove(store, result)
        return result
    }

    internal fun captureGeneration(profileId: Int): WatchedItemsGeneration {
        val owner = owner(profileId)
        return WatchedItemsGeneration(profileId, owner, owner.captureEpoch())
    }

    internal suspend fun decodeStats(profileId: Int): Pair<Long, Long> = owner(profileId).decodeStats()

    private val gson = Gson()
    private val watchedItemsKey = stringSetPreferencesKey("watched_items")
    private val lastSuccessfulPushMsKey = longPreferencesKey("last_successful_watched_push_ms")
    private val deltaCursorKey = longPreferencesKey("watched_items_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("watched_items_delta_initialized")

    suspend fun getLastSuccessfulPushMs(profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null): Long {
        val prefs = owner(profileId, generation).readPreferences(generation?.epoch)
        return prefs[lastSuccessfulPushMsKey] ?: 0L
    }

    /**
     * Advances the stored push point, never lowering it. The comparison happens inside
     * the edit, so two pushes finishing out of order cannot leave the older one on disk.
     * Nothing needs to lower it: deleting a profile removes the whole store.
     */
    suspend fun advanceLastSuccessfulPushMs(timestampMs: Long, profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null) {
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { prefs, decoder ->
            val stored = prefs[lastSuccessfulPushMsKey] ?: 0L
            prefs[lastSuccessfulPushMsKey] = maxOf(stored, timestampMs)
        }
    }

    suspend fun getDeltaCursor(profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null): Long {
        val prefs = owner(profileId, generation).readPreferences(generation?.epoch)
        return prefs[deltaCursorKey] ?: 0L
    }

    suspend fun isDeltaInitialized(profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null): Boolean {
        val prefs = owner(profileId, generation).readPreferences(generation?.epoch)
        return prefs[deltaInitializedKey] ?: false
    }

    suspend fun setDeltaState(cursor: Long, initialized: Boolean = true, profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null) {
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { prefs, decoder ->
            prefs[deltaCursorKey] = cursor.coerceAtLeast(0L)
            prefs[deltaInitializedKey] = initialized
        }
        Log.d(TAG, "setDeltaState: profile=$profileId cursor=${cursor.coerceAtLeast(0L)} initialized=$initialized")
    }

    internal val allItems: Flow<List<WatchedItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        val store = try { owner(pid).store } catch (error: kotlinx.coroutines.CancellationException) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw error
            null
        }
        val changes = (store as? ProfileStoreLifetime)?.generations
            ?: if (store == null) factory.historyGenerationChanges else null
        if (changes != null) changes.flatMapLatest {
            if (factory.isProfileDeleted(pid)) kotlinx.coroutines.flow.flowOf(emptyList<WatchedItem>())
            else try { observeAllItems(pid) } catch (error: kotlinx.coroutines.CancellationException) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw error
                kotlinx.coroutines.flow.flowOf(emptyList<WatchedItem>())
            }
        } else observeAllItems(pid)
    }.distinctUntilChanged()

    fun observeAllItems(profileId: Int): Flow<List<WatchedItem>> = owner(profileId).observe()

    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean> {
        return allItems.map { items ->
            items.any { item ->
                item.contentId == contentId &&
                    item.season == season &&
                    item.episode == episode
            }
        }.distinctUntilChanged()
    }

    fun getWatchedEpisodesForContent(
        contentId: String,
        profileId: Int = profileManager.activeProfileId.value
    ): Flow<Set<Pair<Int, Int>>> {
        return observeAllItems(profileId).map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .map { it.season!! to it.episode!! }
                .toSet()
        }.distinctUntilChanged()
    }

    fun getWatchedEpisodesWithTimestamps(contentId: String): Flow<Map<Pair<Int, Int>, Long>> {
        return allItems.map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associate { (it.season!! to it.episode!!) to it.watchedAt }
        }.distinctUntilChanged()
    }

    suspend fun markAsWatched(
        item: WatchedItem,
        profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null
    ) {
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val itemKey = item.mutationKey()
            decoder.decode(current)
            val filtered = current.filterNot { json ->
                decoder.key(json) == itemKey
            }
            preferences[watchedItemsKey] = filtered.toSet() + gson.toJson(item)
        }
    }

    suspend fun markAsWatchedBatch(
        items: List<WatchedItem>,
        profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null
    ) {
        if (items.isEmpty()) return
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val newKeys = items.map { it.mutationKey() }.toSet()
            decoder.decode(current)
            val filtered = current.filterNot { json ->
                decoder.key(json) in newKeys
            }
            preferences[watchedItemsKey] = filtered.toSet() + items.map { gson.toJson(it) }
        }
    }

    suspend fun unmarkAsWatched(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null
    ) {
        val removeKey = WatchedMutationKey(contentId, season, episode)
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            decoder.decode(current)
            val filtered = current.filterNot { json ->
                decoder.key(json) == removeKey
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun unmarkAsWatchedBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>,
        profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null
    ) {
        if (episodes.isEmpty()) return
        val removeKeys = episodes.map { (s, e) -> WatchedMutationKey(contentId, s, e) }.toSet()
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            decoder.decode(current)
            val filtered = current.filterNot { json ->
                decoder.key(json) in removeKeys
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null): List<WatchedItem> =
        owner(profileId, generation).readItems(generation?.epoch)

    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>, profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null) {
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val localItems = decoder.decode(current)
            val localKeys = localItems.map { Triple(it.contentId, it.season, it.episode) }.toSet()

            val newItems = remoteItems.filter { remote ->
                Triple(remote.contentId, remote.season, remote.episode) !in localKeys
            }

            if (newItems.isNotEmpty()) {
                preferences[watchedItemsKey] = current + newItems.map { gson.toJson(it) }.toSet()
            }
        }
    }

    suspend fun applyRemoteChanges(
        upserts: List<WatchedItem>,
        deletes: List<Triple<String, Int?, Int?>>,
        pendingUpsertKeys: Set<WatchedMutationKey> = emptySet(),
        pendingDeleteKeys: Set<WatchedMutationKey> = emptySet(),
        profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null
    ) {
        if (upserts.isEmpty() && deletes.isEmpty()) {
            Log.d(TAG, "applyRemoteChanges: no changes for profile $profileId")
            return
        }
        var beforeCount = 0
        var afterCount = 0
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            beforeCount = current.size
            val itemsByKey = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            decoder.decode(current).forEach { item ->
                itemsByKey[Triple(item.contentId, item.season, item.episode)] = item
            }
            deletes.forEach { (contentId, season, episode) ->
                val mutationKey = WatchedMutationKey(contentId, season, episode)
                if (mutationKey !in pendingUpsertKeys) {
                    itemsByKey.remove(Triple(contentId, season, episode))
                }
            }
            upserts.forEach { item ->
                val mutationKey = item.mutationKey()
                when {
                    mutationKey in pendingDeleteKeys -> itemsByKey.remove(
                        Triple(item.contentId, item.season, item.episode)
                    )
                    mutationKey !in pendingUpsertKeys -> itemsByKey[
                        Triple(item.contentId, item.season, item.episode)
                    ] = item
                }
            }
            preferences[watchedItemsKey] = itemsByKey.values
                .map { gson.toJson(it) }
                .toSet()
            afterCount = itemsByKey.size
        }
        Log.d(TAG, "applyRemoteChanges: profile=$profileId before=$beforeCount after=$afterCount upserts=${upserts.size} deletes=${deletes.size}")
    }

    suspend fun replaceWithRemoteItems(
        remoteItems: List<WatchedItem>,
        pendingUpsertKeys: Set<WatchedMutationKey> = emptySet(),
        pendingDeleteKeys: Set<WatchedMutationKey> = emptySet(),
        lastSuccessfulPushMs: Long? = null,
        profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null
    ): Boolean {
        var preservedLocalItems = false
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch) { preferences, decoder ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId current=${current.size} remote=${remoteItems.size}")
            val deduped = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            remoteItems.filterNot { it.mutationKey() in pendingDeleteKeys }.forEach { item ->
                deduped[Triple(item.contentId, item.season, item.episode)] = item
            }
            val localItems = decoder.decode(current)
            localItems.forEach { localItem ->
                val mutationKey = localItem.mutationKey()
                val itemKey = Triple(localItem.contentId, localItem.season, localItem.episode)
                if (ServerItemRef.isServerId(localItem.contentId)) {
                    deduped[itemKey] = localItem
                    return@forEach
                }
                val preservePendingUpsert = mutationKey in pendingUpsertKeys
                val preserveAfterPush = mutationKey !in pendingDeleteKeys &&
                    itemKey !in deduped &&
                    lastSuccessfulPushMs != null &&
                    localItem.watchedAt > lastSuccessfulPushMs
                if (preservePendingUpsert || preserveAfterPush) {
                    deduped[itemKey] = localItem
                    preservedLocalItems = true
                }
            }
            preferences[watchedItemsKey] = deduped.values
                .map { gson.toJson(it) }
                .toSet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId stored=${deduped.size} preservedLocal=$preservedLocalItems")
        }
        return preservedLocalItems
    }

    suspend fun clearAll(profileId: Int = profileManager.activeProfileId.value, generation: WatchedItemsGeneration? = null) {
        owner(profileId, generation).edit(expectedEpoch = generation?.epoch, reset = true) { preferences, decoder ->
            preferences.remove(watchedItemsKey)
            preferences.remove(deltaCursorKey)
            preferences.remove(deltaInitializedKey)
        }
    }

}
