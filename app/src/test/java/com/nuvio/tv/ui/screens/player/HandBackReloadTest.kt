package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class HandBackReloadTest {

    @Test
    fun `a player that let go of the film is built again`() {
        assertEquals(HandBackReload.REBUILD, HandBackReload.of(stopped = true, failed = false))
    }

    @Test
    fun `a held player that kept the film just plays`() {
        assertEquals(HandBackReload.PLAY, HandBackReload.of(stopped = false, failed = false))
    }

    @Test
    fun `a player in error is left to the error handling`() {
        assertEquals(HandBackReload.NONE, HandBackReload.of(stopped = true, failed = true))
        assertEquals(HandBackReload.NONE, HandBackReload.of(stopped = false, failed = true))
    }
}
