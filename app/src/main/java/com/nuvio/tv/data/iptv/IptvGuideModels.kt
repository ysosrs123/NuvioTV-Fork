package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.GuideProgramme

data class IptvGuideRef(val profileId: Int, val feedId: String) {
    init { require(profileId >= 0 && feedId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
}
data class IptvGuideTicket(val ref: IptvGuideRef, val configurationVersion: Long, val generation: Long)
data class IptvGuideFeed(val ref: IptvGuideRef, val label: String, val configurationVersion: Long, val requestedGeneration: Long, val activeGeneration: Long?)
data class IptvGuideWindow(val fromMillis: Long, val untilMillis: Long) {
    init { require(untilMillis > fromMillis && untilMillis - fromMillis in 1..(31L * 24 * 60 * 60 * 1000)) }
}
data class IptvProgrammePage(val programmes: List<GuideProgramme>, val hasMore: Boolean)
