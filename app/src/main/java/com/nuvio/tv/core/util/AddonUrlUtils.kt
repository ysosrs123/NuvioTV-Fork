package com.nuvio.tv.core.util

private const val MANIFEST_SUFFIX = "/manifest.json"

/**
 * Canonical form of an addon URL, used as the identity key across install
 * state, enable/disable state, ordering, user-set names, and remote sync.
 *
 * Shared by AddonRepositoryImpl, AddonPreferences and AddonSyncService. Any
 * change to canonicalisation must happen here and nowhere else, since these
 * keys cross the preferences/repository/sync boundary.
 *
 * Rules: trim whitespace and trailing slashes; strip a trailing
 * `/manifest.json` (case-insensitive) from the *path* while preserving any
 * query string (configurable addons carry their config in the query).
 * Case of the remainder is preserved - callers needing case-insensitive
 * matching lowercase on top of this (see AddonRepositoryImpl.normalizeUrl).
 */
fun canonicalizeAddonUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
        path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
    } else {
        path.trimEnd('/')
    }
    return cleanPath + query
}
