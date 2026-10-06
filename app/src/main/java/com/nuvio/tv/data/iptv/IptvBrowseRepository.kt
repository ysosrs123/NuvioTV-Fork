package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideGridRow
import com.nuvio.tv.core.iptv.GuideMatch
import com.nuvio.tv.core.iptv.GuideMatchReason
import com.nuvio.tv.core.iptv.GuideGridWindow
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.layoutGuideRow
import com.nuvio.tv.core.iptv.guideMatchName
import com.nuvio.tv.core.iptv.resolveGuideMapping
import com.nuvio.tv.core.iptv.uniqueNameMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class IptvBrowseRepository(private val catalogue: IptvCatalogueStore, private val guides: IptvGuideStore) {
    suspend fun setGuideFeeds(ref: IptvSourceRef, feeds: List<IptvGuideRef>, priority: List<IptvGuideRef> = emptyList()) = withContext(Dispatchers.IO) {
        require(feeds.all { it.profileId == ref.profileId })
        feeds.forEach { guides.feed(it) }
        currentCoroutineContext().ensureActive()
        catalogue.setGuideFeeds(ref, feeds, priority)
    }

    suspend fun page(ref: IptvSourceRef, query: IptvBrowseQuery = IptvBrowseQuery(), cursor: IptvBrowseCursor? = null,
        limit: Int = 100): IptvBrowsePage = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val page = catalogue.page(ref, query, cursor, limit)
        val ids = page.items.flatMap { listOfNotNull(it.channel.data.guideId?.takeIf(String::isNotBlank), it.overlay.manualGuide?.externalId) }.toSet()
        val feeds = guides.matchingIndexes(ref.profileId, page.guides.feedIds, ids)
        val order = (page.guides.priority + page.guides.feedIds).distinct()
        currentCoroutineContext().ensureActive()
        val matches = page.items.map { row -> resolveGuideMapping(row.channel.data.guideId, row.overlay.manualGuide, feeds, order) }
        val unmatched = page.items.indices.filter { matches[it].reason == GuideMatchReason.NONE }
        val names = unmatched.map { guideMatchName(page.items[it].channel.data.name) }.filter { it.length >= 2 }.toSet()
        val indexes = if (names.isEmpty()) emptyList() else guides.nameIndexes(ref.profileId, order, names)
        currentCoroutineContext().ensureActive()
        IptvBrowsePage(page, page.items.mapIndexed { index, row ->
            val byName = if (index in unmatched) uniqueNameMatch(row.channel.data.name, indexes) else null
            IptvListedChannel(row, byName?.let { GuideMatch(it, GuideMatchReason.NAME) } ?: matches[index])
        })
    }

    suspend fun guideRows(profileId: Int, channels: List<IptvListedChannel>, window: GuideGridWindow,
        maxPerChannel: Int = 400): Map<String, GuideGridRow> = withContext(Dispatchers.IO) {
        require(channels.size <= 200 && maxPerChannel in 1..2000)
        val range = IptvGuideWindow(window.startMillis, window.endMillis)
        channels.associate { row ->
            currentCoroutineContext().ensureActive()
            val key = row.guide.key
            val programmes = mutableListOf<GuideProgramme>()
            if (key != null) {
                var offset = 0
                while (programmes.size < maxPerChannel) {
                    currentCoroutineContext().ensureActive()
                    val page = guides.programmes(IptvGuideRef(profileId, key.feedId), key.externalId, range, offset, 200)
                    programmes += page.programmes; offset += page.programmes.size
                    if (!page.hasMore || page.programmes.isEmpty()) break
                }
            }
            row.item.channel.id to layoutGuideRow(programmes.take(maxPerChannel), window)
        }
    }
}
