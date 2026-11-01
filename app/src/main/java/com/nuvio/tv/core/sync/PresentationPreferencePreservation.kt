package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import com.nuvio.tv.core.calendar.CalendarSettingsKeys

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
            "landscape_poster_scope",
            "watched_titles_mode",
            "addon_landscape_backdrop_logo",
            "continue_watching_card_style",
            "poster_card_width_dp",
            "poster_card_height_dp",
            "poster_card_corner_radius_dp",
            "card_depth_enabled",
            "card_depth_edge_strength",
            "card_depth_sheen_strength",
            "card_depth_edge_coverage",
            "card_depth_posters_enabled",
            "card_depth_continue_watching_enabled",
            "card_depth_episode_cards_enabled",
            "card_depth_cast_enabled",
            "card_depth_trailers_enabled"
        )
        "player_settings" -> setOf(
            "speculative_stream_search_enabled",
            "show_source_line_badges",
            "show_source_line_release_group",
            "addon_stream_timeout_seconds"
        )
        CalendarSettingsDataStore.FEATURE -> CalendarSettingsKeys.names
        else -> emptySet()
    }
    return local.asMap().filterKeys { it.name in keys && it.name !in incomingKeys }
}
