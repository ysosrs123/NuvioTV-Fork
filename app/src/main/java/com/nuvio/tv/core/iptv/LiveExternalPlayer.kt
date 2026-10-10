package com.nuvio.tv.core.iptv

object LiveExternalPlayer {
    fun headers(channel: Map<String, String>, sourceUserAgent: String?): Map<String, String> {
        val cleaned = channel.mapNotNull { (name, value) -> StreamHeaders.clean(value)?.let { name to it } }.toMap()
        val agent = cleaned["User-Agent"] ?: sourceUserAgent?.let(StreamHeaders::clean)
        return if (agent == null) cleaned else cleaned + ("User-Agent" to agent)
    }
}
