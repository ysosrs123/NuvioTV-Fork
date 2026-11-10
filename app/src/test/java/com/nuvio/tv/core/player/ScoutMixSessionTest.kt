package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutMixSessionTest {
    @Test
    fun `only the exact episode on the right profile is in the mix`() {
        val session = ScoutMixSession()
        assertFalse(session.matches(1, "a", "a:1:1"))
        session.start(1, "m", "a", "a:1:1")
        assertTrue(session.matches(1, "a", "a:1:1"))
        assertFalse(session.matches(2, "a", "a:1:1"))
        assertFalse(session.matches(1, "b", "a:1:1"))
        assertFalse(session.matches(1, "a", "a:1:2"))
    }

    @Test
    fun `an episode that ran to its end says so, once`() {
        val session = ScoutMixSession()
        session.start(1, "m", "a", "a:1:1")
        assertTrue(session.returnFromPlayer("a", ScoutMixReturn.ENDED))
        assertEquals(ScoutMixReturn.ENDED, session.finish())
        assertEquals(ScoutMixReturn.LEFT, session.finish())
        assertFalse(session.matches(1, "a", "a:1:1"))
    }

    @Test
    fun `back past the watched mark is seen but does not carry on`() {
        val session = ScoutMixSession()
        session.start(1, "m", "a", "a:1:1")
        assertTrue(session.returnFromPlayer("a", ScoutMixReturn.WATCHED))
        assertEquals(ScoutMixReturn.WATCHED, session.finish())
    }

    @Test
    fun `leaving the sources page counts as nothing`() {
        val session = ScoutMixSession()
        session.start(1, "m", "a", "a:1:1")
        assertEquals(ScoutMixReturn.LEFT, session.finish())
    }

    @Test
    fun `a player showing another show is left alone`() {
        val session = ScoutMixSession()
        assertFalse(session.returnFromPlayer("a", ScoutMixReturn.ENDED))
        session.start(1, "m", "a", "a:1:1")
        assertFalse(session.returnFromPlayer("b", ScoutMixReturn.ENDED))
        assertFalse(session.returnFromPlayer(null, ScoutMixReturn.ENDED))
        assertEquals(ScoutMixReturn.LEFT, session.finish())
    }

    @Test
    fun `active lasts until the page takes back over`() {
        val session = ScoutMixSession()
        assertFalse(session.active)
        session.start(1, "m", "a", "a:1:1")
        assertTrue(session.active)
        session.finish()
        assertFalse(session.active)
    }

    @Test
    fun `moving to another episode of the show still returns to the mix`() {
        val session = ScoutMixSession()
        session.start(1, "m", "a", "a:1:1")
        assertFalse(session.matches(1, "a", "a:1:5"))
        assertTrue(session.returnFromPlayer("a", ScoutMixReturn.LEFT))
    }

    private fun upNext(videoId: String) = ScoutMixUpNext("Mix", "Two", "b", videoId, 1, 2, "E2", null, startsByItself = true)

    @Test
    fun `the next pick belongs to the episode it was chosen after`() {
        val session = ScoutMixSession()
        session.start(1, "m", "a", "a:1:1")
        session.offerNext("a:1:9", upNext("b:1:2"))
        assertNull(session.upNextFor(1, "a", "a:1:1"))
        session.offerNext("a:1:1", upNext("b:1:2"))
        assertEquals("b:1:2", session.upNextFor(1, "a", "a:1:1")?.videoId)
        assertNull(session.upNextFor(2, "a", "a:1:1"))
        assertNull(session.upNextFor(1, "a", "a:1:5"))
        session.start(1, "m", "b", "b:1:2")
        assertNull(session.upNextFor(1, "b", "b:1:2"))
    }

    @Test
    fun `a pressed next card is told apart from one that counted down`() {
        val session = ScoutMixSession()
        session.start(1, "m", "a", "a:1:1")
        assertTrue(session.returnFromPlayer("a", ScoutMixReturn.NEXT))
        assertEquals(ScoutMixReturn.NEXT, session.finish())

        session.start(1, "m", "a", "a:1:1")
        session.pressedNext(1, "a", "a:1:1")
        session.returnFromPlayer("a", ScoutMixReturn.NEXT)
        assertEquals(ScoutMixReturn.NEXT_PRESSED, session.finish())

        session.start(1, "m", "a", "a:1:1")
        assertEquals(ScoutMixReturn.LEFT, session.finish())
    }
}
