package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64

/**
 * Stream servers that may take a long time before their first byte: a server on the home network (a NAS running
 * AIOStreams fetches the NZB and the first articles before it answers) or an AIOStreams Usenet stream anywhere.
 * They get a longer wait for the first data and no warm-up requests, as each request can count as a grab at the
 * indexer. Public CDNs keep the normal limits.
 */
internal object SlowStartServer {
    const val FIRST_DATA_WAIT_MS = 60_000L
    /** Connect plus the first data, with room for one look-up of the link. */
    const val LOOKUP_WAIT_CAP_MS = 75_000L
    /** The start-up watchdog waits this long while no data has arrived yet. */
    const val STARTUP_CEILING_MS = 80_000L

    fun matches(url: String?): Boolean {
        val u = url?.trim()?.toHttpUrlOrNull() ?: return false
        return isLocalHost(u.host) || isUsenet(url) || isNoted(u.toString())
    }

    /** A Usenet entry by what its add-on calls it: an NZB indexer, "Usenet", or Easynews. */
    fun namesUsenet(name: String?, description: String?, addonName: String?): Boolean {
        if (USENET_NAME.containsMatchIn(name?.lineSequence()?.firstOrNull().orEmpty())) return true
        return listOf(name, description, addonName).any { it?.contains("easynews", ignoreCase = true) == true }
    }

    /**
     * Remembers a link whose entry is named as Usenet, so the link gets the longer wait although nothing in the link
     * itself says so (an Easynews link goes through the add-on and redirects).
     */
    fun noteStream(url: String?, name: String?, description: String?, addonName: String?) {
        if (!namesUsenet(name, description, addonName)) return
        val key = url?.trim()?.toHttpUrlOrNull()?.toString() ?: return
        synchronized(noted) {
            noted.remove(key)
            noted.add(key)
            while (noted.size > NOTED_LIMIT) noted.remove(noted.first())
        }
    }

    private fun isNoted(key: String): Boolean = synchronized(noted) { key in noted }

    internal fun clearNoted() = synchronized(noted) { noted.clear() }

    val USENET_NAME = Regex("(?<![a-z])(usenet|nzb)", RegexOption.IGNORE_CASE)
    private const val NOTED_LIMIT = 32
    private val noted = LinkedHashSet<String>()

    /**
     * An AIOStreams Usenet stream: each new read may have the server fetch articles. Its own streams have /usenet/ in
     * the path; its playback links (/api/v1/debrid/playback/...) carry the source as base64 JSON, {"type":"usenet",...}.
     */
    fun isUsenet(url: String?): Boolean {
        val u = url?.trim()?.toHttpUrlOrNull() ?: return false
        if ("/usenet/" in u.encodedPath.lowercase()) return true
        return u.pathSegments.any { it.startsWith("eyJ") && decodedJson(it)?.let(usenetType::containsMatchIn) == true }
    }

    private val usenetType = Regex("\"type\"\\s*:\\s*\"usenet\"",RegexOption.IGNORE_CASE)

    private fun decodedJson(segment: String): String? {
        val s = segment.trimEnd('=')
        return s.dropLast(if (s.length % 4 == 1) 1 else 0).decodeBase64()?.utf8()
    }

    private val homeSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".ts.net")

    /** A private, link-local or Tailscale address, or a home-network name. Loopback has its own handling. */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (homeSuffixes.any { h.endsWith(it) }) return true
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
        if (v4 != null) {
            if (v4.any { it !in 0..255 }) return false
            return v4[0] == 10 || (v4[0] == 172 && v4[1] in 16..31) || (v4[0] == 192 && v4[1] == 168) ||
                (v4[0] == 169 && v4[1] == 254) || (v4[0] == 100 && v4[1] in 64..127)
        }
        if (':' in h) {
            val first = h.trim('[', ']').substringBefore(':')
            return first.length == 4 && (first.startsWith("fc") || first.startsWith("fd") ||
                (first.startsWith("fe") && first[2] in '8'..'b'))
        }
        return false
    }
}
