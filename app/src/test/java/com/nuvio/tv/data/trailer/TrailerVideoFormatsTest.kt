package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerVideoFormatsTest {

    private val extractor = InAppYouTubeExtractor()

    @Test
    fun `codec is read from the mime type`() {
        assertEquals(TrailerVideoCodec.H264, trailerVideoCodecOf("video/mp4; codecs=\"avc1.640028\""))
        assertEquals(TrailerVideoCodec.VP9, trailerVideoCodecOf("video/webm; codecs=\"vp9\""))
        assertEquals(TrailerVideoCodec.VP9, trailerVideoCodecOf("video/webm; codecs=\"vp09.00.40.08\""))
        assertEquals(TrailerVideoCodec.AV1, trailerVideoCodecOf("video/mp4; codecs=\"av01.0.08M.08\""))
        assertEquals(TrailerVideoCodec.OTHER, trailerVideoCodecOf("video/mp4"))
    }

    @Test
    fun `HDR is read from the colour transfer, the quality label or the VP9 profile`() {
        assertTrue(
            isHdrTrailerFormat("video/webm; codecs=\"vp9\"", "1080p", "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084")
        )
        assertTrue(isHdrTrailerFormat("video/mp4; codecs=\"av01.0.09M.10\"", "1080p60 HDR", null))
        assertTrue(isHdrTrailerFormat("video/webm; codecs=\"vp09.02.40.10.01\"", null, null))
        assertFalse(
            isHdrTrailerFormat("video/mp4; codecs=\"avc1.640028\"", "1080p", "COLOR_TRANSFER_CHARACTERISTICS_BT709")
        )
    }

    @Test
    fun `video above 1080p is left out`() {
        val allowed = trailerVideoCandidates(
            listOf(
                video("2160p", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9),
                video("1440p-scope", height = 1072, width = 2560, codec = TrailerVideoCodec.VP9),
                video("1080p", height = 1080, width = 1920, codec = TrailerVideoCodec.VP9),
                video("720p", height = 720, width = 1280, codec = TrailerVideoCodec.H264)
            ),
            TrailerVideoLimits()
        )

        assertEquals(listOf("1080p", "720p"), allowed.map { it.url })
    }

    @Test
    fun `AV1 needs a hardware decoder`() {
        val candidates = listOf(
            video("av1", height = 1080, codec = TrailerVideoCodec.AV1),
            video("vp9", height = 1080, codec = TrailerVideoCodec.VP9)
        )

        assertEquals(listOf("vp9"), trailerVideoCandidates(candidates, TrailerVideoLimits()).map { it.url })
        assertEquals(
            listOf("av1", "vp9"),
            trailerVideoCandidates(candidates, TrailerVideoLimits(av1HardwareDecoder = true)).map { it.url }
        )
    }

    @Test
    fun `HDR needs an HDR display`() {
        val candidates = listOf(
            video("hdr", height = 1080, codec = TrailerVideoCodec.VP9, isHdr = true),
            video("sdr", height = 1080, codec = TrailerVideoCodec.VP9)
        )

        assertEquals(listOf("sdr"), trailerVideoCandidates(candidates, TrailerVideoLimits()).map { it.url })
        assertEquals(
            listOf("hdr", "sdr"),
            trailerVideoCandidates(candidates, TrailerVideoLimits(hdrDisplay = true)).map { it.url }
        )
    }

    @Test
    fun `when the limits leave nothing every stream stays available`() {
        val candidates = listOf(video("2160p-av1", height = 2160, width = 3840, codec = TrailerVideoCodec.AV1))

        assertEquals(candidates, trailerVideoCandidates(candidates, TrailerVideoLimits()))
    }

    @Test
    fun `the limits are given up one at a time, codec first, then HDR, then size`() {
        val av1 = video("1080p-av1", height = 1080, width = 1920, codec = TrailerVideoCodec.AV1)
        val av1Hdr = video("1080p-av1-hdr", height = 1080, width = 1920, codec = TrailerVideoCodec.AV1, isHdr = true)
        val big = video("2160p-vp9", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9)
        val bigHdr = video("2160p-vp9-hdr", height = 2160, width = 3840, codec = TrailerVideoCodec.VP9, isHdr = true)
        val bigAv1 = video("2160p-av1", height = 2160, width = 3840, codec = TrailerVideoCodec.AV1)
        val bigAv1Hdr = video("2160p-av1-hdr", height = 2160, width = 3840, codec = TrailerVideoCodec.AV1, isHdr = true)
        val limits = TrailerVideoLimits()

        assertEquals(listOf(av1), trailerVideoCandidates(listOf(bigAv1Hdr, bigHdr, big, av1Hdr, av1), limits))
        assertEquals(listOf(av1Hdr), trailerVideoCandidates(listOf(bigAv1Hdr, bigHdr, big, av1Hdr), limits))
        assertEquals(listOf(big), trailerVideoCandidates(listOf(bigAv1Hdr, bigHdr, bigAv1, big), limits))
        assertEquals(listOf(bigAv1), trailerVideoCandidates(listOf(bigAv1Hdr, bigHdr, bigAv1), limits))
        assertEquals(listOf(bigAv1Hdr, bigHdr), trailerVideoCandidates(listOf(bigAv1Hdr, bigHdr), limits))
    }

    @Test
    fun `at the same height H264 comes first, then VP9, then AV1`() {
        val ranked = extractor.sortCandidates(
            listOf(
                video("av1-1080", height = 1080, codec = TrailerVideoCodec.AV1, bitrate = 3_000_000.0),
                video("vp9-1080", height = 1080, codec = TrailerVideoCodec.VP9, bitrate = 2_500_000.0),
                video("h264-1080", height = 1080, codec = TrailerVideoCodec.H264, bitrate = 2_000_000.0),
                video("h264-720", height = 720, codec = TrailerVideoCodec.H264, bitrate = 1_500_000.0)
            )
        )

        assertEquals(listOf("h264-1080", "vp9-1080", "av1-1080", "h264-720"), ranked.map { it.url })
    }

    @Test
    fun `a taller stream still beats the preferred codec`() {
        val ranked = extractor.sortCandidates(
            listOf(
                video("h264-720", height = 720, codec = TrailerVideoCodec.H264),
                video("vp9-1080", height = 1080, codec = TrailerVideoCodec.VP9)
            )
        )

        assertEquals("vp9-1080", ranked.first().url)
    }

    private fun video(
        url: String,
        height: Int,
        codec: TrailerVideoCodec,
        width: Int = 0,
        isHdr: Boolean = false,
        bitrate: Double = 1_000_000.0
    ) = StreamCandidate(
        client = "visionos",
        priority = 0,
        url = url,
        score = height * 1_000_000_000.0 + 30 * 1_000_000.0 + bitrate,
        hasN = false,
        itag = "",
        height = height,
        fps = 30,
        ext = "mp4",
        width = width,
        codec = codec,
        isHdr = isHdr
    )
}
