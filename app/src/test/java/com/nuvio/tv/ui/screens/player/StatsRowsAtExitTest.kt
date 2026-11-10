package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsRowsAtExitTest {

    private val played = LastPlaybackDiagnostics(result = "Played")

    @Test
    fun aFilmLeftPartWayKeepsItsRebuffers() {
        val d = played.withRebuffersAtExit(true, 3, 4_200L, 0L)
        assertEquals(3, d.rebufferCount)
        assertEquals(4_200L, d.rebufferTotalMs)
    }

    @Test
    fun aStopStillRunningAtTheExitCounts() {
        assertEquals(5_000L, played.withRebuffersAtExit(true, 1, 4_200L, 800L).rebufferTotalMs)
        assertEquals(4_200L, played.withRebuffersAtExit(true, 1, 4_200L, -5L).rebufferTotalMs)
    }

    @Test
    fun theLastFilmsRecordIsLeftAloneBeforeTheNewFilmsFirstFrame() {
        val last = played.copy(rebufferCount = 2, rebufferTotalMs = 900L)
        assertEquals(last, last.withRebuffersAtExit(false, 0, 0L, 0L))
    }

    @Test
    fun anErrorRecordIsLeftAlone() {
        val error = LastPlaybackDiagnostics(rebufferCount = 2, result = "Error: decoder")
        assertEquals(error, error.withRebuffersAtExit(true, 7, 100L, 0L))
    }

    @Test
    fun memoryRowIsShortEnoughForOneLine() {
        assertEquals("Java 396/512 MB · native 638 MB", memoryRowText(396, 512, 638))
        assertTrue(memoryRowText(1024, 1024, 1024).length < "Java heap 396/512 MB · native heap 8 MB".length)
    }
}
