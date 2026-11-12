package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerFirstPickTest {
    private fun stream(addon: String, name: String) = Stream(
        name = name, title = null, description = null, url = "https://example.invalid/$name", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )

    private val big = stream("Tortoise", "2160p remux")
    private val homeHd = stream("Home", "1080p")
    private val home4k = stream("Home", "2160p")
    private val nas = stream("NAS", "720p")

    @Test
    fun `the server set to go first wins over a bigger add-on stream, with the viewer's rule among its copies`() {
        val picked = serverFirstPick(listOf(big, homeHd, home4k), setOf("Home")) { own -> own.firstOrNull { it.name == "2160p" } }
        assertEquals(home4k, picked)
    }

    @Test
    fun `when the rule finds nothing on the server its first copy still plays`() {
        assertEquals(homeHd, serverFirstPick(listOf(big, homeHd), setOf("Home")) { null })
    }

    @Test
    fun `servers are tried in list order and a server without the title is skipped`() {
        assertEquals(nas, serverFirstPick(listOf(big, nas), setOf("Home", "NAS")) { it.first() })
    }

    @Test
    fun `no server has the title, so auto-play carries on as usual`() {
        assertNull(serverFirstPick(listOf(big), setOf("Home")) { it.first() })
        assertNull(serverFirstPick(listOf(big, homeHd), emptySet()) { it.first() })
    }
}
