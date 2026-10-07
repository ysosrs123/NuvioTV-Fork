package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideGridRow
import com.nuvio.tv.core.iptv.GuideMatch
import com.nuvio.tv.core.iptv.GuideMatchReason
import com.nuvio.tv.core.iptv.GuideGridWindow
import com.nuvio.tv.core.iptv.GuideProgramme
import com.nuvio.tv.core.iptv.airingChannels
import com.nuvio.tv.core.iptv.earliestAiring
import com.nuvio.tv.core.iptv.guideAiringCandidates
import com.nuvio.tv.core.iptv.guideSearchQuery
import com.nuvio.tv.core.iptv.layoutGuideRow
import com.nuvio.tv.core.iptv.guideIdWithoutFeedSuffix
import com.nuvio.tv.core.iptv.guideMatchName
import com.nuvio.tv.core.iptv.resolveGuideMapping
import com.nuvio.tv.core.iptv.SPORTS_AHEAD_MILLIS
import com.nuvio.tv.core.iptv.sportsOrder
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
        IptvBrowsePage(page, listed(ref.profileId, page.guides, page.items))
    }

    suspend fun searchAiring(ref: IptvSourceRef, query: String, nowMillis: Long, limit: Int = 60,
        excludedCategories: Set<String> = emptySet()): List<IptvAiringResult> = withContext(Dispatchers.IO) {
        require(limit in 1..200)
        if (guideSearchQuery(query) == null) return@withContext emptyList()
        val associations = catalogue.guideAssociations(ref)
        val order = (associations.priority + associations.feedIds).distinct()
        if (order.isEmpty()) return@withContext emptyList()
        currentCoroutineContext().ensureActive()
        val matches = guides.airingMatches(ref.profileId, order, query, nowMillis, 400)
        if (matches.isEmpty()) return@withContext emptyList()
        val wanted = guideAiringCandidates(matches.map { it.key to it.channel })
        currentCoroutineContext().ensureActive()
        val items = catalogue.guideMatchCandidates(ref, wanted.guideIds, wanted.nameKeys, wanted.keys, 600, excludedCategories)
        val channels = items.chunked(200).flatMap { listed(ref.profileId, associations, it) }
        airingChannels(channels, { it.item.channel.id }, { it.guide.key }, earliestAiring(matches.map { it.key to it.programme }), limit)
            .map { (channel, programme) -> IptvAiringResult(channel, programme) }
    }

    suspend fun sports(ref: IptvSourceRef, nowMillis: Long, aheadMillis: Long = SPORTS_AHEAD_MILLIS, limit: Int = 60,
        excludedCategories: Set<String> = emptySet()): List<IptvAiringResult> = withContext(Dispatchers.IO) {
        require(limit in 1..200 && aheadMillis >= 0)
        val associations = catalogue.guideAssociations(ref)
        val order = (associations.priority + associations.feedIds).distinct()
        if (order.isEmpty()) return@withContext emptyList()
        currentCoroutineContext().ensureActive()
        val matches = guides.sportsMatches(ref.profileId, order, nowMillis, nowMillis + aheadMillis, 3000)
        if (matches.isEmpty()) return@withContext emptyList()
        val wanted = guideAiringCandidates(matches.distinctBy { it.key }.take(MAX_SPORTS_CHANNELS).map { it.key to it.channel })
        currentCoroutineContext().ensureActive()
        val items = catalogue.guideMatchCandidates(ref, wanted.guideIds, wanted.nameKeys, wanted.keys, 600, excludedCategories)
        val channels = items.chunked(200).flatMap { listed(ref.profileId, associations, it) }
        val listings = airingChannels(channels, { it.item.channel.id }, { it.guide.key }, earliestAiring(matches.map { it.key to it.programme }), 3000)
        sportsOrder(listings, nowMillis).take(limit).map { (channel, programme) -> IptvAiringResult(channel, programme) }
    }

    private suspend fun listed(profileId: Int, associations: IptvGuideAssociations, items: List<IptvCatalogueItem>): List<IptvListedChannel> {
        currentCoroutineContext().ensureActive()
        val ids = items.flatMap { row ->
            val guideId = row.channel.data.guideId?.takeIf(String::isNotBlank)
            listOfNotNull(guideId, guideId?.let(::guideIdWithoutFeedSuffix), row.overlay.manualGuide?.externalId)
        }.toSet()
        val feeds = guides.matchingIndexes(profileId, associations.feedIds, ids)
        val order = (associations.priority + associations.feedIds).distinct()
        currentCoroutineContext().ensureActive()
        val matches = items.map { row -> resolveGuideMapping(row.channel.data.guideId, row.overlay.manualGuide, feeds, order) }
        val unmatched = items.indices.filter { matches[it].reason == GuideMatchReason.NONE }
        val names = unmatched.map { guideMatchName(items[it].channel.data.name) }.filter { it.length >= 2 }.toSet()
        val indexes = if (names.isEmpty()) emptyList() else guides.nameIndexes(profileId, order, names)
        currentCoroutineContext().ensureActive()
        return items.mapIndexed { index, row ->
            val byName = if (index in unmatched) uniqueNameMatch(row.channel.data.name, indexes) else null
            IptvListedChannel(row, byName?.let { GuideMatch(it, GuideMatchReason.NAME) } ?: matches[index])
        }
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

    private companion object {
        const val MAX_SPORTS_CHANNELS = 500
    }
}
