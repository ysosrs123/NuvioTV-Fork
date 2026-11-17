package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.SurroundFormatResolver.DirectSupport
import com.nuvio.tv.core.player.SurroundFormatResolver.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundAacToAc3Test {

    private val tvOverArc = DirectSupport(ac3 = true, eac3 = true, trueHd = false, dts = false, dtsHd = false)

    private fun resolve(
        manualMode: Boolean = false,
        allowAc3: Boolean = true,
        manualTranscodePreferred: Boolean = false,
        manualChannelTargetChannels: Int? = null,
        direct: DirectSupport? = tvOverArc,
        rawMaxPcmChannels: Int? = 8,
        routeIsHdmiArc: Boolean = false,
        softwareDecodersAvailable: Boolean = true,
        tvArcSoundbar: Boolean = false
    ): Resolution = SurroundFormatResolver.resolve(
        manualMode = manualMode,
        allowAc3 = allowAc3,
        allowEac3 = true,
        allowTrueHd = true,
        allowDts = true,
        allowDtsHd = true,
        manualTranscodePreferred = manualTranscodePreferred,
        manualChannelTargetChannels = manualChannelTargetChannels,
        direct = direct,
        rawMaxPcmChannels = rawMaxPcmChannels,
        routeIsBluetooth = false,
        routeIsHdmiArc = routeIsHdmiArc,
        softwareDecodersAvailable = softwareDecodersAvailable,
        forceOpticalActive = false,
        learnedDeniedGroups = emptySet(),
        tvArcSoundbar = tvArcSoundbar
    )

    private fun planned(r: Resolution, forcePassthrough: Boolean = false): Set<String> =
        DeniedTranscodePlanner.effectiveTranscodeMimes(
            policy = r.policy,
            transcodeDeniedToAc3 = r.transcodePreferred,
            forcePassthroughActive = forcePassthrough,
            surroundAacToAc3 = r.surroundAacToAc3
        )

    @Test
    fun `a TV feeding a soundbar over ARC sends surround AAC as Dolby Digital`() {
        val r = resolve(tvArcSoundbar = true)
        assertTrue(r.surroundAacToAc3)
        assertTrue(MimeTypes.AUDIO_AAC in planned(r))
        assertTrue(MimeTypes.AUDIO_TRUEHD in planned(r))
    }

    @Test
    fun `an ARC route found by the box does the same`() {
        assertTrue(resolve(routeIsHdmiArc = true).surroundAacToAc3)
        assertTrue(resolve(routeIsHdmiArc = true, direct = null).surroundAacToAc3)
    }

    @Test
    fun `a stereo-only output that is not a soundbar keeps decoded AAC as PCM`() {
        val all = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)
        val r = resolve(direct = all, rawMaxPcmChannels = 2)
        assertFalse(r.surroundAacToAc3)
        assertEquals(emptySet<String>(), planned(r))
    }

    @Test
    fun `an ARC soundbar that passes every other format still converts surround AAC`() {
        val all = DirectSupport(ac3 = true, eac3 = true, trueHd = true, dts = true, dtsHd = true)
        val r = resolve(direct = all, rawMaxPcmChannels = 8, tvArcSoundbar = true)
        assertTrue(r.surroundAacToAc3)
        assertTrue(MimeTypes.AUDIO_AAC in planned(r))
    }

    @Test
    fun `moving from the soundbar to a receiver and back follows the route`() {
        val arc = resolve(routeIsHdmiArc = true)
        val receiver = resolve(rawMaxPcmChannels = 8)
        assertTrue(arc.surroundAacToAc3)
        assertFalse(receiver.surroundAacToAc3)
        assertFalse(MimeTypes.AUDIO_AAC in planned(receiver))
        assertEquals(arc, resolve(routeIsHdmiArc = true))
    }

    @Test
    fun `outputs that take multichannel PCM keep decoded AAC as PCM`() {
        assertFalse(resolve(rawMaxPcmChannels = 6).surroundAacToAc3)
        assertFalse(resolve(rawMaxPcmChannels = 8).surroundAacToAc3)
        assertFalse(resolve(rawMaxPcmChannels = null).surroundAacToAc3)
    }

    @Test
    fun `no conversion without Dolby Digital output or the FFmpeg decoder`() {
        assertFalse(resolve(tvArcSoundbar = true, direct = tvOverArc.copy(ac3 = false)).surroundAacToAc3)
        assertFalse(resolve(tvArcSoundbar = true, softwareDecodersAvailable = false).surroundAacToAc3)
    }

    @Test
    fun `a chosen decode channel layout wins`() {
        assertFalse(resolve(tvArcSoundbar = true, manualChannelTargetChannels = 6).surroundAacToAc3)
        assertFalse(resolve(tvArcSoundbar = true, manualChannelTargetChannels = 2).surroundAacToAc3)
    }

    @Test
    fun `manual mode converts only with the convert switch on and Dolby Digital allowed`() {
        assertFalse(resolve(manualMode = true, rawMaxPcmChannels = 2).surroundAacToAc3)
        assertTrue(resolve(manualMode = true, manualTranscodePreferred = true, rawMaxPcmChannels = 2).surroundAacToAc3)
        assertFalse(
            resolve(manualMode = true, manualTranscodePreferred = true, allowAc3 = false, rawMaxPcmChannels = 2)
                .surroundAacToAc3
        )
    }

    @Test
    fun `force passthrough leaves the planner empty`() {
        assertEquals(emptySet<String>(), planned(resolve(tvArcSoundbar = true), forcePassthrough = true))
        val manual = resolve(manualMode = true, manualTranscodePreferred = true, rawMaxPcmChannels = 2)
        assertEquals(emptySet<String>(), planned(manual, forcePassthrough = true))
    }
}
