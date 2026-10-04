package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutRetainedStoresTest {
    @Test
    fun `device settings stores survive sign-out`() {
        listOf("seek_thumbnails", "ui_scale_prefs", "appearance_v2", "device_ui_preferences_v2", "device_local_player_prefs", "torrent_settings")
            .forEach { assertFalse(it, isProfileScopedDataStoreFile("$it.preferences_pb")) }
    }

    @Test
    fun `profile stores are still cleared at sign-out`() {
        assertTrue(isProfileScopedDataStoreFile("player_settings.preferences_pb"))
        assertTrue(isProfileScopedDataStoreFile("player_settings_p2.preferences_pb"))
        assertTrue(isProfileScopedDataStoreFile("seek_thumbnails_p2.preferences_pb"))
        assertFalse(isProfileScopedDataStoreFile("player_settings.json"))
    }
}
