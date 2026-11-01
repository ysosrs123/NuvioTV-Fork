package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The generating screen either moves on or ends, so the film is never hidden behind an endless spinner. */
class PrepareScreenGuardTest {
    @Test fun aScreenWithoutASessionEndsAfterThreeSeconds() {
        // On a 2 GB Fire TV Stick: the session ended before its screen came up, the early screen stayed.
        val g = PrepareScreenGuard()
        assertNull(g.note(0L, "checking", owned = true, foreground = true))
        assertNull(g.note(1_000L, "checking", owned = false, foreground = true))
        assertNull(g.note(3_999L, "checking", owned = false, foreground = true))
        assertEquals(PrepareScreenGuard.End.NO_SESSION, g.note(4_000L, "checking", owned = false, foreground = true))
    }

    @Test fun aScreenThatShowsNoProgressEndsAfter45Seconds() {
        val g = PrepareScreenGuard()
        assertNull(g.note(0L, "checking", owned = true, foreground = true))
        assertNull(g.note(44_999L, "checking", owned = true, foreground = true))
        assertEquals(PrepareScreenGuard.End.NO_PROGRESS, g.note(45_000L, "checking", owned = true, foreground = true))
        assertEquals(45_000L, g.stillForMs(45_000L))
    }

    @Test fun eachNewThumbnailStartsTheWaitAgain() {
        val g = PrepareScreenGuard()
        assertNull(g.note(0L, "checking", owned = true, foreground = true))
        assertNull(g.note(30_000L, 0, owned = true, foreground = true))
        assertNull(g.note(60_000L, 1, owned = true, foreground = true))
        assertNull(g.note(104_999L, 1, owned = true, foreground = true))
        assertEquals(PrepareScreenGuard.End.NO_PROGRESS, g.note(105_000L, 1, owned = true, foreground = true))
    }

    @Test fun nothingIsJudgedInTheBackgroundOrWithoutAScreen() {
        val g = PrepareScreenGuard()
        assertNull(g.note(0L, "checking", owned = false, foreground = false))
        assertNull(g.note(100_000L, "checking", owned = false, foreground = false))
        assertNull(g.note(101_000L, "checking", owned = true, foreground = true))
        assertNull(g.note(200_000L, null, owned = false, foreground = true))
        assertEquals(0L, g.stillForMs(200_000L))
        assertNull(g.note(201_000L, "checking", owned = true, foreground = true))
    }

    @Test fun aSessionThatComesBackClearsTheNoSessionWait() {
        val g = PrepareScreenGuard()
        assertNull(g.note(0L, "checking", owned = false, foreground = true))
        assertNull(g.note(2_000L, "checking", owned = true, foreground = true))
        assertNull(g.note(4_000L, "checking", owned = false, foreground = true))
        assertNull(g.note(6_999L, "checking", owned = false, foreground = true))
        assertEquals(PrepareScreenGuard.End.NO_SESSION, g.note(7_000L, "checking", owned = false, foreground = true))
    }
}
