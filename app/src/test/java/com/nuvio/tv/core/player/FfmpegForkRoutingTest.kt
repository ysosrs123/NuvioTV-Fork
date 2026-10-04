package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/** Executes the renderer shipped in app/libs, not a duplicate policy implementation. */
class FfmpegForkRoutingTest {
    private fun renderer() = FfmpegAudioRenderer(null, null, mockk<AudioSink>(relaxed = true))
    private fun transcodes(renderer: FfmpegAudioRenderer, mime: String, channels: Int): Boolean {
        val method = FfmpegAudioRenderer::class.java.getDeclaredMethod("shouldTranscodeToAc3", String::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(renderer, mime, channels) as Boolean
    }
    @Test fun emptyDeniedSetLeavesLosslessFormatsOnTheirExistingPath() {
        val renderer = renderer()
        for (mime in listOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)) {
            assertFalse(transcodes(renderer, mime, 8))
        }
    }
    @Test fun deniedSetIsMimeSpecificAndCopied() {
        val renderer = renderer()
        val denied = mutableSetOf(MimeTypes.AUDIO_TRUEHD)
        renderer.setDeniedTranscodeMimes(denied)
        denied.clear()
        assertTrue(transcodes(renderer, MimeTypes.AUDIO_TRUEHD, 8))
        assertFalse(transcodes(renderer, MimeTypes.AUDIO_DTS_HD, 8))
        renderer.setDeniedTranscodeMimes(emptySet())
        assertFalse(transcodes(renderer, MimeTypes.AUDIO_TRUEHD, 8))
    }
    @Test fun opticalModePreservesNativeAc3AndStereoAac() {
        val renderer = renderer()
        renderer.setForceOpticalPassthrough(true)
        assertFalse(transcodes(renderer, MimeTypes.AUDIO_AC3, 6))
        assertFalse(transcodes(renderer, MimeTypes.AUDIO_AAC, 2))
        assertTrue(transcodes(renderer, MimeTypes.AUDIO_AAC, 6))
        assertTrue(transcodes(renderer, MimeTypes.AUDIO_TRUEHD, 2))
    }
    @Test fun unknownChannelCountCanUseDeniedCodecTranscode() {
        val renderer = renderer()
        renderer.setDeniedTranscodeMimes(setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_AC3))
        assertTrue(transcodes(renderer, MimeTypes.AUDIO_DTS, -1))
        assertFalse(transcodes(renderer, MimeTypes.AUDIO_AC3, -1))
    }
}
