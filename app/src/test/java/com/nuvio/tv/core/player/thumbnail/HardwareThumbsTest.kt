package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Hardware thumbnails: what goes to the hardware decoder, how many at once, and when the box goes back to software. */
class HardwareThumbsTest {
    private fun nal(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun annexB(vararg nals: ByteArray, threeByte: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            out.write(if (threeByte) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1))
            out.write(n)
        }
        return out.toByteArray()
    }

    // HEVC header byte 0 = type << 1
    private val vps = nal(32 shl 1, 1, 0x0C)
    private val sps = nal(33 shl 1, 1, 0x42)
    private val pps = nal(34 shl 1, 1, 0xC1)
    private val sei = nal(39 shl 1, 1, 0x05)
    private val idr = nal(19 shl 1, 1, 0xAF, 0x12, 0x34)
    private val rpu = nal(62 shl 1, 1, 0x08, 0x09)

    @Test fun dolbyVisionRpusAreRemoved() {
        val au = annexB(vps, sps, pps, rpu, idr, rpu)
        assertArrayEquals(annexB(vps, sps, pps, idr), HardwareNal.withoutRpu(au))
    }

    @Test fun aKeyframeWithoutRpuIsLeftAsItIs() {
        val au = annexB(vps, sps, pps, idr)
        assertSame(au, HardwareNal.withoutRpu(au))
        val longer = au + ByteArray(16)
        assertArrayEquals(au, HardwareNal.withoutRpu(longer, au.size))
    }

    @Test fun threeByteStartCodesAreRead() {
        val au = annexB(sps, rpu, idr, threeByte = true)
        assertArrayEquals(annexB(sps, idr), HardwareNal.withoutRpu(au))
        assertEquals(3, HardwareNal.nalRanges(au, au.size).size)
    }

    @Test fun trailingZerosBeforeTheNextStartCodeAreNotPartOfTheNal() {
        val au = annexB(sps + byteArrayOf(0, 0), idr)
        val r = HardwareNal.nalRanges(au, au.size)
        assertEquals(sps.size, r[0].last - r[0].first + 1)
    }

    @Test fun csdHoldsOnlyParameterSets() {
        assertArrayEquals(annexB(vps, sps, pps), HardwareNal.parameterSets(annexB(vps, sps, pps, sei), hevc = true))
        val avcSps = nal(0x67, 0x64, 0x00, 0x28)
        val avcPps = nal(0x68, 0xEE, 0x3C)
        val avcSei = nal(0x06, 0x05)
        assertArrayEquals(annexB(avcSps, avcPps), HardwareNal.parameterSets(annexB(avcSei, avcSps, avcPps), hevc = false))
        assertEquals(0, HardwareNal.parameterSets(ByteArray(0), hevc = true).size)
    }

    private fun why(
        sdk: Int = 30, phase: FetchPhase = FetchPhase.GENERATING, codec: VideoCodec = VideoCodec.HEVC, bitDepth: Int = 10,
        dv: Int? = null, off: String? = null, blocked: Boolean = false, decoder: Boolean = true,
        fourKFromAppMemory: Boolean = false,
    ) = HardwareRoute.softwareBecause(sdk, phase, codec, bitDepth, dv, off, blocked, decoder, fourKFromAppMemory)

    @Test fun fourKStaysSoftwareWhenTheDecoderWouldTakeAppMemoryOnASmallBox() {
        assertEquals(true, why(fourKFromAppMemory = true)?.startsWith("4K on a small box"))
        assertNull(why(fourKFromAppMemory = false))
    }

    @Test fun theSettingDecidesUnlessTheTestPropertyIsSet() {
        assertEquals(true, HardwareThumbSwitch.onFor(true, null))
        assertEquals(true, HardwareThumbSwitch.onFor(true, ""))
        assertEquals(true, HardwareThumbSwitch.onFor(true, "1"))
        assertEquals(false, HardwareThumbSwitch.onFor(true, "0"))
        assertEquals(false, HardwareThumbSwitch.onFor(false, null))
        assertEquals(false, HardwareThumbSwitch.onFor(false, ""))
        assertEquals(true, HardwareThumbSwitch.onFor(false, "1"))
        assertEquals(false, HardwareThumbSwitch.onFor(false, "0"))
        assertEquals(false, HardwareThumbSwitch.onFor(true, " 0 "))
        assertEquals(false, HardwareThumbSwitch.onFor(false, "yes"))
    }

    @Test fun hardwareOnlyWhileNoFilmPlays() {
        assertNull(why(phase = FetchPhase.GENERATING))
        assertNull(why(phase = FetchPhase.PLAYER_CLOSED))
        assertEquals("film playing", why(phase = FetchPhase.PLAYBACK))
    }

    @Test fun formatsThatWorkedGoToHardwareUpTo10Bit() {
        for (c in listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG2, VideoCodec.MPEG4, VideoCodec.VP9, VideoCodec.AV1)) {
            assertNull(c.name, why(codec = c, bitDepth = 8))
        }
        assertNull(why(codec = VideoCodec.VP9, bitDepth = 10))
        assertNull(why(codec = VideoCodec.AV1, bitDepth = 10))
        for (c in listOf(VideoCodec.VC1, VideoCodec.WMV3, VideoCodec.MPEG1)) {
            assertEquals("codec ${c.name}", why(codec = c, bitDepth = 8))
        }
        assertEquals("12-bit", why(bitDepth = 12))
        assertEquals("12-bit", why(codec = VideoCodec.AV1, bitDepth = 12))
    }

    @Test fun dolbyVisionProfile5StaysSoftwareOthersUseTheBaseLayer() {
        assertEquals("Dolby Vision profile 5", why(dv = 5))
        assertNull(why(dv = 7))
        assertNull(why(dv = 8))
    }

    @Test fun failuresBlocksAndMissingDecodersKeepSoftware() {
        assertEquals("no picture in 3 s", why(off = "no picture in 3 s"))
        assertTrue(why(blocked = true)!!.contains("30 days"))
        assertEquals("no hardware decoder", why(decoder = false))
        assertNull(why(sdk = 24))
        assertEquals("Android 23", why(sdk = 23))
    }

    @Test fun dolbyVisionIptNeedsTheSoftwareReshaping() {
        fun d(t: ThumbTransfer) = ColourDecision(t, true, false, 1000f, "")
        assertFalse(HardwareRoute.planUsable(d(ThumbTransfer.DOVI_IPT)))
        for (t in listOf(ThumbTransfer.SDR, ThumbTransfer.PQ, ThumbTransfer.HLG)) assertTrue(HardwareRoute.planUsable(d(t)))
    }

    /** [n] pictures [gapMs] apart from [startMs]; returns the last change and the time after it. */
    private fun HardwareLanes.feed(startMs: Long, gapMs: Long, n: Int = HardwareLanes.WINDOW + 1): Pair<HardwareLanes.Change?, Long> {
        var t = startMs
        var last: HardwareLanes.Change? = null
        repeat(n) {
            onPicture(t)?.let { c -> last = c }
            t += gapMs
        }
        return last to t
    }

    @Test fun startsWithOneAndAddsOneWhileFaster() {
        val l = HardwareLanes(maxInstances = 9)
        assertEquals(1, l.lanes)
        assertEquals(3, l.cap)
        val (a, t1) = l.feed(0, 100)
        assertEquals(2, a!!.to)
        val (b, t2) = l.feed(t1, 60)
        assertEquals(3, b!!.to)
        val (c, _) = l.feed(t2, 40)
        assertNull(c)
        assertEquals(3, l.lanes)
        assertTrue(l.settled)
        assertEquals(10.0, l.rate(1), 0.01)
    }

    @Test fun noGainGoesBackOneAndStays() {
        val l = HardwareLanes(maxInstances = 9)
        val (_, t1) = l.feed(0, 100)
        val (b, t2) = l.feed(t1, 95)
        assertEquals(2, b!!.from)
        assertEquals(1, b.to)
        assertTrue(l.settled)
        val (c, _) = l.feed(t2, 10, n = 40)
        assertNull(c)
        assertEquals(1, l.lanes)
    }

    @Test fun aMissedPictureDropsOneAndStays() {
        val l = HardwareLanes(maxInstances = 9)
        val (_, t1) = l.feed(0, 100)
        assertEquals(2, l.lanes)
        val m = l.onMiss()
        assertEquals(1, m!!.to)
        assertNull(l.feed(t1, 10, n = 40).first)
        assertNull(HardwareLanes(9).onMiss())
    }

    @Test fun capLeavesOneDecoderForThePlayer() {
        assertEquals(1, HardwareLanes.capFor(0))
        assertEquals(1, HardwareLanes.capFor(1))
        assertEquals(1, HardwareLanes.capFor(2))
        assertEquals(2, HardwareLanes.capFor(3))
        assertEquals(3, HardwareLanes.capFor(4))
        assertEquals(3, HardwareLanes.capFor(32))
        val one = HardwareLanes(2)
        assertNull(one.feed(0, 100).first)
        assertEquals(1, one.lanes)
    }

    private class MapMemory : HardwareFailureMemory {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
    }

    private val day = 24L * 60 * 60 * 1000

    @Test fun threeFailuresMeanSoftwareFor30Days() {
        var now = 1_000_000L
        val book = HardwareFailureBook(MapMemory()) { now }
        assertFalse(book.failed("box|c2.hevc"))
        now += day
        assertFalse(book.failed("box|c2.hevc"))
        assertFalse(book.blocked("box|c2.hevc"))
        now += day
        assertTrue(book.failed("box|c2.hevc"))
        assertTrue(book.blocked("box|c2.hevc"))
        assertFalse(book.blocked("box|c2.avc"))
        now += 29 * day
        assertTrue(book.blocked("box|c2.hevc"))
        assertFalse(book.failed("box|c2.hevc"))
        now += 2 * day
        assertFalse(book.blocked("box|c2.hevc"))
        assertFalse(book.failed("box|c2.hevc"))
    }

    @Test fun failuresMoreThan30DaysApartDoNotAddUp() {
        var now = 0L
        val book = HardwareFailureBook(MapMemory()) { now }
        book.failed("k")
        now += 10 * day
        book.failed("k")
        now += 25 * day
        assertFalse(book.failed("k"))
        now += day
        assertFalse(book.failed("k"))
        now += day
        assertTrue(book.failed("k"))
    }

    @Test fun failuresSurviveARestart() {
        val memory = MapMemory()
        var now = 5L
        HardwareFailureBook(memory) { now }.failed("k")
        HardwareFailureBook(memory) { now }.failed("k")
        assertTrue(HardwareFailureBook(memory) { now }.failed("k"))
        assertTrue(HardwareFailureBook(memory) { now }.blocked("k"))
        memory.m["k"] = "rubbish"
        assertFalse(HardwareFailureBook(memory) { now }.blocked("k"))
    }

    @Test fun blackAndOneColourPicturesAreFlat() {
        assertTrue(HardwarePicture.flat(480, 270) { _, _ -> 0xFF000000.toInt() })
        assertTrue(HardwarePicture.flat(480, 270) { _, _ -> 0xFF2080C0.toInt() })
        assertTrue(HardwarePicture.flat(480, 270) { x, _ -> 0xFF000000.toInt() or ((x / 120) * 0x010101) })
    }

    @Test fun anyDetailIsNotFlat() {
        assertFalse(HardwarePicture.flat(480, 270) { x, _ -> 0xFF000000.toInt() or ((x * 255 / 480) * 0x010101) })
        assertFalse(HardwarePicture.flat(480, 270) { x, y -> if (x > 400 && y > 200) -1 else 0xFF000000.toInt() })
    }

    @Test fun anEmptyPictureIsNotFlat() {
        assertFalse(HardwarePicture.flat(0, 0) { _, _ -> 0 })
    }

    @Test fun toneTablesAreSplitAsTheNativeSideWritesThem() {
        val all = FloatArray(3 * ToneLuts.SIZE + 1) { it.toFloat() }
        val t = ToneLuts.from(all)
        assertNotNull(t)
        assertEquals(0f, t!!.lin[0])
        assertEquals(ToneLuts.SIZE.toFloat(), t.scale[0])
        assertEquals((2 * ToneLuts.SIZE).toFloat(), t.oetf[0])
        assertEquals((3 * ToneLuts.SIZE).toFloat(), t.invPeak)
        assertEquals(ToneLuts.SIZE, ToneLuts.SIDE * ToneLuts.SIDE)
        assertNull(ToneLuts.from(FloatArray(10)))
        assertNull(ToneLuts.from(null))
    }

    @Test fun lutIndexesMatchThumbcolor() {
        assertEquals(0, ToneLuts.codeIndex(0f))
        assertEquals(4095, ToneLuts.codeIndex(1f))
        // A 10-bit code 512 as swscale widens it to 16 bits (512 << 6): 12-bit index 2048.
        assertEquals(2048, ToneLuts.codeIndex((512 shl 6) / 65535f + 1e-6f))
        assertEquals(0, ToneLuts.sqrtIndex(0f))
        assertEquals(0, ToneLuts.sqrtIndex(Float.NaN))
        assertEquals(4095, ToneLuts.sqrtIndex(1f))
        assertEquals(4095, ToneLuts.sqrtIndex(4f))
        assertEquals(2048, ToneLuts.sqrtIndex(0.25f))
    }

    private fun cand(name: String, hw: Boolean = true, secure: Boolean = false, size: Boolean = true, ten: Boolean = true) =
        HardwareCodecCandidate(name, hw, secure, size, ten, 16)

    @Test fun pickTakesTheFirstPlainHardwareDecoderThatFits() {
        val list = listOf(
            cand("c2.android.hevc.decoder", hw = false),
            cand("c2.vendor.hevc.decoder.secure", secure = true),
            cand("c2.vendor.small", size = false),
            cand("c2.vendor.eight", ten = false),
            cand("c2.vendor.hevc.decoder"),
        )
        assertEquals("c2.vendor.hevc.decoder", HardwareCodecPick.pick(list, tenBit = true)!!.name)
        assertEquals("c2.vendor.eight", HardwareCodecPick.pick(list, tenBit = false)!!.name)
        assertNull(HardwareCodecPick.pick(list.take(3), tenBit = false))
    }

    @Test fun hardwareDecodesAreChargedLittleAndFundedLikeHd() {
        assertTrue(DecodeTier.HW.rssMb < DecodeTier.SW_UP_TO_1080P.rssMb)
        assertTrue(MemoryGovernor.fundedDecode(0, DecodeTier.HW, false, false, screenHolds = true, lowRam = false))
        assertFalse(MemoryGovernor.fundedDecode(1, DecodeTier.HW, false, false, screenHolds = true, lowRam = false))
        assertFalse(MemoryGovernor.fundedDecode(0, DecodeTier.HW, false, false, screenHolds = true, lowRam = true))
    }

    @Test fun aLaneWithoutHardwareUsesItsSoftwareDecoder() {
        val lane = DecoderLane()
        assertSame(lane.decoder, lane.decoderFor(false))
        assertFalse(lane.decoderFor(true).warm)
        assertNull(lane.hardware)
        lane.closeHardware()
        lane.acquire()
        lane.closeHardware()
        lane.release()
        assertNull(lane.hardware)
    }
}
