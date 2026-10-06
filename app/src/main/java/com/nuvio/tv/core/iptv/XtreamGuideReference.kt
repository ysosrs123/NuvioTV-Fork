package com.nuvio.tv.core.iptv

object XtreamGuideReference {
    private const val PREFIX = "xtream-guide:"
    private val SOURCE_ID = Regex("[A-Za-z0-9_-]{1,80}")

    fun of(sourceId: String): String {
        require(SOURCE_ID.matches(sourceId))
        return PREFIX + sourceId
    }

    fun sourceId(endpoint: String): String? =
        endpoint.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf(SOURCE_ID::matches)
}
