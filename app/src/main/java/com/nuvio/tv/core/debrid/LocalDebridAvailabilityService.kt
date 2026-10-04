package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val account = cacheCheckAccount() ?: return groups
        return groups.updateAvailabilityStatus { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.CHECKING
                    )
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val account = cacheCheckAccount() ?: return groups
        val allHashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (allHashes.isEmpty()) return groups

        // Short-TTL result memo so the same hash surfaced by
        // two addons in DIFFERENT batches isn't checked twice against the
        // debrid API. 60s staleness is acceptable: a NOT_CACHED torrent that
        // becomes cached is picked up on the next stream-list open.
        val now = System.currentTimeMillis()
        val memoKeyPrefix = account.provider.id + "|"
        val fresh = HashMap<String, LocalDebridCachedItem?>()
        val toCheck = ArrayList<String>(allHashes.size)
        synchronized(resultMemo) {
            allHashes.forEach { hash ->
                val entry = resultMemo[memoKeyPrefix + hash]
                if (entry != null && now - entry.atMs <= MEMO_TTL_MS) {
                    fresh[hash] = entry.item
                } else {
                    toCheck += hash
                }
            }
        }

        val checked: Map<String, LocalDebridCachedItem>? = if (toCheck.isEmpty()) {
            emptyMap()
        } else {
            localDebridService.checkCached(account = account, hashes = toCheck)
        }
        if (checked == null) {
            // API failure: mark only what we had to check as UNKNOWN; memoised
            // hashes still resolve below.
            if (fresh.isEmpty()) {
                return groups.updateAvailabilityStatus { stream ->
                    val hash = stream.localAvailabilityHash()
                    if (hash == null) {
                        stream
                    } else {
                        stream.copy(
                            debridCacheStatus = StreamDebridCacheStatus(
                                providerId = account.provider.id,
                                providerName = account.provider.displayName,
                                state = StreamDebridCacheState.UNKNOWN
                            )
                        )
                    }
                }
            }
        } else {
            synchronized(resultMemo) {
                if (resultMemo.size > MEMO_MAX_ENTRIES) resultMemo.clear()
                toCheck.forEach { hash ->
                    resultMemo[memoKeyPrefix + hash] = MemoEntry(checked[hash], now)
                }
            }
            checked.forEach { (hash, item) -> fresh[hash] = item }
            toCheck.forEach { hash -> if (hash !in fresh) fresh[hash] = null }
        }

        return groups.updateAvailabilityStatus { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            if (hash !in fresh) {
                // Only possible on the partial-failure path above: this hash's
                // API call failed while others were memoised.
                return@updateAvailabilityStatus stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.UNKNOWN
                    )
                )
            }
            val cachedItem = fresh[hash]
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = account.provider.id,
                    providerName = account.provider.displayName,
                    state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                    cachedName = cachedItem?.name,
                    cachedSize = cachedItem?.size
                )
            )
        }
    }

    private class MemoEntry(val item: LocalDebridCachedItem?, val atMs: Long)

    private val resultMemo = HashMap<String, MemoEntry>()

    private companion object {
        const val MEMO_TTL_MS = 60_000L
        const val MEMO_MAX_ENTRIES = 4_096
    }

    suspend fun isCached(hash: String): Boolean? {
        val account = cacheCheckAccount() ?: return null
        return localDebridService.isCached(account, hash)
    }

    private suspend fun cacheCheckAccount(): DebridServiceCredential? {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return null
        return settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED
)

fun Stream.localAvailabilityHash(): String? =
    getEffectiveInfoHash()
        ?.trim()
        ?.lowercase()
        ?.takeIf { needsLocalDebridResolve() && it.isNotBlank() }

private fun List<AddonStreams>.updateAvailabilityStatus(
    transform: (Stream) -> Stream
): List<AddonStreams> =
    map { group ->
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
