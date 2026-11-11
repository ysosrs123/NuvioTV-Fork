package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedFormatMpvFallbackPolicyTest {

    private fun unplayable(
        exo: Boolean = true,
        switched: Boolean = false,
        present: Boolean = true,
        selected: Boolean = false,
        support: Int = C.FORMAT_UNSUPPORTED_SUBTYPE
    ) = UnsupportedFormatMpvFallbackPolicy.shouldSwitchForUnplayableVideoTrack(exo, switched, present, selected, support)

    private fun fatal(
        errorCode: Int,
        mime: String?,
        exo: Boolean = true,
        switched: Boolean = false,
        firstFrame: Boolean = false,
        fileName: String? = null,
        streamMime: String? = null
    ) = UnsupportedFormatMpvFallbackPolicy.shouldSwitchForFatalError(
        exo, switched, firstFrame, errorCode, mime, fileName, streamMime
    )

    @Test
    fun videoTrackWithoutAnyDecoder_goesToMpvOnce() {
        assertTrue(unplayable())
        assertTrue(unplayable(support = C.FORMAT_UNSUPPORTED_TYPE))
        assertFalse(unplayable(switched = true))
        assertFalse(unplayable(exo = false))
    }

    @Test
    fun playableOrAudioOnlyStreams_stayOnExoPlayer() {
        assertFalse(unplayable(selected = true, support = C.FORMAT_HANDLED))
        assertFalse(unplayable(support = C.FORMAT_HANDLED))
        assertFalse(unplayable(support = C.FORMAT_EXCEEDS_CAPABILITIES))
        assertFalse(unplayable(support = C.FORMAT_UNSUPPORTED_DRM))
        assertFalse(unplayable(present = false))
    }

    @Test
    fun unreadableContainer_goesToMpvBeforeTheFirstFrameOnly() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        val wmv = "Some.Show.S01E01.WMV"
        assertTrue(fatal(code, mime = null, fileName = wmv))
        assertTrue(fatal(code, mime = null, fileName = "https://host/dl/film.rmvb?token=abc"))
        assertTrue(fatal(code, mime = null, streamMime = "video/x-ms-asf"))
        assertFalse(fatal(code, mime = null, fileName = wmv, firstFrame = true))
        assertFalse(fatal(code, mime = null, fileName = wmv, switched = true))
        assertFalse(fatal(code, mime = null, fileName = wmv, exo = false))
    }

    @Test
    fun deadLinksAndOrdinaryFiles_doNotGoToMpvOnAContainerError() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        assertFalse(fatal(code, mime = null))
        assertFalse(fatal(code, mime = null, fileName = "https://host/expired-link", streamMime = "text/html"))
        assertFalse(fatal(code, mime = null, fileName = "Film.2019.2160p.mkv"))
        assertFalse(fatal(code, mime = null, fileName = "film.wmv.torrent"))
    }

    @Test
    fun decoderFailure_goesToMpvForLegacyFormatsOnly() {
        val init = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        val decoding = PlaybackException.ERROR_CODE_DECODING_FAILED
        assertTrue(fatal(init, MimeTypes.VIDEO_VC1))
        assertTrue(fatal(init, MimeTypes.VIDEO_MP4V))
        assertTrue(fatal(decoding, MimeTypes.VIDEO_MP43, firstFrame = true))
        assertTrue(fatal(init, "video/x-ms-wmv"))

        assertFalse(fatal(init, MimeTypes.VIDEO_H265))
        assertFalse(fatal(init, MimeTypes.VIDEO_H264))
        assertFalse(fatal(init, MimeTypes.VIDEO_DOLBY_VISION))
        assertFalse(fatal(decoding, MimeTypes.VIDEO_AV1, firstFrame = true))
        assertFalse(fatal(init, null))
    }

    @Test
    fun networkAndAudioErrors_neverSwitch() {
        assertFalse(fatal(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, MimeTypes.VIDEO_VC1))
        assertFalse(fatal(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, MimeTypes.VIDEO_MP4V))
        assertFalse(fatal(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, MimeTypes.VIDEO_VC1))
        assertFalse(fatal(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, MimeTypes.VIDEO_VC1))
    }

    @Test
    fun `a transport stream with sound but no picture goes to MPV`() {
        fun missing(name: String?, audio: Boolean = true, mime: String? = null, switched: Boolean = false) =
            UnsupportedFormatMpvFallbackPolicy.shouldSwitchForMissingVideoTrack(true, switched, audio, name, mime)
        assertTrue(missing("Movie.VC1.m2ts"))
        assertTrue(missing("fmt-vc1.ts?token=1"))
        assertTrue(missing(null, mime = MimeTypes.VIDEO_MP2T))
        assertFalse(missing("fmt-vc1.ts", audio = false))
        assertFalse(missing("fmt-vc1.ts", switched = true))
        assertFalse(missing("album.flac"))
        assertFalse(missing("movie.mkv"))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.shouldSwitchForMissingVideoTrack(false, false, true, "a.ts", null))
    }

    @Test
    fun `H264 10-bit, 422 and 444 profiles are recognised from the codec string`() {
        fun high(codecs: String?, mime: String? = MimeTypes.VIDEO_H264) =
            UnsupportedFormatMpvFallbackPolicy.isHighBitDepthH264(mime, codecs)
        assertTrue(high("avc1.6E0033"))
        assertTrue(high("avc1.6e0028"))
        assertTrue(high("avc1.7A0033"))
        assertTrue(high("avc1.F40033"))
        assertTrue(high("avc3.6E0029"))
        assertFalse(high("avc1.640033"))
        assertFalse(high("avc1.4D401F"))
        assertFalse(high(null))
        assertFalse(high("hvc1.2.4.L153", MimeTypes.VIDEO_H265))
        assertFalse(high("avc1.6E0033", MimeTypes.VIDEO_H265))
    }

    @Test
    fun `a 10-bit H264 track the box can't decode goes to MPV before playing, once`() {
        fun hi10p(support: Int, exo: Boolean = true, switched: Boolean = false, codecs: String = "avc1.6E0033") =
            UnsupportedFormatMpvFallbackPolicy.shouldSwitchForHighBitDepthH264(exo, switched, MimeTypes.VIDEO_H264, codecs, support)
        assertTrue(hi10p(C.FORMAT_EXCEEDS_CAPABILITIES))
        assertTrue(hi10p(C.FORMAT_UNSUPPORTED_SUBTYPE))
        assertFalse(hi10p(C.FORMAT_HANDLED))
        assertFalse(hi10p(C.FORMAT_EXCEEDS_CAPABILITIES, switched = true))
        assertFalse(hi10p(C.FORMAT_EXCEEDS_CAPABILITIES, exo = false))
        assertFalse(hi10p(C.FORMAT_EXCEEDS_CAPABILITIES, codecs = "avc1.640033"))
    }

    @Test
    fun `a decoder failure on 10-bit H264 goes to MPV, ordinary H264 does not`() {
        fun fail(codecs: String?) = UnsupportedFormatMpvFallbackPolicy.shouldSwitchForFatalError(
            true, false, false, PlaybackException.ERROR_CODE_DECODING_FAILED, MimeTypes.VIDEO_H264, "a.mkv", null, codecs
        )
        assertTrue(fail("avc1.6E0033"))
        assertFalse(fail("avc1.640033"))
        assertFalse(fail(null))
    }

    @Test
    fun av1DecoderThatNeverStarts_goesToMpv_butNotAfterThePictureShowed() {
        fun fail(firstFrame: Boolean, code: Int = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) =
            UnsupportedFormatMpvFallbackPolicy.shouldSwitchForFatalError(
                true, false, firstFrame, code, MimeTypes.VIDEO_AV1, "a.mkv", null
            )
        assertTrue(fail(firstFrame = false))
        assertTrue(fail(firstFrame = false, code = PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(fail(firstFrame = true))
        assertFalse(fail(firstFrame = false, code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }
}
