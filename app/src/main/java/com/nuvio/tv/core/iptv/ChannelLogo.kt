package com.nuvio.tv.core.iptv

import java.net.URI
import java.util.Locale

const val CHANNEL_LOGO_ATTRIBUTE = "tvg-logo"
private const val MAX_LOGO_CHARACTERS = 2_000

fun channelLogoUrl(value: String?, base: String? = null): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LOGO_CHARACTERS } ?: return null
    val uri = try { URI(text) } catch (_: Exception) { return null }
    val resolved = if (uri.isAbsolute) uri else {
        val origin = base?.let { try { URI(it.trim()) } catch (_: Exception) { null } }?.takeIf(::safeHttp) ?: return null
        try { origin.resolve(uri) } catch (_: Exception) { return null }
    }
    return resolved.takeIf(::safeHttp)?.toASCIIString()?.takeIf { it.length <= MAX_LOGO_CHARACTERS }
}

private fun safeHttp(uri: URI): Boolean =
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
