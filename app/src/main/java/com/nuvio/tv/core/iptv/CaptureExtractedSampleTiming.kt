package com.nuvio.tv.core.iptv

import java.io.IOException

class CaptureSampleTimingException : IOException("Extracted capture timestamps differ from inspection")

class CaptureExtractedSampleTiming private constructor(private val videoOriginUs: Long) {
    fun normalize(windowStart90k: Long, rawTimeUs: Long): Long =
        Math.addExact(ticksToUs(windowStart90k), Math.subtractExact(rawTimeUs, videoOriginUs))

    companion object {

        fun validate(media: TsCaptureInspection, videoUs: List<Long>, audioUs: List<Long>): CaptureExtractedSampleTiming {
            try {
                need(videoUs.size == media.videoFrames && audioUs.size == media.audioFrames && videoUs.isNotEmpty() && audioUs.isNotEmpty())
                need(media.videoStep90k > 0 && media.audioSampleRate in setOf(44100, 48000))
                val origin = videoUs.first()
                need(delta(usToTicks(origin), media.videoFirstPts90k) in -1L..1L)
                for ((i, time) in videoUs.withIndex()) {
                    val expected = ticksToUs(Math.multiplyExact(i.toLong(), media.videoStep90k))
                    need(within(Math.subtractExact(time, origin), expected, 1))
                }
                val audioOrigin = audioUs.first()
                need(delta(usToTicks(audioOrigin), media.audioFirstPts90k) in -1L..1L)
                need(within(Math.subtractExact(audioOrigin, origin),
                    ticksToUs(media.audioFirstPts90k - media.videoFirstPts90k), 2))
                for ((i, time) in audioUs.withIndex()) {
                    if (i > 0) need(time > audioUs[i - 1])

                    val expected = i * 1024_000_000L / media.audioSampleRate
                    need(within(Math.subtractExact(time, audioOrigin), expected, i + 12L))
                }
                return CaptureExtractedSampleTiming(origin)
            } catch (_: ArithmeticException) { throw CaptureSampleTimingException() }
        }

        private fun within(actual: Long, expected: Long, tolerance: Long): Boolean = Math.subtractExact(actual, expected) in -tolerance..tolerance
        private fun need(ok: Boolean) { if (!ok) throw CaptureSampleTimingException() }
        private fun delta(a: Long, b: Long): Long = ((a - b + (1L shl 32)) and ((1L shl 33) - 1)) - (1L shl 32)
        private fun ticksToUs(ticks: Long): Long = Math.addExact(Math.multiplyExact(ticks / 90_000, 1_000_000), (ticks % 90_000) * 1_000_000 / 90_000)
        private fun usToTicks(us: Long): Long = Math.addExact(Math.multiplyExact(us / 1_000_000, 90_000), (us % 1_000_000) * 90_000 / 1_000_000)
    }
}
