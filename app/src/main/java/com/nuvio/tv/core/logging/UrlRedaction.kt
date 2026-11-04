package com.nuvio.tv.core.logging

private val CREDENTIAL_PARAMETERS = listOf("api_key", "apikey", "token", "secret", "x-emby-token", "x-mediabrowser-token", "x-plex-token")
private val SERVER_KEY_PARAMETERS = setOf("api_key", "apikey")

private val URL_CREDENTIAL = Regex(
    """([?&](?:${CREDENTIAL_PARAMETERS.joinToString("|")})=)[^&#\s"']*""",
    RegexOption.IGNORE_CASE
)

private val LOG_CREDENTIAL_PARAMETERS =
    CREDENTIAL_PARAMETERS + listOf(
        "password", "pass", "auth", "access_token", "sig", "signature", "api_password", "auth_token"
    )

private val LOG_URL_CREDENTIAL = Regex(
    """([?&](?:${LOG_CREDENTIAL_PARAMETERS.joinToString("|")})=)[^&#\s"']*""",
    RegexOption.IGNORE_CASE
)

// Scheme, authority, then the rest of the link without trailing sentence punctuation.
private val LINK = Regex("""\b([A-Za-z][A-Za-z0-9+.\-]{0,15}://)([^/?#\s"'<>]*)([^\s"'<>]*[^\s"'<>.,;:!?)\]}])?""")
private val SECRET_SEGMENT = Regex("""[A-Za-z0-9_\-=+%~]{24,}""")
private val DEBRID_SERVICES = setOf(
    "realdebrid", "alldebrid", "premiumize", "debridlink", "easydebrid", "offcloud", "torbox", "putio", "pikpak"
)
private val DEBRID_PAIR = Regex("""((?:${DEBRID_SERVICES.joinToString("|")})=)[^|,/%&?#]+""", RegexOption.IGNORE_CASE)

/** Masks access tokens carried in URL query strings, keeping the rest of the text readable. */
fun String.redactUrlCredentials(): String =
    URL_CREDENTIAL.replace(this) { match -> match.groupValues[1] + "REDACTED" }

/**
 * Masks secrets in every link found in log text: credential query values, user:password@ and
 * key-like path segments (addon configs, API keys, hashes). Scheme, host and port stay readable.
 */
fun String.redactLinksForLog(): String {
    val masked = LOG_URL_CREDENTIAL.replace(this) { match -> match.groupValues[1] + "REDACTED" }
    if (!masked.contains("://")) return masked
    return LINK.replace(masked) { match ->
        val (scheme, authority, rest) = match.destructured
        val at = authority.lastIndexOf('@')
        val host = if (at >= 0) "REDACTED@" + authority.substring(at + 1) else authority
        val pathEnd = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val segments = rest.substring(0, pathEnd).split('/')
        val path = segments.mapIndexed { i, segment ->
            val afterService = i > 0 && segment.isNotEmpty() && segments[i - 1].lowercase() in DEBRID_SERVICES
            if (afterService || segment.isSecretPathSegment()) "REDACTED"
            else DEBRID_PAIR.replace(segment) { it.groupValues[1] + "REDACTED" }
        }.joinToString("/")
        scheme + host + path + rest.substring(pathEnd)
    }
}

internal fun String.isSecretPathSegment(): Boolean =
    (length >= 12 && startsWith("eyJ")) || SECRET_SEGMENT.matches(this)

fun String?.redactedUrlForLog(): String = this?.redactLinksForLog() ?: "(null)"

/** Drops access token parameters from a single URL, keeping the other parameters and any fragment. */
fun String.withoutUrlCredentials(): String = filterQuery { name -> name.lowercase() !in CREDENTIAL_PARAMETERS }

/** The URL to keep for diagnostics: media server streams lose their token, other links stay as they are. */
fun String.diagnosticStreamUrl(isServerStream: Boolean): String {
    val carriesServerKey = queryParameterNames().any { it.lowercase() in SERVER_KEY_PARAMETERS }
    return if (isServerStream || carriesServerKey) withoutUrlCredentials() else this
}

private fun String.queryParameterNames(): List<String> {
    val queryStart = indexOf('?').takeIf { it >= 0 } ?: return emptyList()
    val fragmentStart = indexOf('#', queryStart).takeIf { it >= 0 } ?: length
    return substring(queryStart + 1, fragmentStart).split('&').map { it.substringBefore('=') }
}

private fun String.filterQuery(keep: (String) -> Boolean): String {
    val queryStart = indexOf('?').takeIf { it >= 0 } ?: return this
    val fragmentStart = indexOf('#', queryStart).takeIf { it >= 0 } ?: length
    val kept = substring(queryStart + 1, fragmentStart)
        .split('&')
        .filter { it.isNotEmpty() && keep(it.substringBefore('=')) }
    val query = if (kept.isEmpty()) "" else kept.joinToString("&", prefix = "?")
    return substring(0, queryStart) + query + substring(fragmentStart)
}
