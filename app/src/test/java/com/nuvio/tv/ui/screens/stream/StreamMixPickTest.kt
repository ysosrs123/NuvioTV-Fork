package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StreamMixPickTest {
    private val manual = PlayerSettings(
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
        streamAutoPlaySource = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
        streamAutoPlaySelectedAddons = setOf("Tortoise"),
        streamAutoPlayRegex = "2160p"
    )

    @Test
    fun `a Mix episode with Pick sources for me picks the first suitable source in Manual`() {
        val picked = manual.forMixPick(pickForMe = true)
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, picked.streamAutoPlayMode)
        assertEquals(StreamAutoPlaySource.ALL_SOURCES, picked.streamAutoPlaySource)
        assertEquals(emptySet<String>(), picked.streamAutoPlaySelectedAddons)
        assertEquals("", picked.streamAutoPlayRegex)
        assertEquals(manual.streamAutoPlayTimeoutSeconds, picked.streamAutoPlayTimeoutSeconds)
    }

    @Test
    fun `the switch off or an auto-play rule of the viewer's own leaves the settings alone`() {
        assertSame(manual, manual.forMixPick(pickForMe = false))
        val regex = manual.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH)
        assertSame(regex, regex.forMixPick(pickForMe = true))
    }
}
