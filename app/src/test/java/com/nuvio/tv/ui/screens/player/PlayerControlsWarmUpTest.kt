package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsWarmUpTest {

    private fun warm(
        overlay: Boolean = true,
        warmed: Boolean = false,
        active: Boolean = true,
        controls: Boolean = false
    ) = shouldWarmPlayerControls(overlay, warmed, active, controls)

    @Test
    fun `warms once behind the loading screen`() {
        assertTrue(warm())
    }

    @Test
    fun `does not warm again after the first time`() {
        assertFalse(warm(warmed = true))
    }

    @Test
    fun `does not warm without the loading screen covering it`() {
        assertFalse(warm(overlay = false))
    }

    @Test
    fun `does not warm when the player has an error`() {
        assertFalse(warm(active = false))
    }

    @Test
    fun `does not warm while the real controls are showing`() {
        assertFalse(warm(controls = true))
    }
}
