package com.nuvio.tv.core.iptv

import java.net.URI
import java.util.Locale

fun playlistGuideAddresses(addresses: List<String>, limit: Int = 4): List<String> {
    require(limit > 0)
    return addresses.mapNotNull { raw ->
        val address = raw.trim().takeIf { it.isNotEmpty() && it.length <= 16_384 } ?: return@mapNotNull null
        val uri = try { URI(address) } catch (_: Exception) { return@mapNotNull null }
        address.takeIf {
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
        }
    }.distinct().take(limit)
}
