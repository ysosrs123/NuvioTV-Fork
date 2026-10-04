package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughOutputReturnTest {

    private val hdmi = setOf(
        C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
        C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD
    )
    private val unplugged = emptySet<Int>()

    @Test
    fun retryWaitsWhileHdmiIsGoneAndGoesOnceItIsBack() {
        assertFalse(PassthroughOutputReturn.outputReadyForRetry(unplugged, routeIsHdmi = false))
        assertTrue(PassthroughOutputReturn.outputReadyForRetry(hdmi, routeIsHdmi = true))
    }

    @Test
    fun refusalWithABitstreamOutputPresentIsRetriedAtOnce() {
        val onlyAc3 = setOf(C.ENCODING_AC3)
        assertTrue(PassthroughOutputReturn.outputReadyForRetry(onlyAc3, routeIsHdmi = false))
    }

    @Test
    fun hdmiRouteWithoutReportedFormatsIsRetriedAtOnce() {
        assertTrue(PassthroughOutputReturn.outputReadyForRetry(unplugged, routeIsHdmi = true))
    }

    @Test
    fun retryWaitIsBounded() {
        assertFalse(PassthroughOutputReturn.retryMayProceed(false, PassthroughOutputReturn.MAX_RETRY_WAIT_MS - 1))
        assertTrue(PassthroughOutputReturn.retryMayProceed(false, PassthroughOutputReturn.MAX_RETRY_WAIT_MS))
        assertTrue(PassthroughOutputReturn.retryMayProceed(true, 0L))
    }

    @Test
    fun playerBuiltWhileHdmiWasGoneIsRebuiltWhenItReturns() {
        assertTrue(rebuild(C.ENCODING_DOLBY_TRUEHD, pinned = unplugged, live = hdmi))
        assertTrue(rebuild(C.ENCODING_AC3, pinned = unplugged, live = hdmi))
    }

    @Test
    fun noRebuildWhileHdmiIsStillGone() {
        assertFalse(rebuild(C.ENCODING_DOLBY_TRUEHD, pinned = unplugged, live = unplugged))
    }

    @Test
    fun noRebuildWhenThePlayerAlreadyHadThePassthroughFormats() {
        assertFalse(rebuild(C.ENCODING_DOLBY_TRUEHD, pinned = hdmi, live = hdmi))
        assertFalse(rebuild(C.ENCODING_E_AC3_JOC, pinned = setOf(C.ENCODING_E_AC3), live = hdmi))
        assertFalse(rebuild(C.ENCODING_DTS_HD, pinned = setOf(C.ENCODING_DTS), live = hdmi))
    }

    @Test
    fun noRebuildWhenSomethingElseKeepsTheTrackOnPcm() {
        val trueHd = C.ENCODING_DOLBY_TRUEHD
        assertFalse(rebuild(trueHd, unplugged, hdmi, alreadyBitstream = true))
        assertFalse(rebuild(trueHd, unplugged, hdmi, claimedByIec = true))
        assertFalse(rebuild(trueHd, unplugged, hdmi, policyDenies = true))
        assertFalse(rebuild(trueHd, unplugged, hdmi, forcedPcm = true))
        assertFalse(rebuild(trueHd, pinned = null, live = hdmi))
        assertFalse(rebuild(null, unplugged, hdmi))
    }

    @Test
    fun rebuildsPerStreamAreBounded() {
        val max = PassthroughOutputReturn.MAX_REBUILDS_PER_STREAM
        assertTrue(rebuild(C.ENCODING_AC3, unplugged, hdmi, rebuildsSoFar = max - 1))
        assertFalse(rebuild(C.ENCODING_AC3, unplugged, hdmi, rebuildsSoFar = max))
    }

    @Test
    fun encodingComesFromTheTrackNotFromDecodedPcm() {
        val trueHd = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build()
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build()
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        assertEquals(C.ENCODING_DOLBY_TRUEHD, PassthroughOutputReturn.encodingOf(trueHd))
        assertNull(PassthroughOutputReturn.encodingOf(pcm))
        assertNull(PassthroughOutputReturn.encodingOf(aac))
        assertNull(PassthroughOutputReturn.encodingOf(null))
    }

    @Test
    fun hdmiRoutesAreRecognised() {
        assertTrue(PassthroughOutputReturn.isHdmiRoute("type:hdmi|name:am9_pro"))
        assertTrue(PassthroughOutputReturn.isHdmiRoute("type:hdmi_earc|name:avr"))
        assertFalse(PassthroughOutputReturn.isHdmiRoute("type:built_in_speaker|name:am9_pro"))
        assertFalse(PassthroughOutputReturn.isHdmiRoute(null))
    }

    private fun rebuild(
        encoding: Int?,
        pinned: Set<Int>?,
        live: Set<Int>,
        alreadyBitstream: Boolean = false,
        claimedByIec: Boolean = false,
        policyDenies: Boolean = false,
        forcedPcm: Boolean = false,
        rebuildsSoFar: Int = 0
    ) = PassthroughOutputReturn.shouldRebuild(
        encoding = encoding,
        pinned = pinned,
        live = live,
        alreadyBitstream = alreadyBitstream,
        claimedByIec = claimedByIec,
        policyDenies = policyDenies,
        forcedPcm = forcedPcm,
        rebuildsSoFar = rebuildsSoFar
    )
}
