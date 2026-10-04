package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Header-sensitive identity, shared by warm-up, data-source adoption and AFR. Never log credentials. */
internal data class PlaybackPrewarmIdentity private constructor(
    private val url: String,
    private val headers: Map<String, String>
) {
    override fun toString() = "PlaybackPrewarmIdentity(redacted)"

    companion object {
        fun from(
            url: String,
            defaultHeaders: Map<String, String>,
            requestHeaders: Map<String, String> = emptyMap()
        ): PlaybackPrewarmIdentity? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val headers = linkedMapOf(
                "user-agent" to PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
                "accept-encoding" to "identity"
            )
            for (source in listOf(defaultHeaders, requestHeaders)) {
                for ((name, value) in source) {
                    val key = name.trim().lowercase(Locale.ROOT)
                    if (key.isNotEmpty() && key != "range" && value.isNotBlank()) headers[key] = value.trim()
                }
            }
            return PlaybackPrewarmIdentity(parsed.toString(), headers.toMap())
        }
    }
}
