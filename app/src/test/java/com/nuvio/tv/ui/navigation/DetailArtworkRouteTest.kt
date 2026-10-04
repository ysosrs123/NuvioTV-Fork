package com.nuvio.tv.ui.navigation

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailArtworkRouteTest {
    @Test fun `artwork URLs with query delimiters survive the detail route`() {
        val logo = "https://images.example/title logo.png?lang=en&token=a+b#variant"
        val backdrop = "https://images.example/backdrop.jpg?width=1280&crop=fit"
        val route = Screen.Detail.createRoute("tt123", "series",
            heroBackdropUrl = backdrop, heroLogoUrl = logo)
        val arguments = route.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals(logo, arguments["heroLogoUrl"])
        assertEquals(backdrop, arguments["heroBackdropUrl"])
        assertEquals("false", arguments["playOnLoad"])
    }
}
