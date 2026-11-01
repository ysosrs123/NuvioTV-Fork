package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hardware thumbnails: video memory, remembered decoder counts, older Android, the stall rule and the other formats. */
class HardwareThumbRulesTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private class Memory : HardwareFailureMemory {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private fun HardwareLanes.feed(startMs: Long, gapMs: Long, n: Int = HardwareLanes.WINDOW + 1): Pair<HardwareLanes.Change?, Long> {
        var t = startMs
        var last: HardwareLanes.Change? = null
        repeat(n) {
            onPicture(t)?.let { c -> last = c }
            t += gapMs
        }
        return last to t
    }

    @Test fun thePoolIsReadFromMeminfo() {
        val text = "MemTotal:        3884196 kB\nCmaTotal:         876544 kB\nCmaFree:          670720 kB\nHugePages_Total: 0\n"
        val pool = VideoPool.parse(text)!!
        assertEquals(655, pool.freeMb)
        assertEquals(856, pool.totalMb)
        assertNull(VideoPool.parse("MemTotal:        3884196 kB\nMemFree:  1 kB\n"))
        assertNull(VideoPool.parse("CmaTotal: 0 kB\nCmaFree: 0 kB\n"))
        assertNull(VideoPool.parse("CmaTotal:          16384 kB\nCmaFree:               0 kB\n"))
        assertNull(VideoPool.parse(""))
    }

    @Test fun poolMarginsFor4KAndSmaller() {
        assertTrue(VideoPool.mayAdd(200, fourK = true))
        assertFalse(VideoPool.mayAdd(199, fourK = true))
        assertTrue(VideoPool.mayAdd(80, fourK = false))
        assertFalse(VideoPool.mayAdd(79, fourK = false))
        assertTrue(VideoPool.mayAdd(null, fourK = true))
        assertTrue(VideoPool.mustDrop(47))
        assertFalse(VideoPool.mustDrop(48))
        assertFalse(VideoPool.mustDrop(null))
    }

    @Test fun noDecoderIsAddedWithoutThePoolMargin() {
        val l = HardwareLanes(maxInstances = 9, fourK = true) { 150 }
        val (c, _) = l.feed(0L, 100L)
        assertEquals(1, c!!.from)
        assertEquals(1, c.to)
        assertTrue(c.reason.contains("150 MB free"))
        assertTrue(l.settled)
        assertEquals(1, l.lanes)
        val hd = HardwareLanes(maxInstances = 9, fourK = false) { 150 }
        assertEquals(2, hd.feed(0L, 100L).first!!.to)
    }

    @Test fun aDecoderIsTakenAwayWhenThePoolRunsLow() {
        var free: Int? = 600
        val l = HardwareLanes(maxInstances = 9, fourK = true) { free }
        val (up, t) = l.feed(0L, 100L)
        assertEquals(2, up!!.to)
        free = 40
        val down = l.onPicture(t)!!
        assertEquals(2, down.from)
        assertEquals(1, down.to)
        assertTrue(down.reason.contains("40 MB"))
        assertTrue(l.settled)
        assertNull(l.onPicture(t + 100L))
    }

    @Test fun anUnreadablePoolLeavesSpeedAndMissesInChargeUpTo1080p() {
        val l = HardwareLanes(maxInstances = 9, fourK = false) { null }
        assertEquals(2, l.feed(0L, 100L).first!!.to)
    }

    @Test fun above1080pAnUnreadablePoolKeepsOneDecoder() {
        val l = HardwareLanes(maxInstances = 9, start = 3, fourK = true) { null }
        assertEquals(1, l.cap)
        assertEquals(1, l.lanes)
        assertEquals("video memory pool not readable", l.oneOnly)
        l.feed(0L, 100L, n = 3 * HardwareLanes.WINDOW)
        assertEquals(1, l.lanes)
    }

    @Test fun above1080pASmallBoxKeepsOneDecoder() {
        val xiaomi = HardwareLanes(maxInstances = 9, start = 2, fourK = true, totalRamMb = 1_897) { 450 }
        assertEquals(1, xiaomi.cap)
        assertEquals(1, xiaomi.lanes)
        assertEquals("RAM 1897 MB", xiaomi.oneOnly)
        xiaomi.feed(0L, 100L, n = 3 * HardwareLanes.WINDOW)
        assertEquals(1, xiaomi.lanes)
        assertEquals("RAM not known, video memory pool not readable",
            HardwareLanes(maxInstances = 9, fourK = true, totalRamMb = 0) { null }.oneOnly)
        assertNull(HardwareLanes(maxInstances = 9, fourK = true, totalRamMb = 3_900) { 600 }.oneOnly)
        val hd = HardwareLanes(maxInstances = 9, fourK = false, totalRamMb = 1_897) { 450 }
        assertNull(hd.oneOnly)
        assertEquals(2, hd.feed(0L, 100L).first!!.to)
    }

    @Test fun above1080pASecondDecoderMustLeave200MbAfterItsCost() {
        val tight = HardwareLanes(maxInstances = 9, fourK = true, totalRamMb = 3_900) { 364 }
        val (c, _) = tight.feed(0L, 100L)
        assertEquals(1, c!!.to)
        assertTrue(c.reason.contains("165 MB"))
        val room = HardwareLanes(maxInstances = 9, fourK = true, totalRamMb = 3_900) { 365 }
        assertEquals(2, room.feed(0L, 100L).first!!.to)
        assertTrue(VideoPool.mayAdd4K(365, 165))
        assertFalse(VideoPool.mayAdd4K(364, 165))
        assertFalse(VideoPool.mayAdd4K(null, 0))
    }

    @Test fun above1080pTheCostSeenCountsWhenItIsHigher() {
        var free: Int? = 800
        val l = HardwareLanes(maxInstances = 9, fourK = true, totalRamMb = 3_900) { free }
        // The first decoder took 350 MB: 450 - 350 < 200, so no second one.
        free = 450
        val (c, _) = l.feed(0L, 100L)
        assertEquals(1, c!!.to)
        assertEquals(350, l.costMb(450))
        assertEquals(165, l.costMb(750))
    }

    @Test fun above1080pARememberedStartNeedsThePoolForIt() {
        assertEquals(2, HardwareLanes(maxInstances = 9, start = 3, fourK = true, totalRamMb = 3_900) { 600 }.lanes)
        assertEquals(3, HardwareLanes(maxInstances = 9, start = 3, fourK = true, totalRamMb = 3_900) { 700 }.lanes)
        assertEquals(1, HardwareLanes(maxInstances = 9, start = 2, fourK = true, totalRamMb = 3_900) { 529 }.lanes)
        assertEquals(3, HardwareLanes(maxInstances = 9, start = 3, fourK = false, totalRamMb = 1_897) { 100 }.lanes)
    }

    @Test fun above1080pALowPoolDropsStraightToOne() {
        var free: Int? = 900
        val l = HardwareLanes(maxInstances = 9, start = 3, fourK = true, totalRamMb = 3_900) { free }
        assertEquals(3, l.lanes)
        free = 149
        val down = l.onPicture(0L)!!
        assertEquals(3, down.from)
        assertEquals(1, down.to)
        assertTrue(down.reason.contains("149 MB"))
        assertTrue(l.settled)

        free = 900
        val two = HardwareLanes(maxInstances = 9, start = 2, fourK = true, totalRamMb = 3_900) { free }
        free = 150
        assertNull(two.onPicture(0L))
        assertEquals(2, two.lanes)
        free = null
        assertEquals(1, two.onPicture(100L)!!.to)
    }

    @Test fun aRememberedCountIsTheStartAndStillAdapts() {
        val l = HardwareLanes(maxInstances = 9, start = 2)
        assertEquals(2, l.lanes)
        // Nothing measured below the start: one more is tried.
        val (up, t) = l.feed(0L, 100L)
        assertEquals(3, up!!.to)
        // No faster at 3: back to the remembered 2.
        val (down, _) = l.feed(t, 100L)
        assertEquals(2, down!!.to)
        assertEquals(1, HardwareLanes(maxInstances = 2, start = 3).lanes)
        assertEquals(1, HardwareLanes(maxInstances = 9, start = 0).lanes)
    }

    @Test fun theLaneBookRemembersPerBoxFormatAndSize() {
        val m = Memory()
        val book = HardwareLaneBook(m)
        val k4 = HardwareLaneBook.key("box1", VideoCodec.HEVC, fourK = true)
        assertNotEquals(k4, HardwareLaneBook.key("box1", VideoCodec.HEVC, fourK = false))
        assertNotEquals(k4, HardwareLaneBook.key("box1", VideoCodec.H264, fourK = true))
        assertNotEquals(k4, HardwareLaneBook.key("box2", VideoCodec.HEVC, fourK = true))
        assertEquals(1, book.start(k4))
        val few = HardwareLanes(9)
        few.feed(0L, 100L, n = HardwareLanes.WINDOW - 1)
        assertFalse(book.store(k4, few))
        val l = HardwareLanes(9)
        l.feed(0L, 100L)
        assertEquals(2, l.lanes)
        assertTrue(book.store(k4, l))
        assertFalse(book.store(k4, l))
        assertEquals(2, book.start(k4))
        m.map[k4] = "7"
        assertEquals(1, book.start(k4))
        m.map[k4] = "x"
        assertEquals(1, book.start(k4))
    }

    @Test fun beforeAndroid10TheNameDecides() {
        val never = { error("not asked before Android 10") }
        assertTrue(HardwareCodecPick.hardware(28, "OMX.amlogic.avc.decoder.awesome2", never, never))
        assertTrue(HardwareCodecPick.hardware(24, "OMX.MTK.VIDEO.DECODER.HEVC", never, never))
        assertTrue(HardwareCodecPick.hardware(28, "c2.amlogic.hevc.decoder", never, never))
        assertFalse(HardwareCodecPick.hardware(28, "OMX.google.h264.decoder", never, never))
        assertFalse(HardwareCodecPick.hardware(28, "c2.android.avc.decoder", never, never))
        assertFalse(HardwareCodecPick.hardware(28, "OMX.ffmpeg.hevc.decoder", never, never))
        assertFalse(HardwareCodecPick.hardware(26, "libdav1d", never, never))
    }

    @Test fun fromAndroid10TheSystemDecides() {
        assertTrue(HardwareCodecPick.hardware(29, "OMX.google.h264.decoder", { true }, { false }))
        assertFalse(HardwareCodecPick.hardware(29, "c2.amlogic.avc.decoder", { false }, { false }))
        assertFalse(HardwareCodecPick.hardware(34, "c2.amlogic.avc.decoder", { true }, { true }))
    }

    @Test fun tenBitProfilesPerFormat() {
        assertTrue(HardwareCodecPick.tenBitProfile("video/hevc", 0x2))
        assertFalse(HardwareCodecPick.tenBitProfile("video/hevc", 0x1))
        assertTrue(HardwareCodecPick.tenBitProfile("video/avc", 0x10))
        assertTrue(HardwareCodecPick.tenBitProfile("video/x-vnd.on2.vp9", 0x4))
        assertTrue(HardwareCodecPick.tenBitProfile("video/x-vnd.on2.vp9", 0x1000))
        assertFalse(HardwareCodecPick.tenBitProfile("video/x-vnd.on2.vp9", 0x1))
        assertTrue(HardwareCodecPick.tenBitProfile("video/av01", 0x2))
        assertTrue(HardwareCodecPick.tenBitProfile("video/av01", 0x1000))
        assertFalse(HardwareCodecPick.tenBitProfile("video/av01", 0x1))
        assertFalse(HardwareCodecPick.tenBitProfile("video/mpeg2", 0x2))
    }

    @Test fun aHungDecodeOrNothingMadeEndsTheHardwarePath() {
        assertFalse(HardwareStall.stuck(10_000L, 0L))
        assertFalse(HardwareStall.stuck(10_000L, 4_001L))
        assertTrue(HardwareStall.stuck(10_000L, 4_000L))
        assertFalse(HardwareStall.nothingMade(10_000L, 0L, 0))
        assertFalse(HardwareStall.nothingMade(10_000L, 5_000L, 0))
        assertTrue(HardwareStall.nothingMade(11_000L, 5_000L, 0))
        assertFalse(HardwareStall.nothingMade(60_000L, 5_000L, 1))
        assertTrue(HardwareStall.STUCK_MS < PrepareHandover.STALL_MS)
        assertTrue(HardwareStall.STUCK_MS > HardwareThumbDecoder.TIMEOUT_MS)
    }

    // MPEG-2: sequence header B3, extension B5, GOP B8, picture 00
    private val seq = b(0, 0, 1, 0xB3, 0x78, 0x04, 0x38, 0x13)
    private val ext = b(0, 0, 1, 0xB5, 0x14, 0x8A)
    private val gop = b(0, 0, 1, 0xB8, 0x00, 0x08)
    private val pic = b(0, 0, 1, 0x00, 0x00, 0x0F, 0xFF)

    @Test fun mpeg2SetUpDataIsWhatComesBeforeThePicture() {
        assertArrayEquals(seq + ext + gop, HardwareNal.mpeg2Headers(seq + ext + gop + pic))
        assertNull(HardwareNal.mpeg2Headers(gop + pic))
        assertArrayEquals(seq + ext, HardwareNal.mpeg2Headers(seq + ext))
        assertNull(HardwareNal.mpeg2Headers(ByteArray(0)))
        // The track's own header first, else the keyframe's.
        assertArrayEquals(seq + ext, HardwareSetup.csd(VideoCodec.MPEG2, ByteArray(0), seq + ext, seq + gop + pic))
        assertArrayEquals(seq + gop, HardwareSetup.csd(VideoCodec.MPEG2, ByteArray(0), ByteArray(0), seq + gop + pic))
        assertNull(HardwareSetup.csd(VideoCodec.MPEG2, ByteArray(0), ByteArray(0), pic))
    }

    @Test fun vp9AndAv1GetNoSetUpData() {
        assertNull(HardwareSetup.csd(VideoCodec.VP9, ByteArray(0), b(1, 1, 0), b(0x82, 0x49, 0x83)))
        assertNull(HardwareSetup.csd(VideoCodec.AV1, ByteArray(0), av1c, frame))
        val ps = b(0, 0, 0, 1, 0x67, 0x64, 0, 0, 0, 1, 0x68, 0xEE)
        assertArrayEquals(ps, HardwareSetup.csd(VideoCodec.H264, ps, ByteArray(0), ByteArray(0)))
        assertNull(HardwareSetup.csd(VideoCodec.H264, ByteArray(0), ByteArray(0), ByteArray(0)))
    }

    // AV1 OBUs with sizes: sequence header (type 1), temporal delimiter (2), frame (6)
    private val seqObu = b(0x0A, 3, 1, 2, 3)
    private val td = b(0x12, 0)
    private val frame = b(0x32, 2, 9, 9)
    private val av1c = b(0x81, 0x00, 0x0C, 0x00) + seqObu

    @Test fun av1KeyframesGetTheSequenceHeaderWhenTheyLackIt() {
        assertArrayEquals(seqObu + frame, HardwareNal.av1WithSequenceHeader(frame, av1c))
        assertArrayEquals(td + seqObu + frame, HardwareNal.av1WithSequenceHeader(td + frame, av1c))
        val has = seqObu + frame
        assertSame(has, HardwareNal.av1WithSequenceHeader(has, av1c))
        assertSame(frame, HardwareNal.av1WithSequenceHeader(frame, b(0x81, 0, 0x0C, 0)))
        val notObus = b(0x80, 1, 2)
        assertSame(notObus, HardwareNal.av1WithSequenceHeader(notObus, av1c))
        assertArrayEquals(seqObu + frame, HardwareSetup.keyframe(VideoCodec.AV1, frame, av1c))
        assertSame(frame, HardwareSetup.keyframe(VideoCodec.VP9, frame, av1c))
    }

    @Test fun av1ObuTypesStopAtTheFrame() {
        assertEquals(listOf(2, 1, 6), HardwareNal.av1ObuTypes(td + seqObu + frame + seqObu))
        assertNull(HardwareNal.av1ObuTypes(b(0x0A, 9, 1)))
        // An OBU without a size field cannot be walked.
        assertNull(HardwareNal.av1ObuTypes(b(0x08, 1, 2, 3)))
    }

    @Test fun vp9BitDepthFromTheTrack() {
        assertEquals(10, LegacyCodecs.vp9BitDepth(b(1, 1, 2, 2, 1, 31, 3, 1, 10, 4, 1, 1)))
        assertEquals(8, LegacyCodecs.vp9BitDepth(b(1, 1, 0, 3, 1, 8)))
        assertEquals(10, LegacyCodecs.vp9BitDepth(b(1, 1, 2)))
        assertEquals(8, LegacyCodecs.vp9BitDepth(ByteArray(0)))
        assertEquals(8, LegacyCodecs.vp9BitDepth(b(3, 5, 10)))
    }

    @Test fun vc1AndMpeg1StayOnSoftware() {
        for (c in listOf(VideoCodec.VC1, VideoCodec.WMV3, VideoCodec.MPEG1)) {
            assertFalse(c.name, c in HardwareRoute.CODECS)
        }
        for (c in listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG2, VideoCodec.MPEG4, VideoCodec.VP9, VideoCodec.AV1)) {
            assertTrue(c.name, c in HardwareRoute.CODECS)
        }
        assertTrue(HardwareRoute.endsEachKeyframe(VideoCodec.MPEG4, "c2.amlogic.mpeg4.decoder"))
        assertFalse(HardwareRoute.endsEachKeyframe(VideoCodec.HEVC, "c2.amlogic.hevc.decoder"))
        assertTrue(HardwareRoute.endsEachKeyframe(VideoCodec.H264, "OMX.amlogic.avc.decoder.awesome2"))
        assertTrue(HardwareRoute.endsEachKeyframe(VideoCodec.H264, "c2.amlogic.avc.decoder"))
        assertFalse(HardwareRoute.endsEachKeyframe(VideoCodec.HEVC, "OMX.amlogic.hevc.decoder.awesome2"))
        assertFalse(HardwareRoute.endsEachKeyframe(VideoCodec.VP9, "c2.amlogic.vp9.decoder"))
        assertTrue(HardwareRoute.endsEachKeyframe(VideoCodec.HEVC, "OMX.MTK.VIDEO.DECODER.HEVC"))
        assertTrue(HardwareRoute.endsEachKeyframe(VideoCodec.H264, "c2.mtk.avc.decoder"))
    }

    @Test fun thePlayersFormatSaysWhenTheHardwarePathIsLikely() {
        assertTrue(HardwareRoute.likelyFor(34, "video/hevc", "hvc1.2.4.L153.B0"))
        assertTrue(HardwareRoute.likelyFor(34, "video/dolby-vision", "dvhe.08.06"))
        assertTrue(HardwareRoute.likelyFor(30, "video/avc", null))
        assertFalse(HardwareRoute.likelyFor(34, "video/dolby-vision", "dvhe.05.06"))
        assertFalse(HardwareRoute.likelyFor(34, "video/wvc1", null))
        assertFalse(HardwareRoute.likelyFor(23, "video/hevc", null))
        assertFalse(HardwareRoute.likelyFor(34, null, null))
    }

    // MPEG-4 Part 2: visual object sequence B0, VO 00..1F (here 01 00), VOL 20, user data B2, VOP B6
    private val vos = b(0, 0, 1, 0xB0, 0xF5)
    private val vol = b(0, 0, 1, 0x20, 0x08, 0xC8)
    private val user = b(0, 0, 1, 0xB2) + "XviD0050".toByteArray()
    private val vop = b(0, 0, 1, 0xB6, 0x10, 0x60)

    @Test fun xvidUserDataIsRemoved() {
        assertArrayEquals(vos + vol + vop, HardwareNal.withoutMpeg4UserData(vos + user + vol + vop))
        assertArrayEquals(b(9) + vol + vop, HardwareNal.withoutMpeg4UserData(b(9) + vol + user + vop))
        val clean = vol + vop
        assertSame(clean, HardwareNal.withoutMpeg4UserData(clean))
        assertArrayEquals(vos + vop, HardwareSetup.keyframe(VideoCodec.MPEG4, vos + user + vop, ByteArray(0)))
    }

    @Test fun xvidSetUpDataIsTheHeadersBeforeThePicture() {
        assertArrayEquals(vos + vol, HardwareNal.mpeg4Headers(vos + vol + user + vop))
        assertNull(HardwareNal.mpeg4Headers(vos + vop))
        assertArrayEquals(vol, HardwareSetup.csd(VideoCodec.MPEG4, ByteArray(0), vol + user, vos + vol + vop))
        assertArrayEquals(vos + vol, HardwareSetup.csd(VideoCodec.MPEG4, ByteArray(0), ByteArray(0), vos + vol + user + vop))
        assertNull(HardwareSetup.csd(VideoCodec.MPEG4, ByteArray(0), ByteArray(0), vop))
    }

    @Test fun aVp9IndexIsUsedWithTheSwitchOnByDefault() {
        assertTrue(HardwareThumbSwitch.on)
        fun index(codec: VideoCodec) = MediaIndex(
            ContainerKind.MATROSKA, 1_000L, 60_000_000L,
            VideoTrack(codec, 1920, 1080, 0.0, 4, ByteArray(0), 8, ContainerColour(), null, 1L),
            KeyframeIndex(longArrayOf(0L), longArrayOf(100L), longArrayOf(-1L), intArrayOf(-1)),
        )
        assertEquals(VideoCodec.VP9, KeyframeIndexCodec.decode(KeyframeIndexCodec.encode(index(VideoCodec.VP9)))!!.video.codec)
        assertEquals(VideoCodec.AV1, KeyframeIndexCodec.decode(KeyframeIndexCodec.encode(index(VideoCodec.AV1)))!!.video.codec)
    }

    @Test fun aVp9IndexSavedWhileOnIsReadAgainOnceTheSettingIsOff() {
        fun index(codec: VideoCodec) = MediaIndex(
            ContainerKind.MATROSKA, 1_000L, 60_000_000L,
            VideoTrack(codec, 1920, 1080, 0.0, 4, ByteArray(0), 8, ContainerColour(), null, 1L),
            KeyframeIndex(longArrayOf(0L), longArrayOf(100L), longArrayOf(-1L), intArrayOf(-1)),
        )
        val vp9 = KeyframeIndexCodec.encode(index(VideoCodec.VP9))
        val av1 = KeyframeIndexCodec.encode(index(VideoCodec.AV1))
        try {
            HardwareThumbSwitch.setting = false
            assertFalse(HardwareThumbSwitch.on)
            assertNull(KeyframeIndexCodec.decode(vp9))
            assertEquals(VideoCodec.AV1, KeyframeIndexCodec.decode(av1)!!.video.codec)
            HardwareThumbSwitch.setting = true
            assertEquals(VideoCodec.VP9, KeyframeIndexCodec.decode(vp9)!!.video.codec)
        } finally {
            HardwareThumbSwitch.setting = true
        }
    }
}
