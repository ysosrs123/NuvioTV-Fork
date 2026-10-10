package com.nuvio.tv.data.iptv

import android.content.SharedPreferences
import com.nuvio.tv.core.iptv.ExpiryWarning
import com.nuvio.tv.core.iptv.SourceConnections
import com.nuvio.tv.core.iptv.SourceExpiries
import com.nuvio.tv.core.iptv.SourceExpiry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class IptvSourceConnections(private val store: IptvCatalogueStore, private val preferences: IptvLivePreferences) {
    fun reported(ref: IptvSourceRef, advertised: Int?) {
        val provider = advertised?.takeIf { it > 0 }
        preferences.setProviderConnections(ref, provider ?: 0)
        if (!preferences.connectionsManual(ref)) SourceConnections.automaticLimit(provider)?.let { apply(ref, it) }
    }

    fun reportedExpiry(ref: IptvSourceRef, expiresAtSeconds: Long?) = preferences.preferences.edit().apply {
        val key = preferences.key(ref, EXPIRY)
        if (expiresAtSeconds == null) remove(key) else putLong(key, expiresAtSeconds.coerceAtLeast(SourceExpiries.NONE))
    }.apply()

    fun expiry(ref: IptvSourceRef): Long? = preferences.key(ref, EXPIRY).let { key ->
        if (preferences.preferences.contains(key)) runCatching { preferences.preferences.getLong(key, SourceExpiries.NONE) }.getOrNull() else null
    }

    fun expiries(profileId: Int): List<SourceExpiry> = store.sources(profileId).mapNotNull { source ->
        expiry(source.ref)?.let { SourceExpiry(source.ref.sourceId, source.label, it) }
    }

    fun expiryWarning(profileId: Int, now: () -> Long = System::currentTimeMillis): Flow<ExpiryWarning?> = channelFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == null || (key.startsWith("$profileId:") && key.endsWith(":$EXPIRY"))) changed.trySend(Unit) }
        preferences.preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            while (true) {
                send(withContext(Dispatchers.IO) { runCatching { SourceExpiries.soonest(expiries(profileId), now()) }.getOrNull() })
                withTimeoutOrNull(RECHECK_MILLIS) { changed.receive() }
            }
        } finally { preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun choose(ref: IptvSourceRef, count: Int?) {
        require(count == null || count in 1..SourceConnections.MAX)
        apply(ref, count ?: SourceConnections.automaticLimit(preferences.providerConnections(ref)) ?: 1)
        preferences.setConnectionsManual(ref, count != null)
    }

    private fun apply(ref: IptvSourceRef, limit: Int) {
        val source = store.sources(ref.profileId).single { it.ref == ref }
        val account = store.accounts(ref.profileId).firstOrNull { it.id == source.accountId }
        val change = SourceConnections.change(ref.sourceId, source.accountId, account?.sources.orEmpty().size, account?.maxStreams ?: 1, limit) ?: return
        store.saveAccount(ref.profileId, change.accountId, source.label, change.limit)
        if (change.reassign) store.assignAccount(ref, change.accountId)
        IptvLog.info("source connections limit=${change.limit} moved=${change.reassign}")
    }

    private companion object {
        const val EXPIRY = "provider-expiry"
        const val RECHECK_MILLIS = 60 * 60_000L
    }
}
