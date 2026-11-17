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
        streamMime: String? = null,
        local: Boolean = false
    ) = UnsupportedFormatMpvFallbackPolicy.shouldSwitchForFatalError(
        exo, switched, firstFrame, errorCode, mime, fileName, streamMime, isLocalFile = local
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
    fun refusedLocalBlurayFile_goesToMpvByName() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        assertTrue(fatal(code, mime = null, fileName = "/storage/usb/BDMV/STREAM/00001.m2ts", local = true))
        assertTrue(fatal(code, mime = null, fileName = "content://media/camera/00012.MTS", local = true))
        assertFalse(fatal(code, mime = null, fileName = "/storage/usb/BDMV/STREAM/00001.m2ts", local = true, firstFrame = true))
    }

    @Test
    fun refusedLinkWithABlurayName_isNotTrustedByNameAlone() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        assertFalse(fatal(code, mime = null, fileName = "https://host/d/Film.2009.Remux.M2TS?token=abc"))
        assertFalse(fatal(code, mime = null, fileName = "camera/00012.MTS", streamMime = MimeTypes.VIDEO_MP2T))
    }

    @Test
    fun refusedPlainTransportStream_staysOnTheDeadLinkPath() {
        val code = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        assertFalse(fatal(code, mime = null, fileName = "https://host/live/channel.ts", streamMime = MimeTypes.VIDEO_MP2T))
        assertFalse(fatal(code, mime = null, streamMime = MimeTypes.VIDEO_MP2T))
    }

    @Test
    fun blurayCheck_runsForBlurayNamesAndUnnamedLinks() {
        val check = UnsupportedFormatMpvFallbackPolicy::shouldCheckForBlurayTransportStream
        assertTrue(check("https://host/d/Film.2009.Remux.m2ts?token=abc", MimeTypes.VIDEO_MP2T))
        assertTrue(check("Film 2009 BluRay Remux", null))
        assertTrue(check("https://debrid.example/d/AB12CD34", null))
        assertTrue(check("https://debrid.example/d/AB12CD34", "application/octet-stream"))
        assertTrue(check(null, null))
    }

    @Test
    fun blurayCheck_skipsLinksThatAlreadySayWhatTheyAre() {
        val check = UnsupportedFormatMpvFallbackPolicy::shouldCheckForBlurayTransportStream
        assertFalse(check("https://host/Film.2019.2160p.mkv", null))
        assertFalse(check("https://host/live/channel.ts", MimeTypes.VIDEO_MP2T))
        assertFalse(check("https://host/Film.part1.rar", null))
        assertFalse(check("https://host/expired", "text/html"))
        assertFalse(check("https://debrid.example/d/AB12CD34", MimeTypes.VIDEO_MATROSKA))
        assertFalse(check("https://host/d/Film.m2ts", "text/html; charset=utf-8"))
    }

    @Test
    fun bytesWithATimestampBeforeEveryPacket_areABlurayTransportStream() {
        val bdav = ByteArray(1024).also { bytes -> for (i in 4 until bytes.size step 192) bytes[i] = 0x47 }
        assertTrue(UnsupportedFormatMpvFallbackPolicy.isBlurayTransportStream(bdav))
    }

    @Test
    fun plainTransportStreamsWebPagesAndShortBodies_areNot() {
        val plain = ByteArray(1024).also { bytes -> for (i in 0 until bytes.size step 188) bytes[i] = 0x47 }
        val html = "<!DOCTYPE html><html><head><title>Not found</title></head>".padEnd(1024, ' ').toByteArray()
        val short = ByteArray(400).also { bytes -> for (i in 4 until bytes.size step 192) bytes[i] = 0x47 }
        val oneBadPacket = ByteArray(1024).also { bytes ->
            for (i in 4 until bytes.size step 192) bytes[i] = 0x47
            bytes[4 + 3 * 192] = 0
        }
        assertFalse(UnsupportedFormatMpvFallbackPolicy.isBlurayTransportStream(plain))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.isBlurayTransportStream(html))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.isBlurayTransportStream(short))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.isBlurayTransportStream(oneBadPacket))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.isBlurayTransportStream(ByteArray(0)))
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

    @Test
    fun `Blu-ray names are found in links with tokens and in plain file names`() {
        assertTrue(UnsupportedFormatMpvFallbackPolicy.hasBlurayTransportStreamName("http://127.0.0.1:8765/clip/a4-dtshd-ma.m2ts"))
        assertTrue(UnsupportedFormatMpvFallbackPolicy.hasBlurayTransportStreamName("https://host/f/00001.M2TS?token=abc#t=10"))
        assertTrue(UnsupportedFormatMpvFallbackPolicy.hasBlurayTransportStreamName("Holiday 2009.mts"))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.hasBlurayTransportStreamName("https://host/film.ts?name=x.m2ts"))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.hasBlurayTransportStreamName("film.mkv"))
        assertFalse(UnsupportedFormatMpvFallbackPolicy.hasBlurayTransportStreamName(null))
    }
}
