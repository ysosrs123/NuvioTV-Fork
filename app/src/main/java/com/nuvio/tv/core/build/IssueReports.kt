package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Playback and sign-in reports need Arc's report relay and its signing key set at build time; without both, no report
 * option is shown. An official Nuvio address is never accepted, so reports cannot reach the official team.
 */
object IssueReports {
    val available: Boolean get() = isConfigured(BuildConfig.PLAYBACK_REPORTS_BASE_URL, BuildConfig.REPORTS_SIGNING_KEY)

    internal fun isConfigured(baseUrl: String?, signingKey: String? = "set"): Boolean {
        if (baseUrl.isNullOrBlank() || signingKey.isNullOrBlank()) return false
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && !isOfficialHost(url.host)
    }

    internal fun isOfficialHost(host: String): Boolean {
        val name = host.trimEnd('.').lowercase()
        return OFFICIAL_DOMAINS.any { name == it || name.endsWith(".$it") }
    }

    private val OFFICIAL_DOMAINS = listOf("nuvio.tv", "nuvio.app", "nuvioapp.space", "nuviomedia.com", "nuviomedia.app")
}
