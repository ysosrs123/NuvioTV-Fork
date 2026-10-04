package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.Preferences

/** Older clients omit these choices. Absence must not erase an explicit local preference. */
internal fun captureMissingPresentationChoices(
    feature: String,
    local: Preferences,
    incomingKeys: Set<String>
): Map<Preferences.Key<*>, Any> {
    val keys = when (feature) {
        "layout_settings" -> setOf(
            "focused_poster_backdrop_trailer_enabled",
            "focused_poster_backdrop_trailer_muted",
            "focused_poster_backdrop_trailer_logo",
            "focused_poster_backdrop_trailer_playback_target",
            "modern_hero_full_screen_backdrop",
            "landscape_poster_scope"
        )
        "player_settings" -> setOf("speculative_stream_search_enabled")
        else -> emptySet()
    }
    return local.asMap().filterKeys { it.name in keys && it.name !in incomingKeys }
}
