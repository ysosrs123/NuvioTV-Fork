package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IecStallMarginTest {

    @Test
    fun smallBox_asksForALargerTrackThatFitsTheFourMegabyteAudioHeap() {
        val ms = IecStallMargin.trueHdBufferMs(smallBox = true)
        assertTrue(ms > IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS)
        assertTrue(ms * 192 * 8 * 2 < 3_900_000)
    }

    @Test
    fun otherBoxes_keepTheOneSecondTrack() {
        assertEquals(IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS, IecStallMargin.trueHdBufferMs(smallBox = false))
    }
}
