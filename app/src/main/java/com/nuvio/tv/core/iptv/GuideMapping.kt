package com.nuvio.tv.core.iptv

data class GuideKey(val feedId: String, val externalId: String)
data class GuideFeedIndex(val feedId: String, val channelIds: Set<String>)
enum class GuideMatchReason { MANUAL, EXACT_ID, NAME, MISSING_MANUAL_TARGET, AMBIGUOUS, NONE }
data class GuideMatch(val key: GuideKey?, val reason: GuideMatchReason, val candidates: List<GuideKey> = emptyList())

fun resolveGuideMapping(
    guideId: String?,
    manual: GuideKey?,
    feeds: List<GuideFeedIndex>,
    orderedFeedPriority: List<String> = emptyList(),
): GuideMatch {
    require(feeds.map { it.feedId }.distinct().size == feeds.size)
    require(orderedFeedPriority.distinct().size == orderedFeedPriority.size)
    if (manual != null) {
        val exists = feeds.any { it.feedId == manual.feedId && manual.externalId in it.channelIds }
        return GuideMatch(manual.takeIf { exists }, if (exists) GuideMatchReason.MANUAL else GuideMatchReason.MISSING_MANUAL_TARGET)
    }
    if (guideId.isNullOrBlank()) return GuideMatch(null, GuideMatchReason.NONE)
    val exact = feeds.filter { guideId in it.channelIds }.map { GuideKey(it.feedId, guideId) }
    val candidates = exact.ifEmpty {
        guideIdWithoutFeedSuffix(guideId)?.let { base -> feeds.filter { base in it.channelIds }.map { GuideKey(it.feedId, base) } }.orEmpty()
    }
    val prioritized = orderedFeedPriority.firstNotNullOfOrNull { feed -> candidates.find { it.feedId == feed } }
    val chosen = prioritized ?: candidates.singleOrNull()
    return GuideMatch(chosen, when {
        chosen != null -> GuideMatchReason.EXACT_ID
        candidates.isNotEmpty() -> GuideMatchReason.AMBIGUOUS
        else -> GuideMatchReason.NONE
    }, candidates)
}

fun guideCandidates(
    guideId: String?,
    manual: GuideKey?,
    feeds: List<GuideFeedIndex>,
    orderedFeedPriority: List<String> = emptyList(),
    name: String? = null,
    names: List<GuideNameIndex> = emptyList(),
): List<GuideMatch> {
    val order = (orderedFeedPriority + feeds.map { it.feedId } + names.map { it.feedId }).distinct()
    val ids = feeds.associate { it.feedId to it.channelIds }
    val found = LinkedHashMap<GuideKey, GuideMatchReason>()
    if (manual != null && ids[manual.feedId]?.contains(manual.externalId) == true) found[manual] = GuideMatchReason.MANUAL
    val id = guideId?.takeIf(String::isNotBlank)
    if (id != null) {
        for (feed in order) if (ids[feed]?.contains(id) == true) found.putIfAbsent(GuideKey(feed, id), GuideMatchReason.EXACT_ID)
        guideIdWithoutFeedSuffix(id)?.let { base ->
            for (feed in order) if (ids[feed]?.contains(base) == true) found.putIfAbsent(GuideKey(feed, base), GuideMatchReason.EXACT_ID)
        }
    }
    val key = name?.let(::guideMatchName)?.takeIf { it.length >= 2 }
    if (key != null) {
        val byFeed = names.associate { it.feedId to it.names }
        for (feed in order) byFeed[feed]?.get(key)?.singleOrNull()?.let { found.putIfAbsent(GuideKey(feed, it), GuideMatchReason.NAME) }
    }
    return found.map { (candidate, reason) -> GuideMatch(candidate, reason) }
}

fun chooseGuide(candidates: List<GuideMatch>, manual: GuideKey?, hasProgrammes: (GuideKey) -> Boolean): GuideMatch =
    candidates.firstOrNull { match -> match.key?.let(hasProgrammes) == true } ?: candidates.firstOrNull()
        ?: GuideMatch(null, if (manual != null) GuideMatchReason.MISSING_MANUAL_TARGET else GuideMatchReason.NONE)

fun appendGuideLink(feedIds: List<String>, priority: List<String>, feedId: String, maxFeeds: Int = 16): Pair<List<String>, List<String>>? {
    if (feedId in feedIds || feedIds.size >= maxFeeds) return null
    return (feedIds + feedId) to ((priority + feedIds).distinct() + feedId)
}

fun programmesAt(programmes: List<GuideProgramme>, channelExternalId: String, instantMillis: Long): List<GuideProgramme> =
    programmes.filter { it.channelExternalId == channelExternalId && it.start.precise && it.stop?.precise == true && it.start.epochMillis <= instantMillis && it.stop.epochMillis > instantMillis }
        .sortedWith(compareBy({ it.start.epochMillis }, { it.stop!!.epochMillis }))
