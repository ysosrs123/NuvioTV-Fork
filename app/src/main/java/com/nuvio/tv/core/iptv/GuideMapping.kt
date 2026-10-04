package com.nuvio.tv.core.iptv

/** External guide IDs are only meaningful inside a particular feed. */
data class GuideKey(val feedId: String, val externalId: String)
data class GuideFeedIndex(val feedId: String, val channelIds: Set<String>)
enum class GuideMatchReason { MANUAL, EXACT_ID, MISSING_MANUAL_TARGET, AMBIGUOUS, NONE }
data class GuideMatch(val key: GuideKey?, val reason: GuideMatchReason, val candidates: List<GuideKey> = emptyList())

/**
 * Feed order is explicit source configuration, not download completion order. A missing manual
 * target stays missing instead of silently transferring to another feed. Names are not identities.
 */
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
    val candidates = feeds.filter { guideId in it.channelIds }.map { GuideKey(it.feedId, guideId) }
    val prioritized = orderedFeedPriority.firstNotNullOfOrNull { feed -> candidates.find { it.feedId == feed } }
    val chosen = prioritized ?: candidates.singleOrNull()
    return GuideMatch(chosen, when {
        chosen != null -> GuideMatchReason.EXACT_ID
        candidates.isNotEmpty() -> GuideMatchReason.AMBIGUOUS
        else -> GuideMatchReason.NONE
    }, candidates)
}

/** Caller supplies one feed's records. Half-open intervals preserve exact programme boundaries. */
fun programmesAt(programmes: List<GuideProgramme>, channelExternalId: String, instantMillis: Long): List<GuideProgramme> =
    programmes.filter { it.channelExternalId == channelExternalId && it.start.precise && it.stop?.precise == true && it.start.epochMillis <= instantMillis && it.stop.epochMillis > instantMillis }
        .sortedWith(compareBy({ it.start.epochMillis }, { it.stop!!.epochMillis }))
