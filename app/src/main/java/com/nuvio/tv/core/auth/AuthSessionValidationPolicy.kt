package com.nuvio.tv.core.auth

internal fun isInvalidRemoteSessionResponse(
    statusCode: Int?,
    message: String
): Boolean {
    val normalizedMessage = message.lowercase()
    if (APP_KEY_REFUSED_MARKERS.any(normalizedMessage::contains)) return false
    if (statusCode == 401) return true
    return INVALID_REMOTE_SESSION_MARKERS.any(normalizedMessage::contains)
}

/** The server turned the app away, which says nothing about this account's session. */
private val APP_KEY_REFUSED_MARKERS = listOf(
    "invalid api key",
    "no api key found"
)

/**
 * After a sign-out nobody asked for, the box is only emptied when the server still answers the app normally.
 * A server that is gone, or that refuses the app itself, leaves everything on the box.
 */
internal fun shouldClearBoxAfterUnexpectedSignOut(serverStatusCode: Int?): Boolean =
    serverStatusCode != null && serverStatusCode in 200..299

private val INVALID_REMOTE_SESSION_MARKERS = listOf(
    "session not found",
    "session_not_found",
    "invalid session",
    "invalid token",
    "invalid jwt",
    "jwt expired",
    "user not found",
    "user does not exist"
)

internal fun Throwable.isJwtExpiredAuthError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("jwt expired", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}
