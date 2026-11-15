package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import com.nuvio.tv.data.local.NightModeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvPassthroughTest {

    private val everything = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)

    @Test
    fun `every codec the device plays directly is allowed`() {
        assertEquals(
            "ac3,eac3,dts,dts-hd,truehd",
            mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false)
        )
    }

    @Test
    fun `codecs the device cannot play directly stay decoded`() {
        val dolbyOnly = DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = false, dtsHd = false)
        assertEquals("ac3,eac3", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, dolbyOnly, forceOptical = false))
    }

    @Test
    fun `passthrough switches turned off are respected`() {
        val policy = AudioPassthroughPolicy(allowTrueHd = false, allowDtsHd = false)
        assertEquals("ac3,eac3,dts", mpvAllowedSpdifCodecs(policy, everything, forceOptical = false))
    }

    @Test
    fun `formats learned as refused on this output stay decoded`() {
        val policy = AudioPassthroughPolicy(learnedDeniedGroups = setOf(AudioPassthroughPolicy.Group.EAC3))
        assertEquals("ac3,dts,dts-hd,truehd", mpvAllowedSpdifCodecs(policy, everything, forceOptical = false))
    }

    @Test
    fun `force optical adds only AC-3 and DTS when the device reports nothing`() {
        assertEquals("ac3,dts", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, null, forceOptical = true))
        assertEquals("", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, null, forceOptical = false))
    }

    @Test
    fun `force optical still follows the passthrough switches`() {
        val policy = AudioPassthroughPolicy(allowDts = false)
        assertEquals("ac3", mpvAllowedSpdifCodecs(policy, null, forceOptical = true))
    }

    @Test
    fun `plain playback keeps the allowed codecs`() {
        assertEquals("ac3,eac3", now(allowed = "ac3,eac3"))
    }

    @Test
    fun `bluetooth never gets a bitstream`() {
        assertEquals("", now(allowed = "ac3,eac3", isBluetooth = true))
    }

    @Test
    fun `sound adjustments need decoded sound`() {
        assertEquals("", now(allowed = "ac3", gainDb = 3))
        assertEquals("", now(allowed = "ac3", nightMode = NightModeLevel.entries.first { it != NightModeLevel.OFF }))
        assertEquals("", now(allowed = "ac3", dialogueBoostDb = 4))
    }

    @Test
    fun `other speeds need decoded sound`() {
        assertEquals("", now(allowed = "ac3", playbackSpeed = 1.25f))
        assertEquals("ac3", now(allowed = "ac3", playbackSpeed = 1f))
    }

    @Test
    fun `software decoding gets the automatic deinterlacer`() {
        assertEquals("auto", mpvDeinterlaceFor("no"))
    }

    @Test
    fun `hardware decoding leaves deinterlacing to the decoder`() {
        assertEquals("no", mpvDeinterlaceFor("mediacodec"))
        assertEquals("no", mpvDeinterlaceFor("mediacodec-copy"))
    }

    @Test
    fun `nothing changes before a video decoder runs`() {
        assertNull(mpvDeinterlaceFor(null))
        assertNull(mpvDeinterlaceFor(""))
    }

    private fun now(
        allowed: String,
        isBluetooth: Boolean = false,
        gainDb: Int = 0,
        nightMode: NightModeLevel = NightModeLevel.OFF,
        dialogueBoostDb: Int = 0,
        playbackSpeed: Float = 1f
    ) = mpvSpdifCodecsNow(allowed, isBluetooth, gainDb, nightMode, dialogueBoostDb, playbackSpeed)
}
