package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.XtreamGuideReference
import com.nuvio.tv.core.iptv.playlistGuideAddresses

class IptvXtreamGuides(private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore) {
    fun ensure(source: IptvSourceRef): IptvGuideRef {
        val reference = XtreamGuideReference.of(source.sourceId)
        val linked = catalogue.guideAssociations(source)
        linked.feedIds.map { IptvGuideRef(source.profileId, it) }
            .firstOrNull { feed -> runCatching { guides.endpoint(feed) == reference }.getOrDefault(false) }
            ?.let { return it }
        val existing = guides.feeds(source.profileId, limit = 200).map { it.ref }
            .firstOrNull { feed -> runCatching { guides.endpoint(feed) == reference }.getOrDefault(false) }
        val label = catalogue.sources(source.profileId).single { it.ref == source }.label
        val feed = existing ?: guides.createFeed(source.profileId, "$label guide".take(240), reference)
        if (linked.feedIds.size >= MAX_LINKED_FEEDS) return feed
        catalogue.setGuideFeeds(source, linked.feedIds.map { IptvGuideRef(source.profileId, it) } + feed,
            linked.priority.map { IptvGuideRef(source.profileId, it) })
        return feed
    }

    fun ensurePlaylist(source: IptvSourceRef, addresses: List<String>): List<IptvGuideRef> {
        val usable = playlistGuideAddresses(addresses)
        if (usable.isEmpty()) return emptyList()
        val linked = catalogue.guideAssociations(source)
        val known = allFeeds(source.profileId).mapNotNull { feed -> runCatching { guides.endpoint(feed).trim() }.getOrNull()?.let { it to feed } }.toMap()
        val label = catalogue.sources(source.profileId).single { it.ref == source }.label
        val feeds = linked.feedIds.map { IptvGuideRef(source.profileId, it) }.toMutableList()
        val found = usable.mapIndexed { index, address ->
            known[address] ?: guides.createFeed(source.profileId, "$label guide${if (index == 0) "" else " ${index + 1}"}".take(240), address)
        }
        val added = found.filter { it !in feeds }.take(MAX_LINKED_FEEDS - feeds.size)
        if (added.isNotEmpty()) catalogue.setGuideFeeds(source, feeds + added, linked.priority.map { IptvGuideRef(source.profileId, it) })
        return found
    }

    fun linked(source: IptvSourceRef): IptvGuideRef? {
        val reference = XtreamGuideReference.of(source.sourceId)
        return catalogue.guideAssociations(source).feedIds.map { IptvGuideRef(source.profileId, it) }
            .firstOrNull { feed -> runCatching { guides.endpoint(feed) == reference }.getOrDefault(false) }
    }

    fun linkedPlaylist(source: IptvSourceRef, addresses: List<String>): List<IptvGuideRef> {
        val usable = playlistGuideAddresses(addresses).toSet()
        if (usable.isEmpty()) return emptyList()
        return catalogue.guideAssociations(source).feedIds.map { IptvGuideRef(source.profileId, it) }
            .filter { feed -> runCatching { guides.endpoint(feed).trim() in usable }.getOrDefault(false) }
    }

    fun removeSource(source: IptvSourceRef) {
        val reference = XtreamGuideReference.of(source.sourceId)
        val automatic = allFeeds(source.profileId).filter { feed -> runCatching { guides.endpoint(feed) == reference }.getOrDefault(false) }
        val others = catalogue.sources(source.profileId).filter { it.ref != source }
            .flatMapTo(HashSet()) { catalogue.guideAssociations(it.ref).feedIds }
        val unused = catalogue.guideAssociations(source).feedIds.filter { it !in others }.map { IptvGuideRef(source.profileId, it) }
        catalogue.removeSource(source)
        (automatic + unused).distinct().forEach(::removeFeed)
    }

    fun removeFeed(feed: IptvGuideRef) {
        catalogue.removeGuideFeed(feed)
        guides.removeFeed(feed)
    }

    private fun allFeeds(profileId: Int): List<IptvGuideRef> = buildList {
        while (true) {
            val page = guides.feeds(profileId, offset = size, limit = 200)
            addAll(page.map { it.ref })
            if (page.size < 200) break
        }
    }

    private companion object {
        const val MAX_LINKED_FEEDS = 16
    }
}
