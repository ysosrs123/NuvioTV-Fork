package com.nuvio.tv.core.network

import org.junit.Assert.*
import org.junit.Test

class DiagnosticPlaybackGuardTest {
    @Test fun `player ownership blocks diagnostics until release`() {
        val guard=DiagnosticPlaybackGuard(); val player=guard.enterPlayback()
        assertNull(guard.register { fail("Must not register while a player owns resources") })
        player.close(); assertNotNull(guard.register {})
    }
    @Test fun `playback cancels an admitted diagnostic once per start`() {
        val guard=DiagnosticPlaybackGuard(); var cancelled=0
        val registration=guard.register { cancelled++ }!!
        val player=guard.enterPlayback(); assertEquals(1,cancelled)
        registration.close(); player.close(); guard.enterPlayback().close(); assertEquals(1,cancelled)
    }
    @Test fun `releasing one player cannot expose another paused session`() {
        val guard=DiagnosticPlaybackGuard(); val first=guard.enterPlayback(); val second=guard.enterPlayback()
        first.close(); first.close(); assertNull(guard.register {})
        second.close(); assertNotNull(guard.register {})
    }
    @Test fun `finished diagnostics are not called on future playback`() {
        val guard=DiagnosticPlaybackGuard(); val registration=guard.register { fail("Already closed") }!!
        registration.close(); registration.close(); guard.enterPlayback().close()
    }
}
