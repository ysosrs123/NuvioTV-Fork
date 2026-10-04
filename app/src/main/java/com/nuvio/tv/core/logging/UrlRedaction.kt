package com.nuvio.tv.core.logging

private val CREDENTIAL_PARAMETERS = listOf("api_key", "apikey", "token", "secret", "x-emby-token", "x-mediabrowser-token")
private val SERVER_KEY_PARAMETERS = setOf("api_key", "apikey")

private val URL_CREDENTIAL = Regex(
    """([?&](?:${CREDENTIAL_PARAMETERS.joinToString("|")})=)[^&#\s"']*""",
    RegexOption.IGNORE_CASE
)

/** Masks access tokens carried in URL query strings, keeping the rest of the text readable. */
fun String.redactUrlCredentials(): String =
    URL_CREDENTIAL.replace(this) { match -> match.groupValues[1] + "REDACTED" }

fun String?.redactedUrlForLog(): String = this?.redactUrlCredentials() ?: "(null)"

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
