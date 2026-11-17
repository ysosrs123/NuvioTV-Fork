package com.nuvio.tv.ui.screens.player

import android.media.AudioFormat
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import com.nuvio.tv.data.local.NightModeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvPassthroughTest {

    private val everything = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)

    @Test
    fun `only the formats that fit mpv's 48 kHz carrier are sent as a bitstream`() {
        assertEquals("ac3,dts", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false))
    }

    @Test
    fun `E-AC-3, DTS-HD and TrueHD are always decoded by mpv`() {
        val highRateOnly = DirectSupport(ac3 = false, eac3 = true, trueHd = true, dts = false, dtsHd = true)
        assertEquals("", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, highRateOnly, forceOptical = false))
    }

    @Test
    fun `codecs the device cannot play directly stay decoded`() {
        val dolbyOnly = DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = false, dtsHd = false)
        assertEquals("ac3", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, dolbyOnly, forceOptical = false))
    }

    @Test
    fun `passthrough switches turned off are respected`() {
        val policy = AudioPassthroughPolicy(allowDts = false)
        assertEquals("ac3", mpvAllowedSpdifCodecs(policy, everything, forceOptical = false))
    }

    @Test
    fun `a DTS-HD switch turned off still lets the DTS core through`() {
        val policy = AudioPassthroughPolicy(allowTrueHd = false, allowDtsHd = false)
        assertEquals("ac3,dts", mpvAllowedSpdifCodecs(policy, everything, forceOptical = false))
    }

    @Test
    fun `formats learned as refused on this output stay decoded`() {
        val policy = AudioPassthroughPolicy(learnedDeniedGroups = setOf(AudioPassthroughPolicy.Group.AC3))
        assertEquals("dts", mpvAllowedSpdifCodecs(policy, everything, forceOptical = false))
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
    fun `a TV that lists only PCM gets decoded sound`() {
        val pcmOnly = setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT)
        assertEquals("", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false, sinkEncodings = pcmOnly))
    }

    @Test
    fun `only the formats the TV or receiver lists go out as a bitstream`() {
        val dolbyReceiver = setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3)
        assertEquals("ac3", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false, sinkEncodings = dolbyReceiver))
        val soundbar = setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_DOLBY_TRUEHD)
        assertEquals("ac3,dts", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false, sinkEncodings = soundbar))
    }

    @Test
    fun `a receiver that lists DTS-HD takes the DTS core`() {
        val dtsHdOnly = setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_DTS_HD)
        assertEquals("dts", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false, sinkEncodings = dtsHdOnly))
    }

    @Test
    fun `no HDMI report keeps the device answer, force optical ignores the report`() {
        assertEquals("ac3,dts", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, everything, forceOptical = false, sinkEncodings = null))
        assertEquals("ac3,dts", mpvAllowedSpdifCodecs(AudioPassthroughPolicy.ALLOW_ALL, null, forceOptical = true, sinkEncodings = setOf(AudioFormat.ENCODING_PCM_16BIT)))
    }

    @Test
    fun `the HDMI report counts only for HDMI outputs`() {
        assertEquals(true, mpvSinkReportApplies("type:hdmi|name:tv"))
        assertEquals(true, mpvSinkReportApplies("type:hdmi_arc|name:soundbar"))
        assertEquals(true, mpvSinkReportApplies("type:hdmi_earc|name:receiver"))
        assertEquals(false, mpvSinkReportApplies("type:line_digital|name:optical"))
        assertEquals(false, mpvSinkReportApplies("type:usb_device|name:dac"))
        assertEquals(false, mpvSinkReportApplies(null))
    }

    @Test
    fun `a stream marked for decoded sound gets no bitstream`() {
        assertEquals("", mpvCodecsToApply("https://a/film.mkv", setOf("https://a/film.mkv")) { "ac3,dts" })
        assertEquals("ac3,dts", mpvCodecsToApply("https://a/other.mkv", setOf("https://a/film.mkv")) { "ac3,dts" })
    }

    @Test
    fun `a start that stalls before the first frame retries with decoded sound`() {
        assertEquals(true, mpvRetryDecodesSound("startup_stall", firstFrameRendered = false, bitstreamActive = true))
        assertEquals(true, mpvRetryDecodesSound("startup_absolute", firstFrameRendered = false, bitstreamActive = true))
        assertEquals(false, mpvRetryDecodesSound("startup_stall", firstFrameRendered = false, bitstreamActive = false))
        assertEquals(false, mpvRetryDecodesSound("startup_stall", firstFrameRendered = true, bitstreamActive = true))
        assertEquals(false, mpvRetryDecodesSound("surface_timeout", firstFrameRendered = false, bitstreamActive = true))
        assertEquals(false, mpvRetryDecodesSound("idle_active", firstFrameRendered = false, bitstreamActive = true))
    }

    @Test
    fun `plain playback keeps the allowed codecs`() {
        assertEquals("ac3,dts", now(allowed = "ac3,dts"))
    }

    @Test
    fun `bluetooth never gets a bitstream`() {
        assertEquals("", now(allowed = "ac3,dts", isBluetooth = true))
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
