package com.nuvio.tv.ui.screens.player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioTrackRejectionLogTest {

    @Before
    fun clear() = AudioTrackRejectionLog.reset()

    @Test
    fun labelForMime_mapsBitstreamMimes() {
        assertEquals("DTS-HD", AudioTrackRejectionLog.labelForMime("audio/vnd.dts.hd"))
        assertEquals("DTS", AudioTrackRejectionLog.labelForMime("audio/vnd.dts"))
        assertEquals("TrueHD", AudioTrackRejectionLog.labelForMime("audio/true-hd"))
        assertEquals("EAC3", AudioTrackRejectionLog.labelForMime("audio/eac3"))
        assertEquals("EAC3-JOC", AudioTrackRejectionLog.labelForMime("audio/eac3-joc"))
        assertEquals("AC3", AudioTrackRejectionLog.labelForMime("audio/ac3"))
        assertNull(AudioTrackRejectionLog.labelForMime("audio/mpeg"))
        assertNull(AudioTrackRejectionLog.labelForMime(null))
    }

    @Test
    fun record_dedupesOnEncodingAndRoute_keepingLatest() {
        AudioTrackRejectionLog.record("DTS-HD", "type:hdmi_arc", 100L)
        AudioTrackRejectionLog.record("DTS-HD", "type:hdmi_arc", 200L)
        val snap = AudioTrackRejectionLog.snapshot()
        assertEquals(1, snap.size)
        assertEquals(200L, snap.first().atMs)
    }

    @Test
    fun record_keepsDistinctRoutesSeparate() {
        AudioTrackRejectionLog.record("DTS-HD", "type:hdmi_arc", 100L)
        AudioTrackRejectionLog.record("DTS-HD", "type:hdmi_earc", 100L)
        assertEquals(2, AudioTrackRejectionLog.snapshot().size)
    }

    @Test
    fun encodingsRejectedOn_filtersByRoute() {
        AudioTrackRejectionLog.record("DTS-HD", "type:hdmi_arc", 1L)
        AudioTrackRejectionLog.record("DTS", "type:hdmi_earc", 1L)
        assertEquals(setOf("DTS-HD"), AudioTrackRejectionLog.encodingsRejectedOn("type:hdmi_arc"))
        assertEquals(setOf("DTS-HD", "DTS"), AudioTrackRejectionLog.encodingsRejectedOn(null))
    }
}
