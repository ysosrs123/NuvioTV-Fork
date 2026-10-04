package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `non tracker credentials are excluded from profile settings blobs`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "torbox_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "premiumize_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "real_debrid_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_client_id"))
    }

    @Test
    fun `tracker and non credential settings remain in their existing sync surfaces`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("trakt_settings", "trakt_access_token"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "debrid_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_enabled"))
    }

    @Test
    fun `audio passthrough and denied handling are device-local, excluded from profile settings blobs`() {
        val deviceLocalAudioKeys = listOf(
            "force_optical_passthrough",
            "allow_ac3_passthrough",
            "allow_eac3_passthrough",
            "allow_truehd_passthrough",
            "allow_dts_passthrough",
            "allow_dts_hd_passthrough",
            "denied_codec_handling",
            "mat_passthrough_enabled",
        )
        deviceLocalAudioKeys.forEach { key ->
            assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", key))
            // the exclusion is scoped to player_settings only
            assertFalse(shouldExcludePreferenceFromProfileSettingsSync("theme_settings", key))
        }
    }

    @Test
    fun `box specific fork settings stay on the box`() {
        listOf(
            "assessment_revert_snapshot",
            "audio_rejections_seen",
            "audio_rejections_confirmed",
            "audio_rejection_reset_token",
            "migration_back_buffer_budget_done",
            "inject_hdr10_metadata_on_strip",
        ).forEach { assertTrue(it, shouldExcludePreferenceFromProfileSettingsSync("player_settings", it)) }
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "subtitle_preferred_language"))
    }

    @Test
    fun `surround format settings stay device local under player settings`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "surround_format_mode"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "surround_channel_target"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_ac3_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_eac3_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_truehd_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_dts_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "allow_dtshd_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "denied_codec_handling"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "audio_rejections_seen"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "audio_rejections_confirmed"))
    }

    @Test
    fun `system passthrough stays device local like the other audio chain switches`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "use_system_passthrough"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "tunneling_enabled"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "force_optical_passthrough"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "use_system_passthrough"))
    }

    @Test
    fun `surround format keys are not excluded under an unrelated feature`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "surround_format_mode"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("layout_settings", "audio_rejections_confirmed"))
    }

    @Test
    fun `tunnel memo and rejection reset token stay device local`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "tunnel_dead_audio_classes"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "tunnel_dead_audio_signature"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", "audio_rejection_reset_token"))
    }
}
