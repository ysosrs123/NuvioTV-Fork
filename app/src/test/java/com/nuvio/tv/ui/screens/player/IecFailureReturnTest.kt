package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IecFailureReturnTest {

    @Test
    fun `only the first failure of a DTS-HD family track gets the one rebuild`() {
        assertTrue(IecFailureReturn.retriesAfter(1, MimeTypes.AUDIO_DTS_HD))
        assertTrue(IecFailureReturn.retriesAfter(1, MimeTypes.AUDIO_DTS_X))
        assertFalse(IecFailureReturn.retriesAfter(2, MimeTypes.AUDIO_DTS_HD))
        assertFalse(IecFailureReturn.retriesAfter(1, MimeTypes.AUDIO_TRUEHD))
        assertFalse(IecFailureReturn.retriesAfter(1, null))
    }

    @Test
    fun `the rebuild waits for a settled output and a plain playback state`() {
        assertTrue(rebuild())
        assertFalse(rebuild(outputReady = false))
        assertFalse(rebuild(pending = true))
        assertFalse(rebuild(background = true))
        assertFalse(rebuild(forcedPcm = true))
        assertFalse(rebuild(denied = true))
        assertFalse(rebuild(normalSpeed = false))
    }

    @Test
    fun `an old failure no longer counts against the stream`() {
        val window = IecFailureReturn.FORGET_FAILURE_AFTER_MS
        assertFalse(IecFailureReturn.forgetsEarlierFailure(null, window))
        assertFalse(IecFailureReturn.forgetsEarlierFailure(1_000L, 1_000L + window - 1))
        assertTrue(IecFailureReturn.forgetsEarlierFailure(1_000L, 1_000L + window))
    }

    @Test
    fun `decoding after a failure goes by format, not device`() {
        assertTrue(IecFailureReturn.decodesAfterFailure(MimeTypes.AUDIO_DTS_HD))
        assertTrue(IecFailureReturn.decodesAfterFailure(MimeTypes.AUDIO_DTS_X))
        assertFalse(IecFailureReturn.decodesAfterFailure(MimeTypes.AUDIO_DTS))
        assertFalse(IecFailureReturn.decodesAfterFailure(MimeTypes.AUDIO_TRUEHD))
        assertFalse(IecFailureReturn.decodesAfterFailure(null))
    }

    private fun rebuild(
        outputReady: Boolean = true,
        pending: Boolean = false,
        background: Boolean = false,
        forcedPcm: Boolean = false,
        denied: Boolean = false,
        normalSpeed: Boolean = true
    ) = IecFailureReturn.mayRebuild(outputReady, pending, background, forcedPcm, denied, normalSpeed)
}
