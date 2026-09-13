package com.nuvio.tv.ui.v2.scale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiVisibilityTest {
    @Test
    fun `scale stays frozen from player entry through the outgoing fade`() {
        // Current destination can advance before visibleEntries catches up on entry.
        assertTrue(isPlayerUiVisible("player/url/title", listOf("stream/id")))
        // On exit, current destination changes before the outgoing player is disposed.
        assertTrue(isPlayerUiVisible("home", listOf("player/url/title", "home")))
        assertFalse(isPlayerUiVisible("home", listOf("home")))
        assertFalse(isPlayerUiVisible(null, emptyList()))
    }
}
