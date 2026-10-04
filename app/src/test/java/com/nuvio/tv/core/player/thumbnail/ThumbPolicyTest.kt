package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Colour classification, output geometry, meminfo parsing, retention. */
class ThumbPolicyTest {
    private val none = ContainerColour()
    private fun frame(bits: Int, trc: Int, pri: Int, spc: Int = 0, range: Int = 1, cll: Int = 0, mast: Int = 0) =
        FrameColour(bits, trc, pri, spc, range, cll, mast)

    @Test fun pqFromBitstream() {
        val d = ColourClassifier.classify(none, frame(10, 16, 9, 9, cll = 617), null)
        assertEquals(ThumbTransfer.PQ, d.transfer)
        assertTrue(d.bt2020)
        assertEquals(617f, d.peakNits)
    }

    @Test fun containerWinsOverBitstream() {
        val d = ColourClassifier.classify(ContainerColour(transfer = 16, primaries = 9), frame(10, 1, 1), null)
        assertEquals(ThumbTransfer.PQ, d.transfer)
    }

    /** Some HDR WEB-DLs are tagged transfer 14 with BT.2020, 10-bit and no SEI. They are PQ. */
    @Test fun d1MistaggedPqWebDl() {
        val d = ColourClassifier.classify(none, frame(10, 14, 9, 9), null)
        assertEquals(ThumbTransfer.PQ, d.transfer)
        assertEquals(1000f, d.peakNits)
        assertTrue(d.reason.startsWith("D1"))
    }

    @Test fun d1NeedsTenBitAndBt2020() {
        assertEquals(ThumbTransfer.SDR, ColourClassifier.classify(none, frame(8, 14, 9, 9), null).transfer)
        assertEquals(ThumbTransfer.SDR, ColourClassifier.classify(none, frame(10, 14, 1, 1), null).transfer)
    }

    @Test fun tenBitSdrStaysSdr() {
        assertEquals(ThumbTransfer.SDR, ColourClassifier.classify(none, frame(10, 1, 1, 1), null).transfer)
    }

    @Test fun hlg() {
        assertEquals(ThumbTransfer.HLG, ColourClassifier.classify(none, frame(10, 18, 9, 9), null).transfer)
    }

    @Test fun dolbyVisionIptFromDecoderMetadata() {
        val ipt = frame(10, 2, 2).copy(doviIpt = true)
        assertEquals(ThumbTransfer.DOVI_IPT, ColourClassifier.classify(none, ipt, DolbyVisionConfig(5, 0, false)).transfer)
        assertEquals(ThumbTransfer.DOVI_IPT, ColourClassifier.classify(none, ipt, null).transfer)
    }

    @Test fun dolbyVisionProfiles() {
        assertEquals(ThumbTransfer.PQ, ColourClassifier.classify(none, frame(10, 2, 2), DolbyVisionConfig(7, 6, true)).transfer)
        assertEquals(ThumbTransfer.PQ, ColourClassifier.classify(none, frame(10, 2, 2), DolbyVisionConfig(8, 1, false)).transfer)
        assertEquals(ThumbTransfer.HLG, ColourClassifier.classify(none, frame(10, 2, 2), DolbyVisionConfig(8, 4, false)).transfer)
    }

    @Test fun peakFallbackOrder() {
        assertEquals(4000f, ColourClassifier.classify(none, frame(10, 16, 9, mast = 4000), null).peakNits)
        assertEquals(800f, ColourClassifier.classify(ContainerColour(maxCll = 800), frame(10, 16, 9), null).peakNits)
    }

    @Test fun fullRangeFromContainerOrFrame() {
        assertTrue(ColourClassifier.classify(ContainerColour(range = 2), frame(8, 1, 1), null).fullRange)
        assertTrue(ColourClassifier.classify(none, frame(10, 14, 9, 9, range = 2), null).fullRange)
        assertFalse(ColourClassifier.classify(none, frame(8, 1, 1), null).fullRange)
    }

    @Test fun uhdTo320x180() = assertEquals(listOf(320, 180), ThumbGeometry.outputSize(3840, 2160, 1, 1, 0.0).toList())

    @Test fun scopeAspect() = assertEquals(listOf(320, 116), ThumbGeometry.outputSize(1920, 696, 1, 1, 0.0).toList())

    /** 696x472 with SAR 472:533 displays ~4:3. */
    @Test fun anamorphicSd() {
        val s = ThumbGeometry.outputSize(696, 472, 472, 533, 0.0)
        assertEquals(320, s[0])
        assertEquals(244, s[1])
    }

    @Test fun containerAspectWins() =
        assertEquals(listOf(320, 180), ThumbGeometry.outputSize(1440, 1080, 1, 1, 16.0 / 9.0).toList())

    @Test fun neverUpscale() {
        val s = ThumbGeometry.outputSize(240, 136, 1, 1, 0.0)
        assertEquals(240, s[0])
        assertEquals(136, s[1])
    }

    @Test fun sillyAspectFallsBack() {
        assertEquals(listOf(320, 180), ThumbGeometry.outputSize(1920, 1080, 1, 0, 0.0).toList())
    }

    @Test fun meminfoParse() {
        val m = MemoryGovernor.parseMeminfo(
            "MemTotal:        1710080 kB\nMemFree:           35180 kB\nMemAvailable:     238932 kB\n" +
                "SwapTotal:        524284 kB\nSwapFree:          36928 kB\n",
        )!!
        assertEquals(233L, m.availableMb)
        assertEquals(511L, m.swapTotalMb)
        assertEquals(36L, m.swapFreeMb)
    }

    @Test fun tiers() {
        assertEquals(DecodeTier.SW_UP_TO_1080P, MemoryGovernor.tierFor(1920, 1080))
        assertEquals(DecodeTier.SW_UP_TO_1080P, MemoryGovernor.tierFor(1920, 1088))
        assertEquals(DecodeTier.SW_4K, MemoryGovernor.tierFor(3840, 2160))
    }

    @Test fun retention() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(7 * day, ThumbStore.retentionMs(0.95f))
        assertEquals(30 * day, ThumbStore.retentionMs(0.5f))
        assertEquals(3 * day, ThumbStore.retentionMs(0.01f))
    }

    @Test fun avcHigh10BitDepth() {
        // SPS: header 0x67, profile 110, flags, level 40, then ue(0)=1, ue(1)=010, ue(2)=011, padding
        val sps = byteArrayOf(0x67, 110, 0, 40, 0b1010_0110.toByte(), 0)
        assertEquals(10, CodecConfigParser.avcSpsBitDepth(sps, 0, sps.size))
    }

    @Test fun avcMainIsEightBit() {
        val sps = byteArrayOf(0x67, 77, 0, 40, 0x80.toByte())
        assertEquals(8, CodecConfigParser.avcSpsBitDepth(sps, 0, sps.size))
    }

    @Test fun vmSizeFromProcStatus() {
        val status = listOf("Name: nuvio", "VmPeak:  2532272 kB", "VmSize:  1880548 kB", "VmRSS:    43448 kB")
            .joinToString("\n")
        assertEquals(1836L, MemoryGovernor.parseVmSizeMb(status))
        assertEquals(null, MemoryGovernor.parseVmSizeMb("Name: x"))
    }
}
