package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideMatch

data class IptvBrowseQuery(val search: String = "", val favouritesOnly: Boolean = false,
    val includeHidden: Boolean = false, val includeUnavailable: Boolean = false, val category: String? = null,
    val excludedCategories: Set<String> = emptySet()) {
    init { require(search.length <= 256 && (category?.length ?: 0) <= 240 && excludedCategories.size <= 500 && excludedCategories.all { it.length <= 240 }) }
}
data class IptvCategory(val name: String, val channels: Int)
data class IptvBrowseRevision(val ref: IptvSourceRef, val generation: Long?, val configuration: Long, val overlays: Long)
data class IptvBrowseCursor(val revision: IptvBrowseRevision, val query: IptvBrowseQuery, val offset: Int)
class IptvCatalogueChangedException : IllegalStateException("IPTV catalogue changed; restart paging")
data class IptvGuideAssociations(val feedIds: List<String> = emptyList(), val priority: List<String> = emptyList()) {
    fun without(feedId: String) = IptvGuideAssociations(feedIds - feedId, priority - feedId)
}
data class IptvCataloguePage(val source: IptvSource, val revision: IptvBrowseRevision,
    val items: List<IptvCatalogueItem>, val guides: IptvGuideAssociations, val next: IptvBrowseCursor?)
data class IptvListedChannel(val item: IptvCatalogueItem, val guide: GuideMatch)
data class IptvBrowsePage(val catalogue: IptvCataloguePage, val channels: List<IptvListedChannel>)
