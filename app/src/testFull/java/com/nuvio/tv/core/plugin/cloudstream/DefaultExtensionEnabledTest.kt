package com.nuvio.tv.core.plugin.cloudstream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultExtensionEnabledTest {

    @Test
    fun `an extension the repository marks as down starts off`() {
        assertFalse(defaultExtensionEnabled(status = 0, tvTypes = listOf("Movie")))
    }

    @Test
    fun `working, slow and beta extensions start on`() {
        for (status in 1..3) {
            assertTrue(defaultExtensionEnabled(status = status, tvTypes = listOf("Movie", "TvSeries")))
        }
    }

    @Test
    fun `an extension with an adult type starts off, even with other types`() {
        assertFalse(defaultExtensionEnabled(status = 1, tvTypes = listOf("NSFW")))
        assertFalse(defaultExtensionEnabled(status = 1, tvTypes = listOf("NSFW", "Live")))
        assertFalse(defaultExtensionEnabled(status = 1, tvTypes = listOf("Movie", "nsfw")))
    }

    @Test
    fun `an extension with only live or audio types starts off`() {
        assertFalse(defaultExtensionEnabled(status = 1, tvTypes = listOf("Live")))
        assertFalse(defaultExtensionEnabled(status = 1, tvTypes = listOf("AudioBook", "Podcast")))
    }

    @Test
    fun `live alongside films or series stays on`() {
        assertTrue(defaultExtensionEnabled(status = 1, tvTypes = listOf("Live", "Movie")))
    }

    @Test
    fun `an extension with no listed types stays on`() {
        assertTrue(defaultExtensionEnabled(status = 1, tvTypes = null))
        assertTrue(defaultExtensionEnabled(status = 1, tvTypes = emptyList()))
    }
}
