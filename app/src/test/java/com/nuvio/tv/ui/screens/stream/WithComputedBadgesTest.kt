package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WithComputedBadgesTest {
    private val badge = mockk<StreamBadge>()

    private fun stream(name: String) = Stream(
        name = name,
        title = null,
        description = null,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "addon",
        addonLogo = null
    )

    private fun groups(vararg names: String) =
        listOf(AddonStreams(addonName = "addon", addonLogo = null, streams = names.map(::stream)))

    @Test
    fun `badges lost to a newer list are put back`() {
        val result = withComputedBadges(groups("a", "b"), mapOf("a" to listOf(badge))) { it.name.orEmpty() }

        assertEquals(listOf(badge), result[0].streams[0].badges)
        assertEquals(emptyList<StreamBadge>(), result[0].streams[1].badges)
    }

    @Test
    fun `same list comes back when nothing changes`() {
        val input = groups("a")

        assertSame(input, withComputedBadges(input, mapOf("other" to listOf(badge))) { it.name.orEmpty() })
    }
}
