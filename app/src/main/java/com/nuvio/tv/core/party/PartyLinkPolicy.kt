package com.nuvio.tv.core.party

import java.net.URI

/** Decides whether a stream link may be passed to guests. Anything tied to an account or to this device is not. */
object PartyLinkPolicy {
    private val ACCOUNT_MARKERS = listOf(
        "debrid", "premiumize", "torbox", "offcloud", "pikpak", "put.io", "putio", "easynews", "rdeb.io",
        "api_key", "apikey", "x-emby-token", "x-plex-token", "access_token", "/emby/", "password=",
    )

    fun isShareable(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        if (isLocalHost(host)) return false
        val lowered = url.lowercase()
        return ACCOUNT_MARKERS.none { it in lowered }
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "::1" || !host.contains('.') && !host.contains(':')) return true
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size == 4 && parts.all { it != null && it in 0..255 }) {
            val a = parts[0]!!
            val b = parts[1]!!
            return a == 10 || a == 127 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        return host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
    }
}
