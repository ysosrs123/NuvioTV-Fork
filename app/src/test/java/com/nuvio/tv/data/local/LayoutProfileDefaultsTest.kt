package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.LandscapePosterScope
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LayoutProfileDefaultsTest {
    @Test fun `missing values use new defaults while saved and hydrated choices remain profile scoped`() = runTest {
        val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns active }
        val factory = mockk<ProfileDataStoreFactory>()
        val fresh = MutableStateFlow<Preferences>(emptyPreferences())
        val saved = MutableStateFlow<Preferences>(mutablePreferencesOf(
            booleanPreferencesKey("focused_poster_backdrop_trailer_enabled") to false,
            booleanPreferencesKey("focused_poster_backdrop_trailer_muted") to true,
            booleanPreferencesKey("focused_poster_backdrop_trailer_logo") to false,
            booleanPreferencesKey("modern_hero_full_screen_backdrop") to true,
            stringPreferencesKey("focused_poster_backdrop_trailer_playback_target") to "FEATHERED_WINDOW"
        ))
        every { factory.get(1, "layout_settings") } returns mockk<DataStore<Preferences>> { every { data } returns fresh }
        every { factory.get(2, "layout_settings") } returns mockk<DataStore<Preferences>> { every { data } returns saved }
        val layout = LayoutPreferenceDataStore(factory, manager)
        assertTrue(layout.focusedPosterBackdropTrailerEnabled.first())
        assertFalse(layout.focusedPosterBackdropTrailerMuted.first())
        assertTrue(layout.focusedPosterBackdropTrailerLogoEnabled.first())
        assertFalse(layout.modernHeroFullScreenBackdropEnabled.first())
        assertEquals(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA, layout.focusedPosterBackdropTrailerPlaybackTarget.first())
        active.value = 2
        assertFalse(layout.focusedPosterBackdropTrailerEnabled.first())
        assertTrue(layout.focusedPosterBackdropTrailerMuted.first())
        assertFalse(layout.focusedPosterBackdropTrailerLogoEnabled.first())
        assertTrue(layout.modernHeroFullScreenBackdropEnabled.first())
        assertEquals(FocusedPosterTrailerPlaybackTarget.FEATHERED_WINDOW, layout.focusedPosterBackdropTrailerPlaybackTarget.first())
        fresh.value = saved.value // Same raw preference hydration used by profile sync.
        active.value = 1
        assertFalse(layout.focusedPosterBackdropTrailerEnabled.first())
        assertTrue(layout.focusedPosterBackdropTrailerMuted.first())
        assertEquals(saved.value, fresh.value)
    }

    @Test fun `landscape switch stored by an earlier version is read as home only`() = runTest {
        val manager = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val factory = mockk<ProfileDataStoreFactory>()
        val legacy = booleanPreferencesKey("modern_landscape_posters_enabled")
        val scope = stringPreferencesKey("landscape_poster_scope")
        val stored = MutableStateFlow<Preferences>(emptyPreferences())
        every { factory.get(1, "layout_settings") } returns mockk<DataStore<Preferences>> { every { data } returns stored }
        val layout = LayoutPreferenceDataStore(factory, manager)
        assertEquals(LandscapePosterScope.OFF, layout.landscapePosterScope.first())
        assertFalse(layout.modernLandscapePostersEnabled.first())
        stored.value = preferencesOf(legacy to true)
        assertEquals(LandscapePosterScope.HOME_ONLY, layout.landscapePosterScope.first())
        assertTrue(layout.modernLandscapePostersEnabled.first())
        stored.value = preferencesOf(legacy to true, scope to "EVERYWHERE")
        assertEquals(LandscapePosterScope.EVERYWHERE, layout.landscapePosterScope.first())
        assertTrue(layout.modernLandscapePostersEnabled.first())
        stored.value = preferencesOf(legacy to false, scope to "EVERYWHERE")
        assertEquals(LandscapePosterScope.OFF, layout.landscapePosterScope.first())
        assertFalse(layout.modernLandscapePostersEnabled.first())
    }
}
