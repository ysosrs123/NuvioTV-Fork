package com.nuvio.tv.core.logging

/**
 * What a report says about a server: the service name for well-known services, otherwise only the kind of address.
 * A media server's own name or address can point to someone's home, so it never leaves the box.
 */
object ReportHosts {
    fun label(host: String?): String? {
        val name = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (IP_LITERAL.matches(name)) return if (isPrivateAddress(name)) "local network address" else "internet address"
        if (name == "localhost" || LOCAL_SUFFIXES.any { name.endsWith(it) } || '.' !in name) return "local network name"
        return knownService(name) ?: "other server"
    }

    /** The service behind a well-known host, or null for anything else. */
    fun knownService(host: String?): String? {
        val name = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return KNOWN.firstOrNull { (suffix, _) -> name == suffix || name.endsWith(".$suffix") }?.second
    }

    private fun isPrivateAddress(name: String): Boolean {
        val v4 = V4.matchEntire(name)
        if (v4 != null) {
            val a = v4.groupValues[1].toInt()
            val b = v4.groupValues[2].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
        val v6 = name.trim('[', ']')
        return v6 == "::1" || v6.startsWith("fc") || v6.startsWith("fd") || v6.startsWith("fe80")
    }

    private val IP_LITERAL = Regex("""^(\d{1,3}(\.\d{1,3}){3}|\[?[0-9a-f]*:[0-9a-f:.]*]?)$""")
    private val V4 = Regex("""^(\d+)\.(\d+)\.\d+\.\d+$""")
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".ts.net")
    private val KNOWN = listOf(
        "torbox.app" to "TorBox", "tb-cdn.st" to "TorBox", "tb-cdn.io" to "TorBox", "tb-cdn.pw" to "TorBox",
        "real-debrid.com" to "Real-Debrid", "rdeb.io" to "Real-Debrid",
        "alldebrid.com" to "AllDebrid", "debrid.it" to "AllDebrid",
        "premiumize.me" to "Premiumize", "energycdn.com" to "Premiumize",
        "debrid-link.com" to "Debrid-Link", "easynews.com" to "Easynews", "offcloud.com" to "Offcloud", "put.io" to "put.io",
        "googlevideo.com" to "YouTube", "youtube.com" to "YouTube",
        "strem.io" to "Stremio", "strem.fun" to "Stremio", "elfhosted.com" to "ElfHosted"
    )
}
