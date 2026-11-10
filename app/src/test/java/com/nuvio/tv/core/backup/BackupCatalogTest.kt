package com.nuvio.tv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCatalogTest {
    private fun spec(kind: BackupStoreKind, name: String) = requireNotNull(BackupCatalog.spec(kind, name))

    @Test
    fun `debrid keys and add-on links are kept sealed`() {
        val debrid = spec(BackupStoreKind.PROFILE_STORE, "debrid_settings")
        assertEquals(BackupPart.SECRETS, BackupCatalog.partFor(debrid, "torbox_api_key"))
        assertEquals(BackupPart.SECRETS, BackupCatalog.partFor(debrid, "real_debrid_api_key"))
        assertEquals(BackupPart.SETTINGS, BackupCatalog.partFor(debrid, "preferred_resolver_provider_id"))
        val addons = spec(BackupStoreKind.PROFILE_STORE, "addon_preferences")
        assertEquals(BackupPart.SECRETS, BackupCatalog.partFor(addons, "installed_addon_urls_ordered"))
        assertEquals(BackupPart.SECRETS, BackupCatalog.partFor(addons, "installed_addon_enabled_states"))
    }

    @Test
    fun `box specific sound and picture keys stay apart`() {
        val player = spec(BackupStoreKind.PROFILE_STORE, "player_settings")
        assertEquals(BackupPart.DEVICE, BackupCatalog.partFor(player, "allow_truehd_passthrough"))
        assertEquals(BackupPart.DEVICE, BackupCatalog.partFor(player, "dv7_handling_mode"))
        assertEquals(BackupPart.DEVICE, BackupCatalog.partFor(player, "night_mode"))
        assertEquals(BackupPart.SETTINGS, BackupCatalog.partFor(player, "subtitle_font"))
        assertEquals(BackupPart.SETTINGS, BackupCatalog.partFor(player, "player_chapters_enabled"))
        assertEquals(BackupPart.DEVICE, BackupCatalog.partFor(spec(BackupStoreKind.PROFILE_STORE, "trailer_settings"), "trailer_allow_4k"))
        assertEquals(BackupPart.DEVICE, BackupCatalog.partFor(spec(BackupStoreKind.BOX_STORE, "ui_scale_prefs"), "ui_scale"))
    }

    @Test
    fun `bookkeeping is never copied`() {
        val player = spec(BackupStoreKind.PROFILE_STORE, "player_settings")
        assertNull(BackupCatalog.partFor(player, "migration_max_buffer_bumped_done"))
        assertNull(BackupCatalog.partFor(player, "last_playback_diagnostics_json"))
        val profiles = spec(BackupStoreKind.BOX_STORE, "profile_settings")
        assertNull(BackupCatalog.partFor(profiles, "active_profile_id"))
        assertEquals(BackupPart.PROFILES, BackupCatalog.partFor(profiles, "profiles_json"))
        assertEquals(BackupPart.SETTINGS, BackupCatalog.partFor(profiles, "confirm_exit_enabled"))
    }

    @Test
    fun `stores the account sign-in lives in are not in the catalogue`() {
        val names = BackupCatalog.stores.map { it.name }.toSet()
        listOf("auth_session_notice_store", "profile_settings_sync_state", "watch_progress_preferences", "stream_link_cache")
            .forEach { assertTrue(it !in names) }
    }

    @Test
    fun `having seen the first sign-in page comes back with the settings`() {
        val onboarding = spec(BackupStoreKind.BOX_STORE, "app_onboarding")
        assertEquals(BackupPart.SETTINGS, BackupCatalog.partFor(onboarding, "has_seen_auth_qr_on_first_launch"))
    }

    @Test
    fun `profile file names map to profile ids`() {
        assertEquals("layout_settings", BackupCatalog.profileStoreFileName("layout_settings", 1))
        assertEquals("layout_settings_p3", BackupCatalog.profileStoreFileName("layout_settings", 3))
        assertEquals(1, BackupCatalog.profileIdOf("layout_settings", "layout_settings"))
        assertEquals(3, BackupCatalog.profileIdOf("layout_settings", "layout_settings_p3"))
        assertNull(BackupCatalog.profileIdOf("layout_settings", "layout_settings_px"))
    }

    @Test
    fun `split sorts one store into its parts`() {
        val player = spec(BackupStoreKind.PROFILE_STORE, "player_settings")
        val parts = BackupCatalog.split(
            player,
            mapOf(
                "subtitle_font" to BackupValue.Text("inter"),
                "night_mode" to BackupValue.Flag(true),
                "migration_vod_cache_split_done" to BackupValue.Flag(true)
            )
        )
        assertEquals(setOf("subtitle_font"), parts[BackupPart.SETTINGS]?.keys)
        assertEquals(setOf("night_mode"), parts[BackupPart.DEVICE]?.keys)
        assertEquals(2, parts.size)
    }
}
