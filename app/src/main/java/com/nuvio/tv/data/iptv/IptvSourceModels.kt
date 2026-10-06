package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.ChannelCandidate
import com.nuvio.tv.core.iptv.GuideKey
import com.nuvio.tv.core.iptv.StoredChannel
import com.nuvio.tv.core.iptv.RefreshTicket

enum class IptvStreamFormat { AUTO, HLS, MPEG_TS }

enum class IptvSourceKind { M3U, XTREAM, STALKER }
data class IptvSourceRef(val profileId: Int, val sourceId: String) {
    init { require(profileId >= 0 && sourceId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
}
data class IptvSourceConnection(val endpoint: String, val username: String? = null, val password: String? = null) {
    override fun toString(): String = "IptvSourceConnection(credentials withheld)"
}
data class IptvSource(
    val ref: IptvSourceRef, val label: String, val kind: IptvSourceKind, val accountId: String,
    val configurationVersion: Long, val requestedGeneration: Long,
    val activeGeneration: Long?, val activeConfigurationVersion: Long?,
    val refreshedAtMillis: Long? = null,
) {
    val playbackEligible: Boolean get() = activeGeneration != null && configurationVersion == activeConfigurationVersion
}
data class IptvAccountGroup(val id: String, val label: String, val maxStreams: Int, val sources: List<IptvSourceRef>)
data class IptvCatalogueRecord(val data: ChannelCandidate, val attributes: Map<String, String> = emptyMap()) {
    override fun toString(): String = "IptvCatalogueRecord(metadata withheld)"
}
data class IptvChannelOverlay(
    val customName: String? = null, val favouriteRank: Int? = null, val hidden: Boolean = false,
    val manualGuide: GuideKey? = null, val streamFormat: IptvStreamFormat = IptvStreamFormat.AUTO,
)
data class IptvCatalogueItem(val channel: StoredChannel, val attributes: Map<String, String>, val overlay: IptvChannelOverlay) {
    override fun toString(): String = "IptvCatalogueItem(id=${channel.id}, available=${channel.available})"
}
data class IptvCatalogueSnapshot(val source: IptvSource, val channels: List<IptvCatalogueItem>, val validators: IptvCacheValidators?)
data class IptvCacheValidators(val etag: String? = null, val lastModified: String? = null) {
    override fun toString(): String = "IptvCacheValidators(values withheld)"
}
data class IptvRefreshRequest(val ticket: RefreshTicket, val kind: IptvSourceKind, val connection: IptvSourceConnection, val validators: IptvCacheValidators?) {
    override fun toString(): String = "IptvRefreshRequest(ticket=$ticket, kind=$kind)"
}
