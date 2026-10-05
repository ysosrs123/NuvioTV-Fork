package com.nuvio.tv.core.iptv

import org.junit.Assert.*
import org.junit.Test

class CaptureExtractedSampleTimingTest {
    private fun media() = TsCaptureInspector().inspect(RetainedCaptureFixtures.bytes().inputStream())
    private fun us(ticks: Long) = ticks * 1_000_000 / 90_000
    private fun video(m: TsCaptureInspection, base: Long = 0) = (0 until m.videoFrames).map { us(m.videoFirstPts90k + base + it * m.videoStep90k) }
    private fun audio(m: TsCaptureInspection, base: Long = 0) = (0 until m.audioFrames).map { us(m.audioFirstPts90k + base) + it * 1024_000_000L / m.audioSampleRate }
    private fun rejected(m: TsCaptureInspection, v: List<Long>, a: List<Long>) {
        try { CaptureExtractedSampleTiming.validate(m, v, a); fail() } catch (_: CaptureSampleTimingException) { }
    }

    @Test fun oneVideoReferencePreservesNegativeAudioPhaseAndStableWindowPosition() {
        val m = media(); val v = video(m); val a = audio(m); val timing = CaptureExtractedSampleTiming.validate(m, v, a)
        assertEquals(0L, timing.normalize(0, v.first())); assertEquals(1960000L, timing.normalize(0, v.last()))
        assertEquals(-21333L, timing.normalize(0, a.first()))
        assertEquals(2000000L, timing.normalize(180000, v.first())); assertEquals(1978667L, timing.normalize(180000, a.first()))
    }

    @Test fun alternateUnwrappedBaseAtAudioFirstBoundaryProducesTheSamePositions() {
        val bytes = RetainedCaptureFixtures.transformPts(RetainedCaptureFixtures.bytes()) { it - 127920 }
        val m = TsCaptureInspector().inspect(bytes.inputStream())
        assertEquals(0L, m.videoFirstPts90k); assertEquals(-1920L, m.audioFirstPts90k)
        for (base in listOf(0L, 1L shl 33)) {
            val v = video(m, base); val a = audio(m, base)
            val timing = CaptureExtractedSampleTiming.validate(m, v, a)
            assertEquals(0L, timing.normalize(0, v.first())); assertEquals(-21333L, timing.normalize(0, a.first()))
        }
    }

    @Test fun independentWrapNormalizationOfAudioAndVideoIsRejected() {
        val m = media()
        rejected(m, video(m, 1L shl 33), audio(m))
        rejected(m, video(m), audio(m, 1L shl 33))
    }

    @Test fun missingDuplicatedAndUnevenVideoSamplesAreRejected() {
        val m = media(); val v = video(m); val a = audio(m)
        rejected(m, v.dropLast(1), a)
        rejected(m, v.toMutableList().also { it[20] = it[19] }, a)
        rejected(m, v.toMutableList().also { it[20] += 10000 }, a)
    }

    @Test fun missingResetAndJumpedAudioClocksAreRejected() {
        val m = media(); val v = video(m); val a = audio(m)
        rejected(m, v, a.dropLast(1))
        rejected(m, v, a.map { it - a.first() })
        rejected(m, v, a.toMutableList().also { it[40] += 20000 })
        rejected(m, v, a.toMutableList().also { it[40] = it[39] })
    }

    @Test fun perFrameMicrosecondRoundingIsAllowedForBothDeclaredSampleRates() {
        for (rate in listOf(48000, 44100)) {
            val m = media().copy(audioSampleRate = rate)
            val a = (0 until m.audioFrames).map { us(m.audioFirstPts90k) + it * (1024_000_000L / rate) }
            CaptureExtractedSampleTiming.validate(m, video(m), a)
        }
    }

    @Test fun wrongRawPtsAndArithmeticOverflowCannotValidate() {
        val m = media(); val v = video(m); val a = audio(m)
        rejected(m, v.map { it + 1000 }, a.map { it + 1000 })
        rejected(m, v.toMutableList().also { it[30] = Long.MIN_VALUE }, a)
        rejected(m, v.toMutableList().also { it[1] = Long.MIN_VALUE + v.first() + 40000 }, a)
        val timing = CaptureExtractedSampleTiming.validate(m, v, a)
        try { timing.normalize(Long.MAX_VALUE, v.first()); fail() } catch (_: ArithmeticException) { }
    }
}
