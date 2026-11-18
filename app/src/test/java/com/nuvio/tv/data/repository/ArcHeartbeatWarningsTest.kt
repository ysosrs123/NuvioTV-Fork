package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ArcHeartbeatWarningsTest {
    @Test fun `each warning says whether a film played in front`() {
        assertEquals("14:41:30 level=40 playing front", ArcHeartbeat.trimEntry("14:41:30", 40, playing = true, front = true))
        assertEquals("18:29:57 level=20 idle back", ArcHeartbeat.trimEntry("18:29:57", 20, playing = false, front = false))
    }

    @Test fun `a cut adds the buffer limit before and after, once`() {
        val cut = ArcHeartbeat.withCut("14:41:30 level=40 playing front", 500, 375)
        assertEquals("14:41:30 level=40 playing front buffer 500>375 MB", cut)
        assertEquals(cut, ArcHeartbeat.withCut(cut, 375, 281))
    }
}
