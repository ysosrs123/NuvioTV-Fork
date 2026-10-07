package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.SourceConnections

class IptvSourceConnections(private val store: IptvCatalogueStore, private val preferences: IptvLivePreferences) {
    fun reported(ref: IptvSourceRef, advertised: Int?) {
        val provider = advertised?.takeIf { it > 0 }
        preferences.setProviderConnections(ref, provider ?: 0)
        if (!preferences.connectionsManual(ref)) SourceConnections.automaticLimit(provider)?.let { apply(ref, it) }
    }

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
}
