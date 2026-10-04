package com.nuvio.tv.updater

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class ForkUpdatePreferencesTest {
    @Test fun `migration removes channel while retaining banner skip and last check preferences`() {
        for (channel in listOf("stable", "beta", "future")) {
            val p = mutablePreferencesOf(
                stringPreferencesKey("update_channel") to channel,
                stringPreferencesKey("ignored_release_tag") to "0.9.4-beta-nt1",
                booleanPreferencesKey("update_banner_enabled") to false,
                longPreferencesKey("last_check_at_ms") to 123L
            )
            UpdatePreferences.migrateForkStream(p)
            assertNull(p[stringPreferencesKey("update_channel")])
            assertEquals("0.9.4-beta-nt1", p[stringPreferencesKey("ignored_release_tag")])
            assertEquals(false, p[booleanPreferencesKey("update_banner_enabled")])
            assertEquals(123L, p[longPreferencesKey("last_check_at_ms")])
            val snapshot = p.toPreferences()
            UpdatePreferences.migrateForkStream(p)
            assertEquals(snapshot, p)
        }
    }
    @Test fun `skip applies to one rebuild while old tag skips and forced checks still work`() {
        assertFalse(UpdateBannerPolicy.shouldShow(true, false, true, "tag#1374", "tag", "tag#1374"))
        assertTrue(UpdateBannerPolicy.shouldShow(true, false, true, "tag#1374", "tag", "tag#1375"))
        assertFalse(UpdateBannerPolicy.shouldShow(true, false, true, "tag", "tag", "tag#1375"))
        assertTrue(UpdateBannerPolicy.shouldShow(true, true, false, "tag", "tag", "tag#1375"))
    }
}
