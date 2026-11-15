package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvSubtitleFontPickTest {

    private val bundled = MpvSubFontPick("Noto Sans", false)
    private val previous = MpvSubFontPick("Tahoma", true)

    @Test
    fun `no imported font uses the bundled choice`() {
        assertEquals(bundled, mpvSubtitleFontPick(null, null, setOf("a"), bundled, true, previous))
    }

    @Test
    fun `an imported font libass loaded at start is used`() {
        assertEquals(
            MpvSubFontPick("Arial", true),
            mpvSubtitleFontPick("a", "Arial", setOf("a"), bundled, true, previous)
        )
    }

    @Test
    fun `a font imported during playback keeps the font in use until the next start`() {
        assertEquals(previous, mpvSubtitleFontPick("b", "Arial", setOf("a"), bundled, false, previous))
    }

    @Test
    fun `a not yet loaded font with nothing applied before falls back to the bundled choice`() {
        assertEquals(bundled, mpvSubtitleFontPick("b", "Arial", emptySet(), bundled, false, null))
    }
}
