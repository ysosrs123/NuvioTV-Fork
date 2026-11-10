package com.nuvio.tv.data.repository

import com.nuvio.tv.core.logging.ReportHosts

/**
 * Text that goes into a report. A link is replaced by the name of its service or the kind of server it points to, and
 * keys, addresses and email addresses are taken out, before anything leaves the box.
 */
internal object ReportText {
    fun clean(value: String?, maxLength: Int): String? =
        value?.trim()
            ?.let(::redact)
            ?.replace(WHITESPACE, " ")
            ?.takeIf { it.isNotBlank() }
            ?.limit(maxLength)

    fun rawLine(value: String, maxLength: Int): String? =
        value.replace('\n', ' ')
            .replace('\r', ' ')
            .let(::redact)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.limit(maxLength)

    fun eventLine(value: String, maxLength: Int): String? = rawLine(withoutServerNames(value), maxLength)

    /** A media server's name is the owner's own label, so it never goes into a recorded line. */
    fun withoutServerNames(value: String): String =
        if ("SERVER_SOURCE" !in value && "ServerThumbSet" !in value) {
            value
        } else {
            value.replace(SERVER_SOURCE_NAME, "$1[server]").replace(SERVER_THUMB_LINE, "ServerThumbSet: [server]")
        }

    /** A file name as the player or the link gives it. Release names keep their dots, dashes and underscores. */
    fun fileName(value: String?, maxLength: Int): String? =
        value?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
            ?.let { redactSecrets(it, longTokens = false).trim().limit(maxLength) }
            ?.takeIf { it.isNotBlank() }

    /** The last part of a stream link, decoded, only when it ends in a video file extension. */
    fun fileNameFromLink(url: String?): String? {
        val segment = url?.substringBefore('#')?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/') ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        val extension = decoded.substringAfterLast('.', "").lowercase()
        return decoded.takeIf { extension in VIDEO_EXTENSIONS && it.length > extension.length + 1 }
    }

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mpg", "mpeg", "vob", "iso", "flv", "ogv", "3gp"
    )

    fun isIptvLink(url: String?): Boolean = url != null && IPTV_LINK.containsMatchIn(url)

    fun redact(value: String): String = redactSecrets(URL.replace(value) { "[link: ${ReportHosts.label(hostOf(it.value)) ?: "unknown"}]" })

    private fun hostOf(link: String): String {
        val authority = link.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    }

    private fun redactSecrets(value: String, longTokens: Boolean = true): String =
        value
            .replace(XTREAM_PATH) { "/${it.groupValues[1]}/[redacted]" }
            .replace(SHORT_STREAM_PATH, "[path]")
            .replace(WINDOWS_SHARE, "[path]")
            .replace(HEADER_COLON) { "${it.groupValues[1]}: [redacted]" }
            .replace(JWT, "[redacted]")
            .replace(BEARER) { "${it.groupValues[1]} [redacted]" }
            .replace(KEY_VALUE) { "${it.groupValues[1]}=[redacted]" }
            .replace(LOGIN_VALUE) { "${it.groupValues[1]}=[redacted]" }
            .replace(OAUTH_VALUE) { "${it.groupValues[1]}=[redacted]" }
            .replace(HEADER_EQUALS) { "${it.groupValues[1]}=[redacted]" }
            .replace(EMAIL, "[email]")
            .replace(HOST_IN_MESSAGE, ::hostInMessage)
            .replace(BARE_HOST) { if (it.value.looksLikeHost()) "[host: ${ReportHosts.label(it.value)}]" else it.value }
            .let { if (longTokens) it.replace(LONG_TOKEN, "[redacted]") else it }
            .replace(IPV4) { if (it.value.split('.').all { part -> part.toInt() <= 255 }) "[ip]" else it.value }
            .replace(MAC, "[mac]")
            .replace(IPV6, "[ip]")

    private fun hostInMessage(match: MatchResult): String {
        val (keyword, separator, host) = match.destructured
        val isHost = keyword.lowercase().startsWith("resolve") || '.' in host || ':' in host
        return if (isHost) "$keyword$separator[host: ${ReportHosts.label(host) ?: "unknown"}]" else match.value
    }

    private fun String.looksLikeHost(): Boolean {
        val top = substringAfterLast('.')
        if (top !in TOP_LEVEL_NAMES) return false
        return substringBefore('.') !in PACKAGE_ROOTS || top == "com" || top == "net" || top == "org"
    }

    private fun String.limit(maxLength: Int): String = if (length <= maxLength) this else take(maxLength)

    private val SERVER_SOURCE_NAME = Regex("""(SERVER_SOURCE name=).*?(?=\s+(?:ms|outcome)=|$)""")
    private val SERVER_THUMB_LINE = Regex("""ServerThumbSet.*""")
    private val WHITESPACE = Regex("\\s+")
    private val URL = Regex("""\b(?:(?:https?|wss?|magnet|rtsp|smb|nfs|content|file|intent):|(?:rtsps|rtmp[a-z]?|rtp|udp|mms[a-z]?|srt)://)\S+""", RegexOption.IGNORE_CASE)
    private val IPTV_LINK = Regex("""(?i)^[a-z]+://[^/]+/(?:live|movie|series|timeshift)/[^/?#]+/[^/?#]+/""")
    private val XTREAM_PATH = Regex("""(?i)/(live|movie|series|timeshift)/[^/\s"']+/[^/\s"']+/\S*""")
    private val SHORT_STREAM_PATH = Regex("""(?<![\w.:/])/[^/\s"']+/[^/\s"']+/\d+(?:\.(?:ts|m3u8?))?(?=[\s"',;)]|$)""")
    private val WINDOWS_SHARE = Regex("""\\\\[^\s\\/]+\\\S*""")
    private val LOGIN_VALUE = Regex("""(?i)\b(username|passwd|pwd|(?<=\b)(?:user|pass|login|sn|serial|device_id2?|mac)(?==))\s*[:=]\s*[^\s,;&"']+""")
    private val HEADER_COLON = Regex(
        """(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-emby-token|x-plex-token|x-mediabrowser-token)\b\s*:\s*[^\r\n]+"""
    )
    private val HEADER_EQUALS = Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b\s*=\s*\S+""")
    private val KEY_VALUE = Regex("""(?i)\b(bearer|token|access_token|refresh_token|apikey|api_key|api-key|password|secret)\b\s*[:=]\s*[^\s,;&"']+""")
    private val OAUTH_VALUE = Regex("""(?i)\b(nonce|state|code_verifier|code_challenge)\b\s*[:=]\s*[A-Za-z0-9_-]{16,}""")
    private val BEARER = Regex("""(?i)\b(bearer)\s+(?![:=])[^\s,\]]+""")
    private val JWT = Regex("""\beyJ[\w-]{6,}\.[\w-]{6,}\.[\w-]*""")
    private val LONG_TOKEN = Regex("""\b(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}\b""")
    private val EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val IPV6 = Regex(
        """(?i)(?<![\w:.])(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|(?=[0-9a-f:]*[0-9a-f])(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?)(?![\w:])"""
    )
    private val MAC = Regex("""(?i)(?<![\w:-])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![\w:-])""")
    private val HOST_IN_MESSAGE = Regex(
        """(?i)\b(resolve host(?:name)?|connect(?:ing)? to|connection to|hostname|host)\b(\s*[:=]?\s*["'/]*)([a-z0-9][a-z0-9._-]*|\[[0-9a-f:.]+])"""
    )
    private val BARE_HOST = Regex("""(?<![\w.@/-])(?<!\[link: )(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}(?![\w-]|\.\w)""")
    private val TOP_LEVEL_NAMES = setOf(
        "com", "net", "org", "io", "app", "dev", "me", "tv", "co", "xyz", "info", "biz", "online", "site", "cloud", "club",
        "live", "stream", "top", "pro", "link", "icu", "vip", "st", "to", "cc", "ws", "nz", "au", "uk", "de", "fr", "nl", "es",
        "it", "pt", "br", "in", "jp", "ru", "se", "no", "fi", "dk", "pl", "eu", "ch", "at", "be", "ca", "us", "mx", "ar",
        "local", "lan", "home", "internal", "arpa"
    )
    private val PACKAGE_ROOTS = setOf("com", "org", "net", "io", "android", "androidx", "java", "javax", "kotlin", "kotlinx", "dalvik", "libcore")
}
