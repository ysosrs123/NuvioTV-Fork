package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.XtreamGuideReference

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

    fun removeSource(source: IptvSourceRef) {
        val reference = XtreamGuideReference.of(source.sourceId)
        val automatic = allFeeds(source.profileId).filter { feed -> runCatching { guides.endpoint(feed) == reference }.getOrDefault(false) }
        catalogue.removeSource(source)
        automatic.forEach(::removeFeed)
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
