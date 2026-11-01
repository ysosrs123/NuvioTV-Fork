package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class PresentationPreferencePreservationTest {
    @Test fun missingSpeculativeChoiceIsPreservedAndExplicitRemoteChoiceWins() {
        val key = booleanPreferencesKey("speculative_stream_search_enabled")
        val local = preferencesOf(key to true)
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(key, true)), captureMissingPresentationChoices("player_settings", local, emptySet()))
        assertTrue(captureMissingPresentationChoices("player_settings", local, setOf(key.name)).isEmpty())
    }

    @Test fun `source line switches survive a snapshot from an install without them`() {
        val line = booleanPreferencesKey("show_source_line_badges")
        val group = booleanPreferencesKey("show_source_line_release_group")
        val local = preferencesOf(line to false, group to false)
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(line, false), Pair(group, false)),
            captureMissingPresentationChoices("player_settings", local, setOf("speculative_stream_search_enabled")))
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(group, false)),
            captureMissingPresentationChoices("player_settings", local, setOf(line.name)))
        assertTrue(captureMissingPresentationChoices("player_settings", local, setOf(line.name, group.name)).isEmpty())
        assertTrue(captureMissingPresentationChoices("layout_settings", local, emptySet()).isEmpty())
    }

    @Test fun `older remote snapshots preserve local choices but explicit remote choices win`() {
        val enabled = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
        val muted = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
        val fullscreen = booleanPreferencesKey("modern_hero_full_screen_backdrop")
        val target = stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")
        val other = booleanPreferencesKey("unrelated")
        val local = mutablePreferencesOf(enabled to false, muted to true, fullscreen to true,
            target to "FEATHERED_WINDOW", other to true)
        val preserved = captureMissingPresentationChoices("layout_settings", local, setOf(muted.name))
        assertEquals(mapOf<Preferences.Key<*>, Any>(
            Pair(enabled, false), Pair(fullscreen, true), Pair(target, "FEATHERED_WINDOW")
        ), preserved)
        assertTrue(captureMissingPresentationChoices("theme_settings", local, emptySet()).isEmpty())
        assertTrue(captureMissingPresentationChoices("layout_settings", emptyPreferences(), emptySet()).isEmpty())
        assertEquals(false, local[enabled])
        assertEquals(true, local[muted])
    }

    @Test fun `landscape choice survives a snapshot from an install that only knows the switch`() {
        val scope = stringPreferencesKey("landscape_poster_scope")
        val legacy = booleanPreferencesKey("modern_landscape_posters_enabled")
        val local = preferencesOf(scope to "EVERYWHERE", legacy to true)
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(scope, "EVERYWHERE")),
            captureMissingPresentationChoices("layout_settings", local, setOf(legacy.name)))
        assertTrue(captureMissingPresentationChoices("layout_settings", local, setOf(legacy.name, scope.name)).isEmpty())
    }

    @Test fun `add-on landscape switch survives a snapshot from an install without it`() {
        val addonLandscape = booleanPreferencesKey("addon_landscape_backdrop_logo")
        val local = preferencesOf(addonLandscape to true)
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(addonLandscape, true)),
            captureMissingPresentationChoices("layout_settings", local, setOf("always_show_landscape_clearlogo")))
        assertTrue(captureMissingPresentationChoices("layout_settings", local, setOf(addonLandscape.name)).isEmpty())
    }

    @Test fun `watched titles choice survives a snapshot from an install without it`() {
        val mode = stringPreferencesKey("watched_titles_mode")
        val local = preferencesOf(mode to "HIDE")
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(mode, "HIDE")),
            captureMissingPresentationChoices("layout_settings", local, setOf("hide_unreleased_content")))
        assertTrue(captureMissingPresentationChoices("layout_settings", local, setOf(mode.name)).isEmpty())
    }

    @Test fun `card style, size and depth survive a snapshot from an install without them`() {
        val style = stringPreferencesKey("continue_watching_card_style")
        val width = intPreferencesKey("poster_card_width_dp")
        val height = intPreferencesKey("poster_card_height_dp")
        val corner = intPreferencesKey("poster_card_corner_radius_dp")
        val depthBooleans = listOf(
            "card_depth_enabled",
            "card_depth_posters_enabled",
            "card_depth_continue_watching_enabled",
            "card_depth_episode_cards_enabled",
            "card_depth_cast_enabled",
            "card_depth_trailers_enabled"
        ).map { booleanPreferencesKey(it) }
        val depthInts = listOf(
            "card_depth_edge_strength",
            "card_depth_sheen_strength",
            "card_depth_edge_coverage"
        ).map { intPreferencesKey(it) }
        val local = mutablePreferencesOf(style to "POSTER", width to 140, height to 210, corner to 6)
        depthBooleans.forEach { local[it] = true }
        depthInts.forEach { local[it] = 40 }
        local[booleanPreferencesKey("unrelated")] = true

        val preserved = captureMissingPresentationChoices("layout_settings", local, setOf("hero_section_enabled"))
        val expected = mutableMapOf<Preferences.Key<*>, Any>(
            Pair(style, "POSTER"), Pair(width, 140), Pair(height, 210), Pair(corner, 6)
        )
        depthBooleans.forEach { expected[it] = true }
        depthInts.forEach { expected[it] = 40 }
        assertEquals(expected, preserved)

        val incoming = expected.keys.map { it.name }.toSet()
        assertTrue(captureMissingPresentationChoices("layout_settings", local, incoming).isEmpty())
        assertTrue(captureMissingPresentationChoices("theme_settings", local, emptySet()).isEmpty())
    }

    @Test fun `calendar choices survive a snapshot without them and an explicit remote choice wins`() {
        val row = booleanPreferencesKey("calendar_coming_up_row")
        val hidden = stringSetPreferencesKey("calendar_hidden_titles")
        val zone = stringPreferencesKey("calendar_zone_override")
        val local = preferencesOf(row to true, hidden to setOf("series:imdb:tt1"), zone to "Australia/Sydney")
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(row, true), Pair(hidden, setOf("series:imdb:tt1")), Pair(zone, "Australia/Sydney")),
            captureMissingPresentationChoices("calendar_settings", local, emptySet()))
        assertEquals(mapOf<Preferences.Key<*>, Any>(Pair(hidden, setOf("series:imdb:tt1"))),
            captureMissingPresentationChoices("calendar_settings", local, setOf(row.name, zone.name)))
        assertTrue(captureMissingPresentationChoices("theme_settings", local, emptySet()).isEmpty())
    }

    @Test fun `every calendar setting is synced and kept when a snapshot lacks it`() {
        val synced = com.nuvio.tv.core.calendar.CalendarSettingsKeys.names
        assertEquals(13, synced.size)
        synced.forEach { assertFalse(it, shouldExcludePreferenceFromProfileSettingsSync("calendar_settings", it)) }
        val local = mutablePreferencesOf()
        synced.forEach { local[stringPreferencesKey(it)] = "x" }
        assertEquals(synced, captureMissingPresentationChoices("calendar_settings", local, emptySet()).keys.map { it.name }.toSet())
    }
}
