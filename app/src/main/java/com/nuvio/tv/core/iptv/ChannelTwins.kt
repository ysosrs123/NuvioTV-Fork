package com.nuvio.tv.core.iptv

import java.util.Locale

data class TwinChannel(val sourceId: String, val id: String, val name: String, val guideId: String? = null, val guide: GuideKey? = null)

object ChannelTwins {
    const val MAX_TWINS = 6
    private const val PER_SOURCE = 2

    fun same(target: TwinChannel, candidate: TwinChannel): Boolean {
        if (target.sourceId == candidate.sourceId) return false
        if (target.guide != null && target.guide == candidate.guide) return true
        val id = guideId(target.guideId)
        if (id != null && id == guideId(candidate.guideId)) return true
        val name = guideMatchName(target.name)
        return name.length >= 2 && name == guideMatchName(candidate.name)
    }

    fun pick(target: TwinChannel, candidates: List<TwinChannel>, max: Int = MAX_TWINS): List<TwinChannel> =
        candidates.filter { same(target, it) }.distinctBy { it.sourceId to it.id }.groupBy { it.sourceId }.values.flatMap { it.take(PER_SOURCE) }.take(max)

    private fun guideId(value: String?): String? =
        value?.trim()?.takeIf(String::isNotEmpty)?.let { guideIdWithoutFeedSuffix(it) ?: it }?.lowercase(Locale.ROOT)
}
