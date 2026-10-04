package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ThemeProfileDefaultsTest {
    @Test fun `unset profiles resolve to Glass while explicit choices remain independent`() = runTest {
        val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns active }
        val factory = mockk<ProfileDataStoreFactory>()
        val first = MutableStateFlow<Preferences>(emptyPreferences())
        val second = MutableStateFlow<Preferences>(mutablePreferencesOf(stringPreferencesKey("selected_theme") to "OCEAN"))
        val store1 = mockk<DataStore<Preferences>> { every { data } returns first }
        val store2 = mockk<DataStore<Preferences>> { every { data } returns second }
        every { factory.get(1, "theme_settings") } returns store1
        every { factory.get(2, "theme_settings") } returns store2
        val themes = ThemeDataStore(factory, manager)
        assertEquals(AppTheme.GLASS, resolveAppTheme(themes.themeSelection.first().theme, CosmeticEntitlements.None))
        assertEquals(AppTheme.GLASS, resolveAppTheme(themes.getThemeForProfile(1), CosmeticEntitlements.SupporterPreview))
        active.value = 2
        assertEquals(AppTheme.OCEAN, themes.themeSelection.first().theme)
        assertEquals(AppTheme.OCEAN, themes.observeThemeForProfile(2).first())
        active.value = 1
        assertEquals(AppTheme.GLASS, resolveAppTheme(themes.themeSelection.first().theme, CosmeticEntitlements.None))
        assertEquals("OCEAN", second.value[stringPreferencesKey("selected_theme")])
        assertTrue(first.value.asMap().isEmpty())
    }
}
