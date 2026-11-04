package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val account = cacheCheckAccounts().firstOrNull() ?: return groups
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

    /**
     * Asks every connected account that can tell what it has cached, all at once. A torrent is marked cached with
     * the first account in the preferred order that has it, and not cached only when every account answered no.
     */
    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups
        val allHashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (allHashes.isEmpty()) return groups

        val answers = coroutineScope {
            accounts.map { account -> async { checkAccount(account, allHashes) } }.awaitAll()
        }
        val first = accounts.first()

        return groups.updateAvailabilityStatus { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val cachedAt = accounts.indices.firstOrNull { index -> answers[index]?.get(hash) != null }
            val status = when {
                cachedAt != null -> {
                    val account = accounts[cachedAt]
                    val item = answers[cachedAt]?.get(hash)
                    StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.CACHED,
                        cachedName = item?.name,
                        cachedSize = item?.size
                    )
                }
                answers.all { it != null && hash in it } -> StreamDebridCacheStatus(
                    providerId = first.provider.id,
                    providerName = first.provider.displayName,
                    state = StreamDebridCacheState.NOT_CACHED
                )
                else -> StreamDebridCacheStatus(
                    providerId = first.provider.id,
                    providerName = first.provider.displayName,
                    state = StreamDebridCacheState.UNKNOWN
                )
            }
            stream.copy(debridCacheStatus = status)
        }
    }

    /**
     * What [account] says about [hashes]: cached ones map to their item, checked but not cached ones to null.
     * Hashes this account could not answer for are missing; null when it answered nothing at all.
     */
    private suspend fun checkAccount(
        account: DebridServiceCredential,
        hashes: List<String>
    ): Map<String, LocalDebridCachedItem?>? {
        // Short-TTL memo so the same hash surfaced by two addons in different batches is not checked twice.
        // 60s staleness is acceptable: a NOT_CACHED torrent that becomes cached is picked up on the next list.
        val now = System.currentTimeMillis()
        val memoKeyPrefix = account.provider.id + "|"
        val fresh = HashMap<String, LocalDebridCachedItem?>()
        val toCheck = ArrayList<String>(hashes.size)
        synchronized(resultMemo) {
            hashes.forEach { hash ->
                val entry = resultMemo[memoKeyPrefix + hash]
                if (entry != null && now - entry.atMs <= MEMO_TTL_MS) {
                    fresh[hash] = entry.item
                } else {
                    toCheck += hash
                }
            }
        }
        if (toCheck.isEmpty()) return fresh
        val checked = localDebridService.checkCached(account = account, hashes = toCheck)
            ?: return fresh.takeIf { it.isNotEmpty() }
        synchronized(resultMemo) {
            if (resultMemo.size > MEMO_MAX_ENTRIES) resultMemo.clear()
            toCheck.forEach { hash ->
                resultMemo[memoKeyPrefix + hash] = MemoEntry(checked[hash], now)
            }
        }
        toCheck.forEach { hash -> fresh[hash] = checked[hash] }
        return fresh
    }

    private class MemoEntry(val item: LocalDebridCachedItem?, val atMs: Long)

    private val resultMemo = HashMap<String, MemoEntry>()

    private companion object {
        const val MEMO_TTL_MS = 60_000L
        const val MEMO_MAX_ENTRIES = 4_096
    }

    suspend fun isCached(hash: String): Boolean? {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return null
        var unknown = false
        for (account in accounts) {
            when (localDebridService.isCached(account, hash)) {
                true -> return true
                null -> unknown = true
                false -> Unit
            }
        }
        return if (unknown) null else false
    }

    private suspend fun cacheCheckAccounts(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return emptyList()
        return DebridProviders.plainTorrentServices(settings)
            .filter { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
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
