package com.nuvio.tv.ui.scout

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScoutMenuIconTest {
    @Test
    fun `the menu icon has real path data`() {
        val svg = File("src/main/res/raw/sidebar_scout.svg").readText()
        val data = Regex("""\sd="([^"]*)"""").find(svg)?.groupValues?.get(1).orEmpty()
        assertTrue(data, data.startsWith("M") && data.length > 50)
    }
}
