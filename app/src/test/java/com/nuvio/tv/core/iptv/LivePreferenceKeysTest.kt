package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class LivePreferenceKeysTest {
    private val keys = listOf("1:a:category", "1:a:hidden", "1:b:category", "12:a:category", "2:a:hidden", "multiview-layout", "multiview-quality")

    @Test fun profileRemovalKeepsOtherProfilesAndDeviceSettings() {
        assertEquals(listOf("1:a:category", "1:a:hidden", "1:b:category"), LivePreferenceKeys.ofProfile(keys, 1))
        assertEquals(listOf("1:a:category", "1:a:hidden"), LivePreferenceKeys.ofSource(keys, 1, "a"))
        assertEquals(keys.take(5), LivePreferenceKeys.ofAllProfiles(keys))
        assertEquals("3:x:hidden", LivePreferenceKeys.source(3, "x", "hidden"))
        assertEquals(listOf("1:a:hidden"), LivePreferenceKeys.hiddenOfProfile(keys, 1))
        assertEquals(keys.take(5), LivePreferenceKeys.ofAllProfiles(keys + "settings-format"))
    }
}
