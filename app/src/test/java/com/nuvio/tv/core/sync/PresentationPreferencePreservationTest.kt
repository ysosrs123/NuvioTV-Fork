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
}
